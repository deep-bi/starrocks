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
//   https://github.com/apache/incubator-doris/blob/master/be/src/util/runtime_profile.h

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

#pragma once

#include <sys/resource.h>
#include <sys/time.h> // NOLINT

#include <atomic>
#include <functional>
#include <iostream>
#include <mutex>
#include <optional>
#include <thread>
#include <unordered_set>
#include <utility>

#include "common/compiler_util.h"
#include "common/logging.h"
#include "common/object_pool.h"
#include "gen_cpp/RuntimeProfile_types.h"
#include "gutil/casts.h"
#include "util/stopwatch.hpp"

namespace starrocks {

inline unsigned long long operator"" _ms(unsigned long long x) {
    return x * 1000 * 1000;
}

// Define macros for updating counters.  The macros make it very easy to disable
// all counters at compile time.  Set this to 0 to remove counters.  This is useful
// to do to make sure the counters aren't affecting the system.
#define ENABLE_COUNTERS 1

// Some macro magic to generate unique ids using __COUNTER__
#define CONCAT_IMPL(x, y) x##y
#define MACRO_CONCAT(x, y) CONCAT_IMPL(x, y)

#if ENABLE_COUNTERS
#define ADD_COUNTER(profile, name, type) \
    (profile)->add_counter(name, type, RuntimeProfile::Counter::create_strategy(type))
#define ADD_COUNTER_SKIP_MERGE(profile, name, type, merge_type) \
    (profile)->add_counter(name, type, RuntimeProfile::Counter::create_strategy(type, merge_type))
#define ADD_TIMER(profile, name) \
    (profile)->add_counter(name, TUnit::TIME_NS, RuntimeProfile::Counter::create_strategy(TUnit::TIME_NS))
#define ADD_TIMER_WITH_THRESHOLD(profile, name, threshold) \
    (profile)->add_counter(                                \
            name, TUnit::TIME_NS,                          \
            RuntimeProfile::Counter::create_strategy(TUnit::TIME_NS, TCounterMergeType::MERGE_ALL, threshold))
#define ADD_PEAK_COUNTER(profile, name, type) \
    (profile)->AddHighWaterMarkCounter(name, type, RuntimeProfile::Counter::create_strategy(TCounterAggregateType::AVG))
#define ADD_CHILD_COUNTER(profile, name, type, parent) \
    (profile)->add_child_counter(name, type, RuntimeProfile::Counter::create_strategy(type), parent)
#define ADD_CHILD_COUNTER_SKIP_MERGE(profile, name, type, merge_type, parent) \
    (profile)->add_child_counter(name, type, RuntimeProfile::Counter::create_strategy(type, merge_type), parent)
#define ADD_CHILD_COUNTER_SKIP_MIN_MAX(profile, name, type, min_max_type, parent)                                      \
    (profile)->add_child_counter(                                                                                      \
            name, type, RuntimeProfile::Counter::create_strategy(type, TCounterMergeType::MERGE_ALL, 0, min_max_type), \
            parent)
#define ADD_CHILD_TIMER_THESHOLD(profile, name, parent, threshold) \
    (profile)->add_child_counter(                                  \
            name, TUnit::TIME_NS,                                  \
            RuntimeProfile::Counter::create_strategy(TUnit::TIME_NS, TCounterMergeType::MERGE_ALL, threshold), parent)
#define ADD_CHILD_TIMER(profile, name, parent) \
    (profile)->add_child_counter(name, TUnit::TIME_NS, RuntimeProfile::Counter::create_strategy(TUnit::TIME_NS), parent)
#define SCOPED_TIMER(c) ScopedTimer<MonotonicStopWatch> MACRO_CONCAT(SCOPED_TIMER, __COUNTER__)(c)
#define CANCEL_SAFE_SCOPED_TIMER(c, is_cancelled) \
    ScopedTimer<MonotonicStopWatch> MACRO_CONCAT(SCOPED_TIMER, __COUNTER__)(c, is_cancelled)
#define SCOPED_RAW_TIMER(c) ScopedRawTimer<MonotonicStopWatch> MACRO_CONCAT(SCOPED_RAW_TIMER, __COUNTER__)(c)
#define COUNTER_UPDATE(c, v) (c)->update(v)
#define COUNTER_SET(c, v) (c)->set(v)
// this is only used for HighWaterMarkCounter
#define COUNTER_ADD(c, v) (c)->add(v)
#define ADD_THREAD_COUNTERS(profile, prefix) (profile)->add_thread_counters(prefix)
#define SCOPED_THREAD_COUNTER_MEASUREMENT(c) \
    /*ThreadCounterMeasurement                                        \
      MACRO_CONCAT(SCOPED_THREAD_COUNTER_MEASUREMENT, __COUNTER__)(c)*/
#else
#define ADD_COUNTER(profile, name, type) NULL
#define ADD_TIMER(profile, name) NULL
#define SCOPED_TIMER(c)
#define SCOPED_RAW_TIMER(c)
#define COUNTER_UPDATE(c, v)
#define COUNTER_SET(c, v)
#define COUNTER_ADD(c, v)
#define ADD_THREADCOUNTERS(profile, prefix) NULL
#define SCOPED_THREAD_COUNTER_MEASUREMENT(c)
#endif

class ObjectPool;

// Runtime profile is a group of profiling counters.  It supports adding named counters
// and being able to serialize and deserialize them.
// The profiles support a tree structure to form a hierarchy of counters.
// Runtime profiles supports measuring wall clock rate based counters.  There is a
// single thread per process that will convert an amount (i.e. bytes) counter to a
// corresponding rate based counter.  This thread wakes up at fixed intervals and updates
// all of the rate counters.
// Thread-safe.
class RuntimeProfile {
public:
    inline static const std::string MERGED_INFO_PREFIX_MIN = "__MIN_OF_";
    inline static const std::string MERGED_INFO_PREFIX_MAX = "__MAX_OF_";

