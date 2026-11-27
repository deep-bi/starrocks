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

#include "http/be_peer_http.h"

#include <gtest/gtest.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <memory>
#include <thread>

#include "common/config.h"
#include "http/ev_http_server.h"
#include "http/http_channel.h"
#include "http/http_client.h"
#include "http/http_handler.h"
#include "http/http_headers.h"
#include "http/http_request.h"
#include "testutil/assert.h"
#include "testutil/sync_point.h"
#include "util/defer_op.h"

namespace starrocks {

namespace {

using KeyPtr = std::unique_ptr<EVP_PKEY, decltype(&EVP_PKEY_free)>;
using CertPtr = std::unique_ptr<X509, decltype(&X509_free)>;
using BioPtr = std::unique_ptr<BIO, decltype(&BIO_free)>;

KeyPtr new_key() {
    EVP_PKEY* key = nullptr;
    std::unique_ptr<EVP_PKEY_CTX, decltype(&EVP_PKEY_CTX_free)> ctx(EVP_PKEY_CTX_new_id(EVP_PKEY_EC, nullptr),
                                                                    EVP_PKEY_CTX_free);
    EXPECT_NE(nullptr, ctx);
    EXPECT_EQ(1, EVP_PKEY_keygen_init(ctx.get()));
    EXPECT_EQ(1, EVP_PKEY_CTX_set_ec_paramgen_curve_nid(ctx.get(), NID_X9_62_prime256v1));
    EXPECT_EQ(1, EVP_PKEY_keygen(ctx.get(), &key));
    return {key, EVP_PKEY_free};
}

void add_extension(X509* cert, X509* issuer, int nid, const char* value) {
    X509V3_CTX ctx;
    X509V3_set_ctx_nodb(&ctx);
    X509V3_set_ctx(&ctx, issuer, cert, nullptr, nullptr, 0);
    X509_EXTENSION* ext = X509V3_EXT_conf_nid(nullptr, &ctx, nid, const_cast<char*>(value));
    ASSERT_NE(nullptr, ext);
    X509_add_ext(cert, ext, -1);
    X509_EXTENSION_free(ext);
}

// A self-signed CA when issuer is null, otherwise a server certificate for IP 127.0.0.1 signed by issuer.
CertPtr new_cert(EVP_PKEY* key, const char* common_name, X509* issuer, EVP_PKEY* issuer_key) {
    static long serial = 1;
    CertPtr cert(X509_new(), X509_free);
    X509_set_version(cert.get(), 2);
    ASN1_INTEGER_set(X509_get_serialNumber(cert.get()), serial++);
    X509_gmtime_adj(X509_getm_notBefore(cert.get()), -3600);
    X509_gmtime_adj(X509_getm_notAfter(cert.get()), 24 * 3600);
    X509_set_pubkey(cert.get(), key);
    X509_NAME_add_entry_by_txt(X509_get_subject_name(cert.get()), "CN", MBSTRING_ASC,
                               reinterpret_cast<const unsigned char*>(common_name), -1, -1, 0);
    X509* signer = issuer != nullptr ? issuer : cert.get();
    X509_set_issuer_name(cert.get(), X509_get_subject_name(signer));
    if (issuer == nullptr) {
        add_extension(cert.get(), signer, NID_basic_constraints, "critical,CA:TRUE");
        add_extension(cert.get(), signer, NID_key_usage, "critical,keyCertSign,cRLSign");
    } else {
        add_extension(cert.get(), signer, NID_basic_constraints, "critical,CA:FALSE");
        add_extension(cert.get(), signer, NID_subject_alt_name, "IP:127.0.0.1");
    }
    EXPECT_GT(X509_sign(cert.get(), issuer_key != nullptr ? issuer_key : key, EVP_sha256()), 0);
    return cert;
}

std::string bio_content(BIO* bio) {
    BUF_MEM* mem = nullptr;
    BIO_get_mem_ptr(bio, &mem);
    return {mem->data, mem->length};
}

std::string to_pem(X509* cert) {
    BioPtr bio(BIO_new(BIO_s_mem()), BIO_free);
    EXPECT_EQ(1, PEM_write_bio_X509(bio.get(), cert));
    return bio_content(bio.get());
}

std::string to_pem(EVP_PKEY* key) {
    BioPtr bio(BIO_new(BIO_s_mem()), BIO_free);
    EXPECT_EQ(1, PEM_write_bio_PrivateKey(bio.get(), key, nullptr, nullptr, 0, nullptr, nullptr));
    return bio_content(bio.get());
}

void write_file(const std::string& path, const std::string& content) {
    std::ofstream out(path);
    out << content;
    ASSERT_TRUE(out.good()) << path;
}

class OkHandler : public HttpHandler {
public:
    void handle(HttpRequest* req) override {
        ++calls;
        HttpChannel::send_reply(req, "ok");
    }
    std::atomic<int> calls{0};
};

class RedirectHandler : public HttpHandler {
public:
    void handle(HttpRequest* req) override {
        req->add_output_header(HttpHeaders::LOCATION, target.c_str());
        HttpChannel::send_reply(req, HttpStatus::TEMPORARY_REDIRECT);
    }
    std::string target;
};

} // namespace

class BePeerHttpTest : public testing::Test {
protected:
    void SetUp() override {
        _ssl_enable = config::be_http_ssl_enable;
        _ca_file = config::be_http_ssl_ca_cert_file;
    }

