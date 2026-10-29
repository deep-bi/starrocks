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

#include <gtest/gtest.h>

#include <algorithm>
#include <filesystem>
#include <fstream>
#include <set>
#include <sstream>

#include "fs/fs_util.h"
#include "gen_cpp/AgentService_types.h"
#include "runtime/exec_env.h"
#include "util/cpu_info.h"

#define private public // hack complier
#define protected public

#include "runtime/snapshot_loader.h"

namespace starrocks {

class SnapshotLoaderTest : public testing::Test {
public:
    SnapshotLoaderTest() = default;

private:
    ExecEnv* _exec_env;
};

TEST_F(SnapshotLoaderTest, NormalCase) {
    SnapshotLoader loader(_exec_env, 1L, 2L);

    ASSERT_TRUE(loader._end_with("abt.dat", ".dat"));
    ASSERT_FALSE(loader._end_with("abt.dat", ".da"));

    int64_t tablet_id = 0;
    int32_t schema_hash = 0;
    Status st = loader._get_tablet_id_and_schema_hash_from_file_path("/path/to/1234/5678", &tablet_id, &schema_hash);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(1234, tablet_id);
    ASSERT_EQ(5678, schema_hash);

    st = loader._get_tablet_id_and_schema_hash_from_file_path("/path/to/1234/5678/", &tablet_id, &schema_hash);
    ASSERT_FALSE(st.ok());

    std::filesystem::remove_all("./ss_test/");
    std::map<std::string, std::string> src_to_dest;
    src_to_dest["./ss_test/"] = "./ss_test";
    st = loader._check_local_snapshot_paths(src_to_dest, true);
    ASSERT_FALSE(st.ok());
    st = loader._check_local_snapshot_paths(src_to_dest, false);
    ASSERT_FALSE(st.ok());

    std::filesystem::create_directory("./ss_test/");
    st = loader._check_local_snapshot_paths(src_to_dest, true);
    ASSERT_TRUE(st.ok());
    st = loader._check_local_snapshot_paths(src_to_dest, false);
    ASSERT_TRUE(st.ok());
    std::filesystem::remove_all("./ss_test/");

    std::filesystem::create_directory("./ss_test/");
    std::vector<std::string> files;
    st = loader._get_existing_files_from_local("./ss_test/", &files);
    ASSERT_EQ(0, files.size());
    std::filesystem::remove_all("./ss_test/");

    std::string snapshot_file;
    std::string tablet_file;
    loader._assemble_file_name("/snapshot/path", "/tablet/path", 1234, 2, 5, 12345, 1, ".dat", &snapshot_file,
                               &tablet_file);
    ASSERT_EQ("/snapshot/path/1234_2_5_12345_1.dat", snapshot_file);
    ASSERT_EQ("/tablet/path/1234_2_5_12345_1.dat", tablet_file);

    std::string new_name;
    st = loader._replace_tablet_id("12345.hdr", 5678, &new_name);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ("5678.hdr", new_name);

    st = loader._replace_tablet_id("1234_2_5_12345_1.dat", 5678, &new_name);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ("1234_2_5_12345_1.dat", new_name);

    st = loader._replace_tablet_id("1234_2_5_12345_1.upt", 5678, &new_name);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ("1234_2_5_12345_1.upt", new_name);

    st = loader._replace_tablet_id("1234_2_5_12345_1.cols", 5678, &new_name);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ("1234_2_5_12345_1.cols", new_name);

    st = loader._replace_tablet_id("1234_2_5_12345_1.idx", 5678, &new_name);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ("1234_2_5_12345_1.idx", new_name);

    st = loader._replace_tablet_id("1234_2_5_12345_1.xxx", 5678, &new_name);
    ASSERT_FALSE(st.ok());

    st = loader._get_tablet_id_from_remote_path("/__tbl_10004/__part_10003/__idx_10004/__10005", &tablet_id);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(10005, tablet_id);
}

// Incremental backup, BE side (test-design.md TC-100..). Local directories stand in for the repository:
// without a broker, a path with no scheme gets the POSIX filesystem.
class SnapshotLoaderIncrementalTest : public testing::Test {
protected:
    void SetUp() override {
        std::filesystem::remove_all(_root);
        std::filesystem::create_directories(_root);
    }

    void TearDown() override { std::filesystem::remove_all(_root); }

    // The BE names a local snapshot directory <...>/<tablet id>/<schema hash>.
    std::string local_tablet(int64_t tablet_id, const std::map<std::string, std::string>& files) {
        std::string dir = _root + "/local/" + std::to_string(tablet_id) + "/1234";
        std::filesystem::create_directories(dir);
        for (const auto& [name, content] : files) {
            write_file(dir + "/" + name, content);
        }
        return dir;
    }

    std::string remote_tablet(int64_t tablet_id) {
        std::string dir = _root + "/remote/__" + std::to_string(tablet_id);
        std::filesystem::create_directories(dir);
        return dir;
    }