    class Counter {
    public:
        static TCounterStrategy create_strategy(
                TCounterAggregateType::type aggregate_type,
                TCounterMergeType::type merge_type = TCounterMergeType::MERGE_ALL, int64_t display_threshold = 0,
                TCounterMinMaxType::type min_max_type = TCounterMinMaxType::MIN_MAX_ALL) {
            TCounterStrategy strategy;
            strategy.aggregate_type = aggregate_type;
            strategy.merge_type = merge_type;
            strategy.display_threshold = display_threshold;
            strategy.min_max_type = min_max_type;
            return strategy;
        }

        static TCounterStrategy create_strategy(
                TUnit::type type, TCounterMergeType::type merge_type = TCounterMergeType::MERGE_ALL,
                int64_t display_threshold = 0,
                TCounterMinMaxType::type min_max_type = TCounterMinMaxType::MIN_MAX_ALL) {
            auto aggregate_type = is_time_type(type) ? TCounterAggregateType::AVG : TCounterAggregateType::SUM;
            return create_strategy(aggregate_type, merge_type, display_threshold, min_max_type);
        }

        explicit Counter(TUnit::type type, int64_t value = 0)
                : _value(0), _type(type), _strategy(create_strategy(type)) {}
        explicit Counter(TUnit::type type, const TCounterStrategy& strategy, int64_t value = 0)
                : _value(value), _type(type), _strategy(strategy) {}

        virtual ~Counter() = default;

        virtual void update(int64_t delta) { _value.fetch_add(delta, std::memory_order_relaxed); }

        // Use this to update if the counter is a bitmap
        void bit_or(int64_t delta) {
            int64_t old;
            do {
                old = _value.load(std::memory_order_relaxed);
                if (LIKELY((old | delta) == old)) return; // Bits already set, avoid atomic.
            } while (UNLIKELY(!_value.compare_exchange_strong(old, old | delta, std::memory_order_relaxed)));
        }

        virtual void set(int64_t value) { _value.store(value, std::memory_order_relaxed); }

        virtual void set(double value) { _value.store(bit_cast<int64_t>(value), std::memory_order_relaxed); }

        virtual int64_t value() const { return _value.load(std::memory_order_relaxed); }

        virtual double double_value() const { return bit_cast<double>(_value.load(std::memory_order_relaxed)); }

        virtual void set_min(int64_t min) { _min_value.emplace(min); }
        virtual void set_max(int64_t max) { _max_value.emplace(max); }
        virtual std::optional<int64_t> min_value() const { return _min_value; }
        virtual std::optional<int64_t> max_value() const { return _max_value; }

        TUnit::type type() const { return _type; }

        const TCounterStrategy& strategy() const { return _strategy; }

