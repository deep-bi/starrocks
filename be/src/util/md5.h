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

#pragma once

#include <openssl/evp.h>

#include <cstddef>
#include <memory>
#include <string>

namespace starrocks {

// MD5 is not FIPS approved. It is kept only where another system expects an MD5 value: the md5
// SQL functions, FE-supplied checksums for small files and UDF jars, and checksums stored in
// backup repositories. New code that needs a checksum should use SHA256Digest from util/sha.h.
class Md5Digest {
public:
    static constexpr size_t kDigestLength = 16;

    Md5Digest();

    void update(const void* data, size_t length);
    void digest();

    const std::string& hex() const { return _hex; }

    // raw digest, kDigestLength bytes; valid after digest()
    const unsigned char* binary() const { return _hash; }

private:
    struct CtxDeleter {
        void operator()(EVP_MD_CTX* ctx) const { EVP_MD_CTX_free(ctx); }
    };

    std::unique_ptr<EVP_MD_CTX, CtxDeleter> _ctx;
    unsigned char _hash[kDigestLength] = {};
    std::string _hex;
};

} // namespace starrocks
