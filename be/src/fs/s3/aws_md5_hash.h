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

#include <aws/core/utils/crypto/Hash.h>

#include <memory>

#include "util/md5.h"

namespace starrocks::s3 {

// MD5 for the AWS SDK, built on Md5Digest. The SDK's own OpenSSL MD5 calls EVP_md5() under the
// default properties, which FIPS mode refuses, and it ignores the error, so requests that need
// Content-MD5 (DeleteObjects) would carry a wrong checksum. Install it with
// SDKOptions::cryptoOptions.md5Factory_create_fn; the result is the same in both modes.
class AwsMd5Hash final : public Aws::Utils::Crypto::Hash {
public:
    AwsMd5Hash() : _digest(std::make_unique<Md5Digest>()) {}

    Aws::Utils::Crypto::HashResult Calculate(const Aws::String& str) override;
    Aws::Utils::Crypto::HashResult Calculate(Aws::IStream& stream) override;
    void Update(unsigned char* buffer, size_t bufferSize) override;
    Aws::Utils::Crypto::HashResult GetHash() override;

private:
    std::unique_ptr<Md5Digest> _digest;
};

class AwsMd5HashFactory final : public Aws::Utils::Crypto::HashFactory {
public:
    std::shared_ptr<Aws::Utils::Crypto::Hash> CreateImplementation() const override {
        return std::make_shared<AwsMd5Hash>();
    }
};

} // namespace starrocks::s3