        bool is_sum() const {
            return _strategy.aggregate_type == TCounterAggregateType::SUM ||
                   _strategy.aggregate_type == TCounterAggregateType::SUM_AVG;
        }
        bool is_avg() const {
            return _strategy.aggregate_type == TCounterAggregateType::AVG ||
                   _strategy.aggregate_type == TCounterAggregateType::AVG_SUM;
        }

        bool skip_merge() const {
            return _strategy.merge_type == TCounterMergeType::SKIP_ALL ||
                   _strategy.merge_type == TCounterMergeType::SKIP_FIRST_MERGE;
        }

        bool skip_min_max() const { return _strategy.min_max_type == TCounterMinMaxType::SKIP_ALL; }

        int64_t display_threshold() const { return _strategy.display_threshold; }
        bool should_display() const {
            int64_t threshold = _strategy.display_threshold;
            return threshold == 0 || value() > threshold;
        }

    private:
        friend class RuntimeProfile;

        std::atomic<int64_t> _value;
        const TUnit::type _type;
        const TCounterStrategy _strategy;
        std::optional<int64_t> _min_value;
        std::optional<int64_t> _max_value;
    };

    class ConcurrentTimerCounter;
    class DerivedCounter;
    class EventSequence;
    class SummaryStatsCounter;
    class ThreadCounters;
    class TimeSeriesCounter;

    /// A counter that keeps track of the highest/lowest value seen (reporting that
    /// as value()) and the current value.
    template <bool is_high>
    class WaterMarkCounter : public Counter {
    public:
        explicit WaterMarkCounter(TUnit::type type, int64_t value = 0) : Counter(type, value) { _set_init_value(); }
        explicit WaterMarkCounter(TUnit::type type, const TCounterStrategy& strategy, int64_t value = 0)
                : Counter(type, strategy, value) {
            _set_init_value();
        }

        virtual void add(int64_t delta) {
            int64_t new_val = current_value_.fetch_add(delta, std::memory_order_relaxed) + delta;
            Update(new_val);
        }

        /// Tries to increase the current value by delta. If current_value() + delta
        /// exceeds max, return false and current_value is not changed.
        bool try_add(int64_t delta, int64_t max) {
            while (true) {
                int64_t old_val = current_value_.load(std::memory_order_relaxed);
                int64_t new_val = old_val + delta;
                if (UNLIKELY(new_val > max)) return false;
                if (LIKELY(current_value_.compare_exchange_strong(old_val, new_val, std::memory_order_relaxed))) {
                    Update(new_val);
                    return true;
                }
            }
        }

        void set(int64_t v) override {
            current_value_.store(v, std::memory_order_relaxed);
            Update(v);
        }

        int64_t current_value() const { return current_value_.load(std::memory_order_relaxed); }

    private:
        void _set_init_value() {
            if constexpr (is_high) {
                _value.store(0, std::memory_order_relaxed);
                current_value_.store(0, std::memory_order_relaxed);
            } else {
                _value.store(MAX_INT64, std::memory_order_relaxed);
                current_value_.store(MAX_INT64, std::memory_order_relaxed);
            }
        }

        /// Set '_value' to 'v' if 'v' is larger/lower than '_value'. The entire operation is
        /// atomic.
        void Update(int64_t v) {
            while (true) {
                int64_t old_value = _value.load(std::memory_order_relaxed);
                int64_t new_value;
                if constexpr (is_high) {
                    new_value = std::max(old_value, v);
                } else {
                    new_value = std::min(old_value, v);
                }
                if (new_value == old_value) break; // Avoid atomic update.
                if (LIKELY(_value.compare_exchange_strong(old_value, new_value, std::memory_order_relaxed))) break;
            }
        }

        /// The current value of the counter. _value in the super class represents
        /// the high water mark.
        std::atomic<int64_t> current_value_;
        static const int64_t MAX_INT64 = 9223372036854775807ll;
    };

    using HighWaterMarkCounter = WaterMarkCounter<true>;
    using LowWaterMarkCounter = WaterMarkCounter<false>;

    typedef std::function<int64_t()> DerivedCounterFunction;

    // A DerivedCounter also has a name and type, but the value is computed.
    // Do not call Set() and Update().
    class DerivedCounter : public Counter {
    public:
        DerivedCounter(TUnit::type type, DerivedCounterFunction counter_fn)
                : Counter(type, create_strategy(type), 0), _counter_fn(std::move(counter_fn)) {}

