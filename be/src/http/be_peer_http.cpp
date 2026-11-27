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

#include <unistd.h>

#include <cerrno>
#include <system_error>

#include "common/config.h"
#include "http/http_client.h"

namespace starrocks::be_peer_http {

static const std::string HTTPS_PREFIX = "https://";

static Status check_ca_file(const std::string& ca_file) {
    if (ca_file.empty()) {
        return Status::InvalidArgument(
                "be_http_ssl_ca_cert_file is empty, so this BE cannot verify other BEs over https");
    }
    if (access(ca_file.c_str(), R_OK) != 0) {
        return Status::InvalidArgument("be_http_ssl_ca_cert_file " + ca_file +
                                       " cannot be read: " + std::error_code(errno, std::generic_category()).message());
    }
    return Status::OK();
}

const char* scheme() {
    return config::be_http_ssl_enable ? "https" : "http";
}

Status check_tls_config() {
    if (!config::be_http_ssl_enable) {
        return Status::OK();
    }
    return check_ca_file(config::be_http_ssl_ca_cert_file);
}

Status init_client(HttpClient* client, const std::string& url) {
    RETURN_IF_ERROR(client->init(url));
    if (url.compare(0, HTTPS_PREFIX.size(), HTTPS_PREFIX) != 0) {
        return Status::OK();
    }
    const std::string& ca_file = config::be_http_ssl_ca_cert_file;
    RETURN_IF_ERROR(check_ca_file(ca_file));
    return client->use_ssl_ca_file(ca_file);
}

} // namespace starrocks::be_peer_http
