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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class TlsUtils {
    private TlsUtils() {
    }

    public static KeyManagerFactory keyManagers(String location, String type, String storePassword, String keyPassword)
            throws GeneralSecurityException, IOException {
        if (!"BCFKS".equalsIgnoreCase(type)) {
            throw new GeneralSecurityException("FIPS private keys require ssl_keystore_type=BCFKS");
        }
        char[] password = null;
        char[] privatePassword = null;
        try {
            password = password(storePassword);
            privatePassword = password(keyPassword);
            KeyStore store = keyStore(type);
            try (InputStream in = new FileInputStream(location)) {
                store.load(in, password);
            }
            boolean hasKey = false;
            for (String alias : Collections.list(store.aliases())) {
                if (store.isKeyEntry(alias)) {
                    if (!(store.getKey(alias, privatePassword) instanceof PrivateKey)) {
                        throw new GeneralSecurityException("TLS keystore contains a non-private key");
                    }
                    Certificate[] chain = store.getCertificateChain(alias);
                    if (chain == null || chain.length == 0) {
                        throw new GeneralSecurityException("TLS private key has no certificate chain");
                    }
                    for (Certificate certificate : chain) {
                        ((X509Certificate) certificate).checkValidity();
                    }
                    hasKey = true;
                }
            }
            if (!hasKey) {
                throw new GeneralSecurityException("TLS keystore contains no private key");
            }
            KeyManagerFactory factory = CryptoUtils.keyManagerFactory();
            factory.init(store, privatePassword);
            return factory;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (privatePassword != null) {
                Arrays.fill(privatePassword, '\0');
            }
        }
    }

    private static char[] password(String value) throws GeneralSecurityException {
        if (value == null || value.isEmpty()) {
            throw new GeneralSecurityException("TLS store and private key passwords must not be empty");
        }
        return value.toCharArray();
    }

    private static KeyStore keyStore(String type) throws GeneralSecurityException {
        if (type == null || type.isBlank()) {
            throw new GeneralSecurityException("TLS store type must be configured explicitly");
        }
        return KeyStore.getInstance(type, CryptoUtils.fipsProvider());
    }

    public static KeyStore trustStore(String location, String type, String storePassword)
            throws GeneralSecurityException, IOException {
        String format = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (!Arrays.asList("BCFKS", "JKS", "PEM").contains(format)) {
            throw new GeneralSecurityException("FIPS trust stores must be certificate-only BCFKS, JKS or PEM");
        }
        KeyStore store;
        if ("PEM".equals(format)) {
            store = keyStore("BCFKS");
            store.load(null, null);
            CertificateFactory factory = CertificateFactory.getInstance("X.509", CryptoUtils.fipsProvider());
            Collection<? extends Certificate> certificates;
            try (InputStream in = new FileInputStream(location)) {
                certificates = factory.generateCertificates(in);
            }
            if (certificates.isEmpty()) {
                throw new GeneralSecurityException("PEM trust store contains no certificates");
            }
            int index = 0;
            for (Certificate certificate : certificates) {
                store.setCertificateEntry(Integer.toString(index++), certificate);
            }
        } else {
            store = keyStore(type);
            char[] password = password(storePassword);
            try (InputStream in = new FileInputStream(location)) {
                store.load(in, password);
            } finally {
                if (password != null) {
                    Arrays.fill(password, '\0');
                }
            }
        }
        for (String alias : Collections.list(store.aliases())) {
            if (!store.isCertificateEntry(alias)) {
                throw new GeneralSecurityException("FIPS trust store must contain only certificates");
            }
        }
        return store;
    }

    public static TrustManagerFactory trustManagers(KeyStore store) throws GeneralSecurityException, IOException {
        TrustManagerFactory factory = CryptoUtils.trustManagerFactory();
        factory.init(store == null ? defaultTrustStore() : store);
        return factory;
    }

    private static KeyStore defaultTrustStore() throws GeneralSecurityException, IOException {
        String location = System.getProperty("javax.net.ssl.trustStore");
        String password = System.getProperty("javax.net.ssl.trustStorePassword");
        if (location == null) {
            Path security = Path.of(System.getProperty("java.home"), "lib", "security");
            Path jssecacerts = security.resolve("jssecacerts");
            location = (Files.exists(jssecacerts) ? jssecacerts : security.resolve("cacerts")).toString();
            password = password == null ? "changeit" : password;
        }
        return trustStore(location, System.getProperty("javax.net.ssl.trustStoreType", "JKS"), password);
    }

    public static SSLContext clientContext() throws GeneralSecurityException, IOException {
        SSLContext context = CryptoUtils.sslContext("TLS");
        context.init(null, trustManagers(null).getTrustManagers(), CryptoUtils.secureRandom());
        return context;
    }
}