    static void write_file(const std::string& path, const std::string& content) {
        std::ofstream out(path);
        out << content;
    }

    static std::string read_file(const std::string& path) {
        std::ifstream in(path);
        std::stringstream content;
        content << in.rdbuf();
        return content.str();
    }

    static std::string md5(const std::string& path) { return fs::md5sum(path).value(); }

    static std::set<std::string> list(const std::string& dir) {
        std::set<std::string> names;
        for (const auto& entry : std::filesystem::directory_iterator(dir)) {
            names.insert(entry.path().filename().string());
        }
        return names;
    }

    static std::set<std::string> as_set(const std::vector<std::string>& names) { return {names.begin(), names.end()}; }

    static TUploadReq upload_request() {
        TUploadReq request;
        request.__set_use_broker(false);
        return request;
    }

    // Fewer than 10 files per upload, so the loader never reports progress to an FE.
    SnapshotLoader _loader{nullptr, 1L, 2L};
    const std::string _root = "./snapshot_loader_incremental_test";
};

// TC-100 (F1)
TEST_F(SnapshotLoaderIncrementalTest, upload_skips_a_file_whose_name_and_md5_are_reusable) {
    std::string local = local_tablet(10001, {{"10001_0.dat", "data"}, {"10001.hdr", "header"}});
    std::string remote = remote_tablet(10001);
    std::string data = "10001_0.dat." + md5(local + "/10001_0.dat");
    std::string header = "10001.hdr." + md5(local + "/10001.hdr");
    TUploadReq request = upload_request();
    request.__set_reusable_files({{remote, {data}}});

    std::map<int64_t, std::vector<std::string>> tablet_files;
    Status st = _loader.upload({{local, remote}}, request, &tablet_files);

    ASSERT_TRUE(st.ok()) << st;
    EXPECT_EQ(std::set<std::string>{header}, list(remote));
    EXPECT_EQ((std::set<std::string>{data, header}), as_set(tablet_files[10001]));
}

// TC-101 (F2)
TEST_F(SnapshotLoaderIncrementalTest, upload_sends_a_file_whose_reusable_name_has_another_md5) {
    std::string local = local_tablet(10001, {{"10001_0.dat", "data"}, {"10001.hdr", "header"}});
    std::string remote = remote_tablet(10001);
    std::string data = "10001_0.dat." + md5(local + "/10001_0.dat");
    std::string header = "10001.hdr." + md5(local + "/10001.hdr");
    TUploadReq request = upload_request();
    request.__set_reusable_files({{remote, {"10001_0.dat.00000000000000000000000000000000"}}});

    std::map<int64_t, std::vector<std::string>> tablet_files;
    Status st = _loader.upload({{local, remote}}, request, &tablet_files);

    ASSERT_TRUE(st.ok()) << st;
    EXPECT_EQ((std::set<std::string>{data, header}), list(remote));
}

// TC-102 (F3, F4): without reusable files the upload behaves as today, including a retried task that
// finds a file already uploaded. That file is named with its md5, so the loader trusts it without reading it.
TEST_F(SnapshotLoaderIncrementalTest, upload_without_reusable_files_keeps_todays_behaviour) {
    std::string local = local_tablet(10001, {{"10001_0.dat", "data"}, {"10001.hdr", "header"}});
    std::string remote = remote_tablet(10001);
    std::string data = "10001_0.dat." + md5(local + "/10001_0.dat");
    std::string header = "10001.hdr." + md5(local + "/10001.hdr");
    write_file(remote + "/" + header, "uploaded by an earlier attempt");

    std::map<int64_t, std::vector<std::string>> tablet_files;
    Status st = _loader.upload({{local, remote}}, upload_request(), &tablet_files);

    ASSERT_TRUE(st.ok()) << st;
    EXPECT_EQ((std::set<std::string>{data, header}), list(remote));
    EXPECT_EQ("uploaded by an earlier attempt", read_file(remote + "/" + header));
    EXPECT_EQ("data", read_file(remote + "/" + data));
}

// TC-103: reusable files are per destination, so they don't spill over to another tablet of the same task.
TEST_F(SnapshotLoaderIncrementalTest, upload_reuses_files_only_for_their_own_tablet) {
    std::string local1 = local_tablet(10001, {{"same.dat", "same bytes"}});
    std::string local2 = local_tablet(10002, {{"same.dat", "same bytes"}});
    std::string remote1 = remote_tablet(10001);
    std::string remote2 = remote_tablet(10002);
    std::string same = "same.dat." + md5(local1 + "/same.dat");
    TUploadReq request = upload_request();
    request.__set_reusable_files({{remote1, {same}}});

    std::map<int64_t, std::vector<std::string>> tablet_files;
    Status st = _loader.upload({{local1, remote1}, {local2, remote2}}, request, &tablet_files);

    ASSERT_TRUE(st.ok()) << st;
    EXPECT_EQ(std::set<std::string>{}, list(remote1));
    EXPECT_EQ(std::set<std::string>{same}, list(remote2));
}

} // namespace starrocks
