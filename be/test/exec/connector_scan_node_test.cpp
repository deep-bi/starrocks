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

#include "exec/connector_scan_node.h"

#include <gtest/gtest.h>

#include "column/datum_tuple.h"
#include "exec/exec_node.h"
#include "exec/pipeline/pipeline_metrics.h"
#include "exec/pipeline/query_context.h"
#include "exec/pipeline/scan/chunk_buffer_limiter.h"
#include "exec/pipeline/scan/connector_scan_operator.h"
#include "exec/pipeline/scan/morsel.h"
#include "exec/workgroup/scan_executor.h"
#include "exec/workgroup/scan_task_queue.h"
#include "gen_cpp/PlanNodes_types.h"
#include "runtime/descriptor_helper.h"
#include "runtime/exec_env.h"
#include "runtime/runtime_state.h"
#include "runtime/stream_load/load_stream_mgr.h"
#include "runtime/stream_load/stream_load_pipe.h"
#include "testutil/assert.h"
#include "util/defer_op.h"
#include "util/threadpool.h"

namespace starrocks {

class ConnectorScanNodeTest : public ::testing::Test {
public:
    void SetUp() override {
        config::enable_system_metrics = false;
        config::enable_metric_calculator = false;

        _mem_tracker = std::make_shared<MemTracker>(-1, "connector scan");
        _exec_env = ExecEnv::GetInstance();
    }
    void TearDown() override {}

protected:
    std::shared_ptr<RuntimeState> create_runtime_state();
    std::shared_ptr<RuntimeState> create_runtime_state(const TQueryOptions& query_options);

    DescriptorTbl* create_table_desc(RuntimeState* runtime_state, const std::vector<TypeDescriptor>& types);

    std::shared_ptr<TPlanNode> create_tplan_node_cloud();
    std::vector<TScanRangeParams> create_scan_ranges_cloud(size_t num);

    std::shared_ptr<TPlanNode> create_tplan_node_hive();
    std::vector<TScanRangeParams> create_scan_ranges_hive(size_t num);

    std::shared_ptr<TPlanNode> create_tplan_node_stream_load();
    std::vector<TScanRangeParams> create_scan_ranges_stream_load(RuntimeState* runtime_state,
                                                                 const std::vector<TypeDescriptor>& types,
                                                                 const UniqueId& load_id);

