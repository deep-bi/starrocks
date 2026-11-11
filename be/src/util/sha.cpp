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

#include <glog/logging.h>

#include <string>

namespace starrocks {

namespace detail {

// a failure here means allocation failed or the provider is in its error state; a wrong digest
// would be worse than stopping, since callers compare it with stored values or use it as a key
template <size_t Size>
EvpDigest<Size>::EvpDigest(const EVP_MD* md) : _ctx(EVP_MD_CTX_new()) {
    CHECK(_ctx != nullptr) << "EVP_MD_CTX_new failed";
    CHECK_EQ(EVP_DigestInit_ex(_ctx.get(), md, nullptr), 1) << "EVP_DigestInit_ex failed for " << EVP_MD_get0_name(md);
    DCHECK_EQ(static_cast<size_t>(EVP_MD_CTX_get_size(_ctx.get())), Size);
}

template <size_t Size>
void EvpDigest<Size>::update(const void* data, size_t length) {
    CHECK_EQ(EVP_DigestUpdate(_ctx.get(), data, length), 1) << "EVP_DigestUpdate failed";
}

template <size_t Size>
void EvpDigest<Size>::digest() {
    unsigned int len = 0;
    CHECK_EQ(EVP_DigestFinal_ex(_ctx.get(), _hash, &len), 1) << "EVP_DigestFinal_ex failed";
    DCHECK_EQ(len, Size);
}

template <size_t Size>
std::string EvpDigest<Size>::hex() const {
    static constexpr char kHex[] = "0123456789abcdef";
    std::string out(2 * Size, '\0');
    for (size_t i = 0; i < Size; i++) {
        out[2 * i] = kHex[_hash[i] >> 4];
        out[2 * i + 1] = kHex[_hash[i] & 0x0F];
    }
    return out;
}

// Md5Digest, see util/md5.h
template class EvpDigest<16>;
template class EvpDigest<SHA224_DIGEST_LENGTH>;
template class EvpDigest<SHA256_DIGEST_LENGTH>;
template class EvpDigest<SHA384_DIGEST_LENGTH>;
template class EvpDigest<SHA512_DIGEST_LENGTH>;

} // namespace detail

// fetched once, because a legacy EVP_shaXXX() handle makes every EVP_DigestInit_ex fetch again under
// the method store lock, once per row in the SQL hash functions. The fetch uses the default
// properties at the first call, which must come after init_fips_mode(); init_fips_mode() checks
// that these come from the fips provider
static const EVP_MD* fetch_sha(const char* name) {
    EVP_MD* md = EVP_MD_fetch(nullptr, name, nullptr);
    CHECK(md != nullptr) << "cannot fetch " << name;
    return md;
}

const EVP_MD* SHA224Digest::method() {
    static const EVP_MD* md = fetch_sha("SHA2-224");
    return md;
}

const EVP_MD* SHA256Digest::method() {
    static const EVP_MD* md = fetch_sha("SHA2-256");
    return md;
}

const EVP_MD* SHA384Digest::method() {
    static const EVP_MD* md = fetch_sha("SHA2-384");
    return md;
}

const EVP_MD* SHA512Digest::method() {
    static const EVP_MD* md = fetch_sha("SHA2-512");
    return md;
}

SHA224Digest::SHA224Digest() : EvpDigest(method()) {}

SHA256Digest::SHA256Digest() : EvpDigest(method()) {}

SHA384Digest::SHA384Digest() : EvpDigest(method()) {}

SHA512Digest::SHA512Digest() : EvpDigest(method()) {}

} // namespace starrocks
