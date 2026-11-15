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

package com.starrocks.http;

import com.starrocks.common.Config;
import com.starrocks.http.rest.HttpSSLContextLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

public class HttpsListenerSetupTest {

    private boolean originalEnableHttps;
    private String originalKeystoreLocation;

    @BeforeEach
    public void setUp() throws Exception {
        originalEnableHttps = Config.enable_https;
        originalKeystoreLocation = Config.ssl_keystore_location;
        resetServerTlsContext();
    }

    @AfterEach
    public void tearDown() throws Exception {
        Config.enable_https = originalEnableHttps;
        Config.ssl_keystore_location = originalKeystoreLocation;
        resetServerTlsContext();
    }

    private static void resetServerTlsContext() throws Exception {
        Field initialized = HttpSSLContextLoader.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        initialized.set(null, false);
        Field sslContext = HttpSSLContextLoader.class.getDeclaredField("sslContext");
        sslContext.setAccessible(true);
        sslContext.set(null, null);
    }

    @Test
    public void testHttpsListenerRefusesToSetUpWithoutKeystore() {
        // It used to come up and serve plain HTTP on the HTTPS port.
        Config.enable_https = true;
        Config.ssl_keystore_location = "";

        HttpServer httpsServer = new HttpServer(0, true);
        IllegalArgException e = Assertions.assertThrows(IllegalArgException.class, httpsServer::setup);
        Assertions.assertTrue(e.getMessage().contains("ssl_keystore_location must be set"), e.getMessage());
    }
}
