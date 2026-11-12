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

#include <memory>
#include <vector>

#include "column/array_column.h"
#include "column/column_access_path.h"
#include "column/column_helper.h"
#include "column/fixed_length_column.h"
#include "column/json_column.h"
#include "column/map_column.h"
#include "column/nullable_column.h"
#include "column/vectorized_fwd.h"
#include "common/config.h"
#include "common/statusor.h"
#include "fs/fs_memory.h"
#include "gen_cpp/PlanNodes_types.h"
#include "gutil/casts.h"
#include "storage/chunk_helper.h"
#include "storage/rowset/column_iterator.h"
#include "storage/rowset/column_reader.h"
#include "storage/rowset/column_writer.h"
#include "storage/rowset/json_column_iterator.h"
#include "storage/rowset/segment.h"
#include "storage/tablet_schema_helper.h"
#include "storage/types.h"
#include "testutil/assert.h"
#include "types/logical_type.h"
#include "util/json.h"
#include "util/json_flattener.h"

namespace starrocks {

// NOLINTNEXTLINE
static const std::string TEST_DIR = "/flat_json_column_rw_test";

class FlatJsonColumnRWTest : public testing::Test {
public:
    FlatJsonColumnRWTest() = default;

    ~FlatJsonColumnRWTest() override = default;

protected:
    void SetUp() override {
        config::enable_json_flat_complex_type = true;
        _meta.reset(new ColumnMetaPB());
    }

    void TearDown() override {
        config::enable_json_flat_complex_type = false;
        config::json_flat_sparsity_factor = 0.9;
        config::json_flat_null_factor = 0.3;
    }

    std::shared_ptr<Segment> create_dummy_segment(const std::shared_ptr<FileSystem>& fs, const std::string& fname) {
        return std::make_shared<Segment>(fs, FileInfo{fname}, 1, _dummy_segment_schema, nullptr);
    }

    void test_json(ColumnWriterOptions& writer_opts, const std::string& case_file, ColumnPtr& write_col,
                   ColumnPtr& read_col, ColumnAccessPath* path) {
        auto fs = std::make_shared<MemoryFileSystem>();
        ASSERT_TRUE(fs->create_dir(TEST_DIR).ok());

        TabletColumn json_tablet_column = create_with_default_value<TYPE_JSON>("");
        TypeInfoPtr type_info = get_type_info(json_tablet_column);

        const std::string fname = TEST_DIR + case_file;
        auto segment = create_dummy_segment(fs, fname);

        // write data
        {
            ASSIGN_OR_ABORT(auto wfile, fs->new_writable_file(fname));

            writer_opts.meta = _meta.get();
            writer_opts.meta->set_column_id(0);
            writer_opts.meta->set_unique_id(0);
            writer_opts.meta->set_type(TYPE_JSON);
            writer_opts.meta->set_length(0);
            writer_opts.meta->set_encoding(DEFAULT_ENCODING);
            writer_opts.meta->set_compression(starrocks::LZ4_FRAME);
            writer_opts.meta->set_is_nullable(write_col->is_nullable());
            writer_opts.need_zone_map = false;

            ASSIGN_OR_ABORT(auto writer, ColumnWriter::create(writer_opts, &json_tablet_column, wfile.get()));
            ASSERT_OK(writer->init());

            ASSERT_TRUE(writer->append(*write_col).ok());

            ASSERT_TRUE(writer->finish().ok());
            ASSERT_TRUE(writer->write_data().ok());
            ASSERT_TRUE(writer->write_ordinal_index().ok());

            // close the file
            ASSERT_TRUE(wfile->close().ok());
        }
        // a real segment takes its row count from the footer, and the flat JSON readers check reads against it
        segment->set_num_rows(write_col->size());

        auto res = ColumnReader::create(_meta.get(), segment.get(), nullptr);
        ASSERT_TRUE(res.ok());
        auto reader = std::move(res).value();

        {
            ASSIGN_OR_ABORT(auto iter, reader->new_iterator(path));
            ASSIGN_OR_ABORT(auto read_file, fs->new_random_access_file(fname));

            ColumnIteratorOptions iter_opts;
            OlapReaderStatistics stats;
            iter_opts.stats = &stats;
            iter_opts.read_file = read_file.get();
            ASSERT_TRUE(iter->init(iter_opts).ok());

            // sequence read
            auto st = iter->seek_to_first();
            ASSERT_TRUE(st.ok()) << st.to_string();

            size_t rows_read = write_col->size();
            st = iter->next_batch(&rows_read, read_col.get());
            ASSERT_TRUE(st.ok());
        }
    }

    ColumnPtr create_json(const std::vector<std::string>& jsons, bool is_nullable) {
        auto json_col = JsonColumn::create();
        auto null_col = NullColumn::create();
        auto* json_column = down_cast<JsonColumn*>(json_col.get());
        for (auto& json : jsons) {
            if ("NULL" != json) {
                ASSIGN_OR_ABORT(auto jv, JsonValue::parse(json));
                json_column->append(&jv);
            } else {
                json_column->append_default();
            }
            null_col->append("NULL" == json);
        }

        if (is_nullable) {
            return NullableColumn::create(std::move(json_col), std::move(null_col));
        }
        return json_col;
    }

    // The JSON objects {"a": i, "b": "str_i"} for i in [0, count).
    static std::vector<std::string> ab_jsons(size_t count) {
        std::vector<std::string> jsons;
        for (size_t i = 0; i < count; i++) {
            jsons.emplace_back("{\"a\": " + std::to_string(i) + ", \"b\": \"str_" + std::to_string(i) + "\"}");
        }
        return jsons;
    }

    static void init_column_meta(ColumnMetaPB* meta, LogicalType type, int32_t length, bool is_nullable) {
        meta->set_column_id(0);
        meta->set_unique_id(0);
        meta->set_type(type);
        meta->set_length(length);
        meta->set_encoding(DEFAULT_ENCODING);
        meta->set_compression(starrocks::LZ4_FRAME);
        meta->set_is_nullable(is_nullable);
    }

    // Write `column` as the next column of `wfile`, its JSON as flat JSON. `meta` must be initialized, with the metas
    // of the children of an ARRAY or MAP added.
    void write_flat_column(WritableFile* wfile, ColumnMetaPB* meta, const TabletColumn& tablet_column,
                           const Column& column) {
        ColumnWriterOptions writer_opts;
        writer_opts.meta = meta;
        writer_opts.need_zone_map = false;
        writer_opts.need_flat = true;

        ASSIGN_OR_ABORT(auto writer, ColumnWriter::create(writer_opts, &tablet_column, wfile));
        ASSERT_OK(writer->init());
        ASSERT_OK(writer->append(column));
        ASSERT_OK(writer->finish());
        ASSERT_OK(writer->write_data());
        ASSERT_OK(writer->write_ordinal_index());
    }

private:
    std::shared_ptr<TabletSchema> _dummy_segment_schema;
    std::shared_ptr<ColumnMetaPB> _meta;
};

TEST_F(FlatJsonColumnRWTest, testNormalJson) {
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());

    ASSIGN_OR_ABORT(auto jv1, JsonValue::parse("{\"a\": 1, \"b\": 21}"));
    ASSIGN_OR_ABORT(auto jv2, JsonValue::parse("{\"a\": 2, \"b\": 22}"));
    ASSIGN_OR_ABORT(auto jv3, JsonValue::parse("{\"a\": 3, \"b\": 23}"));
    ASSIGN_OR_ABORT(auto jv4, JsonValue::parse("{\"a\": 4, \"b\": 24}"));
    ASSIGN_OR_ABORT(auto jv5, JsonValue::parse("{\"a\": 5, \"b\": 25}"));

    json_col->append(&jv1);
    json_col->append(&jv2);
    json_col->append(&jv3);
    json_col->append(&jv4);
    json_col->append(&jv5);

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = false;
    test_json(writer_opts, "/test_flat_json_rw1.data", write_col, read_col, nullptr);

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_FALSE(read_json->is_flat_json());
    EXPECT_EQ(5, read_json->size());
    EXPECT_EQ(0, read_json->get_flat_fields().size());
    EXPECT_EQ("{\"a\": 1, \"b\": 21}", read_json->debug_item(0));
    EXPECT_EQ("{\"a\": 4, \"b\": 24}", read_json->debug_item(3));
}