        int64_t value() const override { return _counter_fn(); }

    private:
        DerivedCounterFunction _counter_fn;
    };

    // A set of counters that measure thread info, such as total time, user time, sys time.
    class ThreadCounters {
    private:
        friend class ThreadCounterMeasurement;
        friend class RuntimeProfile;

        Counter* _total_time; // total wall clock time
        Counter* _user_time;  // user CPU time
        Counter* _sys_time;   // system CPU time

        // The number of times a context switch resulted due to a process voluntarily giving
        // up the processor before its time slice was completed.
        Counter* _voluntary_context_switches;

        // The number of times a context switch resulted due to a higher priority process
        // becoming runnable or because the current process exceeded its time slice.
        Counter* _involuntary_context_switches;
    };

    // An EventSequence captures a sequence of events (each added by
    // calling MarkEvent). Each event has a text label, and a time
    // (measured relative to the moment start() was called as t=0). It is
    // useful for tracking the evolution of some serial process, such as
    // the query lifecycle.
    // Not thread-safe.
    class EventSequence {
    public:
        EventSequence() = default;

        // starts the timer without resetting it.
        void start() { _sw.start(); }

        // stops (or effectively pauses) the timer.
        void stop() { _sw.stop(); }

        // Stores an event in sequence with the given label and the
        // current time (relative to the first time start() was called) as
        // the timestamp.
        void mark_event(const std::string& label) { _events.push_back(make_pair(label, _sw.elapsed_time())); }

        int64_t elapsed_time() { return _sw.elapsed_time(); }

        // An Event is a <label, timestamp> pair
        typedef std::pair<std::string, int64_t> Event;

        // An EventList is a sequence of Events, in increasing timestamp order
        typedef std::vector<Event> EventList;

        const EventList& events() const { return _events; }

    private:
        // Stored in increasing time order
        EventList _events;

        // Timer which allows events to be timestamped when they are recorded.
        MonotonicStopWatch _sw;
    };

    // Create a runtime profile object with 'name'.
    explicit RuntimeProfile(std::string name, bool is_averaged_profile = false);

    ~RuntimeProfile() = default;

    RuntimeProfile* parent() const { return _parent; }

    void reset_parent() { _parent = nullptr; }

    // Adds a child profile.  This is thread safe.
    // 'indent' indicates whether the child will be printed w/ extra indentation
    // relative to the parent.
    // If location is non-null, child will be inserted after location.  Location must
    // already be added to the profile.
    void add_child(RuntimeProfile* child, bool indent, RuntimeProfile* location);

    // Creates a new child profile with the given 'name'.
    // If 'prepend' is true, prepended before other child profiles, otherwise appended
    // after other child profiles.
    // If a child profile with that name already exist, the child will be returned, and
    // the arguments 'indent' and 'prepend' will be ignored.
    //
    // [thread-safe]
    RuntimeProfile* create_child(const std::string& name, bool indent = true, bool prepend = false);

    // Remove childs
    void remove_childs();

    // Reverse childs
    void reverse_childs();

    // Sorts all children according to a custom comparator. Does not
    // invalidate pointers to profiles.
    template <class Compare>
    void sort_childer(const Compare& cmp) {
        std::lock_guard<std::mutex> l(_children_lock);
        std::sort(_children.begin(), _children.end(), cmp);
    }

    // Merges the src profile into this one, combining counters that have an identical
    // path. Info strings from profiles are not merged. 'src' would be a const if it
    // weren't for locking.
    // Calling this concurrently on two RuntimeProfiles in reverse order results in
    // undefined behavior.
    void merge(RuntimeProfile* src);

    // Updates this profile w/ the thrift profile: behaves like Merge(), except
    // that existing counters are updated rather than added up.
    // Info strings matched up by key and are updated or added, depending on whether
    // the key has already been registered.
    void update(const TRuntimeProfileTree& thrift_profile);

