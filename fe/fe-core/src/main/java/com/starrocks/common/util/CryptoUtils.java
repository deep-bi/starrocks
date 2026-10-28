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
package com.starrocks.common.util;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Consumes the providers installed at JVM startup. Never installs providers or falls back. */
public final class CryptoUtils {
    private CryptoUtils() {
    }

    public static Provider fipsProvider() {
        return requireProvider("BCFIPS");
    }

    public static Provider jsseProvider() {
        return requireProvider("BCJSSE");
    }

    private static Provider requireProvider(String name) {
        Provider provider = Security.getProvider(name);
        // Passing null to some provider-object factories selects the default provider.
        if (provider == null) {
            throw new IllegalStateException("Required provider " + name + " was not installed at JVM startup");
        }
        return provider;
    }

    public static SecureRandom secureRandom() {
        try {
            return SecureRandom.getInstance("DEFAULT", fipsProvider());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("BCFIPS secure random is unavailable", e);
        }
    }

    public static MessageDigest messageDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm, fipsProvider());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Required digest is unavailable: " + algorithm, e);
        }
    }

    public static SSLContext sslContext(String protocol) throws GeneralSecurityException {
        return SSLContext.getInstance(protocol, jsseProvider());
    }

    public static KeyManagerFactory keyManagerFactory() throws GeneralSecurityException {
        return KeyManagerFactory.getInstance("PKIX", jsseProvider());
    }

    public static TrustManagerFactory trustManagerFactory() throws GeneralSecurityException {
        return TrustManagerFactory.getInstance("PKIX", jsseProvider());
    }
}
