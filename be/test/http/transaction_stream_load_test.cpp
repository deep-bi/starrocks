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

#include "http/action/transaction_stream_load.h"

#include <event2/buffer.h>
#include <event2/http.h>
#include <event2/http_struct.h>
#include <gtest/gtest.h>
#include <rapidjson/document.h>

#include <atomic>
#include <cstring>
#include <string>

#include "agent/master_info.h"
#include "common/config.h"
#include "common/process_exit.h"
#include "gen_cpp/FrontendService_types.h"
#include "gen_cpp/HeartbeatService_types.h"
#include "http/http_channel.h"
#include "http/http_headers.h"
#include "http/http_request.h"
#include "runtime/exec_env.h"
#include "runtime/stream_load/load_stream_mgr.h"
#include "runtime/stream_load/stream_load_executor.h"
#include "runtime/stream_load/transaction_mgr.h"
#include "testutil/assert.h"
#include "testutil/sync_point.h"
#include "util/brpc_stub_cache.h"
#include "util/cpu_info.h"
#include "util/network_util.h"
#include "util/time.h"

class mg_connection;

namespace starrocks {
extern void (*s_injected_send_reply)(HttpRequest*, HttpStatus, std::string_view);
extern std::atomic<bool> k_starrocks_exit;
extern std::atomic<bool> k_starrocks_force_reject;
extern std::atomic<int64_t> k_starrocks_exit_start_ms;
extern std::atomic<int64_t> k_starrocks_fe_aware_shutdown_ms;
namespace {

static std::string k_response_str;
static HttpStatus k_response_status = HttpStatus::OK;
static void inject_send_reply(HttpRequest* request, HttpStatus status, std::string_view content) {
    k_response_status = status;
    k_response_str = content;
}
} // namespace

extern TLoadTxnBeginResult k_stream_load_begin_result;
extern TLoadTxnCommitResult k_stream_load_commit_result;
extern TLoadTxnRollbackResult k_stream_load_rollback_result;
extern TStreamLoadPutResult k_stream_load_put_result;

class TransactionStreamLoadActionTest : public testing::Test {
public:
    TransactionStreamLoadActionTest() = default;
    ~TransactionStreamLoadActionTest() override = default;
    static void SetUpTestSuite() { s_injected_send_reply = inject_send_reply; }
    static void TearDownTestSuite() { s_injected_send_reply = nullptr; }
    void SetUp() override {
        k_starrocks_exit.store(false);
        k_starrocks_force_reject.store(false);
        k_starrocks_exit_start_ms.store(0);
        clear_frontend_aware_of_exit();
        k_stream_load_begin_result = TLoadTxnBeginResult();
        k_stream_load_commit_result = TLoadTxnCommitResult();
        k_stream_load_rollback_result = TLoadTxnRollbackResult();
        k_stream_load_put_result = TStreamLoadPutResult();
        k_response_str = "";
        config::streaming_load_max_mb = 1;

        _env._load_stream_mgr = new LoadStreamMgr();
        _env._brpc_stub_cache = new BrpcStubCache(&_env);
        _env._stream_load_executor = new StreamLoadExecutor(&_env);
        _env._stream_context_mgr = new StreamContextMgr();
        _env._transaction_mgr = new TransactionMgr(&_env);

        _evhttp_req = evhttp_request_new(nullptr, nullptr);
        _evhttp_req->remote_host = nullptr;
    }
    void TearDown() override {
        delete _env._transaction_mgr;
        _env._transaction_mgr = nullptr;
        delete _env._stream_context_mgr;
        _env._stream_context_mgr = nullptr;
        delete _env._brpc_stub_cache;
        _env._brpc_stub_cache = nullptr;
        delete _env._load_stream_mgr;
        _env._load_stream_mgr = nullptr;
        delete _env._stream_load_executor;
        _env._stream_load_executor = nullptr;

        if (_evhttp_req != nullptr) {
            evhttp_request_free(_evhttp_req);
        }
        k_starrocks_exit.store(false);
        k_starrocks_force_reject.store(false);
        k_starrocks_exit_start_ms.store(0);
        clear_frontend_aware_of_exit();
        TMasterInfo restored;
        restored.__set_network_address(make_network_address("127.0.0.1", 8030));
        restored.__set_http_port(8030);
        (void)update_master_info(restored);
    }

protected:
    ExecEnv _env;
    evhttp_request* _evhttp_req = nullptr;
};

TEST_F(TransactionStreamLoadActionTest, txn_begin_no_auth) {
    TransactionManagerAction txn_action(&_env);

    HttpRequest b(_evhttp_req);
    b._headers.emplace(HTTP_LABEL_KEY, "123");
    b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&b);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("INTERNAL_ERROR", doc["Status"].GetString());
}

TEST_F(TransactionStreamLoadActionTest, txn_begin_invalid) {
    TransactionManagerAction txn_action(&_env);

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("INVALID_ARGUMENT", doc["Status"].GetString());
    }

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("INVALID_ARGUMENT", doc["Status"].GetString());
    }

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, "xxx");
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("INVALID_ARGUMENT", doc["Status"].GetString());
    }
}