    // Add a counter with 'name'/'type'.  Returns a counter object that the caller can
    // update.  The counter is owned by the RuntimeProfile object.
    // If parent_name is a non-empty string, the counter is added as a child of
    // parent_name.
    // If the counter already exists, the existing counter object is returned.
    Counter* add_child_counter(const std::string& name, TUnit::type type, const TCounterStrategy& strategy,
                               const std::string& parent_name);
    Counter* add_counter(const std::string& name, TUnit::type type, const TCounterStrategy& strategy) {
        return add_child_counter(name, type, strategy, ROOT_COUNTER);
    }

    template <class Visitor>
    void foreach_children(Visitor&& callback);

    // Add a derived counter with 'name'/'type'. The counter is owned by the
    // RuntimeProfile object.
    // If parent_name is a non-empty string, the counter is added as a child of
    // parent_name.
    // Returns NULL if the counter already exists.
    DerivedCounter* add_derived_counter(const std::string& name, TUnit::type type,
                                        const DerivedCounterFunction& counter_fn, const std::string& parent_name);

    // Add a set of thread counters prefixed with 'prefix'. Returns a ThreadCounters object
    // that the caller can update.  The counter is owned by the RuntimeProfile object.
    ThreadCounters* add_thread_counters(const std::string& prefix);

    // Gets the counter object with 'name'.  Returns NULL if there is no counter with
    // that name.
    std::pair<Counter*, std::string> get_counter_pair(const std::string& name);

    // Gets the counter object with 'name'.  Returns NULL if there is no counter with
    // that name.
    Counter* get_counter(const std::string& name);

    // Adds all counters with 'name' that are registered either in this or
    // in any of the child profiles to 'counters'.
    void get_counters(const std::string& name, std::vector<Counter*>* counters);

    // Copy all but the bucket counters from src profile
    void copy_all_counters_from(RuntimeProfile* src_profile, const std::string& attached_counter_name = ROOT_COUNTER);

    // Remove the counter object with 'name', and it will remove all the child counters recursively
    void remove_counter(const std::string& name);

    // Clean all the counters except saved_names
    void remove_counters(const std::set<std::string>& saved_names);

    // Helper to append to the "ExecOption" info string.
    void append_exec_option(const std::string& option) { add_info_string("ExecOption", option); }

    // Adds a string to the runtime profile.  If a value already exists for 'key',
    // the value will be updated.
    void add_info_string(const std::string& key, const std::string& value = "");

    // Creates and returns a new EventSequence (owned by the runtime
    // profile) - unless a timer with the same 'key' already exists, in
    // which case it is returned.
    // TODO: EventSequences are not merged by Merge()
    EventSequence* add_event_sequence(const std::string& key);

    // Returns a pointer to the info string value for 'key'.  Returns NULL if
    // the key does not exist.
    std::string* get_info_string(const std::string& key);

    // Copy all the string infos from src profile
    void copy_all_info_strings_from(RuntimeProfile* src_profile);

    // Returns the counter for the total elapsed time.
    Counter* total_time_counter() { return &_counter_total_time; }

    // Prints the counters in a name: value format.
    // Does not hold locks when it makes any function calls.
    void pretty_print(std::ostream* s, const std::string& prefix = "") const;

    // Serializes profile to thrift.
    // Does not hold locks when it makes any function calls.
    void to_thrift(TRuntimeProfileTree* tree);
    void to_thrift(std::vector<TRuntimeProfileNode>* nodes);

    // Divides all counters by n
    void divide(int n);

    size_t num_children() const {
        std::lock_guard guard(_children_lock);
        return _child_map.size();
    }

    // Get child of given name
    RuntimeProfile* get_child(const std::string& name);

    // Get child of given index
    RuntimeProfile* get_child(const size_t index);

    // Gets all direct children's profiles
    void get_children(std::vector<RuntimeProfile*>* children);

    // Gets all profiles in tree, including this one.
    void get_all_children(std::vector<RuntimeProfile*>* children);

    // Returns the number of counters in this profile
    int num_counters() const { return _counter_map.size(); }

    // Returns name of this profile
    const std::string& name() const { return _name; }

    // *only call this on top-level profiles*
    // (because it doesn't re-file child profiles)
    void set_name(const std::string& name) { _name = name; }

    int64_t metadata() const { return _metadata; }
    void set_metadata(int64_t md) { _metadata = md; }

    // Derived counter function: return measured throughput as input_value/second.
    static int64_t units_per_second(const Counter* total_counter, const Counter* timer);

    // Derived counter function: return aggregated value
    [[maybe_unused]] static int64_t counter_sum(const std::vector<Counter*>* counters);

