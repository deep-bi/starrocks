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

#include "util/openssl_errors.h"

#include <openssl/err.h>

#include <sstream>

namespace starrocks {

// copy from kudu/src/kudu/util/env_posix.cc
// Copyright (c) 2011 The LevelDB Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file. See the AUTHORS file for names of contributors.

std::string get_openssl_errors() {
    std::ostringstream serr;
    unsigned long l;
    int line, flags;
    const char *file, *data;
    char buf[256];
#if OPENSSL_VERSION_NUMBER >= 0x30000000L
    const char* func;
    while ((l = ERR_get_error_all(&file, &line, &func, &data, &flags)) != 0) {
#else
    while ((l = ERR_get_error_line_data(&file, &line, &data, &flags)) != 0) {
#endif
        ERR_error_string_n(l, buf, sizeof(buf));
        serr << " " << buf << ":" << file << ":" << line << ((flags & ERR_TXT_STRING) ? data : "");
    }
    return serr.str();
}

} // namespace starrocks