TEST_F(TransactionStreamLoadActionTest, txn_begin_normal) {
    TransactionManagerAction txn_action(&_env);

    HttpRequest b(_evhttp_req);
    b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    b._headers.emplace(HTTP_LABEL_KEY, "123");
    b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&b);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("OK", doc["Status"].GetString());

    auto* val = evhttp_find_header(evhttp_request_get_output_headers(_evhttp_req), "Content-Type");
    ASSERT_NE(val, nullptr);
    ASSERT_STREQ("application/json", val);
}
TEST_F(TransactionStreamLoadActionTest, txn_begin_accepts_until_shutdown_delay) {
    ASSERT_TRUE(set_process_exit());

    TransactionManagerAction txn_action(&_env);
    HttpRequest b(_evhttp_req);
    b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    b._headers.emplace(HTTP_LABEL_KEY, "123");
    b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&b);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("OK", doc["Status"].GetString());
}

TEST_F(TransactionStreamLoadActionTest, txn_begin_duplicate_label_within_delay) {
    ASSERT_TRUE(set_process_exit());

    TransactionManagerAction txn_action(&_env);
    HttpRequest begin(_evhttp_req);
    begin._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    begin._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    begin._headers.emplace(HTTP_LABEL_KEY, "123");
    begin._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&begin);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("OK", doc["Status"].GetString());

    // A duplicate BEGIN while admission is still open takes the standard label conflict:
    // the delay window does not make a retry idempotent.
    k_response_str.clear();
    HttpRequest retry(_evhttp_req);
    retry._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    retry._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    retry._headers.emplace(HTTP_LABEL_KEY, "123");
    retry._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&retry);

    rapidjson::Document retry_doc;
    retry_doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("LABEL_ALREADY_EXISTS", retry_doc["Status"].GetString());
}

TEST_F(TransactionStreamLoadActionTest, txn_begin_redirects_after_shutdown_delay) {
    TMasterInfo master_info;
    master_info.__set_network_address(make_network_address("127.0.0.1", 8030));
    master_info.__set_http_port(8030);
    ASSERT_TRUE(update_master_info(master_info));

    k_response_str.clear();
    k_starrocks_exit.store(true);
    k_starrocks_fe_aware_shutdown_ms.store(MonotonicMillis() - config::graceful_exit_reject_delay_ms - 1);

    TransactionManagerAction txn_action(&_env);
    HttpRequest b(_evhttp_req);
    b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    b._headers.emplace(HTTP_LABEL_KEY, "123");
    b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&b);

    // Redirected (307) to the FE leader: no JSON body, a Location header instead.
    ASSERT_EQ(k_response_status, HttpStatus::TEMPORARY_REDIRECT);
    ASSERT_TRUE(k_response_str.empty());
    auto* location = evhttp_find_header(evhttp_request_get_output_headers(_evhttp_req), HttpHeaders::LOCATION);
    ASSERT_NE(location, nullptr);
    ASSERT_NE(std::strstr(location, "http://127.0.0.1:8030"), nullptr);
}

TEST_F(TransactionStreamLoadActionTest, txn_begin_retry_redirected_after_shutdown_delay) {
    TMasterInfo master_info;
    master_info.__set_network_address(make_network_address("127.0.0.1", 8030));
    master_info.__set_http_port(8030);
    ASSERT_TRUE(update_master_info(master_info));

    TransactionManagerAction txn_action(&_env);
    HttpRequest begin(_evhttp_req);
    begin._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    begin._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    begin._headers.emplace(HTTP_LABEL_KEY, "123");
    begin._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&begin);

    k_response_str.clear();
    k_starrocks_exit.store(true);
    k_starrocks_fe_aware_shutdown_ms.store(MonotonicMillis() - config::graceful_exit_reject_delay_ms - 1);

    // The admission gate does not inspect the label: even a retry whose transaction already
    // began here is redirected like any other BEGIN (no idempotent pass-through).
    HttpRequest retry(_evhttp_req);
    retry._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    retry._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    retry._headers.emplace(HTTP_LABEL_KEY, "123");
    retry._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&retry);

    ASSERT_EQ(k_response_status, HttpStatus::TEMPORARY_REDIRECT);
    ASSERT_TRUE(k_response_str.empty());
    auto* location = evhttp_find_header(evhttp_request_get_output_headers(_evhttp_req), HttpHeaders::LOCATION);
    ASSERT_NE(location, nullptr);
    ASSERT_NE(std::strstr(location, "http://127.0.0.1:8030"), nullptr);
}

TEST_F(TransactionStreamLoadActionTest, txn_begin_rejects_after_shutdown_fallback) {
    TMasterInfo master_info;
    master_info.__set_network_address(make_network_address("127.0.0.1", 8030));
    master_info.__set_http_port(8030);
    ASSERT_TRUE(update_master_info(master_info));

    TransactionManagerAction txn_action(&_env);
    HttpRequest begin(_evhttp_req);
    begin._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    begin._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    begin._headers.emplace(HTTP_LABEL_KEY, "123");
    begin._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&begin);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("OK", doc["Status"].GetString());

    // Fallback cutoff without an FE ack: ServiceUnavailable, and no redirect (an unaware FE
    // could route the retry straight back to this BE).
    k_response_str.clear();
    k_starrocks_exit.store(true);
    k_starrocks_exit_start_ms.store(MonotonicMillis() - config::graceful_exit_reject_fallback_ms - 1);

    HttpRequest retry(_evhttp_req);
    retry._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    retry._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    retry._headers.emplace(HTTP_LABEL_KEY, "123");
    retry._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&retry);

    rapidjson::Document retry_doc;
    retry_doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("SERVICE_UNAVAILABLE", retry_doc["Status"].GetString());
    ASSERT_EQ(nullptr, evhttp_find_header(evhttp_request_get_output_headers(_evhttp_req), HttpHeaders::LOCATION));
}

