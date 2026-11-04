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

#include "util/sha.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cstdio>
#include <string>
#include <vector>

namespace starrocks {

template <typename Digest>
static std::string hex_of(const std::string& data) {
    Digest digest;
    digest.update(data.data(), data.size());
    digest.digest();
    return digest.hex();
}

// FIPS 180-4 example vectors
TEST(ShaTest, abc) {
    EXPECT_EQ("23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7", hex_of<SHA224Digest>("abc"));
    EXPECT_EQ("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex_of<SHA256Digest>("abc"));
    EXPECT_EQ(
            "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7",
            hex_of<SHA384Digest>("abc"));
    EXPECT_EQ(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d44"
            "23643ce80e2a9ac94fa54ca49f",
            hex_of<SHA512Digest>("abc"));
}

TEST(ShaTest, empty) {
    EXPECT_EQ("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex_of<SHA256Digest>(""));
}

TEST(ShaTest, chunked_update_matches_one_shot) {
    std::string data(1000000, 'a');
    SHA256Digest digest;
    for (size_t off = 0; off < data.size(); off += 4096) {
        digest.update(data.data() + off, std::min<size_t>(4096, data.size() - off));
    }
    digest.digest();
    EXPECT_EQ("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", digest.hex());
    EXPECT_EQ(digest.hex(), hex_of<SHA256Digest>(data));
}

TEST(ShaTest, binary_matches_hex) {
    SHA384Digest digest;
    digest.update("abc", 3);
    digest.digest();
    ASSERT_EQ(48u, digest.binary_size());
    std::string hex;
    char buf[3];
    for (size_t i = 0; i < digest.binary_size(); i++) {
        snprintf(buf, sizeof(buf), "%02x", digest.binary()[i]);
        hex += buf;
    }
    EXPECT_EQ(hex, digest.hex());
}

// encode_fingerprint_sha256 keeps one digest per row in a vector
TEST(ShaTest, independent_digests_in_vector) {
    std::vector<SHA256Digest> digests(3);
    digests[0].update("abc", 3);
    digests[2].update("abc", 3);
    for (auto& d : digests) {
        d.digest();
    }
    EXPECT_EQ(digests[0].hex(), digests[2].hex());
    EXPECT_EQ("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digests[1].hex());
}

} // namespace starrocks
