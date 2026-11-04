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

#include "fs/fs_util.h"

#include <fmt/format.h>

#include <algorithm>
#include <set>

#include "util/md5.h"
#include "util/sha.h"

namespace starrocks::fs {

Status list_dirs_files(FileSystem* fs, const std::string& path, std::set<std::string>* dirs,
                       std::set<std::string>* files) {
    Status st;
    RETURN_IF_ERROR(fs->iterate_dir(path, [&](std::string_view name) {
        auto full_path = fmt::format("{}/{}", path, name);
        auto is_dir = fs->is_directory(full_path);
        if (!is_dir.ok()) {
            st = is_dir.status();
            return false;
        }
        if (*is_dir && dirs != nullptr) {
            dirs->emplace(name);
        } else if (!*is_dir && files != nullptr) {
            files->emplace(name);
        }
        return true;
    }));
    RETURN_IF_ERROR(st);
    return Status::OK();
}

Status list_dirs_files(const std::string& path, std::set<std::string>* dirs, std::set<std::string>* files) {
    ASSIGN_OR_RETURN(auto fs, FileSystem::CreateSharedFromString(path));
    return list_dirs_files(fs.get(), path, dirs, files);
}

// reads in fixed-size chunks so that checksumming a large segment file doesn't need its size in memory
template <typename Digest>
static StatusOr<std::string> file_digest(const std::string& path) {
    static constexpr int64_t kChunkSize = 1 << 20;
    ASSIGN_OR_RETURN(auto fs, FileSystem::CreateSharedFromString(path));
    ASSIGN_OR_RETURN(auto file, fs->new_random_access_file(path));
    ASSIGN_OR_RETURN(auto length, file->get_size());
    std::unique_ptr<unsigned char[]> buf(new (std::nothrow) unsigned char[std::min(length, kChunkSize)]);
    if (UNLIKELY(buf == nullptr)) {
        return Status::MemoryAllocFailed(fmt::format("alloca size={}", std::min(length, kChunkSize)));
    }
    Digest digest;
    for (int64_t offset = 0; offset < length; offset += kChunkSize) {
        const int64_t n = std::min(kChunkSize, length - offset);
        RETURN_IF_ERROR(file->read_at_fully(offset, buf.get(), n));
        digest.update(buf.get(), n);
    }
    digest.digest();
    return digest.hex();
}

StatusOr<std::string> md5sum(const std::string& path) {
    return file_digest<Md5Digest>(path);
}

StatusOr<std::string> sha256sum(const std::string& path) {
    return file_digest<SHA256Digest>(path);
}

} // namespace starrocks::fs