TEST_F(TransactionStreamLoadActionTest, txn_begin_rejects_when_force_reject) {
    force_reject_exec_plan_fragment();
    TransactionManagerAction txn_action(&_env);
    HttpRequest begin(_evhttp_req);
    begin._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    begin._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    begin._headers.emplace(HTTP_LABEL_KEY, "123");
    begin._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&begin);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("SERVICE_UNAVAILABLE", doc["Status"].GetString());
    ASSERT_EQ(nullptr, evhttp_find_header(evhttp_request_get_output_headers(_evhttp_req), HttpHeaders::LOCATION));
    ASSERT_EQ(nullptr, _env.stream_context_mgr()->get("123"));
}

TEST_F(TransactionStreamLoadActionTest, txn_begin_no_redirect_when_fe_address_unset) {
    TMasterInfo master_info;
    master_info.__set_http_port(0);
    ASSERT_TRUE(update_master_info(master_info));

    k_response_str.clear();
    k_starrocks_exit.store(true);
    k_starrocks_fe_aware_shutdown_ms.store(MonotonicMillis() - config::graceful_exit_reject_delay_ms - 1);

    TransactionManagerAction txn_action(&_env);
    HttpRequest b(_evhttp_req);
    b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    b._headers.emplace(HTTP_LABEL_KEY, "123");
    b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&b);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("SERVICE_UNAVAILABLE", doc["Status"].GetString());
    ASSERT_EQ(nullptr, evhttp_find_header(evhttp_request_get_output_headers(_evhttp_req), HttpHeaders::LOCATION));
}

TEST_F(TransactionStreamLoadActionTest, txn_begin_no_redirect_after_leader_handover) {
    TMasterInfo master_info;
    master_info.__set_network_address(make_network_address("127.0.0.1", 8030));
    master_info.__set_http_port(8030);
    ASSERT_TRUE(update_master_info(master_info));

    // Redirect is only possible once the FE has observed the shutdown heartbeat; the
    // leader-handover scenario below starts from that state.
    k_starrocks_exit.store(true);
    k_starrocks_fe_aware_shutdown_ms.store(MonotonicMillis() - config::graceful_exit_reject_delay_ms - 1);

    // The first FE acknowledges and opens the delay window.
    ASSERT_FALSE(advance_heartbeat_ack("127.0.0.1:8030:1", 100));
    ASSERT_TRUE(advance_heartbeat_ack("127.0.0.1:8030:1", 101));
    ASSERT_TRUE(may_redirect_to_fe_leader());

    // A different leader starts acking (epoch changed): redirect is disabled for the rest of
    // this shutdown, so the cutoff reply is ServiceUnavailable without a Location header.
    ASSERT_FALSE(advance_heartbeat_ack("127.0.0.1:8030:2", 900));
    ASSERT_TRUE(advance_heartbeat_ack("127.0.0.1:8030:2", 901));
    ASSERT_FALSE(may_redirect_to_fe_leader());

    TransactionManagerAction txn_action(&_env);
    HttpRequest b(_evhttp_req);
    b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    b._headers.emplace(HTTP_LABEL_KEY, "123");
    b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&b);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("SERVICE_UNAVAILABLE", doc["Status"].GetString());
    ASSERT_EQ(nullptr, evhttp_find_header(evhttp_request_get_output_headers(_evhttp_req), HttpHeaders::LOCATION));
}

TEST_F(TransactionStreamLoadActionTest, txn_begin_no_redirect_on_legacy_fe) {
    // Legacy FE omits last_heartbeat_time_ms: delay still opens, but BEGIN 307 is off so the
    // old FE cannot bounce the client back to this BE.
    TMasterInfo master_info;
    master_info.__set_network_address(make_network_address("127.0.0.1", 8030));
    master_info.__set_http_port(8030);
    ASSERT_TRUE(update_master_info(master_info));

    ASSERT_TRUE(set_process_exit());
    set_frontend_aware_of_exit();
    disable_begin_redirect();
    ASSERT_TRUE(should_accept_new_request());
    ASSERT_FALSE(may_redirect_to_fe_leader());

    TransactionManagerAction txn_action(&_env);
    HttpRequest begin(_evhttp_req);
    begin._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    begin._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    begin._headers.emplace(HTTP_LABEL_KEY, "legacy");
    begin._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&begin);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_EQ(k_response_status, HttpStatus::OK);
    ASSERT_STREQ("OK", doc["Status"].GetString());
    ASSERT_EQ(nullptr, evhttp_find_header(evhttp_request_get_output_headers(_evhttp_req), HttpHeaders::LOCATION));

    k_response_str.clear();
    k_starrocks_fe_aware_shutdown_ms.store(MonotonicMillis() - config::graceful_exit_reject_delay_ms - 1);
    ASSERT_FALSE(should_accept_new_request());

    HttpRequest retry(_evhttp_req);
    retry._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    retry._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    retry._headers.emplace(HTTP_LABEL_KEY, "legacy");
    retry._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&retry);

    rapidjson::Document retry_doc;
    retry_doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("SERVICE_UNAVAILABLE", retry_doc["Status"].GetString());
    ASSERT_EQ(nullptr, evhttp_find_header(evhttp_request_get_output_headers(_evhttp_req), HttpHeaders::LOCATION));
}
TEST_F(TransactionStreamLoadActionTest, txn_begin_rejects_after_fallback_without_heartbeat) {
    // The fallback deadline can close admission before the FE has observed the shutdown heartbeat.
    // A redirect would let the unaware FE pick this BE again, so reply ServiceUnavailable instead.
    TMasterInfo master_info;
    master_info.__set_network_address(make_network_address("127.0.0.1", 8030));
    master_info.__set_http_port(8030);
    ASSERT_TRUE(update_master_info(master_info));

    k_response_str.clear();
    k_starrocks_exit.store(true);
    k_starrocks_exit_start_ms.store(MonotonicMillis() - config::graceful_exit_reject_fallback_ms - 1);
    ASSERT_FALSE(should_accept_new_request());
    ASSERT_FALSE(is_frontend_aware_of_exit());

    TransactionManagerAction txn_action(&_env);
    HttpRequest b(_evhttp_req);
    b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    b._headers.emplace(HTTP_LABEL_KEY, "123");
    b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&b);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("SERVICE_UNAVAILABLE", doc["Status"].GetString());
    ASSERT_EQ(nullptr, evhttp_find_header(evhttp_request_get_output_headers(_evhttp_req), HttpHeaders::LOCATION));
}