    void TearDown() override {
        config::be_http_ssl_enable = _ssl_enable;
        config::be_http_ssl_ca_cert_file = _ca_file;
    }

private:
    bool _ssl_enable = false;
    std::string _ca_file;
};

TEST_F(BePeerHttpTest, scheme_follows_be_http_ssl_enable) {
    config::be_http_ssl_enable = false;
    EXPECT_STREQ("http", be_peer_http::scheme());
    config::be_http_ssl_enable = true;
    EXPECT_STREQ("https", be_peer_http::scheme());
}

TEST_F(BePeerHttpTest, no_ca_file_needed_over_http) {
    config::be_http_ssl_enable = false;
    config::be_http_ssl_ca_cert_file = "";
    EXPECT_OK(be_peer_http::check_tls_config());
    HttpClient client;
    EXPECT_OK(be_peer_http::init_client(&client, "http://127.0.0.1:1/api/_tablet/_download"));
}

TEST_F(BePeerHttpTest, https_requires_ca_file) {
    config::be_http_ssl_enable = true;
    config::be_http_ssl_ca_cert_file = "";
    Status st = be_peer_http::check_tls_config();
    ASSERT_TRUE(st.is_invalid_argument()) << st;
    EXPECT_NE(std::string_view::npos, st.message().find("be_http_ssl_ca_cert_file is empty")) << st;

    HttpClient client;
    st = be_peer_http::init_client(&client, "https://127.0.0.1:1/api/_tablet/_download");
    ASSERT_TRUE(st.is_invalid_argument()) << st;
    EXPECT_NE(std::string_view::npos, st.message().find("be_http_ssl_ca_cert_file is empty")) << st;
}

TEST_F(BePeerHttpTest, https_requires_readable_ca_file) {
    config::be_http_ssl_enable = true;
    config::be_http_ssl_ca_cert_file = "/nonexistent/be_peer_http_test/ca.pem";
    Status st = be_peer_http::check_tls_config();
    ASSERT_TRUE(st.is_invalid_argument()) << st;
    EXPECT_NE(std::string_view::npos,
              st.message().find("be_http_ssl_ca_cert_file /nonexistent/be_peer_http_test/ca.pem cannot be read"))
            << st;
}

// Requests against a real TLS-only server, as clone and replication send them.
class BePeerHttpsTest : public BePeerHttpTest {
protected:
    static void SetUpTestSuite() {
        s_dir = std::filesystem::temp_directory_path() / ("be_peer_http_test_" + std::to_string(getpid()));
        std::filesystem::create_directories(s_dir);

        KeyPtr ca_key = new_key();
        CertPtr ca = new_cert(ca_key.get(), "be peer test CA", nullptr, nullptr);
        KeyPtr other_ca_key = new_key();
        CertPtr other_ca = new_cert(other_ca_key.get(), "other test CA", nullptr, nullptr);
        KeyPtr server_key = new_key();
        CertPtr server = new_cert(server_key.get(), "127.0.0.1", ca.get(), ca_key.get());
        s_trusted_ca_file = (s_dir / "ca.pem").string();
        s_other_ca_file = (s_dir / "other_ca.pem").string();
        write_file(s_trusted_ca_file, to_pem(ca.get()));
        write_file(s_other_ca_file, to_pem(other_ca.get()));

        s_saved_ssl_enable = config::be_http_ssl_enable;
        s_saved_cert = config::ssl_certificate_path;
        s_saved_key = config::ssl_private_key_path;

        // A filtered run can leave one server unused. Its worker must still be running its event loop before
        // TearDownTestSuite stops it: a loopbreak issued before the worker enters event_base_loop() is lost,
        // and join() then hangs.
        std::atomic<int> workers_in_dispatch{0};
        SyncPoint::GetInstance()->SetCallBack("EvHttpServer::worker:in_dispatch",
                                              [&](void*) { workers_in_dispatch.fetch_add(1); });
        SyncPoint::GetInstance()->EnableProcessing();
        DeferOp defer([]() {
            SyncPoint::GetInstance()->DisableProcessing();
            SyncPoint::GetInstance()->ClearCallBack("EvHttpServer::worker:in_dispatch");
        });

        config::be_http_ssl_enable = false;
        s_plain_server = new EvHttpServer(0);
        s_plain_server->register_handler(GET, "/ok", &s_plain_ok);
        ASSERT_OK(s_plain_server->start());
        s_plain_url = "http://127.0.0.1:" + std::to_string(s_plain_server->get_real_port());

        // The server reads the certificate and key as inline PEM, like ${BE_TLS_CERT} in be.conf.
        config::be_http_ssl_enable = true;
        config::ssl_certificate_path = to_pem(server.get());
        config::ssl_private_key_path = to_pem(server_key.get());
        s_tls_server = new EvHttpServer(0);
        s_tls_server->register_handler(GET, "/ok", &s_tls_ok);
        s_tls_server->register_handler(GET, "/redirect", &s_redirect);
        ASSERT_OK(s_tls_server->start());
        s_tls_port = s_tls_server->get_real_port();
        s_https_url = "https://127.0.0.1:" + std::to_string(s_tls_port);
        s_redirect.target = s_plain_url + "/ok";

        auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
        while (workers_in_dispatch.load() < 2 && std::chrono::steady_clock::now() < deadline) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        ASSERT_EQ(2, workers_in_dispatch.load());
    }