    std::shared_ptr<MemTracker> _mem_tracker = nullptr;
    ExecEnv* _exec_env = nullptr;
};

std::shared_ptr<RuntimeState> ConnectorScanNodeTest::create_runtime_state() {
    TQueryOptions query_options;
    return create_runtime_state(query_options);
}

std::shared_ptr<RuntimeState> ConnectorScanNodeTest::create_runtime_state(const TQueryOptions& query_options) {
    TUniqueId fragment_id;
    TQueryGlobals query_globals;
    std::shared_ptr<RuntimeState> runtime_state =
            std::make_shared<RuntimeState>(fragment_id, query_options, query_globals, _exec_env);
    TUniqueId id;
    runtime_state->init_mem_trackers(id);
    return runtime_state;
}

DescriptorTbl* ConnectorScanNodeTest::create_table_desc(RuntimeState* runtime_state,
                                                        const std::vector<TypeDescriptor>& types) {
    /// Init DescriptorTable
    TDescriptorTableBuilder desc_tbl_builder;
    TTupleDescriptorBuilder tuple_desc_builder;
    for (auto& t : types) {
        TSlotDescriptorBuilder slot_desc_builder;
        slot_desc_builder.type(t).length(t.len).precision(t.precision).scale(t.scale).nullable(true);
        tuple_desc_builder.add_slot(slot_desc_builder.build());
    }
    tuple_desc_builder.build(&desc_tbl_builder);

    DescriptorTbl* tbl = nullptr;
    CHECK(DescriptorTbl::create(runtime_state, runtime_state->obj_pool(), desc_tbl_builder.desc_tbl(), &tbl,
                                config::vector_chunk_size)
                  .ok());

    runtime_state->set_desc_tbl(tbl);
    return tbl;
}

std::shared_ptr<TPlanNode> ConnectorScanNodeTest::create_tplan_node_cloud() {
    std::vector<::starrocks::TTupleId> tuple_ids{0};

    auto tnode = std::make_shared<TPlanNode>();
    tnode->__set_node_id(1);
    tnode->__set_node_type(TPlanNodeType::LAKE_SCAN_NODE);
    tnode->__set_row_tuples(tuple_ids);
    tnode->__set_limit(-1);

    TConnectorScanNode connector_scan_node;
    connector_scan_node.connector_name = connector::Connector::LAKE;
    tnode->__set_connector_scan_node(connector_scan_node);

    return tnode;
}

std::vector<TScanRangeParams> ConnectorScanNodeTest::create_scan_ranges_cloud(size_t num) {
    std::vector<TScanRangeParams> scan_ranges;

    for (int i = 0; i < num; i++) {
        TInternalScanRange internal_scan_range;
        internal_scan_range.__set_tablet_id(i);
        internal_scan_range.__set_version("1");

        TScanRange scan_range;
        scan_range.__set_internal_scan_range(internal_scan_range);

        TScanRangeParams param;
        param.__set_scan_range(scan_range);
        scan_ranges.emplace_back(param);
    }

    return scan_ranges;
}

TEST_F(ConnectorScanNodeTest, test_convert_scan_range_to_morsel_queue_factory_cloud) {
    std::shared_ptr<RuntimeState> runtime_state = create_runtime_state();
    std::vector<TypeDescriptor> types;
    types.emplace_back(TYPE_INT);
    auto* descs = create_table_desc(runtime_state.get(), types);
    auto tnode = create_tplan_node_cloud();
    auto scan_node = std::make_shared<starrocks::ConnectorScanNode>(runtime_state->obj_pool(), *tnode, *descs);
    ASSERT_OK(scan_node->init(*tnode, runtime_state.get()));

    bool enable_tablet_internal_parallel = false;
    auto tablet_internal_parallel_mode = TTabletInternalParallelMode::type::AUTO;
    std::map<int32_t, std::vector<TScanRangeParams>> no_scan_ranges_per_driver_seq;

    // dop is 1 and not so much morsels
    int pipeline_dop = 1;
    auto scan_ranges = create_scan_ranges_cloud(1);
    ASSIGN_OR_ABORT(auto morsel_queue_factory,
                    scan_node->convert_scan_range_to_morsel_queue_factory(
                            scan_ranges, no_scan_ranges_per_driver_seq, scan_node->id(), pipeline_dop, false,
                            enable_tablet_internal_parallel, tablet_internal_parallel_mode));
    ASSERT_TRUE(morsel_queue_factory->is_shared());

    // dop is 2 and not so much morsels
    pipeline_dop = 2;
    scan_ranges = create_scan_ranges_cloud(pipeline_dop * scan_node->io_tasks_per_scan_operator());
    ASSIGN_OR_ABORT(morsel_queue_factory,
                    scan_node->convert_scan_range_to_morsel_queue_factory(
                            scan_ranges, no_scan_ranges_per_driver_seq, scan_node->id(), pipeline_dop, false,
                            enable_tablet_internal_parallel, tablet_internal_parallel_mode, false));
    ASSERT_FALSE(morsel_queue_factory->is_shared());

    // dop is 2 and not so much morsels but enable shared scan
    ASSIGN_OR_ABORT(morsel_queue_factory,
                    scan_node->convert_scan_range_to_morsel_queue_factory(
                            scan_ranges, no_scan_ranges_per_driver_seq, scan_node->id(), pipeline_dop, false,
                            enable_tablet_internal_parallel, tablet_internal_parallel_mode, true));
    ASSERT_TRUE(morsel_queue_factory->is_shared());

    // dop is 2 and so much morsels
    pipeline_dop = 2;
    scan_ranges = create_scan_ranges_cloud(pipeline_dop * scan_node->io_tasks_per_scan_operator() + 1);
    ASSIGN_OR_ABORT(morsel_queue_factory,
                    scan_node->convert_scan_range_to_morsel_queue_factory(
                            scan_ranges, no_scan_ranges_per_driver_seq, scan_node->id(), pipeline_dop, false,
                            enable_tablet_internal_parallel, tablet_internal_parallel_mode));
    ASSERT_TRUE(morsel_queue_factory->is_shared());
}

std::shared_ptr<TPlanNode> ConnectorScanNodeTest::create_tplan_node_hive() {
    std::vector<::starrocks::TTupleId> tuple_ids{0};

    auto tnode = std::make_shared<TPlanNode>();
    tnode->__set_node_id(1);
    tnode->__set_node_type(TPlanNodeType::HDFS_SCAN_NODE);
    tnode->__set_row_tuples(tuple_ids);
    tnode->__set_limit(-1);

    TConnectorScanNode connector_scan_node;
    connector_scan_node.connector_name = connector::Connector::HIVE;
    tnode->__set_connector_scan_node(connector_scan_node);

    return tnode;
}

std::vector<TScanRangeParams> ConnectorScanNodeTest::create_scan_ranges_hive(size_t num) {
    std::vector<TScanRangeParams> scan_ranges;

    for (int i = 0; i < num; i++) {
        THdfsScanRange hdfs_scan_range;
        hdfs_scan_range.__set_full_path("file");
        hdfs_scan_range.__set_offset(i);
        hdfs_scan_range.__set_length(1);

        TScanRange scan_range;
        scan_range.__set_hdfs_scan_range(hdfs_scan_range);

        TScanRangeParams param;
        param.__set_scan_range(scan_range);
        scan_ranges.emplace_back(param);
    }

    return scan_ranges;
}

TEST_F(ConnectorScanNodeTest, test_convert_scan_range_to_morsel_queue_factory_hive) {
    std::shared_ptr<RuntimeState> runtime_state = create_runtime_state();
    std::vector<TypeDescriptor> types;
    types.emplace_back(TYPE_INT);
    auto* descs = create_table_desc(runtime_state.get(), types);
    auto tnode = create_tplan_node_hive();
    auto scan_node = std::make_shared<starrocks::ConnectorScanNode>(runtime_state->obj_pool(), *tnode, *descs);
    ASSERT_OK(scan_node->init(*tnode, runtime_state.get()));

    bool enable_tablet_internal_parallel = false;
    auto tablet_internal_parallel_mode = TTabletInternalParallelMode::type::AUTO;
    std::map<int32_t, std::vector<TScanRangeParams>> no_scan_ranges_per_driver_seq;

    // dop is 1 and not so much morsels
    int pipeline_dop = 1;
    auto scan_ranges = create_scan_ranges_hive(1);
    ASSIGN_OR_ABORT(auto morsel_queue_factory,
                    scan_node->convert_scan_range_to_morsel_queue_factory(
                            scan_ranges, no_scan_ranges_per_driver_seq, scan_node->id(), pipeline_dop, false,
                            enable_tablet_internal_parallel, tablet_internal_parallel_mode));
    ASSERT_TRUE(morsel_queue_factory->is_shared());

    // dop is 2 and not so much morsels
    pipeline_dop = 2;
    scan_ranges = create_scan_ranges_hive(pipeline_dop * scan_node->io_tasks_per_scan_operator());
    ASSIGN_OR_ABORT(morsel_queue_factory,
                    scan_node->convert_scan_range_to_morsel_queue_factory(
                            scan_ranges, no_scan_ranges_per_driver_seq, scan_node->id(), pipeline_dop, false,
                            enable_tablet_internal_parallel, tablet_internal_parallel_mode));
    ASSERT_TRUE(morsel_queue_factory->is_shared());

    // dop is 2 and so much morsels
    pipeline_dop = 2;
    scan_ranges = create_scan_ranges_hive(pipeline_dop * scan_node->io_tasks_per_scan_operator() + 1);
    ASSIGN_OR_ABORT(morsel_queue_factory,
                    scan_node->convert_scan_range_to_morsel_queue_factory(
                            scan_ranges, no_scan_ranges_per_driver_seq, scan_node->id(), pipeline_dop, false,
                            enable_tablet_internal_parallel, tablet_internal_parallel_mode));
    ASSERT_TRUE(morsel_queue_factory->is_shared());
}

std::shared_ptr<TPlanNode> ConnectorScanNodeTest::create_tplan_node_stream_load() {
    std::vector<::starrocks::TTupleId> tuple_ids{0};

    auto tnode = std::make_shared<TPlanNode>();
    tnode->__set_node_id(1);
    tnode->__set_node_type(TPlanNodeType::FILE_SCAN_NODE);
    tnode->__set_row_tuples(tuple_ids);
    tnode->__set_limit(-1);

    TConnectorScanNode connector_scan_node;
    connector_scan_node.connector_name = connector::Connector::FILE;
    tnode->__set_connector_scan_node(connector_scan_node);

    return tnode;
}

std::vector<TScanRangeParams> ConnectorScanNodeTest::create_scan_ranges_stream_load(
        RuntimeState* runtime_state, const std::vector<TypeDescriptor>& types, const UniqueId& load_id) {
    ObjectPool* object_pool = runtime_state->obj_pool();
    TBrokerScanRangeParams* params = object_pool->add(new TBrokerScanRangeParams());
    params->strict_mode = true;
    params->dest_tuple_id = 0;
    params->src_tuple_id = 0;
    for (int i = 0; i < types.size(); i++) {
        params->expr_of_dest_slot[i] = TExpr();
        params->expr_of_dest_slot[i].nodes.emplace_back();
        params->expr_of_dest_slot[i].nodes[0].__set_type(types[i].to_thrift());
        params->expr_of_dest_slot[i].nodes[0].__set_node_type(TExprNodeType::SLOT_REF);
        params->expr_of_dest_slot[i].nodes[0].__set_is_nullable(true);
        params->expr_of_dest_slot[i].nodes[0].__set_slot_ref(TSlotRef());
        params->expr_of_dest_slot[i].nodes[0].slot_ref.__set_slot_id(i);
    }
    for (int i = 0; i < types.size(); i++) {
        params->src_slot_ids.emplace_back(i);
    }

    std::vector<TBrokerRangeDesc>* ranges = object_pool->add(new vector<TBrokerRangeDesc>());
    TBrokerRangeDesc* range = object_pool->add(new TBrokerRangeDesc());
    range->__set_load_id(load_id.to_thrift());
    range->__set_file_type(TFileType::FILE_STREAM);
    range->__set_format_type(TFileFormatType::FORMAT_CSV_PLAIN);
    params->__set_multi_row_delimiter("\n");
    params->__set_multi_column_separator("\t");
    range->__set_path("Invalid Path");
    range->__set_start_offset(0);
    range->__set_size(-1);
    range->__set_num_of_columns_from_file(types.size());
    ranges->push_back(*range);

    TBrokerScanRange* broker_scan_range = object_pool->add(new TBrokerScanRange());
    broker_scan_range->params = *params;
    broker_scan_range->ranges = *ranges;

    TScanRange scan_range;
    scan_range.__set_broker_scan_range(*broker_scan_range);

    TScanRangeParams param;
    param.__set_scan_range(scan_range);

    return std::vector<TScanRangeParams>{param};
}

TEST_F(ConnectorScanNodeTest, test_stream_load_thread_pool) {
    // 1. create StreamLoadPipe
    auto load_id = UniqueId::gen_uid();
    auto pipe = std::make_shared<StreamLoadPipe>(1024 * 1024, 64 * 1024);
    DeferOp remove_pipe([&]() { _exec_env->load_stream_mgr()->remove(load_id); });
    ASSERT_OK(_exec_env->load_stream_mgr()->put(load_id, pipe));

    // 2. create ConnectorScanNode
    TQueryOptions query_options;
    query_options.query_type = TQueryType::LOAD;
    query_options.load_job_type = TLoadJobType::STREAM_LOAD;
    std::shared_ptr<RuntimeState> runtime_state = create_runtime_state(query_options);
    std::vector<TypeDescriptor> types;
    types.emplace_back(TYPE_INT);
    types.emplace_back(TYPE_VARCHAR);
    auto* descs = create_table_desc(runtime_state.get(), types);
    auto tnode = create_tplan_node_stream_load();
    auto scan_node = std::make_shared<starrocks::ConnectorScanNode>(runtime_state->obj_pool(), *tnode, *descs);
    DeferOp close_scan([&]() { scan_node->close(runtime_state.get()); });
    std::vector<TScanRangeParams> scan_ranges = create_scan_ranges_stream_load(runtime_state.get(), types, load_id);
    ASSERT_OK(scan_node->set_scan_ranges(scan_ranges));
    ASSERT_OK(scan_node->init(*tnode, runtime_state.get()));
    ASSERT_OK(scan_node->prepare(runtime_state.get()));
    ASSERT_OK(scan_node->open(runtime_state.get()));

    // 3. run the stream load
    std::string csv_data = "1\ttest";
    ASSERT_OK(pipe->append(csv_data.c_str(), csv_data.size()));
    ASSERT_OK(pipe->finish());

    bool done = false;
    auto chunk = std::make_shared<Chunk>();
    ASSERT_OK(scan_node->get_next(runtime_state.get(), &chunk, &done));
    ASSERT_EQ(1, chunk->num_rows());
    DatumTuple tuple = chunk->get(0);
    ASSERT_EQ(1, tuple.get(0).get_int32());
    ASSERT_EQ("test", tuple.get(1).get_slice());
    ASSERT_TRUE(scan_node->use_stream_load_thread_pool());
}

// When FE sets catalog_type explicitly, use it.
TEST_F(ConnectorScanNodeTest, test_catalog_type_with_explicit_value) {
    std::shared_ptr<RuntimeState> runtime_state = create_runtime_state();
    std::vector<TypeDescriptor> types;
    types.emplace_back(TYPE_INT);
    auto* descs = create_table_desc(runtime_state.get(), types);

    auto tnode = std::make_shared<TPlanNode>();
    tnode->__set_node_id(1);
    tnode->__set_node_type(TPlanNodeType::HDFS_SCAN_NODE);
    std::vector<::starrocks::TTupleId> tuple_ids{0};
    tnode->__set_row_tuples(tuple_ids);
    tnode->__set_limit(-1);
    TConnectorScanNode connector_scan_node;
    connector_scan_node.connector_name = connector::Connector::HIVE;
    connector_scan_node.__set_catalog_type("iceberg");
    tnode->__set_connector_scan_node(connector_scan_node);

    auto scan_node = std::make_shared<starrocks::ConnectorScanNode>(runtime_state->obj_pool(), *tnode, *descs);
    ASSERT_EQ("iceberg", scan_node->catalog_type());
}

// When FE does not set catalog_type (rolling upgrade), catalog_type should be empty
// so that BE skips catalog metrics rather than reporting wrong values.
TEST_F(ConnectorScanNodeTest, test_catalog_type_empty_without_fe_value) {
    std::shared_ptr<RuntimeState> runtime_state = create_runtime_state();
    std::vector<TypeDescriptor> types;
    types.emplace_back(TYPE_INT);
    auto* descs = create_table_desc(runtime_state.get(), types);

    auto tnode = create_tplan_node_hive();
    auto scan_node = std::make_shared<starrocks::ConnectorScanNode>(runtime_state->obj_pool(), *tnode, *descs);
    ASSERT_TRUE(scan_node->catalog_type().empty());
}

// ExecFactory tests: verify make_connector_scan_node correctly copies catalog_type
// and each scan node case routes to the right connector.

// HDFS_SCAN_NODE with pre-set catalog_type: covers make_connector_scan_node copy path
// (exec_factory.cpp lines 163, 167) and the HDFS case (line 275).
TEST_F(ConnectorScanNodeTest, test_exec_factory_hdfs_preserves_catalog_type) {
    auto runtime_state = create_runtime_state();
    std::vector<TypeDescriptor> types;
    types.emplace_back(TYPE_INT);
    auto* descs = create_table_desc(runtime_state.get(), types);

    TPlanNode tnode;
    tnode.__set_node_id(1);
    tnode.__set_node_type(TPlanNodeType::HDFS_SCAN_NODE);
    std::vector<TTupleId> tuple_ids{0};
    tnode.__set_row_tuples(tuple_ids);
    tnode.__set_limit(-1);
    TConnectorScanNode csn;
    csn.connector_name = connector::Connector::HIVE;
    csn.__set_catalog_type("hive");
    tnode.__set_connector_scan_node(csn);

    TPlan plan;
    plan.nodes.push_back(tnode);

    ExecNode* root = nullptr;
    ASSERT_OK(ExecNode::create_tree(runtime_state.get(), runtime_state->obj_pool(), plan, *descs, &root));
    ASSERT_NE(nullptr, root);
    auto* scan = static_cast<ConnectorScanNode*>(root);
    ASSERT_EQ("hive", scan->catalog_type());
}

// LAKE_SCAN_NODE without catalog_type: BE should default to "default"
// (exec_factory.cpp lines 311-314).
TEST_F(ConnectorScanNodeTest, test_exec_factory_lake_defaults_to_default_catalog_type) {
    auto runtime_state = create_runtime_state();
    std::vector<TypeDescriptor> types;
    types.emplace_back(TYPE_INT);
    auto* descs = create_table_desc(runtime_state.get(), types);

    TPlanNode tnode;
    tnode.__set_node_id(1);
    tnode.__set_node_type(TPlanNodeType::LAKE_SCAN_NODE);
    std::vector<TTupleId> tuple_ids{0};
    tnode.__set_row_tuples(tuple_ids);
    tnode.__set_limit(-1);
    // No connector_scan_node set — BE must default catalog_type to "default".

    TPlan plan;
    plan.nodes.push_back(tnode);

    ExecNode* root = nullptr;
    ASSERT_OK(ExecNode::create_tree(runtime_state.get(), runtime_state->obj_pool(), plan, *descs, &root));
    ASSERT_NE(nullptr, root);
    auto* scan = static_cast<ConnectorScanNode*>(root);
    ASSERT_EQ("default", scan->catalog_type());
}

// LAKE_SCAN_NODE with pre-set catalog_type from FE: BE must not overwrite it.
// (exec_factory.cpp line 311, 312 branch not taken since catalog_type already set).
TEST_F(ConnectorScanNodeTest, test_exec_factory_lake_preserves_catalog_type_when_fe_sets_it) {
    auto runtime_state = create_runtime_state();
    std::vector<TypeDescriptor> types;
    types.emplace_back(TYPE_INT);
    auto* descs = create_table_desc(runtime_state.get(), types);

    TPlanNode tnode;
    tnode.__set_node_id(1);
    tnode.__set_node_type(TPlanNodeType::LAKE_SCAN_NODE);
    std::vector<TTupleId> tuple_ids{0};
    tnode.__set_row_tuples(tuple_ids);
    tnode.__set_limit(-1);
    TConnectorScanNode csn;
    csn.connector_name = connector::Connector::LAKE;
    csn.__set_catalog_type("default");
    tnode.__set_connector_scan_node(csn);

    TPlan plan;
    plan.nodes.push_back(tnode);

    ExecNode* root = nullptr;
    ASSERT_OK(ExecNode::create_tree(runtime_state.get(), runtime_state->obj_pool(), plan, *descs, &root));
    ASSERT_NE(nullptr, root);
    auto* scan = static_cast<ConnectorScanNode*>(root);
    ASSERT_EQ("default", scan->catalog_type());
}

// JDBC_SCAN_NODE: covers exec_factory.cpp line 305.
TEST_F(ConnectorScanNodeTest, test_exec_factory_jdbc_scan_node) {
    auto runtime_state = create_runtime_state();
    std::vector<TypeDescriptor> types;
    types.emplace_back(TYPE_INT);
    auto* descs = create_table_desc(runtime_state.get(), types);

    TPlanNode tnode;
    tnode.__set_node_id(1);
    tnode.__set_node_type(TPlanNodeType::JDBC_SCAN_NODE);
    std::vector<TTupleId> tuple_ids{0};
    tnode.__set_row_tuples(tuple_ids);
    tnode.__set_limit(-1);
    TConnectorScanNode csn;
    csn.connector_name = connector::Connector::JDBC;
    csn.__set_catalog_type("jdbc");
    tnode.__set_connector_scan_node(csn);

    TPlan plan;
    plan.nodes.push_back(tnode);

    ExecNode* root = nullptr;
    ASSERT_OK(ExecNode::create_tree(runtime_state.get(), runtime_state->obj_pool(), plan, *descs, &root));
    ASSERT_NE(nullptr, root);
    auto* scan = static_cast<ConnectorScanNode*>(root);
    ASSERT_EQ("jdbc", scan->catalog_type());
}

// ES_HTTP_SCAN_NODE: covers exec_factory.cpp line 293.
TEST_F(ConnectorScanNodeTest, test_exec_factory_es_scan_node) {
    auto runtime_state = create_runtime_state();
    std::vector<TypeDescriptor> types;
    types.emplace_back(TYPE_INT);
    auto* descs = create_table_desc(runtime_state.get(), types);

    TPlanNode tnode;
    tnode.__set_node_id(1);
    tnode.__set_node_type(TPlanNodeType::ES_HTTP_SCAN_NODE);
    std::vector<TTupleId> tuple_ids{0};
    tnode.__set_row_tuples(tuple_ids);
    tnode.__set_limit(-1);
    TConnectorScanNode csn;
    csn.connector_name = connector::Connector::ES;
    csn.__set_catalog_type("elasticsearch");
    tnode.__set_connector_scan_node(csn);

    TPlan plan;
    plan.nodes.push_back(tnode);

    ExecNode* root = nullptr;
    ASSERT_OK(ExecNode::create_tree(runtime_state.get(), runtime_state->obj_pool(), plan, *descs, &root));
    ASSERT_NE(nullptr, root);
    auto* scan = static_cast<ConnectorScanNode*>(root);
    ASSERT_EQ("elasticsearch", scan->catalog_type());
}

// MYSQL_SCAN_NODE: covers exec_factory.cpp line 281.
TEST_F(ConnectorScanNodeTest, test_exec_factory_mysql_scan_node) {
    auto runtime_state = create_runtime_state();
    std::vector<TypeDescriptor> types;
    types.emplace_back(TYPE_INT);
    auto* descs = create_table_desc(runtime_state.get(), types);

    TPlanNode tnode;
    tnode.__set_node_id(1);
    tnode.__set_node_type(TPlanNodeType::MYSQL_SCAN_NODE);
    std::vector<TTupleId> tuple_ids{0};
    tnode.__set_row_tuples(tuple_ids);
    tnode.__set_limit(-1);
    TConnectorScanNode csn;
    csn.connector_name = connector::Connector::MYSQL;
    csn.__set_catalog_type("mysql");
    tnode.__set_connector_scan_node(csn);

    TPlan plan;
    plan.nodes.push_back(tnode);

    ExecNode* root = nullptr;
    ASSERT_OK(ExecNode::create_tree(runtime_state.get(), runtime_state->obj_pool(), plan, *descs, &root));
    ASSERT_NE(nullptr, root);
    auto* scan = static_cast<ConnectorScanNode*>(root);
    ASSERT_EQ("mysql", scan->catalog_type());
}

// These controls exercise registration boundaries with prepared operators and empty real queues.
class ConnectorInputScopeTest : public ConnectorScanNodeTest {
protected:
    void init(bool shared_output = false, bool shared_queue = false, bool random_split = false) {
        TQueryOptions options;
        options.__set_connector_io_tasks_per_scan_operator(2);
        state = create_runtime_state(options);
        query = std::make_shared<pipeline::QueryContext>();
        _mem_tracker = std::make_shared<MemTracker>(2L << 30, "connector input scope parent");
        query->init_mem_tracker(1L << 30, _mem_tracker.get());
        query->extend_query_lifetime();
        state->set_query_ctx(query.get());
        auto* desc = create_table_desc(state.get(), {TypeDescriptor(TYPE_BIGINT)});
        auto plan = create_tplan_node_cloud();
        node = std::make_shared<ConnectorScanNode>(state->obj_pool(), *plan, *desc);
        ASSERT_OK(node->init(*plan, state.get()));
        node->enable_shared_scan(shared_output); // Buffer routing is fixed by the factory constructor.
        if (shared_queue) {
            queues = std::make_unique<pipeline::SharedMorselQueueFactory>(
                    std::make_unique<pipeline::DynamicMorselQueue>(pipeline::Morsels{}, false), 2);
        } else {
            std::map<int, pipeline::MorselQueuePtr> individual_queues;
            for (int driver = 0; driver < 2; ++driver) {
                individual_queues[driver] = std::make_unique<pipeline::DynamicMorselQueue>(pipeline::Morsels{}, false);
            }
            queues = std::make_unique<pipeline::IndividualMorselQueueFactory>(std::move(individual_queues),
                                                                              random_split, random_split);
        }
        factory = std::make_shared<pipeline::ConnectorScanOperatorFactory>(
                1, node.get(), state.get(), 2,
                std::make_unique<pipeline::DynamicChunkBufferLimiter>(64, 64, 1L << 26, 1));
        factory->set_morsel_queue_factory(queues.get());
        factory->set_degree_of_parallelism(2);
        auto* arb = query->connector_scan_operator_mem_share_arbitrator();
        factory->set_mem_share_arb(arb);
        factory->set_scan_mem_limit(arb->scan_mem_limit);
        factory->set_data_source_mem_bytes(connector::DataSourceProvider::MIN_DATA_SOURCE_MEM_BYTES);
        factory->set_chunk_source_mem_bytes(connector::DataSourceProvider::MIN_DATA_SOURCE_MEM_BYTES);
        ASSERT_OK(factory->prepare(state.get()));
        std::unique_ptr<ThreadPool> pool;
        ASSERT_OK(ThreadPoolBuilder("connector_input_scope").set_min_threads(0).set_max_threads(1).build(&pool));
        executor = std::make_unique<workgroup::ScanExecutor>(
                std::move(pool), std::make_unique<workgroup::PriorityScanTaskQueue>(100), &metrics);
        executor->initialize(1);
        op = std::static_pointer_cast<pipeline::ConnectorScanOperator>(factory->create(2, 1));
        op->add_morsel_queue(queues->create(1));
        op->set_scan_executor(executor.get());
        op->set_query_ctx(query);
        ASSERT_OK(op->prepare(state.get()));
        ASSERT_TRUE(queues->create(1)->empty());
        ASSERT_FALSE(queues->create(1)->has_more());
        ASSERT_EQ(0, op->_num_running_io_tasks.load());
        ASSERT_TRUE(op->_get_scan_status().ok());
        ASSERT_TRUE(factory->get_chunk_buffer().empty(1));
        ASSERT_TRUE(op->is_finished());
    }
    void TearDown() override {
        if (executor != nullptr) executor->close();
        if (op != nullptr) {
            ASSERT_OK(op->set_finishing(state.get()));
            op->close(state.get());
        }
        op.reset();
        if (factory != nullptr) {
            EXPECT_TRUE(factory->get_active_inputs().empty());
            factory->close(state.get());
        }
        if (query != nullptr) {
            EXPECT_EQ(0, query->connector_scan_mem_tracker()->consumption());
            EXPECT_EQ(0, query->mem_tracker()->consumption());
        }
        factory.reset();
        queues.reset();
        ConnectorScanNodeTest::TearDown();
    }
    void check_foreign_source() {
        factory->attach_shared_input(0, 0);
        {
            DeferOp detach([&] { factory->detach_shared_input(0, 0); });
            EXPECT_FALSE(op->is_finished());
        }
        EXPECT_TRUE(op->is_finished());
    }