TEST_F(TransactionStreamLoadActionTest, txn_commit_fail) {
    TransactionManagerAction txn_action(&_env);

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_COMMIT);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("INTERNAL_ERROR", doc["Status"].GetString());
    }

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_COMMIT);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("TXN_NOT_EXISTS", doc["Status"].GetString());
    }
}

TEST_F(TransactionStreamLoadActionTest, txn_prepare_fail) {
    TransactionManagerAction txn_action(&_env);

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_COMMIT);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("INTERNAL_ERROR", doc["Status"].GetString());
    }

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_PREPARE);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("TXN_NOT_EXISTS", doc["Status"].GetString());
    }
}

TEST_F(TransactionStreamLoadActionTest, txn_rollback) {
    TransactionManagerAction txn_action(&_env);

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_ROLLBACK);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_ROLLBACK);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("TXN_NOT_EXISTS", doc["Status"].GetString());
    }
}

TEST_F(TransactionStreamLoadActionTest, txn_commit_success_after_shutdown_cutoff) {
    TransactionManagerAction txn_action(&_env);

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }
    ASSERT_TRUE(set_process_exit());
    k_starrocks_fe_aware_shutdown_ms.store(MonotonicMillis() - config::graceful_exit_reject_delay_ms - 1);
    ASSERT_FALSE(should_accept_new_request());

    {
        TransactionStreamLoadAction action(&_env);

        HttpRequest request(_evhttp_req);
        request.set_handler(&action);

        request._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        request._headers.emplace(HttpHeaders::CONTENT_LENGTH, "16");
        request._headers.emplace(HTTP_LABEL_KEY, "123");
        action.on_header(&request);
        action.handle(&request);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_COMMIT);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }
}

// Setup transaction stream load flow for prepare testing
void setup_prepare_txn_test(TransactionManagerAction& txn_action, ExecEnv* env, evhttp_request* ev_request) {
    // Begin transaction
    HttpRequest b(ev_request);
    b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    b._headers.emplace(HTTP_LABEL_KEY, "123");
    b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
    txn_action.handle(&b);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("OK", doc["Status"].GetString());

    // Perform load
    TransactionStreamLoadAction action(env);
    HttpRequest request(ev_request);
    request.set_handler(&action);

    request._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    request._headers.emplace(HttpHeaders::CONTENT_LENGTH, "16");
    request._headers.emplace(HTTP_LABEL_KEY, "123");
    action.on_header(&request);
    action.handle(&request);

    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("OK", doc["Status"].GetString());
}

TEST_F(TransactionStreamLoadActionTest, txn_prepared_success_without_timeout) {
    TransactionManagerAction txn_action(&_env);
    setup_prepare_txn_test(txn_action, &_env, _evhttp_req);

    // Enable sync point to capture the prepared_timeout_second value
    SyncPoint::GetInstance()->EnableProcessing();
    DeferOp defer([]() {
        SyncPoint::GetInstance()->ClearCallBack("StreamLoadExecutor::prepare_txn:rpc");
        SyncPoint::GetInstance()->DisableProcessing();
    });

    SyncPoint::GetInstance()->SetCallBack("StreamLoadExecutor::prepare_txn:rpc", [&](void* arg) {
        auto* request = static_cast<TLoadTxnCommitRequest*>(arg);
        EXPECT_FALSE(request->__isset.prepared_timeout_second);
    });

    HttpRequest b(_evhttp_req);
    b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    b._headers.emplace(HTTP_LABEL_KEY, "123");
    b._params.emplace(HTTP_TXN_OP_KEY, TXN_PREPARE);
    txn_action.handle(&b);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("OK", doc["Status"].GetString());
}

