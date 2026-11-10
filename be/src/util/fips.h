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

#pragma once

#include <string>

#include "common/status.h"

namespace starrocks {

// Switches the process-wide OpenSSL library context to the OpenSSL FIPS provider loaded from
// config::fips_module_dir, using config::fips_openssl_conf. Call once, early in main, before
// anything else initialises OpenSSL. Returns an error if fips.so or conf/fipsmodule.cnf is
// missing, or if the provider fails its self-tests or integrity check; the caller must not
// continue in that case.
Status init_fips_mode();

// Call right after Aws::InitAPI. In FIPS mode, puts back OpenSSL's random number generator if the
// AWS SDK replaced it, and checks that RAND_bytes is served by the fips provider. No-op otherwise.
Status restore_fips_rand();

// True once init_fips_mode() has succeeded.
bool is_fips_mode_enabled();

// "OpenSSL FIPS Provider 3.1.2" when enabled, "disabled" otherwise.
const std::string& fips_mode_description();

} // namespace starrocks
