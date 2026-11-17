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

#include <aws/core/utils/Array.h>
#include <aws/core/utils/Outcome.h>
#include <aws/core/utils/crypto/HashResult.h>

namespace starrocks::s3 {

using Aws::Utils::ByteBuffer;
using Aws::Utils::Crypto::HashResult;

static HashResult to_result(const Md5Digest& digest) {
    return HashResult(ByteBuffer(digest.binary(), Md5Digest::kDigestLength));
}

HashResult AwsMd5Hash::Calculate(const Aws::String& str) {
    Md5Digest digest;
    digest.update(str.data(), str.size());
    digest.digest();
    return to_result(digest);
}

// same stream handling as the SDK's MD5OpenSSLImpl: hash from the start of the stream, then
// restore the caller's read position
HashResult AwsMd5Hash::Calculate(Aws::IStream& stream) {
    Md5Digest digest;
    auto current_pos = stream.tellg();
    if (current_pos == -1) {
        current_pos = 0;
        stream.clear();
    }
    stream.seekg(0, stream.beg);

    char buffer[Aws::Utils::Crypto::Hash::INTERNAL_HASH_STREAM_BUFFER_SIZE];
    while (stream.good()) {
        stream.read(buffer, sizeof(buffer));
        auto bytes_read = stream.gcount();
        if (bytes_read > 0) {
            digest.update(buffer, static_cast<size_t>(bytes_read));
        }
    }

    stream.clear();
    stream.seekg(current_pos, stream.beg);
    digest.digest();
    return to_result(digest);
}

void AwsMd5Hash::Update(unsigned char* buffer, size_t bufferSize) {
    _digest->update(buffer, bufferSize);
}

// the SDK leaves its context finalised here; starting a fresh digest makes reuse safe as well
HashResult AwsMd5Hash::GetHash() {
    _digest->digest();
    auto result = to_result(*_digest);
    _digest = std::make_unique<Md5Digest>();
    return result;
}

} // namespace starrocks::s3