TEST_F(TransactionStreamLoadActionTest, txn_prepared_success_with_timeout) {
    TransactionManagerAction txn_action(&_env);
    setup_prepare_txn_test(txn_action, &_env, _evhttp_req);

    // Enable sync point to capture the prepared_timeout_second value
    SyncPoint::GetInstance()->EnableProcessing();
    DeferOp defer([]() {
        SyncPoint::GetInstance()->ClearCallBack("StreamLoadExecutor::prepare_txn:rpc");
        SyncPoint::GetInstance()->DisableProcessing();
    });

    SyncPoint::GetInstance()->SetCallBack("StreamLoadExecutor::prepare_txn:rpc", [&](void* arg) {
        auto* request = static_cast<TLoadTxnCommitRequest*>(arg);
        EXPECT_TRUE(request->__isset.prepared_timeout_second);
        EXPECT_EQ(300, request->prepared_timeout_second);
    });

    HttpRequest b(_evhttp_req);
    b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    b._headers.emplace(HTTP_LABEL_KEY, "123");
    b._headers.emplace(HTTP_PREPARED_TIMEOUT, "300");
    b._params.emplace(HTTP_TXN_OP_KEY, TXN_PREPARE);
    txn_action.handle(&b);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("OK", doc["Status"].GetString());
}

TEST_F(TransactionStreamLoadActionTest, txn_prepared_with_invalid_timeout) {
    TransactionManagerAction txn_action(&_env);
    setup_prepare_txn_test(txn_action, &_env, _evhttp_req);

    // Test invalid timeout format
    HttpRequest b(_evhttp_req);
    b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    b._headers.emplace(HTTP_LABEL_KEY, "123");
    b._headers.emplace(HTTP_PREPARED_TIMEOUT, "invalid_timeout");
    b._params.emplace(HTTP_TXN_OP_KEY, TXN_PREPARE);
    txn_action.handle(&b);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("INVALID_ARGUMENT", doc["Status"].GetString());
    ASSERT_NE(nullptr, std::strstr(doc["Message"].GetString(), "Invalid prepared_timeout: invalid_timeout"));
}

TEST_F(TransactionStreamLoadActionTest, txn_prepared_with_negative_timeout) {
    TransactionManagerAction txn_action(&_env);
    setup_prepare_txn_test(txn_action, &_env, _evhttp_req);

    // Test negative timeout value
    HttpRequest b(_evhttp_req);
    b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    b._headers.emplace(HTTP_LABEL_KEY, "123");
    b._headers.emplace(HTTP_PREPARED_TIMEOUT, "-1");
    b._params.emplace(HTTP_TXN_OP_KEY, TXN_PREPARE);
    txn_action.handle(&b);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("INVALID_ARGUMENT", doc["Status"].GetString());
    ASSERT_NE(nullptr, std::strstr(doc["Message"].GetString(), "Invalid prepared_timeout: -1"));
}

TEST_F(TransactionStreamLoadActionTest, txn_prepared_with_zero_timeout) {
    TransactionManagerAction txn_action(&_env);
    setup_prepare_txn_test(txn_action, &_env, _evhttp_req);

    // Test zero timeout value
    HttpRequest b(_evhttp_req);
    b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
    b._headers.emplace(HTTP_LABEL_KEY, "123");
    b._headers.emplace(HTTP_PREPARED_TIMEOUT, "0");
    b._params.emplace(HTTP_TXN_OP_KEY, TXN_PREPARE);
    txn_action.handle(&b);

    rapidjson::Document doc;
    doc.Parse(k_response_str.c_str());
    ASSERT_STREQ("INVALID_ARGUMENT", doc["Status"].GetString());
    ASSERT_NE(nullptr, std::strstr(doc["Message"].GetString(), "Invalid prepared_timeout: 0"));
}

TEST_F(TransactionStreamLoadActionTest, txn_put_fail) {
    TransactionManagerAction txn_action(&_env);

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    {
        TransactionStreamLoadAction action(&_env);

        HttpRequest request(_evhttp_req);
        request.set_handler(&action);

        request._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        request._headers.emplace(HttpHeaders::CONTENT_LENGTH, "16");
        request._headers.emplace(HTTP_LABEL_KEY, "123");
        Status status = Status::InternalError("TestFail");
        status.to_thrift(&k_stream_load_put_result.status);
        action.on_header(&request);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("INTERNAL_ERROR", doc["Status"].GetString());
    }

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_COMMIT);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("TXN_NOT_EXISTS", doc["Status"].GetString());
    }
}

TEST_F(TransactionStreamLoadActionTest, txn_commit_fe_fail) {
    TransactionManagerAction txn_action(&_env);

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    {
        TransactionStreamLoadAction action(&_env);

        HttpRequest request(_evhttp_req);
        request.set_handler(&action);

        request._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        request._headers.emplace(HttpHeaders::CONTENT_LENGTH, "16");
        request._headers.emplace(HTTP_LABEL_KEY, "123");
        action.on_header(&request);
        action.handle(&request);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_COMMIT);
        Status status = Status::InternalError("TestFail");
        status.to_thrift(&k_stream_load_commit_result.status);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("INTERNAL_ERROR", doc["Status"].GetString());
    }
}

TEST_F(TransactionStreamLoadActionTest, txn_prepare_fe_fail) {
    TransactionManagerAction txn_action(&_env);

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    {
        TransactionStreamLoadAction action(&_env);

        HttpRequest request(_evhttp_req);
        request.set_handler(&action);

        request._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        request._headers.emplace(HttpHeaders::CONTENT_LENGTH, "16");
        request._headers.emplace(HTTP_LABEL_KEY, "123");
        action.on_header(&request);
        action.handle(&request);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_PREPARE);
        Status status = Status::InternalError("TestFail");
        status.to_thrift(&k_stream_load_commit_result.status);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("INTERNAL_ERROR", doc["Status"].GetString());
    }
}