TEST_F(FlatJsonColumnRWTest, testNormalJsonWithPath) {
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());

    ASSIGN_OR_ABORT(auto jv1, JsonValue::parse("{\"a\": 1, \"b\": 21}"));
    ASSIGN_OR_ABORT(auto jv2, JsonValue::parse("{\"a\": 2, \"b\": 22}"));
    ASSIGN_OR_ABORT(auto jv3, JsonValue::parse("{\"a\": 3, \"b\": 23}"));
    ASSIGN_OR_ABORT(auto jv4, JsonValue::parse("{\"a\": 4, \"b\": 24}"));
    ASSIGN_OR_ABORT(auto jv5, JsonValue::parse("{\"a\": 5, \"b\": 25}"));

    json_col->append(&jv1);
    json_col->append(&jv2);
    json_col->append(&jv3);
    json_col->append(&jv4);
    json_col->append(&jv5);

    ASSIGN_OR_ABORT(auto root_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ASSIGN_OR_ABORT(auto f1_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.a", 0));
    ASSIGN_OR_ABORT(auto f2_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.b", 0));
    root_path->children().emplace_back(std::move(f1_path));
    root_path->children().emplace_back(std::move(f2_path));

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = false;
    test_json(writer_opts, "/test_flat_json_rw1.data", write_col, read_col, root_path.get());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_json->size());
    EXPECT_EQ(2, read_json->get_flat_fields().size());
    EXPECT_EQ("{a: 1, b: 21}", read_json->debug_item(0));
    EXPECT_EQ("{a: 4, b: 24}", read_json->debug_item(3));
}

TEST_F(FlatJsonColumnRWTest, testNormalFlatJsonWithPath) {
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());

    ASSIGN_OR_ABORT(auto jv1, JsonValue::parse("{\"a\": 1, \"b\": 21}"));
    ASSIGN_OR_ABORT(auto jv2, JsonValue::parse("{\"a\": 2, \"b\": 22}"));
    ASSIGN_OR_ABORT(auto jv3, JsonValue::parse("{\"a\": 3, \"b\": 23}"));
    ASSIGN_OR_ABORT(auto jv4, JsonValue::parse("{\"a\": 4, \"b\": 24}"));
    ASSIGN_OR_ABORT(auto jv5, JsonValue::parse("{\"a\": 5, \"b\": 25}"));

    json_col->append(&jv1);
    json_col->append(&jv2);
    json_col->append(&jv3);
    json_col->append(&jv4);
    json_col->append(&jv5);

    ASSIGN_OR_ABORT(auto root_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ASSIGN_OR_ABORT(auto f1_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.a", 0));
    ASSIGN_OR_ABORT(auto f2_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.b", 0));
    root_path->children().emplace_back(std::move(f1_path));
    root_path->children().emplace_back(std::move(f2_path));

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw1.data", write_col, read_col, root_path.get());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_json->size());
    ASSERT_EQ(2, read_json->get_flat_fields().size());
    EXPECT_EQ("{a: 1, b: 21}", read_json->debug_item(0));
    EXPECT_EQ("{a: 4, b: 24}", read_json->debug_item(3));

    EXPECT_EQ("3", read_json->get_flat_field("a")->debug_item(2));
}

TEST_F(FlatJsonColumnRWTest, testNormalFlatJsonWithoutPath) {
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());

    ASSIGN_OR_ABORT(auto jv1, JsonValue::parse("{\"a\": 1, \"b\": 21}"));
    ASSIGN_OR_ABORT(auto jv2, JsonValue::parse("{\"a\": 2, \"b\": 22}"));
    ASSIGN_OR_ABORT(auto jv3, JsonValue::parse("{\"a\": 3, \"b\": 23}"));
    ASSIGN_OR_ABORT(auto jv4, JsonValue::parse("{\"a\": 4, \"b\": 24}"));
    ASSIGN_OR_ABORT(auto jv5, JsonValue::parse("{\"a\": 5, \"b\": 25}"));

    json_col->append(&jv1);
    json_col->append(&jv2);
    json_col->append(&jv3);
    json_col->append(&jv4);
    json_col->append(&jv5);

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw1.data", write_col, read_col, nullptr);

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_FALSE(read_json->is_flat_json());
    EXPECT_EQ(5, read_json->size());
    ASSERT_EQ(0, read_json->get_flat_fields().size());
    EXPECT_EQ("{\"a\": 1, \"b\": 21}", read_json->debug_item(0));
    EXPECT_EQ("{\"a\": 4, \"b\": 24}", read_json->debug_item(3));
}

TEST_F(FlatJsonColumnRWTest, testNullNormalFlatJson) {
    config::json_flat_null_factor = 0.4;
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());

    ASSIGN_OR_ABORT(auto jv1, JsonValue::parse("{\"a\": 1, \"b\": 21}"));
    ASSIGN_OR_ABORT(auto jv2, JsonValue::parse("{\"a\": 2, \"b\": 22}"));
    ASSIGN_OR_ABORT(auto jv3, JsonValue::parse("{\"a\": 3, \"b\": 23}"));
    ASSIGN_OR_ABORT(auto jv4, JsonValue::parse("{\"a\": 4, \"b\": 24}"));
    ASSIGN_OR_ABORT(auto jv5, JsonValue::parse("{\"a\": 5, \"b\": 25}"));

    json_col->append(&jv1);
    json_col->append(&jv2);
    json_col->append(&jv3);
    json_col->append(&jv4);
    json_col->append(&jv5);

    auto null_col = NullColumn::create();
    null_col->append(1);
    null_col->append(1);
    null_col->append(1);
    null_col->append(1);
    null_col->append(0);

    ColumnPtr write_nl_col = NullableColumn::create(std::move(write_col), std::move(null_col));

    ASSIGN_OR_ABORT(auto root_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ASSIGN_OR_ABORT(auto f1_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.a", 0));
    ASSIGN_OR_ABORT(auto f2_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.b", 0));
    root_path->children().emplace_back(std::move(f1_path));
    root_path->children().emplace_back(std::move(f2_path));

    ColumnPtr read_col = NullableColumn::create(JsonColumn::create(), NullColumn::create());
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_nl_col, read_col, root_path.get());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ("NULL", read_col->debug_item(0));
    EXPECT_EQ("{a: 5, b: 25}", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, tesArrayFlatJson) {
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());

    ASSIGN_OR_ABORT(auto jv1, JsonValue::parse(R"([{"a": 1}, {"b": 21}] )"));
    ASSIGN_OR_ABORT(auto jv2, JsonValue::parse("{\"a\": 2, \"b\": 22}"));
    ASSIGN_OR_ABORT(auto jv3, JsonValue::parse("{\"a\": 3, \"b\": 23}"));
    ASSIGN_OR_ABORT(auto jv4, JsonValue::parse("{\"a\": 4, \"b\": 24}"));
    ASSIGN_OR_ABORT(auto jv5, JsonValue::parse("{\"a\": 5, \"b\": 25}"));

    json_col->append(&jv1);
    json_col->append(&jv2);
    json_col->append(&jv3);
    json_col->append(&jv4);
    json_col->append(&jv5);

    ASSIGN_OR_ABORT(auto root_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ASSIGN_OR_ABORT(auto f1_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.a", 0));
    ASSIGN_OR_ABORT(auto f2_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.b", 0));
    root_path->children().emplace_back(std::move(f1_path));
    root_path->children().emplace_back(std::move(f2_path));

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw3.data", write_col, read_col, root_path.get());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_json->size());
    ASSERT_EQ(2, read_json->get_flat_fields().size());
    EXPECT_EQ("{a: NULL, b: NULL}", read_json->debug_item(0));
    EXPECT_EQ("{a: 4, b: 24}", read_json->debug_item(3));
}

TEST_F(FlatJsonColumnRWTest, testEmptyFlatObject) {
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());

    ASSIGN_OR_ABORT(auto jv1, JsonValue::parse(R"("" )"));
    ASSIGN_OR_ABORT(auto jv2, JsonValue::parse("{\"a\": 2, \"b\": 22}"));
    ASSIGN_OR_ABORT(auto jv3, JsonValue::parse("{\"a\": 3, \"b\": 23}"));
    ASSIGN_OR_ABORT(auto jv4, JsonValue::parse("{\"a\": 4, \"b\": 24}"));
    ASSIGN_OR_ABORT(auto jv5, JsonValue::parse("{\"a\": 5, \"b\": 25}"));

    json_col->append(&jv1);
    json_col->append(&jv2);
    json_col->append(&jv3);
    json_col->append(&jv4);
    json_col->append(&jv5);

    ASSIGN_OR_ABORT(auto root_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ASSIGN_OR_ABORT(auto f1_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.a", 0));
    ASSIGN_OR_ABORT(auto f2_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.b", 0));
    root_path->children().emplace_back(std::move(f1_path));
    root_path->children().emplace_back(std::move(f2_path));

    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;

    ColumnPtr read_col = JsonColumn::create();
    test_json(writer_opts, "/test_flat_json_rw4.data", write_col, read_col, root_path.get());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_json->size());
    ASSERT_EQ(2, read_json->get_flat_fields().size());
    EXPECT_EQ("{a: NULL, b: NULL}", read_json->debug_item(0));
    EXPECT_EQ("{a: 4, b: 24}", read_json->debug_item(3));
}

TEST_F(FlatJsonColumnRWTest, testMergeRemainFlatJson) {
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());

    ASSIGN_OR_ABORT(auto jv1, JsonValue::parse(R"({"a": 1, "b": 21, "c": 31})"));
    ASSIGN_OR_ABORT(auto jv2, JsonValue::parse(R"({"a": 2, "b": 22, "d": 32})"));
    ASSIGN_OR_ABORT(auto jv3, JsonValue::parse(R"({"a": 3, "b": 23, "e": [1,2,3]})"));
    ASSIGN_OR_ABORT(auto jv4, JsonValue::parse(R"({"a": 4, "b": 24, "g": {"x": 1}})"));
    ASSIGN_OR_ABORT(auto jv5, JsonValue::parse(R"({"a": 5, "b": 25})"));

    json_col->append(&jv1);
    json_col->append(&jv2);
    json_col->append(&jv3);
    json_col->append(&jv4);
    json_col->append(&jv5);

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, nullptr);

    EXPECT_EQ(3, writer_opts.meta->children_columns_size());
    EXPECT_TRUE(writer_opts.meta->json_meta().is_flat());
    EXPECT_TRUE(writer_opts.meta->json_meta().has_remain());
    EXPECT_EQ("a", writer_opts.meta->children_columns(0).name());
    EXPECT_EQ("b", writer_opts.meta->children_columns(1).name());
    EXPECT_EQ("remain", writer_opts.meta->children_columns(2).name());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_FALSE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({"a": 1, "b": 21, "c": 31})", read_col->debug_item(0));
    EXPECT_EQ(R"({"a": 2, "b": 22, "d": 32})", read_col->debug_item(1));
    EXPECT_EQ(R"({"a": 3, "b": 23, "e": [1, 2, 3]})", read_col->debug_item(2));
    EXPECT_EQ(R"({"a": 4, "b": 24, "g": {"x": 1}})", read_col->debug_item(3));
    EXPECT_EQ(R"({"a": 5, "b": 25})", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testMergeRemainFlatJson2) {
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());
    std::vector<std::string> json = {
            R"({"a": 1, "b": {"b1": 22, "b2": {"b3": "abc"}, "b4": 1}, "c": 31})",
            R"({"a": 2, "b": {"b1": 23, "b2": {"b3": "efg"}, "b4": [1, 2, 3]}, "d": 32})",
            R"({"a": 3, "b": {"b1": 24, "b2": {"b3": "xyz"}, "b4": {"b5": 1}}, "e": [1, 2, 3]})",
            R"({"a": 4, "b": {"b1": 25, "b2": {"b3": "qwe"}, "b4": {"b7": 2}}, "g": {"x": 1}})",
            R"({"a": 5, "b": {"b1": 26, "b2": {"b3": "sdf"}, "b4": 23}})"};

    for (auto& x : json) {
        ASSIGN_OR_ABORT(auto jv, JsonValue::parse(x));
        json_col->append(jv);
    }

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, nullptr);

    EXPECT_EQ(5, writer_opts.meta->children_columns_size());
    EXPECT_TRUE(writer_opts.meta->json_meta().is_flat());
    EXPECT_TRUE(writer_opts.meta->json_meta().has_remain());
    EXPECT_EQ("a", writer_opts.meta->children_columns(0).name());
    EXPECT_EQ("b.b1", writer_opts.meta->children_columns(1).name());
    EXPECT_EQ("b.b2.b3", writer_opts.meta->children_columns(2).name());
    EXPECT_EQ("b.b4", writer_opts.meta->children_columns(3).name());
    EXPECT_EQ("remain", writer_opts.meta->children_columns(4).name());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_FALSE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());

    for (size_t i = 0; i < json.size(); i++) {
        EXPECT_EQ(json[i], read_col->debug_item(i));
    }
}

TEST_F(FlatJsonColumnRWTest, testMergeMiddleRemainFlatJson) {
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());

    ASSIGN_OR_ABORT(auto jv1, JsonValue::parse(R"({"a": 1, "b": 21, "c": 31})"));
    ASSIGN_OR_ABORT(auto jv2, JsonValue::parse(R"({"a": 2, "b": 22, "d": 32})"));
    ASSIGN_OR_ABORT(auto jv3, JsonValue::parse(R"({"a": 3, "b": 23, "e": [1,2,3]})"));
    ASSIGN_OR_ABORT(auto jv4, JsonValue::parse(R"({"a": 4, "b": 24, "g": {"x": 1}})"));
    ASSIGN_OR_ABORT(auto jv5, JsonValue::parse(R"({"a": 5, "b": 25})"));

    json_col->append(&jv1);
    json_col->append(&jv2);
    json_col->append(&jv3);
    json_col->append(&jv4);
    json_col->append(&jv5);

    ASSIGN_OR_ABORT(auto root_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ASSIGN_OR_ABORT(auto f1_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.a", 0));
    ASSIGN_OR_ABORT(auto f2_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.c", 0));
    root_path->children().emplace_back(std::move(f1_path));
    root_path->children().emplace_back(std::move(f2_path));

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root_path.get());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());

    EXPECT_EQ(3, writer_opts.meta->children_columns_size());
    EXPECT_TRUE(writer_opts.meta->json_meta().is_flat());
    EXPECT_TRUE(writer_opts.meta->json_meta().has_remain());
    EXPECT_EQ("a", writer_opts.meta->children_columns(0).name());
    EXPECT_EQ("b", writer_opts.meta->children_columns(1).name());
    EXPECT_EQ("remain", writer_opts.meta->children_columns(2).name());

    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ("{a: 1, c: 31}", read_col->debug_item(0));
    EXPECT_EQ("{a: 2, c: NULL}", read_col->debug_item(1));
    EXPECT_EQ("{a: 3, c: NULL}", read_col->debug_item(2));
    EXPECT_EQ("{a: 4, c: NULL}", read_col->debug_item(3));
    EXPECT_EQ("{a: 5, c: NULL}", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testMergeMiddleRemainFlatJson2) {
    config::json_flat_null_factor = 0.4;
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());
    std::vector<std::string> json = {
            R"({"a": 1, "b": {"b1": 22, "b2": {"b3": "abc"}, "b4": 1}, "c": 31})",
            R"({"a": 2, "b": {"b1": 23, "b2": {"b3": "efg"}, "b4": [1, 2, 3]}, "d": 32})",
            R"({"a": 3, "b": {"b1": 24, "b2": {"b3": "xyz"}, "b4": {"b5": 1}}, "e": [1, 2, 3]})",
            R"({"a": 4, "b": {"b1": 25, "b2": {"b3": "qwe"}, "b4": {"b7": 2}}, "g": {"x": 1}})",
            R"({"a": 5, "b": {"b1": 26, "b2": {"b3": "sdf"}, "b4": 23}})"};

    for (auto& x : json) {
        ASSIGN_OR_ABORT(auto jv, JsonValue::parse(x));
        json_col->append(jv);
    }

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, nullptr);

    EXPECT_EQ(5, writer_opts.meta->children_columns_size());
    EXPECT_TRUE(writer_opts.meta->json_meta().is_flat());
    EXPECT_TRUE(writer_opts.meta->json_meta().has_remain());
    EXPECT_EQ("a", writer_opts.meta->children_columns(0).name());
    EXPECT_EQ("b.b1", writer_opts.meta->children_columns(1).name());
    EXPECT_EQ("b.b2.b3", writer_opts.meta->children_columns(2).name());
    EXPECT_EQ("b.b4", writer_opts.meta->children_columns(3).name());
    EXPECT_EQ("remain", writer_opts.meta->children_columns(4).name());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_FALSE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    for (size_t i = 0; i < json.size(); i++) {
        EXPECT_EQ(json[i], read_col->debug_item(i));
    }
}

TEST_F(FlatJsonColumnRWTest, testMergeMiddleRemainFlatJson3) {
    config::json_flat_null_factor = 0.4;
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());
    std::vector<std::string> json = {
            R"({"a": 1, "b": {"b1": 22, "b2": {"b3": "abc", "c1": {"c2": "a", "ce": 1},"bc": 1}, "b4": 1}})",
            R"({"a": 2, "b": {"b1": 23, "b2": {"b3": "efg", "c1": {"c2": "b", "cd": 2},"bd": 2}, "b4": [1, 2, 3]}})",
            R"({"a": 3, "b": {"b1": 24, "b2": {"b3": "xyz", "c1": {"c2": "c", "cf": 3},"be": 3}, "b4": {"b5": 1}}})",
            R"({"a": 4, "b": {"b1": 25, "b2": {"b3": "qwe", "c1": {"c2": "d", "cg": 4},"bf": 4}, "b4": {"b7": 2}}})",
            R"({"a": 5, "b": {"b1": 26, "b2": {"b3": "sdf", "c1": {"c2": "e", "ch": 5},"bg": 5}, "b4": 23}})"};

    for (auto& x : json) {
        ASSIGN_OR_ABORT(auto jv, JsonValue::parse(x));
        json_col->append(jv);
    }

    ASSIGN_OR_ABORT(auto root_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ASSIGN_OR_ABORT(auto b_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.b", 0));
    ASSIGN_OR_ABORT(auto b2_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.b.b2", 0));

    b_path->children().emplace_back(std::move(b2_path));
    root_path->children().emplace_back(std::move(b_path));

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root_path.get());

    EXPECT_EQ(6, writer_opts.meta->children_columns_size());
    EXPECT_TRUE(writer_opts.meta->json_meta().is_flat());
    EXPECT_TRUE(writer_opts.meta->json_meta().has_remain());
    EXPECT_EQ("a", writer_opts.meta->children_columns(0).name());
    EXPECT_EQ("b.b1", writer_opts.meta->children_columns(1).name());
    EXPECT_EQ("b.b2.b3", writer_opts.meta->children_columns(2).name());
    EXPECT_EQ("b.b2.c1.c2", writer_opts.meta->children_columns(3).name());
    EXPECT_EQ("b.b4", writer_opts.meta->children_columns(4).name());
    EXPECT_EQ("remain", writer_opts.meta->children_columns(5).name());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b2: {"b3": "abc", "bc": 1, "c1": {"c2": "a", "ce": 1}}})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b2: {"b3": "efg", "bd": 2, "c1": {"c2": "b", "cd": 2}}})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b2: {"b3": "xyz", "be": 3, "c1": {"c2": "c", "cf": 3}}})", read_col->debug_item(2));
    EXPECT_EQ(R"({b.b2: {"b3": "qwe", "bf": 4, "c1": {"c2": "d", "cg": 4}}})", read_col->debug_item(3));
    EXPECT_EQ(R"({b.b2: {"b3": "sdf", "bg": 5, "c1": {"c2": "e", "ch": 5}}})", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testDeepFlatJson) {
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());
    std::vector<std::string> json = {
            R"({"a": 1, "b": {"b1": 22, "b2": {"b3": "abc"}, "b4": 1}, "c": 31})",
            R"({"a": 2, "b": {"b1": 23, "b2": {"b3": "efg"}, "b4": [1,2,3]}, "d": 32})",
            R"({"a": 3, "b": {"b1": 24, "b2": {"b3": "xyz"}, "b4": {"b5": 1}}, "e": [1,2,3]})",
            R"({"a": 4, "b": {"b1": 25, "b2": {"b3": "qwe"}, "b4": {"b7": 2}}, "g": {"x": 1}})",
            R"({"a": 5, "b": {"b1": 26, "b2": {"b3": "sdf"}, "b4": 23}})"};

    for (auto& x : json) {
        ASSIGN_OR_ABORT(auto jv, JsonValue::parse(x));
        json_col->append(jv);
    }

    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b4.b5");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b2.b3");

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    EXPECT_EQ(5, writer_opts.meta->children_columns_size());
    EXPECT_TRUE(writer_opts.meta->json_meta().is_flat());
    EXPECT_TRUE(writer_opts.meta->json_meta().has_remain());
    EXPECT_EQ("a", writer_opts.meta->children_columns(0).name());
    EXPECT_EQ("b.b1", writer_opts.meta->children_columns(1).name());
    EXPECT_EQ("b.b2.b3", writer_opts.meta->children_columns(2).name());
    EXPECT_EQ("b.b4", writer_opts.meta->children_columns(3).name());
    EXPECT_EQ("remain", writer_opts.meta->children_columns(4).name());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "abc"})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "efg"})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b4.b5: 1, b.b2.b3: "xyz"})", read_col->debug_item(2));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "qwe"})", read_col->debug_item(3));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "sdf"})", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testHyperFlatJson) {
    config::json_flat_null_factor = 0.4;
    config::json_flat_sparsity_factor = 0.5;

    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());
    std::vector<std::string> json = {
            R"({"a": 1, "gg": "te1", "ff": {"f1": "985"}, "b": {"b1": 22, "b2": {"b3": "abc", "c1": {"c2": "a", "ce": 1},"bc": 1}, "b4": 1}})",
            R"({"a": 2, "gg": "te2", "ff": {"f1": "984"}, "b": {"b1": 23, "b2": {"b3": "efg", "c1": {"c2": "b", "cd": 2},"bd": 2}, "b4": [1, 2, 3]}})",
            R"({"a": 3, "gg": "te3", "ff": {"f1": "983"}, "b": {"b1": 24, "b2": {"b3": "xyz", "c1": {"c2": "c", "cf": 3},"be": 3}, "b4": {"b5": 1}}})",
            R"({"a": 4, "gg": "te4", "ff": 781, "b": {"b1": 25, "b2": {"b3": "qwe", "c1": {"c2": "d", "cg": 4},"bf": 4}, "b4": {"b7": 2}}})",
            R"({"a": 5, "gg": "te5", "ff": 782, "b": {"b1": 26, "b2": {"b3": "sdf", "c1": {"c2": "e", "ch": 5},"bg": 5}, "b4": 23}})"};

    for (auto& x : json) {
        ASSIGN_OR_ABORT(auto jv, JsonValue::parse(x));
        json_col->append(jv);
    }

    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b4.b5");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b2.b3");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "a");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "ff.f1");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "gg.g1");

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    int index = 0;
    EXPECT_EQ(8, writer_opts.meta->children_columns_size());
    EXPECT_TRUE(writer_opts.meta->json_meta().is_flat());
    EXPECT_TRUE(writer_opts.meta->json_meta().has_remain());
    EXPECT_EQ("a", writer_opts.meta->children_columns(index++).name());
    EXPECT_EQ("b.b1", writer_opts.meta->children_columns(index++).name());
    EXPECT_EQ("b.b2.b3", writer_opts.meta->children_columns(index++).name());
    EXPECT_EQ("b.b2.c1.c2", writer_opts.meta->children_columns(index++).name());
    EXPECT_EQ("b.b4", writer_opts.meta->children_columns(index++).name());
    EXPECT_EQ("ff", writer_opts.meta->children_columns(index++).name());
    EXPECT_EQ("gg", writer_opts.meta->children_columns(index++).name());
    EXPECT_EQ("remain", writer_opts.meta->children_columns(index++).name());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "abc", a: 1, ff.f1: "985", gg.g1: NULL})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "efg", a: 2, ff.f1: "984", gg.g1: NULL})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b4.b5: 1, b.b2.b3: "xyz", a: 3, ff.f1: "983", gg.g1: NULL})", read_col->debug_item(2));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "qwe", a: 4, ff.f1: NULL, gg.g1: NULL})", read_col->debug_item(3));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "sdf", a: 5, ff.f1: NULL, gg.g1: NULL})", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testMergeRemainJson) {
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());

    ASSIGN_OR_ABORT(auto jv1, JsonValue::parse(R"({"a": 1, "b": 21, "c": 31})"));
    ASSIGN_OR_ABORT(auto jv2, JsonValue::parse(R"({"a": 2, "b": 22, "d": 32})"));
    ASSIGN_OR_ABORT(auto jv3, JsonValue::parse(R"({"a": 3, "b": 23, "e": [1,2,3]})"));
    ASSIGN_OR_ABORT(auto jv4, JsonValue::parse(R"({"a": 4, "b": 24, "g": {"x": 1}})"));
    ASSIGN_OR_ABORT(auto jv5, JsonValue::parse(R"({"a": 5, "b": 25})"));

    json_col->append(&jv1);
    json_col->append(&jv2);
    json_col->append(&jv3);
    json_col->append(&jv4);
    json_col->append(&jv5);

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = false;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, nullptr);

    EXPECT_EQ(0, writer_opts.meta->children_columns_size());
    EXPECT_FALSE(writer_opts.meta->json_meta().is_flat());
    EXPECT_FALSE(writer_opts.meta->json_meta().has_remain());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_FALSE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({"a": 1, "b": 21, "c": 31})", read_col->debug_item(0));
    EXPECT_EQ(R"({"a": 2, "b": 22, "d": 32})", read_col->debug_item(1));
    EXPECT_EQ(R"({"a": 3, "b": 23, "e": [1, 2, 3]})", read_col->debug_item(2));
    EXPECT_EQ(R"({"a": 4, "b": 24, "g": {"x": 1}})", read_col->debug_item(3));
    EXPECT_EQ(R"({"a": 5, "b": 25})", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testMergeMiddleRemainJson) {
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());

    ASSIGN_OR_ABORT(auto jv1, JsonValue::parse(R"({"a": 1, "b": 21, "c": 31})"));
    ASSIGN_OR_ABORT(auto jv2, JsonValue::parse(R"({"a": 2, "b": 22, "d": 32})"));
    ASSIGN_OR_ABORT(auto jv3, JsonValue::parse(R"({"a": 3, "b": 23, "e": [1,2,3]})"));
    ASSIGN_OR_ABORT(auto jv4, JsonValue::parse(R"({"a": 4, "b": 24, "g": {"x": 1}})"));
    ASSIGN_OR_ABORT(auto jv5, JsonValue::parse(R"({"a": 5, "b": 25})"));

    json_col->append(&jv1);
    json_col->append(&jv2);
    json_col->append(&jv3);
    json_col->append(&jv4);
    json_col->append(&jv5);

    ASSIGN_OR_ABORT(auto root_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ASSIGN_OR_ABORT(auto f1_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.a", 0));
    ASSIGN_OR_ABORT(auto f2_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.c", 0));
    root_path->children().emplace_back(std::move(f1_path));
    root_path->children().emplace_back(std::move(f2_path));

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = false;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root_path.get());

    EXPECT_EQ(0, writer_opts.meta->children_columns_size());
    EXPECT_FALSE(writer_opts.meta->json_meta().is_flat());
    EXPECT_FALSE(writer_opts.meta->json_meta().has_remain());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());

    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ("{a: 1, c: 31}", read_col->debug_item(0));
    EXPECT_EQ("{a: 2, c: NULL}", read_col->debug_item(1));
    EXPECT_EQ("{a: 3, c: NULL}", read_col->debug_item(2));
    EXPECT_EQ("{a: 4, c: NULL}", read_col->debug_item(3));
    EXPECT_EQ("{a: 5, c: NULL}", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testMergeMiddleRemainJson2) {
    config::json_flat_null_factor = 0.4;
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());
    std::vector<std::string> json = {
            R"({"a": 1, "b": {"b1": 22, "b2": {"b3": "abc", "c1": {"c2": "a", "ce": 1},"bc": 1}, "b4": 1}})",
            R"({"a": 2, "b": {"b1": 23, "b2": {"b3": "efg", "c1": {"c2": "b", "cd": 2},"bd": 2}, "b4": [1, 2, 3]}})",
            R"({"a": 3, "b": {"b1": 24, "b2": {"b3": "xyz", "c1": {"c2": "c", "cf": 3},"be": 3}, "b4": {"b5": 1}}})",
            R"({"a": 4, "b": {"b1": 25, "b2": {"b3": "qwe", "c1": {"c2": "d", "cg": 4},"bf": 4}, "b4": {"b7": 2}}})",
            R"({"a": 5, "b": {"b1": 26, "b2": {"b3": "sdf", "c1": {"c2": "e", "ch": 5},"bg": 5}, "b4": 23}})"};

    for (auto& x : json) {
        ASSIGN_OR_ABORT(auto jv, JsonValue::parse(x));
        json_col->append(jv);
    }

    ASSIGN_OR_ABORT(auto root_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ASSIGN_OR_ABORT(auto b_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.b", 0));
    ASSIGN_OR_ABORT(auto b2_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.b.b2", 0));

    b_path->children().emplace_back(std::move(b2_path));
    root_path->children().emplace_back(std::move(b_path));

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = false;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root_path.get());

    EXPECT_EQ(0, writer_opts.meta->children_columns_size());
    EXPECT_FALSE(writer_opts.meta->json_meta().is_flat());
    EXPECT_FALSE(writer_opts.meta->json_meta().has_remain());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b2: {"b3": "abc", "bc": 1, "c1": {"c2": "a", "ce": 1}}})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b2: {"b3": "efg", "bd": 2, "c1": {"c2": "b", "cd": 2}}})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b2: {"b3": "xyz", "be": 3, "c1": {"c2": "c", "cf": 3}}})", read_col->debug_item(2));
    EXPECT_EQ(R"({b.b2: {"b3": "qwe", "bf": 4, "c1": {"c2": "d", "cg": 4}}})", read_col->debug_item(3));
    EXPECT_EQ(R"({b.b2: {"b3": "sdf", "bg": 5, "c1": {"c2": "e", "ch": 5}}})", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testDeepJson) {
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());
    std::vector<std::string> json = {
            R"({"a": 1, "b": {"b1": 22, "b2": {"b3": "abc"}, "b4": 1}, "c": 31})",
            R"({"a": 2, "b": {"b1": 23, "b2": {"b3": "efg"}, "b4": [1,2,3]}, "d": 32})",
            R"({"a": 3, "b": {"b1": 24, "b2": {"b3": "xyz"}, "b4": {"b5": 1}}, "e": [1,2,3]})",
            R"({"a": 4, "b": {"b1": 25, "b2": {"b3": "qwe"}, "b4": {"b7": 2}}, "g": {"x": 1}})",
            R"({"a": 5, "b": {"b1": 26, "b2": {"b3": "sdf"}, "b4": 23}})"};

    for (auto& x : json) {
        ASSIGN_OR_ABORT(auto jv, JsonValue::parse(x));
        json_col->append(jv);
    }

    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b4.b5");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b2.b3");

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = false;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    EXPECT_EQ(0, writer_opts.meta->children_columns_size());
    EXPECT_FALSE(writer_opts.meta->json_meta().is_flat());
    EXPECT_FALSE(writer_opts.meta->json_meta().has_remain());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "abc"})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "efg"})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b4.b5: 1, b.b2.b3: "xyz"})", read_col->debug_item(2));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "qwe"})", read_col->debug_item(3));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "sdf"})", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testHyperJson) {
    config::json_flat_null_factor = 0.4;
    config::json_flat_sparsity_factor = 0.5;

    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());
    std::vector<std::string> json = {
            R"({"a": 1, "gg": "te1", "ff": {"f1": "985"}, "b": {"b1": 22, "b2": {"b3": "abc", "c1": {"c2": "a", "ce": 1},"bc": 1}, "b4": 1}})",
            R"({"a": 2, "gg": "te2", "ff": {"f1": "984"}, "b": {"b1": 23, "b2": {"b3": "efg", "c1": {"c2": "b", "cd": 2},"bd": 2}, "b4": [1, 2, 3]}})",
            R"({"a": 3, "gg": "te3", "ff": {"f1": "983"}, "b": {"b1": 24, "b2": {"b3": "xyz", "c1": {"c2": "c", "cf": 3},"be": 3}, "b4": {"b5": 1}}})",
            R"({"a": 4, "gg": "te4", "ff": 781, "b": {"b1": 25, "b2": {"b3": "qwe", "c1": {"c2": "d", "cg": 4},"bf": 4}, "b4": {"b7": 2}}})",
            R"({"a": 5, "gg": "te5", "ff": 782, "b": {"b1": 26, "b2": {"b3": "sdf", "c1": {"c2": "e", "ch": 5},"bg": 5}, "b4": 23}})"};

    for (auto& x : json) {
        ASSIGN_OR_ABORT(auto jv, JsonValue::parse(x));
        json_col->append(jv);
    }

    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b4.b5");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b2.b3");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "a");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "ff.f1");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "gg.g1");

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = false;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    EXPECT_EQ(0, writer_opts.meta->children_columns_size());
    EXPECT_FALSE(writer_opts.meta->json_meta().is_flat());
    EXPECT_FALSE(writer_opts.meta->json_meta().has_remain());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "abc", a: 1, ff.f1: "985", gg.g1: NULL})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "efg", a: 2, ff.f1: "984", gg.g1: NULL})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b4.b5: 1, b.b2.b3: "xyz", a: 3, ff.f1: "983", gg.g1: NULL})", read_col->debug_item(2));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "qwe", a: 4, ff.f1: NULL, gg.g1: NULL})", read_col->debug_item(3));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "sdf", a: 5, ff.f1: NULL, gg.g1: NULL})", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testHyperNoCastTypeJson) {
    config::json_flat_null_factor = 0.4;
    config::json_flat_sparsity_factor = 0.5;

    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());
    std::vector<std::string> json = {
            R"({"a": 1, "gg": "te1", "ff": {"f1": "985"}, "b": {"b1": 22, "b2": {"b3": "abc", "c1": {"c2": "a", "ce": 1},"bc": 1}, "b4": 1}})",
            R"({"a": 2, "gg": "te2", "ff": {"f1": "984"}, "b": {"b1": 23, "b2": {"b3": "efg", "c1": {"c2": "b", "cd": 2},"bd": 2}, "b4": [1, 2, 3]}})",
            R"({"a": 3, "gg": "te3", "ff": {"f1": "983"}, "b": {"b1": 24, "b2": {"b3": "xyz", "c1": {"c2": "c", "cf": 3},"be": 3}, "b4": {"b5": 1}}})",
            R"({"a": 4, "gg": "te4", "ff": 781, "b": {"b1": 25, "b2": {"b3": "qwe", "c1": {"c2": "d", "cg": 4},"bf": 4}, "b4": {"b7": 2}}})",
            R"({"a": 5, "gg": "te5", "ff": 782, "b": {"b1": 26, "b2": {"b3": "sdf", "c1": {"c2": "e", "ch": 5},"bg": 5}, "b4": 23}})"};

    for (auto& x : json) {
        ASSIGN_OR_ABORT(auto jv, JsonValue::parse(x));
        json_col->append(jv);
    }

    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_BIGINT, "b.b4.b5");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_VARCHAR, "b.b2.b3");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_BIGINT, "a");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "ff.f1");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_VARCHAR, "gg.g1");

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: 'abc', a: 1, ff.f1: "985", gg.g1: NULL})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: 'efg', a: 2, ff.f1: "984", gg.g1: NULL})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b4.b5: 1, b.b2.b3: 'xyz', a: 3, ff.f1: "983", gg.g1: NULL})", read_col->debug_item(2));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: 'qwe', a: 4, ff.f1: NULL, gg.g1: NULL})", read_col->debug_item(3));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: 'sdf', a: 5, ff.f1: NULL, gg.g1: NULL})", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testHyperCastTypeJson) {
    config::json_flat_null_factor = 0.4;
    config::json_flat_sparsity_factor = 0.5;

    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());
    std::vector<std::string> json = {
            R"({"a": 1, "gg": "te1", "ff": {"f1": "985"}, "b": {"b1": 22, "b2": {"b3": "abc", "c1": {"c2": "a", "ce": 1},"bc": 1}, "b4": 1}})",
            R"({"a": 2, "gg": "te2", "ff": {"f1": "984"}, "b": {"b1": 23, "b2": {"b3": "efg", "c1": {"c2": "b", "cd": 2},"bd": 2}, "b4": [1, 2, 3]}})",
            R"({"a": 3, "gg": "te3", "ff": {"f1": "983"}, "b": {"b1": 24, "b2": {"b3": "xyz", "c1": {"c2": "c", "cf": 3},"be": 3}, "b4": {"b5": 1}}})",
            R"({"a": 4, "gg": "te4", "ff": 781, "b": {"b1": 25, "b2": {"b3": "qwe", "c1": {"c2": "d", "cg": 4},"bf": 4}, "b4": {"b7": 2}}})",
            R"({"a": 5, "gg": "te5", "ff": 782, "b": {"b1": 26, "b2": {"b3": "sdf", "c1": {"c2": "e", "ch": 5},"bg": 5}, "b4": 23}})"};

    for (auto& x : json) {
        ASSIGN_OR_ABORT(auto jv, JsonValue::parse(x));
        json_col->append(jv);
    }

    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_DOUBLE, "b.b4.b5");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_BIGINT, "b.b2");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_VARCHAR, "a");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_BIGINT, "ff.f1");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "gg.g1");

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2: NULL, a: '1', ff.f1: 985, gg.g1: NULL})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2: NULL, a: '2', ff.f1: 984, gg.g1: NULL})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b4.b5: 1, b.b2: NULL, a: '3', ff.f1: 983, gg.g1: NULL})", read_col->debug_item(2));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2: NULL, a: '4', ff.f1: NULL, gg.g1: NULL})", read_col->debug_item(3));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2: NULL, a: '5', ff.f1: NULL, gg.g1: NULL})", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testHyperCastTypeJson2) {
    config::json_flat_null_factor = 0.4;
    config::json_flat_sparsity_factor = 0.5;

    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());
    std::vector<std::string> json = {
            R"({"a": 1, "gg": "te1", "ff": {"f1": "985"}, "b": {"b1": 22, "b2": {"b3": "abc", "c1": {"c2": "a", "ce": 1},"bc": 1}, "b4": 1}})",
            R"({"a": 2, "gg": "te2", "ff": {"f1": "984"}, "b": {"b1": 23, "b2": {"b3": "efg", "c1": {"c2": "b", "cd": 2},"bd": 2}, "b4": [1, 2, 3]}})",
            R"({"a": 3, "gg": "te3", "ff": {"f1": "983"}, "b": {"b1": 24, "b2": {"b3": "xyz", "c1": {"c2": "c", "cf": 3},"be": 3}, "b4": {"b5": 1}}})",
            R"({"a": 4, "gg": "te4", "ff": 781, "b": {"b1": 25, "b2": {"b3": "qwe", "c1": {"c2": "d", "cg": 4},"bf": 4}, "b4": {"b7": 2}}})",
            R"({"a": 5, "gg": "te5", "ff": 782, "b": {"b1": 26, "b2": {"b3": "sdf", "c1": {"c2": "e", "ch": 5},"bg": 5}, "b4": 23}})"};

    for (auto& x : json) {
        ASSIGN_OR_ABORT(auto jv, JsonValue::parse(x));
        json_col->append(jv);
    }

    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_DOUBLE, "b.b4.b5");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_VARCHAR, "b.b2");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_VARCHAR, "a");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_BIGINT, "ff.f1");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "gg.g1");

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(
            R"({b.b4.b5: NULL, b.b2: '{"b3": "abc", "bc": 1, "c1": {"c2": "a", "ce": 1}}', a: '1', ff.f1: 985, gg.g1: NULL})",
            read_col->debug_item(0));
    EXPECT_EQ(
            R"({b.b4.b5: NULL, b.b2: '{"b3": "efg", "bd": 2, "c1": {"c2": "b", "cd": 2}}', a: '2', ff.f1: 984, gg.g1: NULL})",
            read_col->debug_item(1));
    EXPECT_EQ(
            R"({b.b4.b5: 1, b.b2: '{"b3": "xyz", "be": 3, "c1": {"c2": "c", "cf": 3}}', a: '3', ff.f1: 983, gg.g1: NULL})",
            read_col->debug_item(2));
    EXPECT_EQ(
            R"({b.b4.b5: NULL, b.b2: '{"b3": "qwe", "bf": 4, "c1": {"c2": "d", "cg": 4}}', a: '4', ff.f1: NULL, gg.g1: NULL})",
            read_col->debug_item(3));
    EXPECT_EQ(
            R"({b.b4.b5: NULL, b.b2: '{"b3": "sdf", "bg": 5, "c1": {"c2": "e", "ch": 5}}', a: '5', ff.f1: NULL, gg.g1: NULL})",
            read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testMergeRemainNullFlatJson) {
    config::json_flat_null_factor = 1;
    config::json_flat_sparsity_factor = 0.4;
    // clang-format off
    std::vector<std::string> jsons = {
        R"({"a": 1, "b": 21, "c": 31})",
        R"({"a": 2, "b": 22, "d": 32})",
        R"({"a": 3, "b": 23, "e": [1,2,3]})",
        R"({"a": 4, "b": 24, "g": {"x": 1}})",
        R"(NULL)"
    };
    // clang-format on
    auto write_col = create_json(jsons, true);
    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, nullptr);

    EXPECT_EQ(4, writer_opts.meta->children_columns_size());
    EXPECT_TRUE(writer_opts.meta->json_meta().is_flat());
    EXPECT_TRUE(writer_opts.meta->json_meta().has_remain());
    EXPECT_EQ("nulls", writer_opts.meta->children_columns(0).name());
    EXPECT_EQ("a", writer_opts.meta->children_columns(1).name());
    EXPECT_EQ("b", writer_opts.meta->children_columns(2).name());
    EXPECT_EQ("remain", writer_opts.meta->children_columns(3).name());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_FALSE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({"a": 1, "b": 21, "c": 31})", read_col->debug_item(0));
    EXPECT_EQ(R"({"a": 2, "b": 22, "d": 32})", read_col->debug_item(1));
    EXPECT_EQ(R"({"a": 3, "b": 23, "e": [1, 2, 3]})", read_col->debug_item(2));
    EXPECT_EQ(R"({"a": 4, "b": 24, "g": {"x": 1}})", read_col->debug_item(3));
    EXPECT_EQ(R"(NULL)", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testMergeRemainNullFlatJson1) {
    config::json_flat_null_factor = 0;
    config::json_flat_sparsity_factor = 0.4;
    // clang-format off
    std::vector<std::string> jsons = {
        R"({"a": 1, "b": 21, "c": 31})",
        R"({"a": 2, "b": 22, "d": 32})",
        R"({"a": 3, "b": 23, "e": [1,2,3]})",
        R"({})",
        R"(NULL)"
    };
    // clang-format on
    auto write_col = create_json(jsons, true);
    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, nullptr);

    EXPECT_EQ(0, writer_opts.meta->children_columns_size());
    EXPECT_FALSE(writer_opts.meta->json_meta().is_flat());
    EXPECT_FALSE(writer_opts.meta->json_meta().has_remain());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_FALSE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({"a": 1, "b": 21, "c": 31})", read_col->debug_item(0));
    EXPECT_EQ(R"({"a": 2, "b": 22, "d": 32})", read_col->debug_item(1));
    EXPECT_EQ(R"({"a": 3, "b": 23, "e": [1, 2, 3]})", read_col->debug_item(2));
    EXPECT_EQ(R"({})", read_col->debug_item(3));
    EXPECT_EQ(R"(NULL)", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testMergeRemainNullFlatJson2) {
    config::json_flat_null_factor = 1;
    config::json_flat_sparsity_factor = 0.4;

    std::vector<std::string> jsons = {
            R"({"a": 1, "b": {"b1": 22, "b2": {"b3": "abc"}, "b4": 1}, "c": 31})",
            R"({"a": 2, "b": {"b1": 23, "b2": {"b3": "efg"}, "b4": [1, 2, 3]}, "d": 32})",
            R"({"a": 3, "b": {"b1": 24, "b2": {"b3": "xyz"}, "b4": {"b5": 1}}, "e": [1, 2, 3]})", R"(NULL)",
            R"({"a": 5, "b": {}})"};
    auto write_col = create_json(jsons, true);
    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, nullptr);

    EXPECT_EQ(6, writer_opts.meta->children_columns_size());
    EXPECT_TRUE(writer_opts.meta->json_meta().is_flat());
    EXPECT_TRUE(writer_opts.meta->json_meta().has_remain());
    EXPECT_EQ("nulls", writer_opts.meta->children_columns(0).name());
    EXPECT_EQ("a", writer_opts.meta->children_columns(1).name());
    EXPECT_EQ("b.b1", writer_opts.meta->children_columns(2).name());
    EXPECT_EQ("b.b2.b3", writer_opts.meta->children_columns(3).name());
    EXPECT_EQ("b.b4", writer_opts.meta->children_columns(4).name());
    EXPECT_EQ("remain", writer_opts.meta->children_columns(5).name());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_FALSE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());

    for (size_t i = 0; i < jsons.size(); i++) {
        EXPECT_EQ(jsons[i], read_col->debug_item(i));
    }
}

