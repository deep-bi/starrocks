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

#include "util/md5.h"

#include <glog/logging.h>

namespace starrocks {

// "fips=no" takes MD5 from the default provider, also when fips=yes is the default property, so
// the result is the same with and without FIPS mode. the method is fetched once: MD5 never comes
// from the fips provider, so the time of the first call doesn't matter.
static const EVP_MD* md5_method() {
    static EVP_MD* md = EVP_MD_fetch(nullptr, "MD5", "fips=no");
    CHECK(md != nullptr) << "cannot fetch MD5 from the default OpenSSL provider";
    return md;
}

Md5Digest::Md5Digest() : EvpDigest(md5_method()) {}

} // namespace starrocks