    std::shared_ptr<RuntimeState> state;
    pipeline::QueryContextPtr query;
    std::shared_ptr<ConnectorScanNode> node;
    pipeline::MorselQueueFactoryPtr queues;
    std::shared_ptr<pipeline::ConnectorScanOperatorFactory> factory;
    pipeline::ScanExecutorMetrics metrics;
    std::unique_ptr<workgroup::ScanExecutor> executor;
    std::shared_ptr<pipeline::ConnectorScanOperator> op;
};

// A driver whose own queue is empty (more drivers than tablets) must finish while another
// driver's reader is still registered: nothing that reader produces can reach this operator.
TEST_F(ConnectorInputScopeTest, EmptyIndividualQueueDoesNotWaitForForeignSource) {
    init();
    ASSERT_FALSE(HasFatalFailure());
    factory->attach_shared_input(0, 0);
    DeferOp detach([&] { factory->detach_shared_input(0, 0); });
    EXPECT_TRUE(op->is_finished());
}

TEST_F(ConnectorInputScopeTest, OwnRegisteredSourcePreventsEos) {
    init();
    ASSERT_FALSE(HasFatalFailure());
    const int last_slot = node->io_tasks_per_scan_operator() - 1;
    factory->attach_shared_input(1, last_slot);
    {
        DeferOp detach([&] { factory->detach_shared_input(1, last_slot); });
        EXPECT_FALSE(op->is_finished());
    }
    EXPECT_TRUE(op->is_finished());
}

TEST_F(ConnectorInputScopeTest, DirectOutputSharedQueueWaitsForForeignSource) {
    init(false, true);
    ASSERT_FALSE(HasFatalFailure());
    check_foreign_source();
}

TEST_F(ConnectorInputScopeTest, SharedOutputWaitsForForeignSource) {
    init(true, true);
    ASSERT_FALSE(HasFatalFailure());
    check_foreign_source();
}

TEST_F(ConnectorInputScopeTest, RandomSplitRoutingWaitsForForeignSource) {
    init(false, false, true);
    ASSERT_FALSE(HasFatalFailure());
    check_foreign_source();
}

TEST_F(ConnectorInputScopeTest, UnknownQueueFactoryWaitsForForeignSource) {
    init();
    ASSERT_FALSE(HasFatalFailure());
    factory->set_morsel_queue_factory(nullptr);
    DeferOp restore([&] { factory->set_morsel_queue_factory(queues.get()); });
    check_foreign_source();
}

TEST_F(ConnectorInputScopeTest, EmptyFinalChunkPreventsEos) {
    init();
    ASSERT_FALSE(HasFatalFailure());
    auto final_chunk = std::make_shared<Chunk>();
    final_chunk->owner_info().set_owner_id(77, true);
    ASSERT_TRUE(factory->get_chunk_buffer().put(1, final_chunk, nullptr));
    EXPECT_FALSE(op->is_finished());
    auto result = op->pull_chunk(state.get());
    ASSERT_TRUE(result.ok()) << result.status();
    ASSERT_NE(nullptr, result.value());
    EXPECT_TRUE(result.value()->is_empty());
    EXPECT_TRUE(result.value()->owner_info().is_last_chunk());
    EXPECT_TRUE(op->is_finished());
}

} // namespace starrocks
