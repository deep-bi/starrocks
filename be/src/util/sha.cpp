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
    // the legacy EVP_shaXXX() handles are fetched here with the current default properties, so
    // a digest constructed after init_fips_mode() uses the fips provider
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

SHA224Digest::SHA224Digest() : EvpDigest(EVP_sha224()) {}

SHA256Digest::SHA256Digest() : EvpDigest(EVP_sha256()) {}

SHA384Digest::SHA384Digest() : EvpDigest(EVP_sha384()) {}

SHA512Digest::SHA512Digest() : EvpDigest(EVP_sha512()) {}

} // namespace starrocks