TEST_F(FlatJsonColumnRWTest, testMergeMiddleRemainNullFlatJson) {
    config::json_flat_null_factor = 1;
    config::json_flat_sparsity_factor = 0.4;

    // clang-format off
    std::vector<std::string> jsons = {
        R"({"a": 1, "b": 21, "c": 31})",
        R"({"a": 2, "b": 22, "d": 32})",
        R"({"a": 3, "b": 23, "e": [1,2,3]})",
        R"({"a": 4, "b": 24, "g": {"x": 1}})",
        R"({})"
    };
    // clang-format on
    ColumnPtr write_col = create_json(jsons, true);

    ASSIGN_OR_ABORT(auto root_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ASSIGN_OR_ABORT(auto f1_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.a", 0));
    ASSIGN_OR_ABORT(auto f2_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.c", 0));
    root_path->children().emplace_back(std::move(f1_path));
    root_path->children().emplace_back(std::move(f2_path));

    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root_path.get());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());

    EXPECT_EQ(4, writer_opts.meta->children_columns_size());
    EXPECT_TRUE(writer_opts.meta->json_meta().is_flat());
    EXPECT_TRUE(writer_opts.meta->json_meta().has_remain());
    EXPECT_EQ("nulls", writer_opts.meta->children_columns(0).name());
    EXPECT_EQ("a", writer_opts.meta->children_columns(1).name());
    EXPECT_EQ("b", writer_opts.meta->children_columns(2).name());
    EXPECT_EQ("remain", writer_opts.meta->children_columns(3).name());

    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ("{a: 1, c: 31}", read_col->debug_item(0));
    EXPECT_EQ("{a: 2, c: NULL}", read_col->debug_item(1));
    EXPECT_EQ("{a: 3, c: NULL}", read_col->debug_item(2));
    EXPECT_EQ("{a: 4, c: NULL}", read_col->debug_item(3));
    EXPECT_EQ("{a: NULL, c: NULL}", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testMergeMiddleRemainNullFlatJson2) {
    config::json_flat_null_factor = 1;
    config::json_flat_sparsity_factor = 0.4;
    std::vector<std::string> json = {
            R"({"a": 1, "b": {"b1": 22, "b2": {"b3": "abc"}, "b4": 1}, "c": 31})",
            R"({"a": 2, "b": {"b1": 23, "b2": {"b3": "efg"}, "b4": [1, 2, 3]}, "d": 32})",
            R"({"a": 3, "b": {"b1": 24, "b2": {"b3": "xyz"}, "b4": {"b5": 1}}, "e": [1, 2, 3]})", R"(NULL)", R"(NULL)"};
    ColumnPtr write_col = create_json(json, true);

    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, nullptr);

    EXPECT_EQ(6, writer_opts.meta->children_columns_size());
    EXPECT_TRUE(writer_opts.meta->json_meta().is_flat());
    EXPECT_TRUE(writer_opts.meta->json_meta().has_remain());
    EXPECT_EQ("nulls", writer_opts.meta->children_columns(0).name());
    EXPECT_EQ("a", writer_opts.meta->children_columns(1).name());
    EXPECT_EQ("b.b1", writer_opts.meta->children_columns(2).name());
    EXPECT_EQ("b.b2.b3", writer_opts.meta->children_columns(3).name());
    EXPECT_EQ("b.b4", writer_opts.meta->children_columns(4).name());
    EXPECT_EQ("remain", writer_opts.meta->children_columns(5).name());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_FALSE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    for (size_t i = 0; i < json.size(); i++) {
        EXPECT_EQ(json[i], read_col->debug_item(i));
    }
}

TEST_F(FlatJsonColumnRWTest, testMergeMiddleRemainNullFlatJson3) {
    config::json_flat_null_factor = 1;
    config::json_flat_sparsity_factor = 0.4;
    std::vector<std::string> json = {
            R"({"a": 1, "b": {"b1": 22, "b2": {"b3": "abc", "c1": {"c2": "a", "ce": 1},"bc": 1}, "b4": 1}})",
            R"({"a": 2, "b": {"b1": 23, "b2": {"b3": "efg", "c1": {"c2": "b", "cd": 2},"bd": 2}, "b4": [1, 2, 3]}})",
            R"({"a": 3, "b": {"b1": 24, "b2": {"b3": "xyz", "c1": {"c2": "c", "cf": 3},"be": 3}, "b4": {"b5": 1}}})",
            R"({"a": 4, "b": {"b1": 25, "b2": {"b3": "qwe", "c1": {"c2": "d", "cg": 4},"bf": 4}, "b4": {"b7": 2}}})",
            R"({"a": 5, "b": {"b1": 26, "b2": {"b3": "sdf", "c1": {"c2": "e", "ch": 5},"bg": 5}, "b4": 23}})"};
    ColumnPtr write_col = create_json(json, true);

    ASSIGN_OR_ABORT(auto root_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ASSIGN_OR_ABORT(auto b_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.b", 0));
    ASSIGN_OR_ABORT(auto b2_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.b.b2", 0));

    b_path->children().emplace_back(std::move(b2_path));
    root_path->children().emplace_back(std::move(b_path));

    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root_path.get());

    EXPECT_EQ(7, writer_opts.meta->children_columns_size());
    EXPECT_TRUE(writer_opts.meta->json_meta().is_flat());
    EXPECT_TRUE(writer_opts.meta->json_meta().has_remain());
    EXPECT_EQ("nulls", writer_opts.meta->children_columns(0).name());
    EXPECT_EQ("a", writer_opts.meta->children_columns(1).name());
    EXPECT_EQ("b.b1", writer_opts.meta->children_columns(2).name());
    EXPECT_EQ("b.b2.b3", writer_opts.meta->children_columns(3).name());
    EXPECT_EQ("b.b2.c1.c2", writer_opts.meta->children_columns(4).name());
    EXPECT_EQ("b.b4", writer_opts.meta->children_columns(5).name());
    EXPECT_EQ("remain", writer_opts.meta->children_columns(6).name());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b2: {"b3": "abc", "bc": 1, "c1": {"c2": "a", "ce": 1}}})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b2: {"b3": "efg", "bd": 2, "c1": {"c2": "b", "cd": 2}}})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b2: {"b3": "xyz", "be": 3, "c1": {"c2": "c", "cf": 3}}})", read_col->debug_item(2));
    EXPECT_EQ(R"({b.b2: {"b3": "qwe", "bf": 4, "c1": {"c2": "d", "cg": 4}}})", read_col->debug_item(3));
    EXPECT_EQ(R"({b.b2: {"b3": "sdf", "bg": 5, "c1": {"c2": "e", "ch": 5}}})", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testDeepNullFlatJson) {
    config::json_flat_null_factor = 1;
    config::json_flat_sparsity_factor = 0.4;

    std::vector<std::string> json = {R"({"a": 1, "b": {"b1": 22, "b2": {"b3": "abc"}, "b4": 1}, "c": 31})",
                                     R"({"a": 2, "b": {"b1": 23, "b2": {"b3": "efg"}, "b4": [1,2,3]}, "d": 32})",
                                     R"({"a": 3, "b": {"b1": 24, "b2": {"b3": "xyz"}, "b4": {"b5": 1}}, "e": [1,2,3]})",
                                     R"(NULL)", R"({"a": 5, "b": {"b1": 26, "b2": {}, "b4": 23}})"};

    ColumnPtr write_col = create_json(json, true);
    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b4.b5");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b2.b3");

    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    EXPECT_EQ(6, writer_opts.meta->children_columns_size());
    EXPECT_TRUE(writer_opts.meta->json_meta().is_flat());
    EXPECT_TRUE(writer_opts.meta->json_meta().has_remain());
    EXPECT_EQ("nulls", writer_opts.meta->children_columns(0).name());
    EXPECT_EQ("a", writer_opts.meta->children_columns(1).name());
    EXPECT_EQ("b.b1", writer_opts.meta->children_columns(2).name());
    EXPECT_EQ("b.b2.b3", writer_opts.meta->children_columns(3).name());
    EXPECT_EQ("b.b4", writer_opts.meta->children_columns(4).name());
    EXPECT_EQ("remain", writer_opts.meta->children_columns(5).name());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "abc"})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "efg"})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b4.b5: 1, b.b2.b3: "xyz"})", read_col->debug_item(2));
    EXPECT_EQ(R"(NULL)", read_col->debug_item(3));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: NULL})", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testHyperNullFlatJson) {
    config::json_flat_null_factor = 1;
    config::json_flat_sparsity_factor = 0.4;

    std::vector<std::string> json = {
            R"({"a": 1, "gg": "te1", "ff": {"f1": "985"}, "b": {"b1": 22, "b2": {"b3": "abc", "c1": {"c2": "a", "ce": 1},"bc": 1}, "b4": 1}})",
            R"({"a": 2, "gg": "te2", "ff": {"f1": "984"}, "b": {"b1": 23, "b2": {"b3": "efg", "c1": {"c2": "b", "cd": 2},"bd": 2}, "b4": [1, 2, 3]}})",
            R"({"a": 3, "gg": "te3", "ff": {"f1": "983"}, "b": {"b1": 24, "b2": {"b3": "xyz", "c1": {"c2": "c", "cf": 3},"be": 3}, "b4": {"b5": 1}}})",
            R"({"a": 4, "gg": "te4", "ff": 781, "b": {"b1": 25, "b2": {"b3": "qwe", "c1": {"c2": "d", "cg": 4},"bf": 4}, "b4": {"b7": 2}}})",
            R"(NULL)"};
    ColumnPtr write_col = create_json(json, true);

    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_DOUBLE, "b.b4.b5");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_VARCHAR, "b.b2");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_VARCHAR, "a");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_BIGINT, "ff.f1");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "gg.g1");

    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    EXPECT_EQ(9, writer_opts.meta->children_columns_size());
    EXPECT_TRUE(writer_opts.meta->json_meta().is_flat());
    EXPECT_TRUE(writer_opts.meta->json_meta().has_remain());
    int index = 0;
    EXPECT_EQ("nulls", writer_opts.meta->children_columns(index++).name());
    EXPECT_EQ("a", writer_opts.meta->children_columns(index++).name());
    EXPECT_EQ("b.b1", writer_opts.meta->children_columns(index++).name());
    EXPECT_EQ("b.b2.b3", writer_opts.meta->children_columns(index++).name());
    EXPECT_EQ("b.b2.c1.c2", writer_opts.meta->children_columns(index++).name());
    EXPECT_EQ("b.b4", writer_opts.meta->children_columns(index++).name());
    EXPECT_EQ("ff", writer_opts.meta->children_columns(index++).name());
    EXPECT_EQ("gg", writer_opts.meta->children_columns(index++).name());
    EXPECT_EQ("remain", writer_opts.meta->children_columns(index++).name());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(
            R"({b.b4.b5: NULL, b.b2: '{"b3": "abc", "bc": 1, "c1": {"c2": "a", "ce": 1}}', a: '1', ff.f1: 985, gg.g1: NULL})",
            read_col->debug_item(0));
    EXPECT_EQ(
            R"({b.b4.b5: NULL, b.b2: '{"b3": "efg", "bd": 2, "c1": {"c2": "b", "cd": 2}}', a: '2', ff.f1: 984, gg.g1: NULL})",
            read_col->debug_item(1));
    EXPECT_EQ(
            R"({b.b4.b5: 1, b.b2: '{"b3": "xyz", "be": 3, "c1": {"c2": "c", "cf": 3}}', a: '3', ff.f1: 983, gg.g1: NULL})",
            read_col->debug_item(2));
    EXPECT_EQ(
            R"({b.b4.b5: NULL, b.b2: '{"b3": "qwe", "bf": 4, "c1": {"c2": "d", "cg": 4}}', a: '4', ff.f1: NULL, gg.g1: NULL})",
            read_col->debug_item(3));
    EXPECT_EQ(R"(NULL)", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testMergeRemainNullJson) {
    config::json_flat_null_factor = 1;
    config::json_flat_sparsity_factor = 0.4;

    // clang-format off
    std::vector<std::string> jsons = {
            R"({"a": 1, "b": 21, "c": 31})",
            R"({"a": 2, "b": 22, "d": 32})",
            R"({"a": 3, "b": 23, "e": [1,2,3]})",
            R"({})",
            R"(NULL)"
    };
    // clang-format on
    ColumnPtr write_col = create_json(jsons, true);

    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = false;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, nullptr);

    EXPECT_EQ(0, writer_opts.meta->children_columns_size());
    EXPECT_FALSE(writer_opts.meta->json_meta().is_flat());
    EXPECT_FALSE(writer_opts.meta->json_meta().has_remain());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_FALSE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({"a": 1, "b": 21, "c": 31})", read_col->debug_item(0));
    EXPECT_EQ(R"({"a": 2, "b": 22, "d": 32})", read_col->debug_item(1));
    EXPECT_EQ(R"({"a": 3, "b": 23, "e": [1, 2, 3]})", read_col->debug_item(2));
    EXPECT_EQ(R"({})", read_col->debug_item(3));
    EXPECT_EQ(R"(NULL)", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testMergeMiddleRemainNullJson) {
    config::json_flat_null_factor = 1;
    config::json_flat_sparsity_factor = 0.4;

    // clang-format off
    std::vector<std::string> jsons = {
            R"({"a": 1, "b": 21, "c": 31})",
            R"({"a": 2, "b": 22, "d": 32})",
            R"({"a": 3, "b": 23, "e": [1,2,3]})",
            R"({})",
            R"(NULL)"
    };
    // clang-format on
    ColumnPtr write_col = create_json(jsons, true);

    ASSIGN_OR_ABORT(auto root_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ASSIGN_OR_ABORT(auto f1_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.a", 0));
    ASSIGN_OR_ABORT(auto f2_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.c", 0));
    root_path->children().emplace_back(std::move(f1_path));
    root_path->children().emplace_back(std::move(f2_path));

    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = false;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root_path.get());

    EXPECT_EQ(0, writer_opts.meta->children_columns_size());
    EXPECT_FALSE(writer_opts.meta->json_meta().is_flat());
    EXPECT_FALSE(writer_opts.meta->json_meta().has_remain());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());

    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ("{a: 1, c: 31}", read_col->debug_item(0));
    EXPECT_EQ("{a: 2, c: NULL}", read_col->debug_item(1));
    EXPECT_EQ("{a: 3, c: NULL}", read_col->debug_item(2));
    EXPECT_EQ("{a: NULL, c: NULL}", read_col->debug_item(3));
    EXPECT_EQ("NULL", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testMergeMiddleRemainNullJson2) {
    config::json_flat_null_factor = 1;
    config::json_flat_sparsity_factor = 0.4;

    std::vector<std::string> json = {
            R"({"a": 1, "b": {"b1": 22, "b2": {"b3": "abc", "c1": {"c2": "a", "ce": 1},"bc": 1}, "b4": 1}})",
            R"({"a": 2, "b": {"b1": 23, "b2": {"b3": "efg", "c1": {"c2": "b", "cd": 2},"bd": 2}, "b4": [1, 2, 3]}})",
            R"({"a": 3, "b": {"b1": 24, "b2": {"b3": "xyz", "c1": {"c2": "c", "cf": 3},"be": 3}, "b4": {"b5": 1}}})",
            R"({})", R"(NULL)"};

    ColumnPtr write_col = create_json(json, true);

    ASSIGN_OR_ABORT(auto root_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ASSIGN_OR_ABORT(auto b_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.b", 0));
    ASSIGN_OR_ABORT(auto b2_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.b.b2", 0));

    b_path->children().emplace_back(std::move(b2_path));
    root_path->children().emplace_back(std::move(b_path));

    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = false;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root_path.get());

    EXPECT_EQ(0, writer_opts.meta->children_columns_size());
    EXPECT_FALSE(writer_opts.meta->json_meta().is_flat());
    EXPECT_FALSE(writer_opts.meta->json_meta().has_remain());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b2: {"b3": "abc", "bc": 1, "c1": {"c2": "a", "ce": 1}}})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b2: {"b3": "efg", "bd": 2, "c1": {"c2": "b", "cd": 2}}})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b2: {"b3": "xyz", "be": 3, "c1": {"c2": "c", "cf": 3}}})", read_col->debug_item(2));
    EXPECT_EQ(R"({b.b2: NULL})", read_col->debug_item(3));
    EXPECT_EQ(R"(NULL)", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testDeepNullJson) {
    config::json_flat_null_factor = 1;
    config::json_flat_sparsity_factor = 0.4;

    std::vector<std::string> json = {R"({"a": 1, "b": {"b1": 22, "b2": {"b3": "abc"}, "b4": 1}, "c": 31})",
                                     R"({"a": 2, "b": {"b1": 23, "b2": {"b3": "efg"}, "b4": [1,2,3]}, "d": 32})",
                                     R"({"a": 3, "b": {"b1": 24, "b2": {"b3": "xyz"}, "b4": {"b5": 1}}, "e": [1,2,3]})",
                                     R"({"a": 4, "b": {}, "g": {"x": 1}})", R"(NULL)"};

    ColumnPtr write_col = create_json(json, true);

    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b4.b5");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b2.b3");

    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = false;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    EXPECT_EQ(0, writer_opts.meta->children_columns_size());
    EXPECT_FALSE(writer_opts.meta->json_meta().is_flat());
    EXPECT_FALSE(writer_opts.meta->json_meta().has_remain());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "abc"})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "efg"})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b4.b5: 1, b.b2.b3: "xyz"})", read_col->debug_item(2));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: NULL})", read_col->debug_item(3));
    EXPECT_EQ(R"(NULL)", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testHyperNullJson) {
    config::json_flat_null_factor = 1;
    config::json_flat_sparsity_factor = 0.4;

    std::vector<std::string> json = {
            R"({"a": 1, "gg": "te1", "ff": {"f1": "985"}, "b": {"b1": 22, "b2": {"b3": "abc", "c1": {"c2": "a", "ce": 1},"bc": 1}, "b4": 1}})",
            R"({"a": 2, "gg": "te2", "ff": {"f1": "984"}, "b": {"b1": 23, "b2": {"b3": "efg", "c1": {"c2": "b", "cd": 2},"bd": 2}, "b4": [1, 2, 3]}})",
            R"({"a": 3, "gg": "te3", "ff": {"f1": "983"}, "b": {"b1": 24, "b2": {"b3": "xyz", "c1": {"c2": "c", "cf": 3},"be": 3}, "b4": {"b5": 1}}})",
            R"({"a": 4, "gg": "te4", "ff": 781, "b": {"b1": 25, "b2": {"b3": "qwe", "c1": {"c2": "d", "cg": 4},"bf": 4}, "b4": {"b7": 2}}})",
            R"(NULL)"};

    auto write_col = create_json(json, true);

    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b4.b5");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b2.b3");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "a");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "ff.f1");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "gg.g1");

    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = false;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    EXPECT_EQ(0, writer_opts.meta->children_columns_size());
    EXPECT_FALSE(writer_opts.meta->json_meta().is_flat());
    EXPECT_FALSE(writer_opts.meta->json_meta().has_remain());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "abc", a: 1, ff.f1: "985", gg.g1: NULL})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "efg", a: 2, ff.f1: "984", gg.g1: NULL})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b4.b5: 1, b.b2.b3: "xyz", a: 3, ff.f1: "983", gg.g1: NULL})", read_col->debug_item(2));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "qwe", a: 4, ff.f1: NULL, gg.g1: NULL})", read_col->debug_item(3));
    EXPECT_EQ(R"(NULL)", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testHyperNullJson2) {
    config::json_flat_null_factor = 0;
    config::json_flat_sparsity_factor = 0.4;

    std::vector<std::string> json = {
            R"({"a": 1, "gg": "te1", "ff": {"f1": "985"}, "b": {"b1": 22, "b2": {"b3": "abc", "c1": {"c2": "a", "ce": 1},"bc": 1}, "b4": 1}})",
            R"({"a": 2, "gg": "te2", "ff": {"f1": "984"}, "b": {"b1": 23, "b2": {"b3": "efg", "c1": {"c2": "b", "cd": 2},"bd": 2}, "b4": [1, 2, 3]}})",
            R"({"a": 3, "gg": "te3", "ff": {"f1": "983"}, "b": {"b1": 24, "b2": {"b3": "xyz", "c1": {"c2": "c", "cf": 3},"be": 3}, "b4": {"b5": 1}}})",
            R"({"a": 4, "gg": "te4", "ff": 781, "b": {"b1": 25, "b2": {"b3": "qwe", "c1": {"c2": "d", "cg": 4},"bf": 4}, "b4": {"b7": 2}}})",
            R"(NULL)"};

    auto write_col = create_json(json, true);

    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b4.b5");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "b.b2.b3");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "a");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "ff.f1");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "gg.g1");

    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    EXPECT_EQ(0, writer_opts.meta->children_columns_size());
    EXPECT_FALSE(writer_opts.meta->json_meta().is_flat());
    EXPECT_FALSE(writer_opts.meta->json_meta().has_remain());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "abc", a: 1, ff.f1: "985", gg.g1: NULL})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "efg", a: 2, ff.f1: "984", gg.g1: NULL})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b4.b5: 1, b.b2.b3: "xyz", a: 3, ff.f1: "983", gg.g1: NULL})", read_col->debug_item(2));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: "qwe", a: 4, ff.f1: NULL, gg.g1: NULL})", read_col->debug_item(3));
    EXPECT_EQ(R"(NULL)", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testHyperNoCastTypeNullJson) {
    config::json_flat_null_factor = 1;
    config::json_flat_sparsity_factor = 0.4;

    std::vector<std::string> json = {
            R"({"a": 1, "gg": "te1", "ff": {"f1": "985"}, "b": {"b1": 22, "b2": {"b3": "abc", "c1": {"c2": "a", "ce": 1},"bc": 1}, "b4": 1}})",
            R"({"a": 2, "gg": "te2", "ff": {"f1": "984"}, "b": {"b1": 23, "b2": {"b3": "efg", "c1": {"c2": "b", "cd": 2},"bd": 2}, "b4": [1, 2, 3]}})",
            R"({"a": 3, "gg": "te3", "ff": {"f1": "983"}, "b": {"b1": 24, "b2": {"b3": "xyz", "c1": {"c2": "c", "cf": 3},"be": 3}, "b4": {"b5": 1}}})",
            R"({"a": 4, "gg": "te4", "ff": 781, "b": {"b1": 25, "b2": {}, "b4": {"b7": 2}}})", R"(NULL)"};

    ColumnPtr write_col = create_json(json, true);

    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_BIGINT, "b.b4.b5");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_VARCHAR, "b.b2.b3");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_BIGINT, "a");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "ff.f1");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_VARCHAR, "gg.g1");

    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: 'abc', a: 1, ff.f1: "985", gg.g1: NULL})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: 'efg', a: 2, ff.f1: "984", gg.g1: NULL})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b4.b5: 1, b.b2.b3: 'xyz', a: 3, ff.f1: "983", gg.g1: NULL})", read_col->debug_item(2));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2.b3: NULL, a: 4, ff.f1: NULL, gg.g1: NULL})", read_col->debug_item(3));
    EXPECT_EQ(R"(NULL)", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testHyperCastTypeNullJson) {
    config::json_flat_null_factor = 1;
    config::json_flat_sparsity_factor = 0.4;

    std::vector<std::string> json = {
            R"({"a": 1, "gg": "te1", "ff": {"f1": "985"}, "b": {"b1": 22, "b2": {"b3": "abc", "c1": {"c2": "a", "ce": 1},"bc": 1}, "b4": 1}})",
            R"({"a": 2, "gg": "te2", "ff": {"f1": "984"}, "b": {"b1": 23, "b2": {"b3": "efg", "c1": {"c2": "b", "cd": 2},"bd": 2}, "b4": [1, 2, 3]}})",
            R"({"a": 3, "gg": "te3", "ff": {"f1": "983"}, "b": {"b1": 24, "b2": {"b3": "xyz", "c1": {"c2": "c", "cf": 3},"be": 3}, "b4": {"b5": 1}}})",
            R"({"a": 4, "gg": "te4", "ff": 781, "b": {}})", R"(NULL)"};
    ColumnPtr write_col = create_json(json, true);

    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_DOUBLE, "b.b4.b5");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_BIGINT, "b.b2");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_VARCHAR, "a");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_BIGINT, "ff.f1");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "gg.g1");

    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2: NULL, a: '1', ff.f1: 985, gg.g1: NULL})", read_col->debug_item(0));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2: NULL, a: '2', ff.f1: 984, gg.g1: NULL})", read_col->debug_item(1));
    EXPECT_EQ(R"({b.b4.b5: 1, b.b2: NULL, a: '3', ff.f1: 983, gg.g1: NULL})", read_col->debug_item(2));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2: NULL, a: '4', ff.f1: NULL, gg.g1: NULL})", read_col->debug_item(3));
    EXPECT_EQ(R"(NULL)", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testHyperCastTypeNullJson2) {
    config::json_flat_null_factor = 1;
    config::json_flat_sparsity_factor = 0.4;

    std::vector<std::string> json = {
            R"({"a": 1, "gg": "te1", "ff": {"f1": "985"}, "b": {"b1": 22, "b2": {"b3": "abc", "c1": {"c2": "a", "ce": 1},"bc": 1}, "b4": 1}})",
            R"({"a": 2, "gg": "te2", "ff": {"f1": "984"}, "b": {"b1": 23, "b2": {"b3": "efg", "c1": {"c2": "b", "cd": 2},"bd": 2}, "b4": [1, 2, 3]}})",
            R"({"a": 3, "gg": "te3", "ff": {"f1": "983"}, "b": {"b1": 24, "b2": {"b3": "xyz", "c1": {"c2": "c", "cf": 3},"be": 3}, "b4": {"b5": 1}}})",
            R"({"a": 4, "gg": "te4", "ff": 781, "b": {}})", R"(NULL)"};
    ColumnPtr write_col = create_json(json, true);

    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_DOUBLE, "b.b4.b5");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_VARCHAR, "b.b2");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_VARCHAR, "a");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_BIGINT, "ff.f1");
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "gg.g1");

    ColumnPtr read_col = write_col->clone_empty();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    auto* read_json = down_cast<JsonColumn*>(down_cast<NullableColumn*>(read_col.get())->data_column().get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(5, read_col->size());
    EXPECT_EQ(
            R"({b.b4.b5: NULL, b.b2: '{"b3": "abc", "bc": 1, "c1": {"c2": "a", "ce": 1}}', a: '1', ff.f1: 985, gg.g1: NULL})",
            read_col->debug_item(0));
    EXPECT_EQ(
            R"({b.b4.b5: NULL, b.b2: '{"b3": "efg", "bd": 2, "c1": {"c2": "b", "cd": 2}}', a: '2', ff.f1: 984, gg.g1: NULL})",
            read_col->debug_item(1));
    EXPECT_EQ(
            R"({b.b4.b5: 1, b.b2: '{"b3": "xyz", "be": 3, "c1": {"c2": "c", "cf": 3}}', a: '3', ff.f1: 983, gg.g1: NULL})",
            read_col->debug_item(2));
    EXPECT_EQ(R"({b.b4.b5: NULL, b.b2: NULL, a: '4', ff.f1: NULL, gg.g1: NULL})", read_col->debug_item(3));
    EXPECT_EQ(R"(NULL)", read_col->debug_item(4));
}

