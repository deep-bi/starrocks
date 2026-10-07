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

#include <gtest/gtest.h>

#include <atomic>

#include "common/process_exit.h"
#include "runtime/exec_env.h"
#include "runtime/stream_load/load_stream_mgr.h"
#include "runtime/stream_load/stream_load_context.h"
#include "runtime/stream_load/stream_load_executor.h"
#include "testutil/assert.h"
#include "testutil/sync_point.h"
#include "util/cpu_info.h"
#include "util/defer_op.h"

namespace starrocks {

extern std::atomic<bool> k_starrocks_exit;
extern std::atomic<bool> k_starrocks_force_reject;
extern std::atomic<int64_t> k_starrocks_exit_start_ms;

class StreamLoadExecutorAdmissionTest : public testing::Test {
protected:
    static void SetUpTestSuite() { CpuInfo::init(); }

    void SetUp() override {
        // StreamLoadContext releases its entry in the env's LoadStreamMgr on destruction.
        _env._load_stream_mgr = new LoadStreamMgr();
    }

    void TearDown() override {
        delete _env._load_stream_mgr;
        _env._load_stream_mgr = nullptr;
    }

    ExecEnv _env;
};

TEST_F(StreamLoadExecutorAdmissionTest, execute_plan_fragment_preserves_be_test_sync_point) {
    StreamLoadExecutor executor(&_env);
    StreamLoadContext ctx(&_env);

    SyncPoint::GetInstance()->EnableProcessing();
    SyncPoint::GetInstance()->SetCallBack("StreamLoadExecutor::execute_plan_fragment:1", [](void* arg) {
        *static_cast<Status*>(arg) = Status::InternalError("TestFail");
    });
    DeferOp defer([]() {
        SyncPoint::GetInstance()->ClearCallBack("StreamLoadExecutor::execute_plan_fragment:1");
        SyncPoint::GetInstance()->DisableProcessing();
    });

    ASSERT_OK(executor.execute_plan_fragment(&ctx, false));
    auto status = ctx.future.get();
    ASSERT_TRUE(status.is_internal_error());
    ASSERT_EQ("TestFail", status.message());
}

// Verify the guard releases its count when rejection occurs.
TEST_F(StreamLoadExecutorAdmissionTest, execute_plan_fragment_rejects_when_force_reject) {
    ASSERT_TRUE(set_process_exit());
    force_reject_exec_plan_fragment();
    DeferOp reset_exit([] {
        k_starrocks_exit.store(false);
        k_starrocks_force_reject.store(false);
        k_starrocks_exit_start_ms.store(0);
    });

    StreamLoadExecutor executor(&_env);
    StreamLoadContext ctx(&_env);

    Status status = executor.execute_plan_fragment(&ctx, false);
    ASSERT_TRUE(status.is_service_unavailable());
    EXPECT_EQ(0, shutdown_work_inflight());
}

TEST_F(StreamLoadExecutorAdmissionTest, execute_plan_fragment_skips_gate_when_already_granted) {
    ASSERT_TRUE(set_process_exit());
    force_reject_exec_plan_fragment();
    DeferOp reset_exit([] {
        k_starrocks_exit.store(false);
        k_starrocks_force_reject.store(false);
        k_starrocks_exit_start_ms.store(0);
    });

    StreamLoadExecutor executor(&_env);
    StreamLoadContext ctx(&_env);

    ASSERT_OK(executor.execute_plan_fragment(&ctx, true));
    EXPECT_EQ(0, shutdown_work_inflight());
}

} // namespace starrocks
