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

#include "fs/fs_util.h"

#include <gtest/gtest.h>

#include <unistd.h>

#include <cstdio>
#include <fstream>
#include <string>

#include "testutil/assert.h"

namespace starrocks {

class FsUtilTest : public testing::Test {
protected:
    void SetUp() override { _path = testing::TempDir() + "/fs_util_test_" + std::to_string(::getpid()); }
    void TearDown() override { std::remove(_path.c_str()); }

    void write_file(const std::string& data) {
        std::ofstream out(_path, std::ios::binary | std::ios::trunc);
        out.write(data.data(), data.size());
    }

    std::string _path;
};

// three full 1 MiB chunks and a partial one, so the chunk boundaries are covered
TEST_F(FsUtilTest, checksums_span_chunks) {
    std::string data;
    data.reserve(256 * 12289);
    for (int i = 0; i < 12289; i++) {
        for (int b = 0; b < 256; b++) {
            data.push_back(static_cast<char>(b));
        }
    }
    write_file(data);
    ASSIGN_OR_ABORT(auto md5, fs::md5sum(_path));
    ASSIGN_OR_ABORT(auto sha256, fs::sha256sum(_path));
    EXPECT_EQ("87655cb9e87a968c3f7254e28db408de", md5);
    EXPECT_EQ("679361bf172a2b2f3d48919ee7b2b6fea9cf6e351005736ac7274ac8fde4e0f2", sha256);
}

TEST_F(FsUtilTest, checksums_of_empty_file) {
    write_file("");
    ASSIGN_OR_ABORT(auto md5, fs::md5sum(_path));
    ASSIGN_OR_ABORT(auto sha256, fs::sha256sum(_path));
    EXPECT_EQ("d41d8cd98f00b204e9800998ecf8427e", md5);
    EXPECT_EQ("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256);
}

TEST_F(FsUtilTest, missing_file_is_an_error) {
    EXPECT_FALSE(fs::md5sum(_path + ".missing").ok());
    EXPECT_FALSE(fs::sha256sum(_path + ".missing").ok());
}

} // namespace starrocks
