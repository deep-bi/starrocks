// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/http/ev_http_server.cpp

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "http/ev_http_server.h"

#include <event2/buffer.h>
#include <event2/bufferevent.h>
#include <event2/event.h>
#include <event2/http.h>
#include <event2/http_struct.h>
#include <event2/keyvalq_struct.h>
#include <openssl/pem.h>

#include <memory>
#include <sstream>
#include <utility>

#include "common/config.h"
#include "common/logging.h"
#include "http/http_channel.h"
#include "http/http_handler.h"
#include "http/http_headers.h"
#include "http/http_request.h"
#include "service/backend_options.h"
#include "service/brpc.h"
#include "util/debug_util.h"
#include "util/defer_op.h"
#include "util/errno.h"
#include "util/thread.h"

namespace starrocks {

static void on_chunked(struct evhttp_request* ev_req, void* param) {
    auto* request = (HttpRequest*)ev_req->on_free_cb_arg;
    request->handler()->on_chunk_data(request);
}

static void on_free(struct evhttp_request* ev_req, void* arg) {
    auto* request = (HttpRequest*)arg;
    delete request;
}

static void on_request(struct evhttp_request* ev_req, void* arg) {
    auto request = (HttpRequest*)ev_req->on_free_cb_arg;
    if (request == nullptr) {
        // In this case, request's on_header return -1
        return;
    }
    request->handler()->handle(request);
}

static int on_header(struct evhttp_request* ev_req, void* param) {
    auto* server = (EvHttpServer*)ev_req->on_complete_cb_arg;
    return server->on_header(ev_req);
}

// param is pointer of EvHttpServer
static int on_connection(struct evhttp_request* req, void* param) {
    auto* server = (EvHttpServer*)param;
    // libevent creates a plain connection when the bufferevent callback returns nullptr,
    // so an HTTPS server refuses any request that did not arrive over TLS.
    if (server->ssl_enabled()) {
        struct bufferevent* bev = evhttp_connection_get_bufferevent(evhttp_request_get_connection(req));
        if (bufferevent_openssl_get_ssl(bev) == nullptr) {
            return -1;
        }
    }
    evhttp_request_set_header_cb(req, on_header);
    // only used on_complete_cb's argument
    evhttp_request_set_on_complete_cb(req, nullptr, param);
    return 0;
}

EvHttpServer::EvHttpServer(int port, int num_workers) : _port(port), _num_workers(num_workers), _real_port(0) {
    _host = BackendOptions::get_service_bind_address();
    DCHECK_GT(_num_workers, 0);
    auto res = pthread_rwlock_init(&_rw_lock, nullptr);
    DCHECK_EQ(res, 0);
}

EvHttpServer::EvHttpServer(std::string host, int port, int num_workers)
        : _host(std::move(host)), _port(port), _num_workers(num_workers), _real_port(0) {
    DCHECK_GT(_num_workers, 0);
    auto res = pthread_rwlock_init(&_rw_lock, nullptr);
    DCHECK_EQ(res, 0);
}

EvHttpServer::~EvHttpServer() {
    pthread_rwlock_destroy(&_rw_lock);

    if (_ecdh != nullptr) {
        EC_KEY_free(_ecdh);
    }
    if (_ssl_ctx != nullptr) {
        SSL_CTX_free(_ssl_ctx);
    }
}

// Same rule brpc applies to ssl_certificate_path and ssl_private_key_path: a value that
// starts with "-----BEGIN" is PEM content, anything else is a file path.
static bool is_pem_content(const std::string& value) {
    size_t pos = value.find_first_not_of(" \t\r\n");
    return pos != std::string::npos && value.compare(pos, 10, "-----BEGIN") == 0;
}

// For log and error messages: the key setting may hold the private key itself.
static std::string describe_pem_source(const std::string& value) {
    return is_pem_content(value) ? "inline PEM" : value;
}

static std::string openssl_error() {
    unsigned long err = ERR_get_error();
    ERR_clear_error();
    if (err == 0) {
        return "unknown error";
    }
    char buf[256];
    ERR_error_string_n(err, buf, sizeof(buf));
    return buf;
}

// Loads the server certificate and the intermediate certificates that follow it.
static bool use_certificate_chain(SSL_CTX* ctx, const std::string& value) {
    if (!is_pem_content(value)) {
        return SSL_CTX_use_certificate_chain_file(ctx, value.c_str()) == 1;
    }
    BIO* bio = BIO_new_mem_buf(value.data(), static_cast<int>(value.size()));
    if (bio == nullptr) {
        return false;
    }
    DeferOp free_bio([bio] { BIO_free(bio); });

    X509* cert = PEM_read_bio_X509_AUX(bio, nullptr, nullptr, nullptr);
    if (cert == nullptr) {
        return false;
    }
    int ret = SSL_CTX_use_certificate(ctx, cert);
    X509_free(cert);
    if (ret != 1 || SSL_CTX_clear_chain_certs(ctx) != 1) {
        return false;
    }
    X509* ca = nullptr;
    while ((ca = PEM_read_bio_X509(bio, nullptr, nullptr, nullptr)) != nullptr) {
        if (SSL_CTX_add0_chain_cert(ctx, ca) != 1) {
            X509_free(ca);
            return false;
        }
    }
    // Reading past the last certificate fails with PEM_R_NO_START_LINE; any other error is a bad certificate.
    unsigned long err = ERR_peek_last_error();
    if (ERR_GET_LIB(err) == ERR_LIB_PEM && ERR_GET_REASON(err) == PEM_R_NO_START_LINE) {
        ERR_clear_error();
        return true;
    }
    return false;
}

static bool use_private_key(SSL_CTX* ctx, const std::string& value) {
    if (!is_pem_content(value)) {
        return SSL_CTX_use_PrivateKey_file(ctx, value.c_str(), SSL_FILETYPE_PEM) == 1;
    }
    BIO* bio = BIO_new_mem_buf(value.data(), static_cast<int>(value.size()));
    if (bio == nullptr) {
        return false;
    }
    EVP_PKEY* key = PEM_read_bio_PrivateKey(bio, nullptr, nullptr, nullptr);
    BIO_free(bio);
    if (key == nullptr) {
        return false;
    }
    int ret = SSL_CTX_use_PrivateKey(ctx, key);
    EVP_PKEY_free(key);
    return ret == 1;
}

Status EvHttpServer::_init_ssl_ctx() {
    const std::string& cert = config::ssl_certificate_path;
    const std::string& key = config::ssl_private_key_path;
    if (cert.empty() || key.empty()) {
        return Status::InvalidArgument(
                "be_http_ssl_enable=true requires ssl_certificate_path and ssl_private_key_path");
    }

    SSL_library_init();
    SSL_load_error_strings();
    OpenSSL_add_all_algorithms();

    _ssl_ctx = SSL_CTX_new(TLS_server_method());
    if (_ssl_ctx == nullptr) {
        return Status::InternalError("Failed to create SSL context: " + openssl_error());
    }
    SSL_CTX_set_options(_ssl_ctx, SSL_OP_SINGLE_DH_USE | SSL_OP_SINGLE_ECDH_USE);
    if (SSL_CTX_set_min_proto_version(_ssl_ctx, TLS1_2_VERSION) != 1) {
        return Status::InternalError("Failed to set the minimum TLS version: " + openssl_error());
    }

    _ecdh = EC_KEY_new_by_curve_name(NID_X9_62_prime256v1);
    if (_ecdh == nullptr) {
        LOG(WARNING) << "EC_KEY_new_by_curve_name failed";
    } else if (SSL_CTX_set_tmp_ecdh(_ssl_ctx, _ecdh) != 1) {
        LOG(WARNING) << "SSL_CTX_set_tmp_ecdh failed";
    }

    if (!use_certificate_chain(_ssl_ctx, cert)) {
        return Status::InternalError("Failed to load SSL certificate from " + describe_pem_source(cert) + ": " +
                                     openssl_error());
    }
    if (!use_private_key(_ssl_ctx, key)) {
        return Status::InternalError("Failed to load SSL private key from " + describe_pem_source(key) + ": " +
                                     openssl_error());
    }
    if (SSL_CTX_check_private_key(_ssl_ctx) != 1) {
        return Status::InternalError("SSL private key does not match certificate: " + openssl_error());
    }

    LOG(INFO) << "be_http_port " << _port << " serves HTTPS (TLS 1.2 or later) with the certificate from "
              << describe_pem_source(cert);
    return Status::OK();
}

static struct bufferevent* new_ssl_bufferevent(struct event_base* base, void* arg) {
    SSL* ssl = SSL_new((SSL_CTX*)arg);
    if (ssl == nullptr) {
        // on_connection refuses the plain connection libevent creates instead
        LOG(WARNING) << "SSL_new failed: " << openssl_error();
        return nullptr;
    }
    return bufferevent_openssl_socket_new(base, -1, ssl, BUFFEREVENT_SSL_ACCEPTING, BEV_OPT_CLOSE_ON_FREE);
}

Status EvHttpServer::start() {
    // In the 3.5 deepbi releases enable_https switched this port to HTTPS. Since 4.0 it is
    // the upstream flag that only adds optional TLS to brpc_port, so say so instead of
    // silently serving plain HTTP to a cluster configured for HTTPS.
    if (config::enable_https && !config::be_http_ssl_enable) {
        LOG(WARNING) << "enable_https=true adds optional TLS to brpc_port only; be_http_port " << _port
                     << " serves plain HTTP. Set be_http_ssl_enable=true to serve HTTPS on it.";
    }

    if (config::be_http_ssl_enable) {
        RETURN_IF_ERROR(_init_ssl_ctx());
    }

    // bind to
    RETURN_IF_ERROR(_bind());
    for (int i = 0; i < _num_workers; ++i) {
        auto worker = [this]() {
            struct event_base* base = event_base_new();
            if (base == nullptr) {
                LOG(WARNING) << "Couldn't create an event_base.";
                return;
            }
            pthread_rwlock_wrlock(&_rw_lock);
            _event_bases.push_back(base);
            pthread_rwlock_unlock(&_rw_lock);

            /* Create a new evhttp object to handle requests. */
            struct evhttp* http = evhttp_new(base);
            if (http == nullptr) {
                LOG(WARNING) << "Couldn't create an evhttp.";
                return;
            }

            pthread_rwlock_wrlock(&_rw_lock);
            _https.push_back(http);
            pthread_rwlock_unlock(&_rw_lock);

            if (ssl_enabled()) {
                evhttp_set_bevcb(http, new_ssl_bufferevent, _ssl_ctx);
            }
            auto res = evhttp_accept_socket(http, _server_fd);
            if (res < 0) {
                LOG(WARNING) << "evhttp accept socket failed"
                             << ", error:" << errno_to_string(errno);
                return;
            }

            evhttp_set_newreqcb(http, on_connection, this);
            evhttp_set_gencb(http, on_request, this);

            event_base_dispatch(base);
        };
        _workers.emplace_back(worker);
        Thread::set_thread_name(_workers.back(), "http_server");
    }
    return Status::OK();
}

void EvHttpServer::stop() {
    // break the base to stop the event
    for (auto base : _event_bases) {
        event_base_loopbreak(base);
    }

    // shutdown the socket to wake up the epoll_wait
    shutdown(_server_fd, SHUT_RDWR);
}

void EvHttpServer::join() {
    for (auto& thread : _workers) {
        if (thread.joinable()) {
            thread.join();
        }
    }

    // close the socket at last
    close(_server_fd);

    // free the evhttp and event_base
    for (auto http : _https) {
        evhttp_free(http);
    }

    for (auto base : _event_bases) {
        event_base_free(base);
    }
}

Status EvHttpServer::_bind() {
    butil::EndPoint point;
    auto res = butil::str2endpoint(_host.c_str(), _port, &point);
    if (res < 0) {
        std::stringstream ss;
        ss << "convert address failed, host=" << _host << ", port=" << _port;
        return Status::InternalError(ss.str());
    }
    // reuse_addr arg is removed in brpc 0.9.7 and use gflag instead.
    // default reuse_addr is true and reuse_port is false.
    _server_fd = butil::tcp_listen(point);
    if (_server_fd < 0) {
        std::stringstream ss;
        ss << "Failed to listen port. port: " << _port << ", error: " << errno_to_string(errno);
        return Status::InternalError(ss.str());
    }
    if (_port == 0) {
        struct sockaddr_in addr;
        socklen_t socklen = sizeof(addr);
        const int rc = getsockname(_server_fd, (struct sockaddr*)&addr, &socklen);
        if (rc == 0) {
            _real_port = ntohs(addr.sin_port);
        }
    }
    res = butil::make_non_blocking(_server_fd);
    if (res < 0) {
        std::stringstream ss;
        ss << "Failed to generate a non-blocking socket. error: " << errno_to_string(errno);
        return Status::InternalError(ss.str());
    }
    return Status::OK();
}

bool EvHttpServer::register_handler(const HttpMethod& method, const std::string& path, HttpHandler* handler) {
    if (handler == nullptr) {
        LOG(WARNING) << "dummy handler for http method " << method << " with path " << path;
        return false;
    }

    bool result = true;
    pthread_rwlock_wrlock(&_rw_lock);
    PathTrie<HttpHandler*>* root = nullptr;
    switch (method) {
    case GET:
        root = &_get_handlers;
        break;
    case PUT:
        root = &_put_handlers;
        break;
    case POST:
        root = &_post_handlers;
        break;
    case DELETE:
        root = &_delete_handlers;
        break;
    case HEAD:
        root = &_head_handlers;
        break;
    case OPTIONS:
        root = &_options_handlers;
        break;
    default:
        LOG(WARNING) << "unknown HTTP method, method=" << method;
        result = false;
    }
    if (result) {
        result = root->insert(path, handler);
    }
    pthread_rwlock_unlock(&_rw_lock);

    return result;
}

void EvHttpServer::register_static_file_handler(HttpHandler* handler) {
    DCHECK(handler != nullptr);
    DCHECK(_static_file_handler == nullptr);
    pthread_rwlock_wrlock(&_rw_lock);
    _static_file_handler = handler;
    pthread_rwlock_unlock(&_rw_lock);
}

int EvHttpServer::on_header(struct evhttp_request* ev_req) {
    std::unique_ptr<HttpRequest> request(new HttpRequest(ev_req));
    auto res = request->init_from_evhttp();
    if (res < 0) {
        return -1;
    }
    auto handler = _find_handler(request.get());
    if (handler == nullptr) {
        evhttp_remove_header(evhttp_request_get_input_headers(ev_req), HttpHeaders::EXPECT);
        HttpChannel::send_reply(request.get(), HttpStatus::NOT_FOUND, "Not Found");
        return 0;
    }
    // set handler before call on_header, because handler_ctx will set in on_header
    request->set_handler(handler);
    res = handler->on_header(request.get());
    if (res < 0) {
        // reply has already sent by handler's on_header
        evhttp_remove_header(evhttp_request_get_input_headers(ev_req), HttpHeaders::EXPECT);
        return 0;
    }

    // If request body would be big(greater than 1GB),
    // it is better that request_will_be_read_progressively is set true,
    // this can make body read in chunk, not in total
    if (handler->request_will_be_read_progressively()) {
        evhttp_request_set_chunked_cb(ev_req, on_chunked);
    }

    evhttp_request_set_on_free_cb(ev_req, on_free, request.release());
    return 0;
}

HttpHandler* EvHttpServer::_find_handler(HttpRequest* req) {
    auto& path = req->raw_path();

    HttpHandler* handler = nullptr;

    pthread_rwlock_rdlock(&_rw_lock);
    switch (req->method()) {
    case GET:
        _get_handlers.retrieve(path, &handler, req->params());
        // Static file handler is a fallback handler
        if (handler == nullptr) {
            handler = _static_file_handler;
        }
        break;
    case PUT:
        _put_handlers.retrieve(path, &handler, req->params());
        break;
    case POST:
        _post_handlers.retrieve(path, &handler, req->params());
        break;
    case DELETE:
        _delete_handlers.retrieve(path, &handler, req->params());
        break;
    case HEAD:
        _head_handlers.retrieve(path, &handler, req->params());
        break;
    case OPTIONS:
        _options_handlers.retrieve(path, &handler, req->params());
        break;
    default:
        LOG(WARNING) << "unknown HTTP method, method=" << req->method();
        break;
    }
    pthread_rwlock_unlock(&_rw_lock);
    return handler;
}

} // namespace starrocks
