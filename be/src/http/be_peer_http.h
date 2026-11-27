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

#pragma once

#include <string>

#include "common/status.h"

namespace starrocks {

class HttpClient;

// Requests from this BE to the be_http_port of other BEs, such as the snapshot downloads of clone
// and replication. With be_http_ssl_enable, every BE serves only TLS on be_http_port, so these
// requests use https and verify the peer against be_http_ssl_ca_cert_file.
namespace be_peer_http {

// "https" when be_http_ssl_enable is true, otherwise "http".
const char* scheme();

// With be_http_ssl_enable, checks that be_http_ssl_ca_cert_file is set and readable. Always OK
// when be_http_ssl_enable is false.
Status check_tls_config();

// client->init(url). For an https url, also verifies the peer against be_http_ssl_ca_cert_file
// only, and fails with an error that names the setting if it is empty or unreadable.
Status init_client(HttpClient* client, const std::string& url);

} // namespace be_peer_http
} // namespace starrocks
