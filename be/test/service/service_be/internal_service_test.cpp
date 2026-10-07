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

#include "service/service_be/internal_service.h"

#include <brpc/controller.h>
#include <gtest/gtest.h>

#include <atomic>
#include <memory>

#include "common/process_exit.h"
#include "common/utils.h"
#include "exec/pipeline/query_context.h"
#include "exec/tablet_sink_index_channel.h"
#include "runtime/exec_env.h"
#include "service/brpc_service_test_util.h"
#include "testutil/assert.h"
#include "testutil/sync_point.h"
#include "util/defer_op.h"
#include "util/metrics.h"
#include "util/uid_util.h"

namespace starrocks {

class InternalServiceTest : public testing::Test {};

TEST_F(InternalServiceTest, test_get_info_timeout_invalid) {
    BackendInternalServiceImpl<PInternalService> service(ExecEnv::GetInstance());
    PProxyRequest request;
    PProxyResult response;
    service._get_info_impl(&request, &response, nullptr, -10);
    auto st = Status(response.status());
    ASSERT_TRUE(st.is_time_out());
}

TEST_F(InternalServiceTest, test_tablet_writer_add_chunks_via_http) {
    BackendInternalServiceImpl<PInternalService> service(ExecEnv::GetInstance());
    {
        PHttpRequest request;
        PTabletWriterAddBatchResult response;
        brpc::Controller cntl;
        MockClosure closure;
        service.tablet_writer_add_chunks_via_http(&cntl, &request, &response, &closure);
        auto st = Status(response.status());
        ASSERT_FALSE(st.ok());
    }
    {
        brpc::Controller cntl;
        PTabletWriterAddChunksRequest req;
        auto* r = req.add_requests();
        r->set_txn_id(1000);
        r->set_index_id(2000);
        r->set_sender_id(3000);
        serialize_to_iobuf<PTabletWriterAddChunksRequest>(req, &cntl.request_attachment());
        PHttpRequest request;
        PTabletWriterAddBatchResult response;
        MockClosure closure;
        service.tablet_writer_add_chunks_via_http(&cntl, &request, &response, &closure);
        auto st = Status(response.status());
        ASSERT_FALSE(st.ok());
        ASSERT_TRUE(response.status().error_msgs().at(0).find("no associated load channel") != std::string::npos);
    }
    {
        PHttpRequest request;
        PTabletWriterAddBatchResult response;
        brpc::Controller cntl;
        MockClosure closure;
        service.PInternalServiceImplBase::tablet_writer_add_chunks_via_http(&cntl, &request, &response, &closure);
        auto st = Status(response.status());
        ASSERT_TRUE(st.is_not_supported());
    }
}

TEST_F(InternalServiceTest, test_tablet_writer_add_chunk_via_http) {
    BackendInternalServiceImpl<PInternalService> service(ExecEnv::GetInstance());
    {
        PHttpRequest request;
        PTabletWriterAddBatchResult response;
        brpc::Controller cntl;
        MockClosure closure;
        service.tablet_writer_add_chunk_via_http(&cntl, &request, &response, &closure);
        auto st = Status(response.status());
        ASSERT_FALSE(st.ok());
    }
    {
        PHttpRequest request;
        PTabletWriterAddBatchResult response;
        brpc::Controller cntl;
        size_t request_size = 123; // fake
        cntl.request_attachment().append(&request_size, sizeof(request_size));
        MockClosure closure;
        service.tablet_writer_add_chunk_via_http(&cntl, &request, &response, &closure);
        auto st = Status(response.status());
        ASSERT_FALSE(st.ok());
    }
    {
        brpc::Controller cntl;
        PTabletWriterAddChunksRequest req;
        auto* r = req.add_requests();
        r->set_txn_id(1000);
        r->set_index_id(2000);
        r->set_sender_id(3000);
        serialize_to_iobuf<PTabletWriterAddChunksRequest>(req, &cntl.request_attachment());
        PHttpRequest request;
        PTabletWriterAddBatchResult response;
        MockClosure closure;
        service.tablet_writer_add_chunk_via_http(&cntl, &request, &response, &closure);
        auto st = Status(response.status());
        ASSERT_FALSE(st.ok());
    }
    {
        brpc::Controller cntl;
        PTabletWriterAddChunkRequest req;
        req.set_txn_id(1000);
        req.set_index_id(2000);
        req.set_sender_id(3000);
        serialize_to_iobuf<PTabletWriterAddChunkRequest>(req, &cntl.request_attachment());
        PHttpRequest request;
        PTabletWriterAddBatchResult response;
        MockClosure closure;
        service.tablet_writer_add_chunk_via_http(&cntl, &request, &response, &closure);
        auto st = Status(response.status());
        ASSERT_FALSE(st.ok());
        ASSERT_TRUE(response.status().error_msgs().at(0).find("no associated load channel") != std::string::npos);
    }
    {
        PHttpRequest request;
        PTabletWriterAddBatchResult response;
        brpc::Controller cntl;
        MockClosure closure;
        service.PInternalServiceImplBase::tablet_writer_add_chunk_via_http(&cntl, &request, &response, &closure);
        auto st = Status(response.status());
        ASSERT_TRUE(st.is_not_supported());
    }
}

TEST_F(InternalServiceTest, test_load_diagnose) {
    BackendInternalServiceImpl<PInternalService> service(ExecEnv::GetInstance());
    PLoadDiagnoseRequest request;
    request.set_txn_id(1);
    request.mutable_id()->set_hi(0);
    request.mutable_id()->set_lo(0);
    request.set_profile(true);
    request.set_stack_trace(true);
    PLoadDiagnoseResult response;
    brpc::Controller cntl;
    MockClosure closure;
    service.load_diagnose(&cntl, &request, &response, &closure);
    ASSERT_TRUE(response.has_profile_status());
    auto st = Status(response.profile_status());
    ASSERT_FALSE(st.ok());
    ASSERT_TRUE(st.message().find("can't find the load channel") != std::string::npos);
    ASSERT_TRUE(response.has_stack_trace_status());
    st = Status(response.stack_trace_status());
    ASSERT_FALSE(st.ok());
    ASSERT_TRUE(st.message().find("can't find the load channel") != std::string::npos);
}

TEST_F(InternalServiceTest, test_get_load_replica_status) {
    BackendInternalServiceImpl<PInternalService> service(ExecEnv::GetInstance());
    PLoadReplicaStatusRequest request;
    request.mutable_load_id()->set_hi(0);
    request.mutable_load_id()->set_lo(0);
    request.set_txn_id(1);
    request.set_sink_id(1);
    request.set_node_id(1);
    request.add_tablet_ids(1);
    PLoadReplicaStatusResult response;
    brpc::Controller cntl;
    MockClosure closure;
    service.get_load_replica_status(&cntl, &request, &response, &closure);
    ASSERT_EQ(1, response.replica_statuses_size());
}

TEST_F(InternalServiceTest, test_fetch_datacache_via_brpc) {
    BackendInternalServiceImpl<PInternalService> service(ExecEnv::GetInstance());

    PFetchDataCacheRequest request;
    PFetchDataCacheResponse response;
    request.set_request_id(0);
    request.set_cache_key("test_file");
    request.set_offset(0);
    request.set_size(1024);

    {
        brpc::Controller cntl;
        MockClosure closure;
        service._fetch_datacache(&cntl, &request, &response, &closure);
        auto st = Status(response.status());
        ASSERT_FALSE(st.ok());
    }

    std::shared_ptr<BlockCache> cache(new BlockCache);
    {
        CacheOptions options;
        options.mem_space_size = 20 * 1024 * 1024;
        options.block_size = 256 * 1024 * 1024;
        options.max_concurrent_inserts = 100000;
        options.max_flying_memory_mb = 100;
        options.engine = "starcache";
        options.inline_item_count_limit = 1000;
        Status status = cache->init(options);
        ASSERT_TRUE(status.ok());

        const size_t cache_size = 1024;
        const std::string cache_key = "test_file";
        std::string value(cache_size, 'a');
        Status st = cache->write(cache_key, 0, cache_size, value.c_str());
        ASSERT_TRUE(st.ok());

        CacheEnv* cache_env = CacheEnv::GetInstance();
        cache_env->_block_cache = cache;
    }

    {
        brpc::Controller cntl;
        MockClosure closure;
        service.fetch_datacache(&cntl, &request, &response, &closure);
        for (int retry = 3; retry > 0; --retry) {
            if (closure.has_run()) {
                break;
            }
            sleep(1);
        }
        auto st = Status(response.status());
        // Read cache data.
        ASSERT_TRUE(st.ok()) << st.message();

        IOBuffer buffer;
        cntl.response_attachment().swap(buffer.raw_buf());
        std::string target_value(1024, 'a');
        ASSERT_EQ(buffer.const_raw_buf().to_string(), target_value);
    }
}

extern std::atomic<bool> k_starrocks_exit;
extern std::atomic<bool> k_starrocks_quick_exit;
extern std::atomic<bool> k_starrocks_force_reject;
extern IntGauge streaming_load_current_processing;

TEST_F(InternalServiceTest, test_short_circuit_rejected_while_shutting_down) {
    // Verify rejection and guard cleanup for a short-circuit RPC.
    k_starrocks_exit.store(true);
    k_starrocks_force_reject.store(true);
    BackendInternalServiceImpl<PInternalService> service(ExecEnv::GetInstance());

    PExecShortCircuitRequest request;
    PExecShortCircuitResult response;
    brpc::Controller cntl;
    MockClosure closure;

    service.exec_short_circuit(&cntl, &request, &response, &closure);

    ASSERT_TRUE(cntl.Failed());
    ASSERT_EQ(brpc::EINTERNAL, cntl.ErrorCode());
    // ErrorText includes brpc's error-code prefix.
    ASSERT_EQ("[E2001]BE is shutting down", cntl.ErrorText());

    k_starrocks_exit.store(false);
    k_starrocks_force_reject.store(false);
}

TEST_F(InternalServiceTest, test_exec_plan_fragment_rejected_while_shutting_down) {
    // Verify rejection and guard cleanup for a fragment-prep RPC.
    k_starrocks_exit.store(true);
    k_starrocks_force_reject.store(true);

    BackendInternalServiceImpl<PInternalService> service(ExecEnv::GetInstance());

    PExecPlanFragmentRequest request;
    PExecPlanFragmentResult response;
    brpc::Controller cntl;
    MockClosure closure;

    // Rejection moved to the public entry (admission gate): force_reject makes it SetFailed
    // and restore the inflight count, instead of the private worker which no longer rejects.
    service.exec_plan_fragment(&cntl, &request, &response, &closure);

    ASSERT_TRUE(cntl.Failed());
    ASSERT_EQ(brpc::EINTERNAL, cntl.ErrorCode());
    ASSERT_EQ("[E2001]BE is shutting down", cntl.ErrorText());

    k_starrocks_exit.store(false);
    k_starrocks_force_reject.store(false);
}

TEST_F(InternalServiceTest, test_exec_batch_plan_fragments_rejected_while_shutting_down) {
    k_starrocks_exit.store(true);
    k_starrocks_force_reject.store(true);

    BackendInternalServiceImpl<PInternalService> service(ExecEnv::GetInstance());

    PExecBatchPlanFragmentsRequest request;
    PExecBatchPlanFragmentsResult response;
    brpc::Controller cntl;
    MockClosure closure;

    // Rejection moved to the public entry (admission gate), same as exec_plan_fragment.
    service.exec_batch_plan_fragments(&cntl, &request, &response, &closure);

    ASSERT_TRUE(cntl.Failed());
    ASSERT_EQ(brpc::EINTERNAL, cntl.ErrorCode());
    ASSERT_EQ("[E2001]BE is shutting down", cntl.ErrorText());

    k_starrocks_exit.store(false);
    k_starrocks_force_reject.store(false);
}

TEST_F(InternalServiceTest, test_drain_resample_observes_successor_after_predecessor_release) {
    // Drain reads shutdown_work first, then registries. Hold one predecessor, then at
    // before_query_read publish this test's query and drop the predecessor so the
    // re-sample must still see the successor (never a false zero).
    // before+1 / before assumes this fixture is serial: no sibling test mutates
    // shutdown_work during the sample. Not a process-wide invariant.

    TUniqueId query_id = UniqueId::gen_uid().to_thrift();
    std::atomic<bool> registered_query{false};

    const size_t before = shutdown_work_inflight();
    std::atomic<bool> owns_shutdown_work{true};
    inc_shutdown_work();
    ASSERT_EQ(before + 1, shutdown_work_inflight());

    SyncPoint::GetInstance()->EnableProcessing();
    SyncPoint::GetInstance()->SetCallBack("ExecEnv::_get_running_fragments_count:before_query_read",
                                          [&](void* arg) {
                                              auto st = ExecEnv::GetInstance()->query_context_mgr()->get_or_register(
                                                      query_id,
                                                      /*return_error_if_not_exist=*/false);
                                              ASSERT_OK(st);
                                              registered_query.store(true);
                                              if (owns_shutdown_work.exchange(false)) {
                                                  dec_shutdown_work();
                                              }
                                          });
    DeferOp clear_sync_point([&] {
        SyncPoint::GetInstance()->ClearCallBack("ExecEnv::_get_running_fragments_count:before_query_read");
        SyncPoint::GetInstance()->DisableProcessing();
        if (registered_query.load()) {
            ExecEnv::GetInstance()->query_context_mgr()->remove(query_id);
        }
        if (owns_shutdown_work.exchange(false)) {
            dec_shutdown_work();
        }
    });

    size_t count = ExecEnv::GetInstance()->get_running_fragments_count_for_test();
    ASSERT_GT(count, 0);
    ASSERT_FALSE(owns_shutdown_work.load());
    ASSERT_EQ(before, shutdown_work_inflight());
}

TEST_F(InternalServiceTest, test_quick_exit_still_counts_pipeline_query) {
    ASSERT_NE(nullptr, ExecEnv::GetInstance()->query_context_mgr());

    TUniqueId query_id = UniqueId::gen_uid().to_thrift();
    ASSERT_OK(ExecEnv::GetInstance()->query_context_mgr()->get_or_register(query_id, false));
    const auto stream_before = streaming_load_current_processing.value();
    streaming_load_current_processing.increment(1);
    inc_shutdown_work();
    DeferOp restore([&] {
        ExecEnv::GetInstance()->query_context_mgr()->remove(query_id);
        streaming_load_current_processing.set_value(stream_before);
        dec_shutdown_work();
        k_starrocks_quick_exit.store(false);
    });

    ASSERT_TRUE(set_process_quick_exit());
    EXPECT_GE(ExecEnv::GetInstance()->get_running_fragments_count_for_test(), 1);

    k_starrocks_quick_exit.store(false);
    EXPECT_GE(ExecEnv::GetInstance()->get_running_fragments_count_for_test(), 2);
}

TEST_F(InternalServiceTest, test_quick_exit_counts_shutdown_work) {
    // A bare ExecEnv has no fragment/query managers, so only the shared admission counter counts.
    ExecEnv env;
    const size_t work_before = shutdown_work_inflight();
    inc_shutdown_work();
    bool owns = true;
    DeferOp restore([&] {
        if (owns) {
            dec_shutdown_work();
        }
        k_starrocks_quick_exit.store(false);
    });

    ASSERT_TRUE(set_process_quick_exit());
    EXPECT_GE(env.get_running_fragments_count_for_test(), work_before + 1);

    owns = false;
    dec_shutdown_work();
    EXPECT_EQ(work_before, env.get_running_fragments_count_for_test());
}
} // namespace starrocks
