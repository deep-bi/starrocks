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
//   https://github.com/apache/incubator-doris/blob/master/be/src/exec/es/es_scan_reader.cpp

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

#include "exec/es/es_scan_reader.h"

#include <map>
#include <sstream>
#include <string>

#include "common/config.h"
#include "common/logging.h"
#include "common/status.h"
#include "exec/es/es_scroll_parser.h"
#include "exec/es/es_scroll_query.h"
#include "fmt/compile.h"
#include "runtime/exec_env.h"
#include "util/priority_thread_pool.hpp"

namespace starrocks {

// hits.hits._id used for obtain ES document `_id`
const std::string SOURCE_SCROLL_SEARCH_FILTER_PATH =
        "filter_path=_scroll_id,hits.hits._source,hits.total,hits.hits._id";
// hits.hits._score used for processing field not exists in one batch
const std::string DOCVALUE_SCROLL_SEARCH_FILTER_PATH =
        "filter_path=_scroll_id,hits.total,hits.hits._score,hits.hits.fields";

const std::string REQUEST_PREFERENCE_PREFIX = "&preference=_shards:";
const std::string REQUEST_SEARCH_SCROLL_PATH = "/_search/scroll";

ESScanReader::ESScanReader(const std::string& target, const std::map<std::string, std::string>& props,
                           bool doc_value_mode)
        : _scroll_keep_alive(config::es_scroll_keepalive),
          _http_timeout_ms(config::es_http_timeout_ms),
          _doc_value_mode(doc_value_mode) {
    _target = target;
    _index = props.at(KEY_INDEX);
    if (props.find(KEY_TYPE) != props.end()) {
        _type = props.at(KEY_TYPE);
    }
    if (props.find(KEY_USER_NAME) != props.end()) {
        _user_name = props.at(KEY_USER_NAME);
    }
    if (props.find(KEY_PASS_WORD) != props.end()) {
        _passwd = props.at(KEY_PASS_WORD);
    }
    if (props.find(KEY_SHARD) != props.end()) {
        _shards = props.at(KEY_SHARD);
    }
    if (props.find(KEY_QUERY) != props.end()) {
        _query = props.at(KEY_QUERY);
    }

    if (props.find(KEY_ES_NET_SSL) != props.end()) {
        std::istringstream(props.at(KEY_ES_NET_SSL)) >> std::boolalpha >> _ssl_enabled;
    }

    std::string batch_size_str = props.at(KEY_BATCH_SIZE);
    _batch_size = atoi(batch_size_str.c_str());
    std::string filter_path = _doc_value_mode ? DOCVALUE_SCROLL_SEARCH_FILTER_PATH : SOURCE_SCROLL_SEARCH_FILTER_PATH;

    if (props.find(KEY_TERMINATE_AFTER) != props.end()) {
        _exactly_once = true;
        // just send a normal search against the elasticsearch with additional terminate_after param to achieve terminate early effect when limit take effect
        if (_type.empty()) {
            _search_url = fmt::format("{}/{}/_search?terminate_after={}&preference=_shards:{}&{}", _target, _index,
                                      props.at(KEY_TERMINATE_AFTER), _shards, filter_path);
        } else {
            _search_url = fmt::format("{}/{}/{}/_search?terminate_after={}&preference=_shards:{}&{}", _target, _index,
                                      _type, props.at(KEY_TERMINATE_AFTER), _shards, filter_path);
        }
    } else {
        _exactly_once = false;
        // scroll request for scanning
        // add terminate_after for the first scroll to avoid decompress all postings list
        if (_type.empty()) {
            _init_scroll_url = fmt::format("{}/{}/_search?scroll={}&preference=_shards:{}&{}", _target, _index,
                                           _scroll_keep_alive, _shards, filter_path);
        } else {
            _init_scroll_url = fmt::format("{}/{}/{}/_search?scroll={}&preference=_shards:{}&{}", _target, _index,
                                           _type, _scroll_keep_alive, _shards, filter_path);
        }
        _next_scroll_url = _target + REQUEST_SEARCH_SCROLL_PATH + "?" + filter_path;
    }
    _eos = false;
}

ESScanReader::~ESScanReader() = default;

Status ESScanReader::open() {
    _is_first = true;
    if (_exactly_once) {
        RETURN_IF_ERROR(_network_client.init(_search_url));
        LOG(INFO) << "search request URL: " << _search_url;
    } else {
        RETURN_IF_ERROR(_network_client.init(_init_scroll_url));
        LOG(INFO) << "First scroll request URL: " << _init_scroll_url;
    }
    if (!_user_name.empty() || !_passwd.empty()) {
        _network_client.set_basic_auth(_user_name, _passwd);
    }
    _network_client.set_content_type("application/json");
    if (_ssl_enabled) {
        _network_client.trust_all_ssl();
    }
    // phase open, we cached the first response for `get_next` phase
    Status status = _network_client.execute_post_request(_query, &_cached_response);
    VLOG(2) << "ES Query:" << _query;
    if (!status.ok() || _network_client.get_http_status() != 200) {
        std::string err_msg = fmt::format("Failed to connect to ES server, errmsg is: {}", status.message());
        return Status::InternalError(err_msg);
    }
    VLOG(2) << "open _cached response: " << _cached_response;
    return Status::OK();
}

template <class T>
Status ESScanReader::get_next(bool* scan_eos, std::unique_ptr<T>& scroll_parser) {
    std::string response;
    // if is first scroll request, should return the cached response
    *scan_eos = true;
    if (_eos) {
        return Status::OK();
    }

    if (_is_first) {
        response = _cached_response;
        _is_first = false;
    } else {
        if (_exactly_once) {
            return Status::OK();
        }
        RETURN_IF_ERROR(_network_client.init(_next_scroll_url));
        if (!_user_name.empty() || !_passwd.empty()) {
            _network_client.set_basic_auth(_user_name, _passwd);
        }
        _network_client.set_content_type("application/json");
        _network_client.set_timeout_ms(_http_timeout_ms);
        if (_ssl_enabled) {
            _network_client.trust_all_ssl();
        }
        RETURN_IF_ERROR(_network_client.execute_post_request(
                ESScrollQueryBuilder::build_next_scroll_body(_scroll_id, _scroll_keep_alive), &response));
        long status = _network_client.get_http_status();
        if (status == 404) {
            LOG(WARNING) << "request scroll search failure 404["
                         << ", response: " << (response.empty() ? "empty response" : response);
            return Status::InternalError("No search context found for " + _scroll_id);
        }
        if (status != 200) {
            LOG(WARNING) << "request scroll search failure["
                         << "http status" << status
                         << ", response: " << (response.empty() ? "empty response" : response);
            return Status::InternalError("request scroll search failure: " +
                                         (response.empty() ? "empty response" : response));
        }
    }

    scroll_parser.reset(new T(_doc_value_mode));
    VLOG(2) << "get_next request ES, returned response: " << response;
    Status status = scroll_parser->parse(response, _exactly_once);
    if (!status.ok()) {
        _eos = true;
        LOG(WARNING) << status.message();
        return status;
    }

    // request ES just only once
    if (_exactly_once) {
        _eos = true;
    } else {
        _scroll_id = scroll_parser->get_scroll_id();
        if (scroll_parser->get_size() == 0) {
            _eos = true;
            return Status::OK();
        }

        _eos = scroll_parser->get_size() < _batch_size;
    }
    *scan_eos = false;
    return Status::OK();
}

template Status ESScanReader::get_next<ScrollParser>(bool* scan_eos, std::unique_ptr<ScrollParser>& scroll_parser);

Status ESScanReader::close() {
    if (_scroll_id.empty()) {
        return Status::OK();
    }

    std::string scratch_target = _target + REQUEST_SEARCH_SCROLL_PATH;
    std::function<void()> send_del_request = [user_name = _user_name, passwd = _passwd, enable_ssl = _ssl_enabled,
                                              scroll_id = _scroll_id, scratch_target]() {
        HttpClient client;
        RETURN_IF(!client.init(scratch_target).ok(), (void)0);
        if (!user_name.empty() || !passwd.empty()) {
            client.set_basic_auth(user_name, passwd);
        }
        client.set_method(DELETE);
        client.set_content_type("application/json");
        client.set_timeout_ms(5 * 1000);
        if (enable_ssl) {
            client.trust_all_ssl();
        }
        std::string response;
        auto payload = ESScrollQueryBuilder::build_clear_scroll_body(scroll_id);
        auto st = client.execute_delete_request(payload, &response);
        if (!st.ok()) {
            LOG(WARNING) << "es delete scroll id failed:" << st.to_string();
            return;
        }
        if (client.get_http_status() != 200) {
            LOG(WARNING) << "es_scan_reader delete scroll context failure status code:" << client.get_http_status();
        }
    };
    auto* thread_pool = ExecEnv::GetInstance()->pipeline_sink_io_pool();
    if (!thread_pool->try_offer(send_del_request)) {
        LOG(WARNING) << "try to delete scroll id failed";
    }
    return Status::OK();
}
} // namespace starrocks