    static void TearDownTestSuite() {
        for (EvHttpServer* server : {s_tls_server, s_plain_server}) {
            if (server != nullptr) {
                server->stop();
                server->join();
                delete server;
            }
        }
        s_tls_server = nullptr;
        s_plain_server = nullptr;
        config::be_http_ssl_enable = s_saved_ssl_enable;
        config::ssl_certificate_path = s_saved_cert;
        config::ssl_private_key_path = s_saved_key;
        std::filesystem::remove_all(s_dir);
    }

    void SetUp() override {
        BePeerHttpTest::SetUp();
        config::be_http_ssl_enable = true;
        config::be_http_ssl_ca_cert_file = s_trusted_ca_file;
    }

    static Status get(const std::string& url, std::string* response) {
        HttpClient client;
        RETURN_IF_ERROR(be_peer_http::init_client(&client, url));
        client.set_timeout_ms(10000);
        return client.execute(response);
    }

    inline static std::filesystem::path s_dir;
    inline static std::string s_trusted_ca_file;
    inline static std::string s_other_ca_file;
    inline static bool s_saved_ssl_enable = false;
    inline static std::string s_saved_cert;
    inline static std::string s_saved_key;
    inline static EvHttpServer* s_plain_server = nullptr;
    inline static EvHttpServer* s_tls_server = nullptr;
    inline static OkHandler s_plain_ok;
    inline static OkHandler s_tls_ok;
    inline static RedirectHandler s_redirect;
    inline static std::string s_plain_url;
    inline static std::string s_https_url;
    inline static int s_tls_port = 0;
};

TEST_F(BePeerHttpsTest, trusted_ca_verifies_peer) {
    std::string response;
    ASSERT_OK(get(s_https_url + "/ok", &response));
    EXPECT_EQ("ok", response);
}

TEST_F(BePeerHttpsTest, peer_signed_by_another_ca_is_rejected) {
    config::be_http_ssl_ca_cert_file = s_other_ca_file;
    int calls = s_tls_ok.calls.load();
    std::string response;
    Status st = get(s_https_url + "/ok", &response);
    ASSERT_FALSE(st.ok());
    EXPECT_NE(std::string_view::npos, st.message().find("SSL certificate problem")) << st;
    EXPECT_EQ(calls, s_tls_ok.calls.load());
}

TEST_F(BePeerHttpsTest, host_not_in_certificate_is_rejected) {
    int calls = s_tls_ok.calls.load();
    std::string response;
    // The server listens on 0.0.0.0; its certificate names only 127.0.0.1.
    Status st = get("https://127.0.0.2:" + std::to_string(s_tls_port) + "/ok", &response);
    ASSERT_FALSE(st.ok());
    EXPECT_NE(std::string_view::npos,
              st.message().find("no alternative certificate subject name matches target host name '127.0.0.2'"))
            << st;
    EXPECT_EQ(calls, s_tls_ok.calls.load());
}

TEST_F(BePeerHttpsTest, redirect_to_http_is_not_followed) {
    // Control: a client without the peer options follows the redirect to the plain server.
    HttpClient unrestricted;
    ASSERT_OK(unrestricted.init(s_https_url + "/redirect"));
    unrestricted.trust_all_ssl();
    unrestricted.set_timeout_ms(10000);
    std::string response;
    int calls = s_plain_ok.calls.load();
    ASSERT_OK(unrestricted.execute(&response));
    ASSERT_EQ(calls + 1, s_plain_ok.calls.load());

    response.clear();
    Status st = get(s_https_url + "/redirect", &response);
    ASSERT_FALSE(st.ok());
    EXPECT_NE(std::string_view::npos, st.message().find("Protocol \"http\" not supported or disabled in libcurl"))
            << st;
    EXPECT_EQ(calls + 1, s_plain_ok.calls.load()) << st;
}

TEST_F(BePeerHttpsTest, http_peer_without_https) {
    config::be_http_ssl_enable = false;
    config::be_http_ssl_ca_cert_file = "";
    std::string response;
    ASSERT_OK(get(std::string(be_peer_http::scheme()) + s_plain_url.substr(4) + "/ok", &response));
    EXPECT_EQ("ok", response);
}

} // namespace starrocks
