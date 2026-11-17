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

package com.starrocks.mysql.ssl;

import com.google.common.base.Strings;
import com.starrocks.common.Config;
import com.starrocks.common.util.CryptoUtils;
import com.starrocks.common.util.TlsUtils;
import com.starrocks.http.SslUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;

public class SSLContextLoader {
    private static final Logger LOG = LogManager.getLogger(SSLContextLoader.class);

    private static SSLContext sslContext;
    private static volatile String[] filteredCiphers;

    public static void load() throws Exception {
        sslContext = null;
        filteredCiphers = null;
        if (Config.ssl_force_secure_transport && Strings.isNullOrEmpty(Config.ssl_keystore_location)) {
            throw new IllegalArgumentException("MySQL secure transport requires ssl_keystore_location");
        }
        if (!Strings.isNullOrEmpty(Config.ssl_keystore_location)) {
            SSLContext candidate = createSSLContext();
            filteredCiphers = filterCiphers(candidate);
            sslContext = candidate;
        }
    }

    public static SSLContext getSslContext() {
        return sslContext;
    }

    public static SSLEngine newServerEngine() {
        SSLEngine engine = sslContext.createSSLEngine();
        SSLParameters parameters = sslContext.getDefaultSSLParameters();
        parameters.setCipherSuites(filteredCiphers);
        engine.setSSLParameters(parameters);
        engine.setEnabledProtocols(new String[] {"TLSv1.2", "TLSv1.3"});
        return engine;
    }

    private static SSLContext createSSLContext() throws Exception {
        KeyManagerFactory kmf = TlsUtils.keyManagers(Config.ssl_keystore_location, Config.ssl_keystore_type,
                Config.ssl_keystore_password, Config.ssl_key_password);
        SSLContext sslContext = CryptoUtils.sslContext("TLSv1.2");
        TrustManager[] trustManagers = null;
        if (!Strings.isNullOrEmpty(Config.ssl_truststore_location)) {
            trustManagers = createTrustManagers(Config.ssl_truststore_location, Config.ssl_truststore_password);
        }
        if (trustManagers == null) {
            trustManagers = TlsUtils.trustManagers(null).getTrustManagers();
        }
        sslContext.init(kmf.getKeyManagers(), trustManagers, CryptoUtils.secureRandom());

        return sslContext;
    }

    private static String[] filterCiphers(SSLContext context) {
        String[] supportedCiphers = context.getDefaultSSLParameters().getCipherSuites();
        return SslUtil.filterCipherSuites(supportedCiphers);
    }

    private static TrustManager[] createTrustManagers(String filepath, String keystorePassword) throws Exception {
        return TlsUtils.trustManagers(TlsUtils.trustStore(filepath, Config.ssl_truststore_type, keystorePassword))
                .getTrustManagers();
    }
}
