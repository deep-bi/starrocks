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
#include <future>
#include <memory>
#include <thread>

#include "util/priority_thread_pool.hpp"
#include "util/threadpool.h"

namespace starrocks {

TEST(BrpcShutdownDeadlockTest, PriorityThreadPoolDrainsAcceptedTasksOnShutdown) {
    PriorityThreadPool pool("brpc_query_rpc_test", 1, 8);

    std::promise<void> unblock_first_task;
    auto unblock_first_task_future = unblock_first_task.get_future();
    std::atomic<bool> second_task_done{false};

    ASSERT_TRUE(pool.try_offer([&] { unblock_first_task_future.wait(); }));
    ASSERT_TRUE(pool.try_offer([&] { second_task_done.store(true); }));

    pool.shutdown();
    unblock_first_task.set_value();
    pool.join();

    ASSERT_TRUE(second_task_done.load());
}

} // namespace starrocks