TEST_F(FlatJsonColumnRWTest, testHyperDeepFlatternJson) {
    config::json_flat_null_factor = 0.4;
    config::json_flat_sparsity_factor = 0.5;

    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());
    std::vector<std::string> json = {R"({"a": 1, "gg": "te1", "ff": {"f1": [{"e2": 1, "e3": 2}, 2, 3]}})",
                                     R"({"a": 2, "gg": "te2", "ff": 780})", R"({"a": 3, "gg": "te3", "ff": 781})",
                                     R"({"a": 5, "gg": "te5", "ff": 782})"};

    for (auto& x : json) {
        ASSIGN_OR_ABORT(auto jv, JsonValue::parse(x));
        json_col->append(jv);
    }

    ASSIGN_OR_ABORT(auto root, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ColumnAccessPath::insert_json_path(root.get(), LogicalType::TYPE_JSON, "ff.f1");

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;
    writer_opts.need_flat = true;
    test_json(writer_opts, "/test_flat_json_rw2.data", write_col, read_col, root.get());

    auto* read_json = down_cast<JsonColumn*>(read_col.get());
    EXPECT_TRUE(read_json->is_flat_json());
    EXPECT_EQ(4, read_col->size());
    EXPECT_EQ(R"({ff.f1: [{"e2": 1, "e3": 2}, 2, 3]})", read_col->debug_item(0));
    EXPECT_EQ(R"({ff.f1: NULL})", read_col->debug_item(3));
}

