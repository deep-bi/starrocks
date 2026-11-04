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

#include <openssl/evp.h>
#include <openssl/sha.h>

#include <cstddef>
#include <memory>
#include <string>

namespace starrocks {

namespace detail {

struct EvpMdCtxDeleter {
    void operator()(EVP_MD_CTX* ctx) const { EVP_MD_CTX_free(ctx); }
};

// goes through EVP so that, in FIPS mode, the digest comes from the fips provider
template <size_t Size>
class EvpDigest {
public:
    void update(const void* data, size_t length);
    void digest();

    // hex-encoded result, 2 * Size characters
    std::string hex() const;

    // raw binary result, Size bytes
    const unsigned char* binary() const { return _hash; }
    size_t binary_size() const { return Size; }

protected:
    explicit EvpDigest(const EVP_MD* md);

private:
    std::unique_ptr<EVP_MD_CTX, EvpMdCtxDeleter> _ctx;
    unsigned char _hash[Size] = {};
};

} // namespace detail

class SHA224Digest : public detail::EvpDigest<SHA224_DIGEST_LENGTH> {
public:
    SHA224Digest();
};

class SHA256Digest : public detail::EvpDigest<SHA256_DIGEST_LENGTH> {
public:
    SHA256Digest();
};

class SHA384Digest : public detail::EvpDigest<SHA384_DIGEST_LENGTH> {
public:
    SHA384Digest();
};

class SHA512Digest : public detail::EvpDigest<SHA512_DIGEST_LENGTH> {
public:
    SHA512Digest();
};

} // namespace starrocks