    // Function that returns a counter metric.
    // Note: this function should not block (or take a long time).
    typedef std::function<int64_t()> SampleFn;

    /// Adds a high water mark counter to the runtime profile. Otherwise, same behavior
    /// as AddCounter().
    HighWaterMarkCounter* AddHighWaterMarkCounter(const std::string& name, TUnit::type unit,
                                                  const TCounterStrategy& strategy,
                                                  const std::string& parent_name = "");

    LowWaterMarkCounter* AddLowWaterMarkCounter(const std::string& name, TUnit::type unit,
                                                const TCounterStrategy& strategy, const std::string& parent_name = "");

    // Recursively compute the fraction of the 'total_time' spent in this profile and
    // its children.
    // This function updates _local_time_percent for each profile.
    void compute_time_in_profile();

    void inc_version() {
        std::lock_guard<std::mutex> l(_version_lock);
        _version += 1;
    }

    int64_t get_version() const {
        std::lock_guard<std::mutex> l(_version_lock);
        return _version;
    }

public:
    // The root counter name for all top level counters.
    const static std::string ROOT_COUNTER;

private:
    // vector of (profile, indentation flag)
    typedef std::vector<std::pair<RuntimeProfile*, bool>> ChildVector;

    void add_child_unlock(RuntimeProfile* child, bool indent, ChildVector::iterator pos);
    Counter* add_counter_unlock(const std::string& name, TUnit::type type, const TCounterStrategy& strategy,
                                const std::string& parent_name);

    RuntimeProfile* get_child_unlock(const std::string& name);

    RuntimeProfile* _parent;

    // Pool for allocated counters. Usually owned by the creator of this
    // object, but occasionally allocated in the constructor.
    std::unique_ptr<ObjectPool> _pool;

    // Name for this runtime profile.
    std::string _name;

    // user-supplied, uninterpreted metadata.
    int64_t _metadata;

    /// True if this profile is an average derived from other profiles.
    /// All counters in this profile must be of unit AveragedCounter.
    bool _is_averaged_profile;

    // Map from counter names to counters and parent counter names.
    // The profile owns the memory for the counters.
    typedef std::map<std::string, std::pair<Counter*, std::string>> CounterMap;
    CounterMap _counter_map;

    // Map from parent counter name to a set of child counter name.
    // All top level counters are the child of "" (root).
    typedef std::map<std::string, std::set<std::string>> ChildCounterMap;
    ChildCounterMap _child_counter_map;

    // A set of bucket counters registered in this runtime profile.
    std::set<std::vector<Counter*>*> _bucketing_counters;

    // protects _counter_map, _counter_child_map and _bucketing_counters
    mutable std::mutex _counter_lock;

    // Child profiles.  Does not own memory.
    // We record children in both a map (to facilitate updates) and a vector
    // (to print things in the order they were registered)
    typedef std::map<std::string, RuntimeProfile*> ChildMap;
    ChildMap _child_map;

    ChildVector _children;
    mutable std::mutex _children_lock; // protects _child_map and _children

    typedef std::map<std::string, std::string> InfoStrings;
    InfoStrings _info_strings;

    // Keeps track of the order in which InfoStrings are displayed when printed
    typedef std::vector<std::string> InfoStringsDisplayOrder;
    InfoStringsDisplayOrder _info_strings_display_order;

    // Protects _info_strings and _info_strings_display_order
    mutable std::mutex _info_strings_lock;

    typedef std::map<std::string, EventSequence*> EventSequenceMap;
    EventSequenceMap _event_sequence_map;
    mutable std::mutex _event_sequences_lock;

    Counter _counter_total_time;
    // Time spent in just in this profile (i.e. not the children) as a fraction
    // of the total time in the entire profile tree.
    double _local_time_percent;

    // Protects _version
    mutable std::mutex _version_lock;
    // The version of this profile. It is used to prevent updating this profile
    // from an old one.
    int64_t _version{0};

    // update a subtree of profiles from nodes, rooted at *idx. If the version
    // of the parent node, or the version of root node for this subtree is older,
    // skip to update the subtree, but still traverse the nodes of subtree to
    // get the node immediately following this subtree.
    // On return, *idx points to the node immediately following this subtree.
    void update(const std::vector<TRuntimeProfileNode>& nodes, int* idx, bool is_parent_node_old);