TEST_F(TransactionStreamLoadActionTest, txn_begin_fe_fail) {
    TransactionManagerAction txn_action(&_env);

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
        Status status = Status::InternalError("TestFail");
        status.to_thrift(&k_stream_load_begin_result.status);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("INTERNAL_ERROR", doc["Status"].GetString());
    }
}

TEST_F(TransactionStreamLoadActionTest, txn_plan_fail) {
    TransactionManagerAction txn_action(&_env);

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    {
        SyncPoint::GetInstance()->EnableProcessing();
        SyncPoint::GetInstance()->SetCallBack("StreamLoadExecutor::execute_plan_fragment:1",
                                              [](void* arg) { *(Status*)arg = Status::InternalError("TestFail"); });
        TransactionStreamLoadAction action(&_env);

        HttpRequest request(_evhttp_req);
        request.set_handler(&action);

        request._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        request._headers.emplace(HttpHeaders::CONTENT_LENGTH, "16");
        request._headers.emplace(HTTP_LABEL_KEY, "123");
        action.on_header(&request);
        action.handle(&request);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());

        SyncPoint::GetInstance()->ClearCallBack("StreamLoadExecutor::execute_plan_fragment:1");
        SyncPoint::GetInstance()->DisableProcessing();
    }
}

TEST_F(TransactionStreamLoadActionTest, txn_list) {
    TransactionManagerAction txn_action(&_env);

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_LIST);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
        ASSERT_STREQ("123", doc["Label"].GetString());
    }
}

TEST_F(TransactionStreamLoadActionTest, txn_list_allowed_after_shutdown_cutoff) {
    ASSERT_TRUE(set_process_exit());
    k_starrocks_fe_aware_shutdown_ms.store(MonotonicMillis() - config::graceful_exit_reject_delay_ms - 1);

    TransactionManagerAction txn_action(&_env);
    HttpRequest request(_evhttp_req);
    request._params.emplace(HTTP_TXN_OP_KEY, TXN_LIST);
    txn_action.handle(&request);

    // LIST is read-only and remains available while new writes are rejected.
    ASSERT_TRUE(k_response_str.empty());
}

TEST_F(TransactionStreamLoadActionTest, txn_idle_timeout) {
    TransactionManagerAction txn_action(&_env);

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._headers.emplace(HTTP_IDLE_TRANSACTION_TIMEOUT, "1");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    sleep(4);

    {
        TransactionStreamLoadAction action(&_env);

        HttpRequest request(_evhttp_req);
        request.set_handler(&action);

        request._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        request._headers.emplace(HttpHeaders::CONTENT_LENGTH, "16");
        request._headers.emplace(HTTP_LABEL_KEY, "123");
        action.on_header(&request);
        action.handle(&request);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("TXN_NOT_EXISTS", doc["Status"].GetString());
    }
}

