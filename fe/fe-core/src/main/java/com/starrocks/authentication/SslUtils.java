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

// This file is based on code available under the Apache license here:
//   https://github.com/trinodb/trino/blob/master/lib/trino-plugin-toolkit/src/main/java/io/trino/plugin/base/ssl/SslUtils.java
package com.starrocks.authentication;

import com.starrocks.common.util.CryptoUtils;
import com.starrocks.common.util.TlsUtils;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Optional;
import javax.net.ssl.SSLContext;

public final class SslUtils {
    private SslUtils() {
    }

    public static SSLContext createSSLContext(
            Optional<File> trustStorePath,
            Optional<String> trustStorePassword,
            String trustStoreType)
            throws GeneralSecurityException, IOException {
        KeyStore trust = trustStorePath.isPresent()
                ? TlsUtils.trustStore(trustStorePath.get().getPath(), trustStoreType, trustStorePassword.orElse(null))
                : null;
        SSLContext context = CryptoUtils.sslContext("TLS");
        context.init(null, TlsUtils.trustManagers(trust).getTrustManagers(), CryptoUtils.secureRandom());
        return context;
    }
}