TEST_F(FlatJsonColumnRWTest, testGetIORangeVec) {
    ColumnPtr write_col = JsonColumn::create();
    auto* json_col = down_cast<JsonColumn*>(write_col.get());

    ASSIGN_OR_ABORT(auto jv1, JsonValue::parse("{\"a\": 1, \"b\": 21}"));
    ASSIGN_OR_ABORT(auto jv2, JsonValue::parse("{\"a\": 2, \"b\": 22}"));
    ASSIGN_OR_ABORT(auto jv3, JsonValue::parse("{\"a\": 3, \"b\": 23}"));
    ASSIGN_OR_ABORT(auto jv4, JsonValue::parse("{\"a\": 4, \"b\": 24}"));
    ASSIGN_OR_ABORT(auto jv5, JsonValue::parse("{\"a\": 5, \"b\": 25}"));

    json_col->append(&jv1);
    json_col->append(&jv2);
    json_col->append(&jv3);
    json_col->append(&jv4);
    json_col->append(&jv5);

    ColumnPtr read_col = JsonColumn::create();
    ColumnWriterOptions writer_opts;

    auto fs = std::make_shared<MemoryFileSystem>();
    ASSERT_TRUE(fs->create_dir(TEST_DIR).ok());

    TabletColumn json_tablet_column = create_with_default_value<TYPE_JSON>("");
    TypeInfoPtr type_info = get_type_info(json_tablet_column);

    const std::string fname = TEST_DIR + "/test_flat_json_rw1.data";
    auto segment = create_dummy_segment(fs, fname);

    // write data
    {
        ASSIGN_OR_ABORT(auto wfile, fs->new_writable_file(fname));

        writer_opts.meta = _meta.get();
        writer_opts.meta->set_column_id(0);
        writer_opts.meta->set_unique_id(0);
        writer_opts.meta->set_type(TYPE_JSON);
        writer_opts.meta->set_length(0);
        writer_opts.meta->set_encoding(DEFAULT_ENCODING);
        writer_opts.meta->set_compression(starrocks::LZ4_FRAME);
        writer_opts.meta->set_is_nullable(write_col->is_nullable());
        writer_opts.need_zone_map = false;

        ASSIGN_OR_ABORT(auto writer, ColumnWriter::create(writer_opts, &json_tablet_column, wfile.get()));
        ASSERT_OK(writer->init());

        ASSERT_TRUE(writer->append(*write_col).ok());

        ASSERT_TRUE(writer->finish().ok());
        ASSERT_TRUE(writer->write_data().ok());
        ASSERT_TRUE(writer->write_ordinal_index().ok());

        // close the file
        ASSERT_TRUE(wfile->close().ok());
    }

    auto res = ColumnReader::create(_meta.get(), segment.get(), nullptr);
    ASSERT_TRUE(res.ok());
    auto reader = std::move(res).value();

    ASSIGN_OR_ABORT(auto iter, reader->new_iterator(nullptr));
    ASSIGN_OR_ABORT(auto read_file, fs->new_random_access_file(fname));

    ColumnIteratorOptions iter_opts;
    OlapReaderStatistics stats;
    iter_opts.stats = &stats;
    iter_opts.read_file = read_file.get();
    ASSERT_TRUE(iter->init(iter_opts).ok());

    SparseRange<> range;
    range.add(Range<>(0, write_col->size()));
    auto status_or = iter->get_io_range_vec(range, read_col.get());
    ASSERT_TRUE(status_or.ok());
    ASSERT_EQ((*status_or).size(), 1);
}

