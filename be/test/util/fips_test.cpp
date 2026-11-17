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

#include "util/fips.h"

#include <gtest/gtest.h>
#include <unistd.h>

#include <cstdio>

#include "common/config.h"

namespace starrocks {

// unit tests have no fips.so, so only the off path and the refusal paths are testable here; the on
// path is covered by starting a packaged build with enable_fips_mode = true
TEST(FipsTest, disabled_by_default) {
    ASSERT_FALSE(is_fips_mode_enabled());
    ASSERT_EQ("disabled", fips_mode_description());
}

TEST(FipsTest, restore_rand_is_noop_when_disabled) {
    ASSERT_TRUE(restore_fips_rand().ok());
}

// init_fips_mode() changes process-wide OpenSSL state even when it fails, so it runs in a child
#ifndef THREAD_SANITIZER
TEST(FipsDeathTest, refuses_without_openssl_fips_conf) {
    EXPECT_EXIT(
            {
                config::fips_module_dir = testing::TempDir();
                config::fips_openssl_conf = testing::TempDir() + "/no-such-openssl-fips.cnf";
                auto st = init_fips_mode();
                fprintf(stderr, "%s\n", st.to_string().c_str());
                _exit(!st.ok() && !is_fips_mode_enabled() ? 0 : 1);
            },
            ::testing::ExitedWithCode(0), "FIPS mode: cannot load .*no-such-openssl-fips.cnf");
}
#endif

} // namespace starrocks
