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

#include "fs/s3/aws_md5_hash.h"

#include <aws/core/utils/HashingUtils.h>
#include <aws/core/utils/Outcome.h>
#include <aws/core/utils/crypto/HashResult.h>
#include <aws/core/utils/memory/stl/AWSStringStream.h>
#include <gtest/gtest.h>

#include <string>

namespace starrocks::s3 {

static std::string hex(const Aws::Utils::Crypto::HashResult& result) {
    EXPECT_TRUE(result.IsSuccess());
    return std::string(Aws::Utils::HashingUtils::HexEncode(result.GetResult()).c_str());
}

// RFC 1321 test suite
TEST(AwsMd5HashTest, calculate_string) {
    AwsMd5Hash hash;
    EXPECT_EQ("900150983cd24fb0d6963f7d28e17f72", hex(hash.Calculate(Aws::String("abc"))));
    EXPECT_EQ("d41d8cd98f00b204e9800998ecf8427e", hex(hash.Calculate(Aws::String())));
}

// like the sdk's own implementation: hash the whole stream, then restore the read position
TEST(AwsMd5HashTest, calculate_stream_restores_position) {
    Aws::StringStream ss;
    ss << "abc";
    ss.seekg(1);
    AwsMd5Hash hash;
    EXPECT_EQ("900150983cd24fb0d6963f7d28e17f72", hex(hash.Calculate(ss)));
    EXPECT_EQ(1, static_cast<int>(ss.tellg()));
}

TEST(AwsMd5HashTest, update_and_get_hash_can_be_reused) {
    AwsMd5Hash hash;
    unsigned char a[] = {'a'};
    unsigned char bc[] = {'b', 'c'};
    hash.Update(a, sizeof(a));
    hash.Update(bc, sizeof(bc));
    EXPECT_EQ("900150983cd24fb0d6963f7d28e17f72", hex(hash.GetHash()));
    EXPECT_EQ("d41d8cd98f00b204e9800998ecf8427e", hex(hash.GetHash()));
}

TEST(AwsMd5HashTest, factory_creates_independent_hashes) {
    AwsMd5HashFactory factory;
    auto h1 = factory.CreateImplementation();
    auto h2 = factory.CreateImplementation();
    ASSERT_NE(h1.get(), h2.get());
    EXPECT_EQ("900150983cd24fb0d6963f7d28e17f72", hex(h1->Calculate(Aws::String("abc"))));
}

} // namespace starrocks::s3