    // Helper function to compute compute the fraction of the total time spent in
    // this profile and its children.
    // Called recusively.
    void compute_time_in_profile(int64_t total_time);

    // Print the child counters of the given counter name
    static void print_child_counters(const std::string& prefix, const std::string& counter_name,
                                     const CounterMap& counter_map, const ChildCounterMap& child_counter_map,
                                     std::ostream* s);

public:
    // Merge all the isomorphic sub profiles and the caller must know for sure
    // that all the children are isomorphic, otherwise, the behavior is undefined
    // The merged result will be stored in the first profile
    static RuntimeProfile* merge_isomorphic_profiles(ObjectPool* obj_pool, std::vector<RuntimeProfile*>& profiles,
                                                     bool require_identical = true);

private:
    static const std::unordered_set<std::string> NON_MERGE_COUNTER_NAMES;
    // Merge all the isomorphic counters
    typedef std::tuple<int64_t, int64_t, int64_t> MergedInfo;
    static MergedInfo merge_isomorphic_counters(std::vector<Counter*>& counters);

    static bool is_time_type(TUnit::type type) {
        return TUnit::type::CPU_TICKS == type || TUnit::type::TIME_NS == type || TUnit::type::TIME_MS == type ||
               TUnit::type::TIME_S == type;
    }

    std::string get_children_name_string();
};

// Utility class to update the counter at object construction and destruction.
// When the object is constructed, decrement the counter by val.
// When the object goes out of scope, increment the counter by val.
class ScopedCounter {
public:
    ScopedCounter(RuntimeProfile::Counter* counter, int64_t val) : _val(val), _counter(counter) {
        if (counter == nullptr) {
            return;
        }

        _counter->update(-1L * _val);
    }

    // Increment the counter when object is destroyed
    ~ScopedCounter() {
        if (_counter != nullptr) {
            _counter->update(_val);
        }
    }

    // Disable copy constructor and assignment
    ScopedCounter(const ScopedCounter& counter) = delete;
    ScopedCounter& operator=(const ScopedCounter& counter) = delete;

private:
    int64_t _val;
    RuntimeProfile::Counter* _counter;
};

// Utility class to update time elapsed when the object goes out of scope.
// 'T' must implement the stopWatch "interface" (start,stop,elapsed_time) but
// we use templates not to pay for virtual function overhead.
template <class T>
class ScopedTimer {
public:
    explicit ScopedTimer(RuntimeProfile::Counter* counter, const bool* is_cancelled = nullptr)
            : _counter(counter), _is_cancelled(is_cancelled) {
        if (counter == nullptr) {
            return;
        }
        DCHECK(counter->type() == TUnit::TIME_NS);
        _sw.start();
    }

    // Disable copy constructor and assignment
    ScopedTimer(const ScopedTimer& timer) = delete;
    ScopedTimer& operator=(const ScopedTimer& timer) = delete;

    void stop() { _sw.stop(); }

    void start() { _sw.start(); }

    int64_t elapsed_time() { return _sw.elapsed_time(); }

    bool is_cancelled() { return _is_cancelled != nullptr && *_is_cancelled; }

    void UpdateCounter() {
        if (_counter != nullptr && !is_cancelled()) {
            _counter->update(_sw.elapsed_time());
        }
    }

    // Update counter when object is destroyed
    ~ScopedTimer() {
        _sw.stop();
        UpdateCounter();
    }

private:
    T _sw;
    RuntimeProfile::Counter* _counter;
    const bool* _is_cancelled;
};

// Utility class to update time elapsed when the object goes out of scope.
// 'T' must implement the stopWatch "interface" (start,stop,elapsed_time) but
// we use templates not to pay for virtual function overhead.
template <class T>
class ScopedRawTimer {
public:
    explicit ScopedRawTimer(int64_t* counter) : _counter(counter) { _sw.start(); }
    // Update counter when object is destroyed
    ~ScopedRawTimer() { *_counter += _sw.elapsed_time(); }

    // Disable copy constructor and assignment
    ScopedRawTimer(const ScopedRawTimer& timer) = delete;
    ScopedRawTimer& operator=(const ScopedRawTimer& timer) = delete;

private:
    T _sw;
    int64_t* _counter;
};

} // namespace starrocks