// A damaged flat JSON segment can hold a sub-column, or the null stream, with fewer values than the segment has rows.
// Path reads (JsonFlatColumnIterator) and whole JSON reads (JsonMergeIterator) must fail every read that reaches the
// missing row with Corruption, and still read the rows before it. The damaged column is made of two flat JSON columns
// written to one file, with N and N - 1 rows: one sub-column of the first is replaced by the same one of the second.
TEST_F(FlatJsonColumnRWTest, testReadShortSubColumn) {
    constexpr rowid_t kRows = 10;
    std::vector<std::string> jsons;
    for (rowid_t i = 0; i < kRows; i++) {
        jsons.emplace_back("{\"a\": " + std::to_string(i) + ", \"b\": \"str_" + std::to_string(i) + "\"}");
    }
    const std::vector<std::string> short_jsons(jsons.begin(), jsons.end() - 1);

    auto fs = std::make_shared<MemoryFileSystem>();
    ASSERT_TRUE(fs->create_dir(TEST_DIR).ok());
    const std::string fname = TEST_DIR + "/test_flat_json_short_sub_column.data";
    TabletColumn json_tablet_column = create_with_default_value<TYPE_JSON>("");

    ColumnMetaPB full_meta;
    ColumnMetaPB short_meta;
    {
        ASSIGN_OR_ABORT(auto wfile, fs->new_writable_file(fname));
        auto write = [&](ColumnMetaPB* meta, const std::vector<std::string>& rows) {
            ColumnWriterOptions writer_opts;
            writer_opts.meta = meta;
            writer_opts.meta->set_column_id(0);
            writer_opts.meta->set_unique_id(0);
            writer_opts.meta->set_type(TYPE_JSON);
            writer_opts.meta->set_length(0);
            writer_opts.meta->set_encoding(DEFAULT_ENCODING);
            writer_opts.meta->set_compression(starrocks::LZ4_FRAME);
            writer_opts.meta->set_is_nullable(true);
            writer_opts.need_zone_map = false;
            writer_opts.need_flat = true;

            ASSIGN_OR_ABORT(auto writer, ColumnWriter::create(writer_opts, &json_tablet_column, wfile.get()));
            ASSERT_OK(writer->init());
            auto column = create_json(rows, true);
            ASSERT_OK(writer->append(*column));
            ASSERT_OK(writer->finish());
            ASSERT_OK(writer->write_data());
            ASSERT_OK(writer->write_ordinal_index());
        };
        ASSERT_NO_FATAL_FAILURE(write(&full_meta, jsons));
        ASSERT_NO_FATAL_FAILURE(write(&short_meta, short_jsons));
        ASSERT_OK(wfile->close());
    }
    ASSERT_TRUE(full_meta.json_meta().is_flat());
    ASSERT_EQ(full_meta.children_columns_size(), short_meta.children_columns_size());

    auto segment = create_dummy_segment(fs, fname);
    segment->set_num_rows(kRows);
    ASSIGN_OR_ABORT(auto read_file, fs->new_random_access_file(fname));

    ASSIGN_OR_ABORT(auto root_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root", 0));
    ASSIGN_OR_ABORT(auto a_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.a", 0));
    ASSIGN_OR_ABORT(auto b_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.b", 0));
    root_path->children().emplace_back(std::move(a_path));
    root_path->children().emplace_back(std::move(b_path));

    // A short column read first lowers the rows of every sequential read after it, so the columns agree with each
    // other: only the rows asked for show that one is short.
    for (const std::string short_child : {"nulls", "a", "b"}) {
        ColumnMetaPB meta = full_meta;
        int index = -1;
        for (int i = 0; i < meta.children_columns_size(); i++) {
            if (meta.children_columns(i).name() == short_child) {
                index = i;
            }
        }
        ASSERT_GE(index, 0) << short_child;
        ASSERT_EQ(short_child, short_meta.children_columns(index).name());
        *meta.mutable_children_columns(index) = short_meta.children_columns(index);
        ASSIGN_OR_ABORT(auto reader, ColumnReader::create(&meta, segment.get(), nullptr));

        for (ColumnAccessPath* path : {static_cast<ColumnAccessPath*>(nullptr), root_path.get()}) {
            const std::string name = short_child + (path == nullptr ? " short, whole json" : " short, path");
            OlapReaderStatistics stats;
            ColumnIteratorOptions iter_opts;
            iter_opts.stats = &stats;
            iter_opts.read_file = read_file.get();
            auto new_iter = [&]() {
                ASSIGN_OR_ABORT(auto iter, reader->new_iterator(path));
                EXPECT_OK(iter->init(iter_opts));
                return iter;
            };
            auto new_dst = []() -> ColumnPtr {
                return NullableColumn::create(JsonColumn::create(), NullColumn::create());
            };

            // the rows before the missing one are still readable
            {
                auto iter = new_iter();
                ASSERT_OK(iter->seek_to_ordinal(0));
                auto dst = new_dst();
                ASSERT_OK(iter->next_batch(SparseRange<>(0, kRows - 1), dst.get())) << name;
                ASSERT_EQ(kRows - 1, dst->size()) << name;
            }
            // a sequential read of every row
            {
                auto iter = new_iter();
                ASSERT_OK(iter->seek_to_first());
                auto dst = new_dst();
                size_t n = kRows;
                auto st = iter->next_batch(&n, dst.get());
                ASSERT_TRUE(st.is_corruption()) << name << ": " << st;
            }
            // a range read of every row: the short column stops at its last value without an error
            {
                auto iter = new_iter();
                ASSERT_OK(iter->seek_to_ordinal(0));
                auto dst = new_dst();
                auto st = iter->next_batch(SparseRange<>(0, kRows), dst.get());
                ASSERT_TRUE(st.is_corruption()) << name << ": " << st;
            }
            // a fetch of the missing row
            {
                auto iter = new_iter();
                auto dst = new_dst();
                rowid_t rowids[] = {kRows - 1};
                auto st = iter->fetch_values_by_rowid(rowids, 1, dst.get());
                ASSERT_TRUE(st.is_corruption()) << name << ": " << st;
            }
        }
    }
}

// A flat JSON column can be the element of an ARRAY. Its sub-columns then hold one value per element, not one per
// segment row: here 4 rows hold 10 elements. Whole JSON reads (JsonMergeIterator) and path reads
// (JsonFlatColumnIterator) of the elements must give every element in every read mode. A sub-column with fewer values
// than the array's offsets ask for must still fail with Corruption.
TEST_F(FlatJsonColumnRWTest, testFlatJsonInArray) {
    constexpr rowid_t kRows = 4;
    const std::vector<uint32_t> sizes = {3, 0, 2, 5};
    // the element each row starts at
    const std::vector<uint32_t> starts = {0, 3, 3, 5};
    // the same arrays without the last element
    const std::vector<uint32_t> short_sizes = {3, 0, 2, 4};

    auto new_array = [&](const std::vector<uint32_t>& array_sizes) -> ArrayColumn::Ptr {
        UInt32Column::Ptr offsets = UInt32Column::create();
        offsets->append(0);
        uint32_t end = 0;
        for (uint32_t size : array_sizes) {
            end += size;
            offsets->append(end);
        }
        ColumnPtr elements = create_json(ab_jsons(end), true);
        return ArrayColumn::create(elements, offsets);
    };

    TabletColumn array_column = create_array(0, false);
    TabletColumn json_column = create_with_default_value<TYPE_JSON>("");
    array_column.add_sub_column(json_column);

    auto fs = std::make_shared<MemoryFileSystem>();
    ASSERT_TRUE(fs->create_dir(TEST_DIR).ok());
    const std::string fname = TEST_DIR + "/test_flat_json_in_array.data";

    ArrayColumn::Ptr src = new_array(sizes);
    ColumnMetaPB full_meta;
    ColumnMetaPB short_meta;
    {
        ASSIGN_OR_ABORT(auto wfile, fs->new_writable_file(fname));
        auto write = [&](ColumnMetaPB* meta, const std::vector<uint32_t>& array_sizes) {
            init_column_meta(meta, TYPE_ARRAY, 0, false);
            init_column_meta(meta->add_children_columns(), TYPE_JSON, 0, true);
            ArrayColumn::Ptr column = new_array(array_sizes);
            write_flat_column(wfile.get(), meta, array_column, *column);
        };
        ASSERT_NO_FATAL_FAILURE(write(&full_meta, sizes));
        ASSERT_NO_FATAL_FAILURE(write(&short_meta, short_sizes));
        ASSERT_OK(wfile->close());
    }
    // the elements are stored as flat JSON, with the same sub-columns in both arrays
    ASSERT_TRUE(full_meta.children_columns(0).json_meta().is_flat());
    ASSERT_EQ(full_meta.children_columns(0).children_columns_size(),
              short_meta.children_columns(0).children_columns_size());

    auto segment = create_dummy_segment(fs, fname);
    segment->set_num_rows(kRows);
    ASSIGN_OR_ABORT(auto read_file, fs->new_random_access_file(fname));

    // the array -> all its elements -> the JSON paths a and b
    ASSIGN_OR_ABORT(auto root_path, ColumnAccessPath::create(TAccessPathType::ROOT, "root", 0));
    ASSIGN_OR_ABORT(auto all_path, ColumnAccessPath::create(TAccessPathType::ALL, "root.all", 0));
    ASSIGN_OR_ABORT(auto a_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.all.a", 0));
    ASSIGN_OR_ABORT(auto b_path, ColumnAccessPath::create(TAccessPathType::FIELD, "root.all.b", 0));
    all_path->children().emplace_back(std::move(a_path));
    all_path->children().emplace_back(std::move(b_path));
    root_path->children().emplace_back(std::move(all_path));

    OlapReaderStatistics stats;
    ColumnIteratorOptions iter_opts;
    iter_opts.stats = &stats;
    iter_opts.read_file = read_file.get();
    auto new_dst = []() -> ArrayColumn::Ptr {
        UInt32Column::Ptr offsets = UInt32Column::create();
        NullableColumn::Ptr elements = NullableColumn::create(JsonColumn::create(), NullColumn::create());
        return ArrayColumn::create(elements, offsets);
    };
    // `dst` must hold the rows `rows` of the array written
    auto check = [&](ArrayColumn::Ptr& dst, const std::vector<rowid_t>& rows, bool by_path, const std::string& name) {
        ASSERT_EQ(rows.size(), dst->size()) << name;
        if (!by_path) {
            for (size_t i = 0; i < rows.size(); i++) {
                ASSERT_EQ(src->debug_item(rows[i]), dst->debug_item(i)) << name;
            }
            return;
        }
        // a path read gives flat JSON: the value of the path a is the position of the element in the column
        auto* elements = down_cast<NullableColumn*>(dst->elements_column().get());
        auto* json = down_cast<JsonColumn*>(elements->data_column().get());
        ASSERT_TRUE(json->is_flat_json()) << name;
        size_t pos = 0;
        for (rowid_t row : rows) {
            for (uint32_t i = 0; i < sizes[row]; i++, pos++) {
                ASSERT_LT(pos, json->size()) << name;
                ASSERT_EQ(std::to_string(starts[row] + i), json->get_flat_field("a")->debug_item(pos)) << name;
            }
        }
        ASSERT_EQ(pos, json->size()) << name;
    };

    for (ColumnAccessPath* path : {static_cast<ColumnAccessPath*>(nullptr), root_path.get()}) {
        const bool by_path = path != nullptr;
        const std::string name = by_path ? "path" : "whole json";
        ColumnMetaPB meta = full_meta;
        ASSIGN_OR_ABORT(auto reader, ColumnReader::create(&meta, segment.get(), nullptr));
        auto new_iter = [&]() {
            ASSIGN_OR_ABORT(auto iter, reader->new_iterator(path));
            EXPECT_OK(iter->init(iter_opts));
            return iter;
        };

        // a sequential read of every row: it asks for more elements than the segment has rows
        {
            auto iter = new_iter();
            ASSERT_OK(iter->seek_to_first());
            auto dst = new_dst();
            size_t n = kRows;
            ASSERT_OK(iter->next_batch(&n, dst.get())) << name;
            ASSERT_NO_FATAL_FAILURE(check(dst, {0, 1, 2, 3}, by_path, name));
        }
        // a range read of rows 0 and 3
        {
            SparseRange<> range;
            range.add(Range<>(0, 1));
            range.add(Range<>(3, 4));
            auto iter = new_iter();
            ASSERT_OK(iter->seek_to_ordinal(range.begin()));
            auto dst = new_dst();
            ASSERT_OK(iter->next_batch(range, dst.get())) << name;
            ASSERT_NO_FATAL_FAILURE(check(dst, {0, 3}, by_path, name));
        }
        // a fetch of rows 0, 2 and 3: the elements of row 2 start before the element whose position is the segment's
        // row count, and end after it
        {
            auto iter = new_iter();
            auto dst = new_dst();
            rowid_t rowids[] = {0, 2, 3};
            ASSERT_OK(iter->fetch_values_by_rowid(rowids, 3, dst.get())) << name;
            ASSERT_NO_FATAL_FAILURE(check(dst, {0, 2, 3}, by_path, name));
        }
    }

    // The sub-column a of the elements has one value less than the array's offsets ask for.
    {
        ColumnMetaPB meta = full_meta;
        ColumnMetaPB* json_meta = meta.mutable_children_columns(0);
        const ColumnMetaPB& short_json_meta = short_meta.children_columns(0);
        int index = -1;
        for (int i = 0; i < json_meta->children_columns_size(); i++) {
            if (json_meta->children_columns(i).name() == "a") {
                index = i;
            }
        }
        ASSERT_GE(index, 0);
        ASSERT_EQ("a", short_json_meta.children_columns(index).name());
        *json_meta->mutable_children_columns(index) = short_json_meta.children_columns(index);
        ASSIGN_OR_ABORT(auto reader, ColumnReader::create(&meta, segment.get(), nullptr));
        auto new_iter = [&]() {
            ASSIGN_OR_ABORT(auto iter, reader->new_iterator());
            EXPECT_OK(iter->init(iter_opts));
            return iter;
        };

        // the rows before the missing element are still readable
        {
            auto iter = new_iter();
            ASSERT_OK(iter->seek_to_ordinal(0));
            auto dst = new_dst();
            ASSERT_OK(iter->next_batch(SparseRange<>(0, kRows - 1), dst.get()));
            ASSERT_NO_FATAL_FAILURE(check(dst, {0, 1, 2}, false, "short a"));
        }
        // a sequential read of every row
        {
            auto iter = new_iter();
            ASSERT_OK(iter->seek_to_first());
            auto dst = new_dst();
            size_t n = kRows;
            auto st = iter->next_batch(&n, dst.get());
            ASSERT_TRUE(st.is_corruption()) << st;
        }
        // a fetch of the row with the missing element
        {
            auto iter = new_iter();
            auto dst = new_dst();
            rowid_t rowids[] = {kRows - 1};
            auto st = iter->fetch_values_by_rowid(rowids, 1, dst.get());
            ASSERT_TRUE(st.is_corruption()) << st;
        }
    }
}

// The same for a flat JSON column that is the value of a MAP: 3 rows hold 6 values.
TEST_F(FlatJsonColumnRWTest, testFlatJsonInMap) {
    constexpr rowid_t kRows = 3;
    const std::vector<uint32_t> sizes = {2, 0, 4};

    TabletColumn map_column = create_map(0, false);
    TabletColumn key_column = create_int_value(1, STORAGE_AGGREGATE_NONE, true);
    TabletColumn value_column = create_with_default_value<TYPE_JSON>("");
    map_column.add_sub_column(key_column);
    map_column.add_sub_column(value_column);

    UInt32Column::Ptr src_offsets = UInt32Column::create();
    NullableColumn::Ptr src_keys = NullableColumn::create(Int32Column::create(), NullColumn::create());
    src_offsets->append(0);
    uint32_t end = 0;
    for (uint32_t size : sizes) {
        for (uint32_t i = 0; i < size; i++, end++) {
            src_keys->append_datum(static_cast<int32_t>(end));
        }
        src_offsets->append(end);
    }
    ColumnPtr src_values = create_json(ab_jsons(end), true);
    MapColumn::Ptr src = MapColumn::create(src_keys, src_values, src_offsets);

    auto fs = std::make_shared<MemoryFileSystem>();
    ASSERT_TRUE(fs->create_dir(TEST_DIR).ok());
    const std::string fname = TEST_DIR + "/test_flat_json_in_map.data";

    ColumnMetaPB map_meta;
    {
        ASSIGN_OR_ABORT(auto wfile, fs->new_writable_file(fname));
        init_column_meta(&map_meta, TYPE_MAP, 0, false);
        init_column_meta(map_meta.add_children_columns(), TYPE_INT, key_column.length(), false);
        init_column_meta(map_meta.add_children_columns(), TYPE_JSON, 0, true);
        ASSERT_NO_FATAL_FAILURE(write_flat_column(wfile.get(), &map_meta, map_column, *src));
        ASSERT_OK(wfile->close());
    }
    // the values are stored as flat JSON
    ASSERT_TRUE(map_meta.children_columns(1).json_meta().is_flat());

    auto segment = create_dummy_segment(fs, fname);
    segment->set_num_rows(kRows);
    ASSIGN_OR_ABORT(auto read_file, fs->new_random_access_file(fname));
    ASSIGN_OR_ABORT(auto reader, ColumnReader::create(&map_meta, segment.get(), nullptr));

    OlapReaderStatistics stats;
    ColumnIteratorOptions iter_opts;
    iter_opts.stats = &stats;
    iter_opts.read_file = read_file.get();
    auto new_iter = [&]() {
        ASSIGN_OR_ABORT(auto iter, reader->new_iterator());
        EXPECT_OK(iter->init(iter_opts));
        return iter;
    };
    auto new_dst = []() -> MapColumn::Ptr {
        UInt32Column::Ptr offsets = UInt32Column::create();
        NullableColumn::Ptr keys = NullableColumn::create(Int32Column::create(), NullColumn::create());
        NullableColumn::Ptr values = NullableColumn::create(JsonColumn::create(), NullColumn::create());
        return MapColumn::create(keys, values, offsets);
    };

    // a sequential read of every row: it asks for more values than the segment has rows
    {
        auto iter = new_iter();
        ASSERT_OK(iter->seek_to_first());
        auto dst = new_dst();
        size_t n = kRows;
        ASSERT_OK(iter->next_batch(&n, dst.get()));
        ASSERT_EQ(kRows, dst->size());
        for (rowid_t row = 0; row < kRows; row++) {
            ASSERT_EQ(src->debug_item(row), dst->debug_item(row));
        }
    }
    // a fetch of rows 0 and 2: the values of row 2 start before the value whose position is the segment's row count,
    // and end after it
    {
        auto iter = new_iter();
        auto dst = new_dst();
        rowid_t rowids[] = {0, 2};
        ASSERT_OK(iter->fetch_values_by_rowid(rowids, 2, dst.get()));
        ASSERT_EQ(2, dst->size());
        ASSERT_EQ(src->debug_item(0), dst->debug_item(0));
        ASSERT_EQ(src->debug_item(2), dst->debug_item(1));
    }
}

} // namespace starrocks
