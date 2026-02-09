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
#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <future>
#include <mutex>
#include <thread>

#include "service/service_be/shutdown_order.h"

namespace starrocks {

class MockExecPool {
public:
    MockExecPool() : _worker([this] { run(); }) {}

    ~MockExecPool() {
        shutdown();
        if (_worker.joinable()) {
            _worker.join();
        }
    }

    bool submit(std::function<void()> task) {
        std::lock_guard<std::mutex> lock(_mu);
        if (_stopped) {
            return false;
        }
        _tasks.emplace_back(std::move(task));
        _cv.notify_one();
        return true;
    }

    void set_allow_run(bool allow_run) {
        std::lock_guard<std::mutex> lock(_mu);
        _allow_run = allow_run;
        _cv.notify_all();
    }

    void shutdown() {
        std::lock_guard<std::mutex> lock(_mu);
        _stopped = true;
        _tasks.clear();
        _cv.notify_all();
    }

    bool is_stopped() const { return _stopped.load(); }

private:
    void run() {
        std::unique_lock<std::mutex> lock(_mu);
        while (true) {
            _cv.wait(lock, [&] { return _stopped || (_allow_run && !_tasks.empty()); });
            if (_stopped) {
                return;
            }
            auto task = std::move(_tasks.front());
            _tasks.pop_front();
            lock.unlock();
            task();
            lock.lock();
        }
    }

    std::mutex _mu;
    std::condition_variable _cv;
    std::deque<std::function<void()>> _tasks;
    std::thread _worker;
    std::atomic<bool> _stopped{false};
    bool _allow_run{false};
};

class MockBrpcServer {
public:
    explicit MockBrpcServer(std::function<bool(std::function<void()>)> submit_task, MockExecPool* pool)
            : _submit_task(std::move(submit_task)), _pool(pool) {}

    ~MockBrpcServer() {
        cancel();
        join();
    }

    void start_inflight_request() {
        _inflight.store(true);
        _request_thread = std::thread([this] {
            bool accepted = _submit_task([this] {
                {
                    std::lock_guard<std::mutex> lock(_mu);
                    _done = true;
                }
                _cv.notify_all();
            });

            {
                std::lock_guard<std::mutex> lock(_mu);
                _accepted = accepted;
                _queued = true;
                _waiting = accepted;
            }
            _cv.notify_all();

            if (!accepted) {
                _finish_request();
                return;
            }

            std::unique_lock<std::mutex> lock(_mu);
            _cv.wait(lock, [&] { return _cancel || _done; });

            if (_accepted && !_done && _pool->is_stopped()) {
                _deadlock_possible.store(true);
            }
            _waiting = false;
            _finish_request();
        });
    }

    void wait_task_queued() {
        std::unique_lock<std::mutex> lock(_mu);
        _cv.wait(lock, [&] { return _queued; });
    }

    void wait_request_waiting() {
        std::unique_lock<std::mutex> lock(_mu);
        _cv.wait(lock, [&] { return _waiting; });
    }

    bool join_for(std::chrono::milliseconds timeout) {
        std::unique_lock<std::mutex> lock(_done_mu);
        return _done_cv.wait_for(lock, timeout, [&] { return !_inflight.load(); });
    }

    void cancel() {
        {
            std::lock_guard<std::mutex> lock(_mu);
            _cancel = true;
        }
        _cv.notify_all();
    }

    bool deadlock_possible() const { return _deadlock_possible.load(); }

    void join() {
        if (_request_thread.joinable()) {
            _request_thread.join();
        }
    }

private:
    void _finish_request() {
        _inflight.store(false);
        std::lock_guard<std::mutex> lock(_done_mu);
        _done_cv.notify_all();
    }

    std::function<bool(std::function<void()>)> _submit_task;
    MockExecPool* _pool;
    std::thread _request_thread;

    std::mutex _mu;
    std::condition_variable _cv;
    bool _accepted{false};
    bool _queued{false};
    bool _waiting{false};
    bool _done{false};
    bool _cancel{false};

    std::atomic<bool> _inflight{false};
    std::atomic<bool> _deadlock_possible{false};
    std::mutex _done_mu;
    std::condition_variable _done_cv;
};

static void stop_exec_before_join(const CoreShutdownHooks& hooks) {
    if (hooks.stop_servers) {
        hooks.stop_servers();
    }
    if (hooks.stop_exec_env) {
        hooks.stop_exec_env();
    }
    if (hooks.join_servers) {
        hooks.join_servers();
    }
}

TEST(BrpcShutdownDeadlockTest, DetectsDeadlockWhenExecStopsFirst) {
    MockExecPool pool;
    MockBrpcServer brpc([&](std::function<void()> task) { return pool.submit(std::move(task)); }, &pool);

    brpc.start_inflight_request();
    brpc.wait_task_queued();
    brpc.wait_request_waiting();

    CoreShutdownHooks hooks;
    hooks.stop_servers = [] {};
    hooks.stop_exec_env = [&] { pool.shutdown(); };
    hooks.join_servers = [&] {
        pool.set_allow_run(true);
        ASSERT_FALSE(brpc.join_for(std::chrono::milliseconds(80)));
    };

    stop_exec_before_join(hooks);
    ASSERT_TRUE(pool.is_stopped());

    brpc.cancel();
    ASSERT_TRUE(brpc.join_for(std::chrono::milliseconds(500)));
    ASSERT_TRUE(brpc.deadlock_possible());
}

TEST(BrpcShutdownDeadlockTest, DrainsBeforeExecStop) {
    MockExecPool pool;
    MockBrpcServer brpc([&](std::function<void()> task) { return pool.submit(std::move(task)); }, &pool);

    brpc.start_inflight_request();
    brpc.wait_task_queued();
    brpc.wait_request_waiting();

    CoreShutdownHooks hooks;
    hooks.stop_servers = [] {};
    hooks.join_servers = [&] {
        pool.set_allow_run(true);
        ASSERT_TRUE(brpc.join_for(std::chrono::milliseconds(250)));
    };
    hooks.stop_exec_env = [&] { pool.shutdown(); };

    shutdown_core_components(hooks);
    ASSERT_TRUE(pool.is_stopped());

    ASSERT_FALSE(brpc.deadlock_possible());
}

} // namespace starrocks