TEST_F(TransactionStreamLoadActionTest, txn_not_same_load) {
    TransactionManagerAction txn_action(&_env);

    {
        HttpRequest b(_evhttp_req);
        b._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        b._headers.emplace(HttpHeaders::CONTENT_LENGTH, "0");
        b._headers.emplace(HTTP_DB_KEY, "db");
        b._headers.emplace(HTTP_LABEL_KEY, "123");
        b._params.emplace(HTTP_TXN_OP_KEY, TXN_BEGIN);
        txn_action.handle(&b);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    TransactionStreamLoadAction action(&_env);
    {
        HttpRequest request(_evhttp_req);
        request.set_handler(&action);

        request._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        request._headers.emplace(HttpHeaders::CONTENT_LENGTH, "16");
        request._headers.emplace(HTTP_DB_KEY, "db");
        request._headers.emplace(HTTP_LABEL_KEY, "123");
        request._headers.emplace(HTTP_COLUMN_SEPARATOR, "|");
        action.on_header(&request);
        action.handle(&request);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    {
        HttpRequest request(_evhttp_req);
        request.set_handler(&action);

        request._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        request._headers.emplace(HttpHeaders::CONTENT_LENGTH, "16");
        request._headers.emplace(HTTP_DB_KEY, "db");
        request._headers.emplace(HTTP_LABEL_KEY, "123");
        request._headers.emplace(HTTP_COLUMN_SEPARATOR, "|");
        action.on_header(&request);
        action.handle(&request);

        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }

    {
        HttpRequest request(_evhttp_req);
        request.set_handler(&action);

        request._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        request._headers.emplace(HttpHeaders::CONTENT_LENGTH, "16");
        request._headers.emplace(HTTP_DB_KEY, "db");
        request._headers.emplace(HTTP_LABEL_KEY, "123");
        request._headers.emplace(HTTP_COLUMN_SEPARATOR, ",");
        ASSERT_EQ(-1, action.on_header(&request));
    }
}

#define SET_MEMORY_LIMIT_EXCEEDED(stmt)                                                            \
    do {                                                                                           \
        DeferOp defer([]() {                                                                       \
            SyncPoint::GetInstance()->ClearCallBack("ByteBuffer::allocate_with_tracker");          \
            SyncPoint::GetInstance()->DisableProcessing();                                         \
        });                                                                                        \
        SyncPoint::GetInstance()->EnableProcessing();                                              \
        SyncPoint::GetInstance()->SetCallBack("ByteBuffer::allocate_with_tracker", [](void* arg) { \
            *((Status*)arg) = Status::MemoryLimitExceeded("TestFail");                             \
        });                                                                                        \
        { stmt; }                                                                                  \
    } while (0)

TEST_F(TransactionStreamLoadActionTest, huge_malloc) {
    TransactionStreamLoadAction action(&_env);
    auto ctx = new StreamLoadContext(&_env);
    ctx->db = "db";
    ctx->table = "tbl";
    ctx->label = "huge_malloc";
    ctx->ref();
    ctx->body_sink = std::make_shared<StreamLoadPipe>();
    bool remove_from_stream_context_mgr = false;
    DeferOp defer([&]() {
        if (remove_from_stream_context_mgr) {
            _env.stream_context_mgr()->remove(ctx->label);
        }
        if (ctx->unref()) {
            delete ctx;
        }
    });
    ASSERT_OK((_env.stream_context_mgr())->put(ctx->label, ctx));
    remove_from_stream_context_mgr = true;

    HttpRequest request(_evhttp_req);
    request.set_handler(&action);
    std::string content = "abc";

    auto evb = request.get_evhttp_request()->input_buffer;
    request._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
    request._headers.emplace(HttpHeaders::CONTENT_LENGTH, "16");
    request._headers.emplace(HTTP_DB_KEY, ctx->db);
    request._headers.emplace(HTTP_TABLE_KEY, ctx->table);
    request._headers.emplace(HTTP_LABEL_KEY, ctx->label);
    ASSERT_EQ(0, action.on_header(&request));

    evbuffer_add(evb, content.data(), content.size());
    SET_MEMORY_LIMIT_EXCEEDED({
        ctx->status = Status::OK();
        action.on_chunk_data(&request);
        ASSERT_TRUE(ctx->status.is_mem_limit_exceeded());
    });
    ctx->status = Status::OK();
    action.on_chunk_data(&request);
    ASSERT_TRUE(ctx->status.ok());

    evbuffer_add(evb, content.data(), content.size());
    SET_MEMORY_LIMIT_EXCEEDED({
        ctx->buffer = ByteBufferPtr(new ByteBuffer(1));
        ctx->status = Status::OK();
        action.on_chunk_data(&request);
        ASSERT_TRUE(ctx->status.is_mem_limit_exceeded());
        ctx->buffer = nullptr;
    });
    ctx->buffer = ByteBufferPtr(new ByteBuffer(1));
    ctx->status = Status::OK();
    action.on_chunk_data(&request);
    ASSERT_TRUE(ctx->status.ok());
    ctx->buffer = nullptr;

    evbuffer_add(evb, content.data(), content.size());
    auto old_format = ctx->format;
    SET_MEMORY_LIMIT_EXCEEDED({
        ctx->format = TFileFormatType::FORMAT_JSON;
        ctx->buffer = ByteBufferPtr(new ByteBuffer(1));
        ctx->status = Status::OK();
        action.on_chunk_data(&request);
        ASSERT_TRUE(ctx->status.is_mem_limit_exceeded());
        ctx->buffer = nullptr;
    });
    ctx->format = TFileFormatType::FORMAT_JSON;
    ctx->buffer = ByteBufferPtr(new ByteBuffer(1));
    ctx->status = Status::OK();
    action.on_chunk_data(&request);
    ASSERT_TRUE(ctx->status.ok());
    ctx->buffer = nullptr;
    ctx->format = old_format;
}

TEST_F(TransactionStreamLoadActionTest, release_resource_for_success_request) {
    TransactionStreamLoadAction action(&_env);
    auto ctx = new StreamLoadContext(&_env);
    ctx->ref();
    ctx->db = "db";
    ctx->table = "tbl";
    ctx->label = "release_resource_for_success_request";
    ctx->body_sink = std::make_shared<StreamLoadPipe>();
    bool remove_from_stream_context_mgr = false;
    DeferOp defer([&]() {
        if (remove_from_stream_context_mgr) {
            _env.stream_context_mgr()->remove(ctx->label);
        }
        if (ctx->unref()) {
            delete ctx;
        }
    });
    ASSERT_OK((_env.stream_context_mgr())->put(ctx->label, ctx));
    remove_from_stream_context_mgr = true;
    ASSERT_TRUE(ctx->lock.try_lock());
    ctx->lock.unlock();

    // normal request
    {
        k_response_str = "";
        HttpRequest request(_evhttp_req);
        request.set_handler(&action);
        std::string content = "abc";
        auto evb = request.get_evhttp_request()->input_buffer;
        evbuffer_add(evb, content.data(), content.size());
        request._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        request._headers.emplace(HttpHeaders::CONTENT_LENGTH, std::to_string(content.length()));
        request._headers.emplace(HTTP_DB_KEY, ctx->db);
        request._headers.emplace(HTTP_TABLE_KEY, ctx->table);
        request._headers.emplace(HTTP_LABEL_KEY, ctx->label);
        ASSERT_EQ(0, action.on_header(&request));
        ASSERT_EQ(3, ctx->num_refs());
        ASSERT_FALSE(ctx->lock.try_lock());
        ASSERT_TRUE(k_response_str.empty());
        action.on_chunk_data(&request);
        ASSERT_EQ(3, ctx->num_refs());
        ASSERT_FALSE(ctx->lock.try_lock());
        SyncPoint::GetInstance()->EnableProcessing();
        DeferOp defer([]() {
            SyncPoint::GetInstance()->ClearCallBack("TransactionStreamLoad::send_reply");
            SyncPoint::GetInstance()->DisableProcessing();
        });
        SyncPoint::GetInstance()->SetCallBack("TransactionStreamLoad::send_reply", [&](void* arg) {
            ASSERT_EQ(2, ctx->num_refs());
            ASSERT_TRUE(ctx->lock.try_lock());
            ctx->lock.unlock();
        });
        action.handle(&request);
        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("OK", doc["Status"].GetString());
    }
    ASSERT_EQ(2, ctx->num_refs());
    ASSERT_TRUE(ctx->lock.try_lock());
    ctx->lock.unlock();
}

TEST_F(TransactionStreamLoadActionTest, release_resource_for_on_header_failure) {
    TransactionStreamLoadAction action(&_env);
    auto ctx = new StreamLoadContext(&_env);
    ctx->ref();
    ctx->db = "db";
    ctx->table = "tbl";
    ctx->label = "release_resource_for_on_header_failure";
    ctx->body_sink = std::make_shared<StreamLoadPipe>();
    bool remove_from_stream_context_mgr = false;
    DeferOp defer([&]() {
        if (remove_from_stream_context_mgr) {
            _env.stream_context_mgr()->remove(ctx->label);
        }
        if (ctx->unref()) {
            delete ctx;
        }
    });
    ASSERT_OK((_env.stream_context_mgr())->put(ctx->label, ctx));
    remove_from_stream_context_mgr = true;
    ASSERT_TRUE(ctx->lock.try_lock());
    ctx->lock.unlock();

    // on_header fail because of invalid format
    {
        k_response_str = "";
        HttpRequest request(_evhttp_req);
        request.set_handler(&action);
        std::string content = "abc";
        auto evb = request.get_evhttp_request()->input_buffer;
        evbuffer_add(evb, content.data(), content.size());
        request._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        request._headers.emplace(HttpHeaders::CONTENT_LENGTH, std::to_string(content.length()));
        request._headers.emplace(HTTP_DB_KEY, ctx->db);
        request._headers.emplace(HTTP_TABLE_KEY, ctx->table);
        request._headers.emplace(HTTP_LABEL_KEY, ctx->label);
        request._headers.emplace(HTTP_FORMAT_KEY, "unknown");
        SyncPoint::GetInstance()->EnableProcessing();
        DeferOp defer([]() {
            SyncPoint::GetInstance()->ClearCallBack("TransactionStreamLoad::send_reply");
            SyncPoint::GetInstance()->DisableProcessing();
        });
        SyncPoint::GetInstance()->SetCallBack("TransactionStreamLoad::send_reply", [&](void* arg) {
            ASSERT_EQ(3, ctx->num_refs());
            ASSERT_TRUE(ctx->lock.try_lock());
            ctx->lock.unlock();
        });
        ASSERT_EQ(-1, action.on_header(&request));
        rapidjson::Document doc;
        doc.Parse(k_response_str.c_str());
        ASSERT_STREQ("INTERNAL_ERROR", doc["Status"].GetString());
        ASSERT_NE(nullptr, std::strstr(doc["Message"].GetString(), "unknown data format, format=unknown"));
    }
    ASSERT_EQ(2, ctx->num_refs());
    ASSERT_TRUE(ctx->lock.try_lock());
    ctx->lock.unlock();
}

TEST_F(TransactionStreamLoadActionTest, release_resource_for_not_handle) {
    TransactionStreamLoadAction action(&_env);
    auto ctx = new StreamLoadContext(&_env);
    ctx->ref();
    ctx->db = "db";
    ctx->table = "tbl";
    ctx->label = "release_resource_for_not_handle";
    ctx->body_sink = std::make_shared<StreamLoadPipe>();
    bool remove_from_stream_context_mgr = false;
    DeferOp defer([&]() {
        if (remove_from_stream_context_mgr) {
            _env.stream_context_mgr()->remove(ctx->label);
        }
        if (ctx->unref()) {
            delete ctx;
        }
    });
    ASSERT_OK((_env.stream_context_mgr())->put(ctx->label, ctx));
    remove_from_stream_context_mgr = true;
    ASSERT_TRUE(ctx->lock.try_lock());
    ctx->lock.unlock();

    // skip on_chunk_data and handle
    {
        k_response_str = "";
        HttpRequest request(_evhttp_req);
        request.set_handler(&action);
        std::string content = "abc";
        auto evb = request.get_evhttp_request()->input_buffer;
        evbuffer_add(evb, content.data(), content.size());
        request._headers.emplace(HttpHeaders::AUTHORIZATION, "Basic cm9vdDo=");
        request._headers.emplace(HttpHeaders::CONTENT_LENGTH, std::to_string(content.length()));
        request._headers.emplace(HTTP_DB_KEY, ctx->db);
        request._headers.emplace(HTTP_TABLE_KEY, ctx->table);
        request._headers.emplace(HTTP_LABEL_KEY, ctx->label);
        ASSERT_EQ(0, action.on_header(&request));
        ASSERT_EQ(3, ctx->num_refs());
        ASSERT_FALSE(ctx->lock.try_lock());
        ASSERT_TRUE(k_response_str.empty());
    }
    ASSERT_EQ(2, ctx->num_refs());
    ASSERT_TRUE(ctx->lock.try_lock());
    ctx->lock.unlock();
}

} // namespace starrocks
