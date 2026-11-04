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

// a failure here means allocation failed or no provider can serve MD5; a wrong checksum would be
// worse than stopping, since callers compare it with values from the FE and backup repositories
Md5Digest::Md5Digest() : _ctx(EVP_MD_CTX_new()) {
    CHECK(_ctx != nullptr) << "EVP_MD_CTX_new failed";
    CHECK_EQ(EVP_DigestInit_ex(_ctx.get(), md5_method(), nullptr), 1) << "EVP_DigestInit_ex failed for MD5";
}

void Md5Digest::update(const void* data, size_t length) {
    CHECK_EQ(EVP_DigestUpdate(_ctx.get(), data, length), 1) << "EVP_DigestUpdate failed for MD5";
}

void Md5Digest::digest() {
    unsigned int len = 0;
    CHECK_EQ(EVP_DigestFinal_ex(_ctx.get(), _hash, &len), 1) << "EVP_DigestFinal_ex failed for MD5";
    DCHECK_EQ(len, kDigestLength);

    static constexpr char kHex[] = "0123456789abcdef";
    _hex.resize(2 * kDigestLength);
    for (size_t i = 0; i < kDigestLength; i++) {
        _hex[2 * i] = kHex[_hash[i] >> 4];
        _hex[2 * i + 1] = kHex[_hash[i] & 0x0F];
    }
}

} // namespace starrocks
