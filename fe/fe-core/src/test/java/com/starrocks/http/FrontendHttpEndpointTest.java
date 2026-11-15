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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FrontendHttpEndpointTest {

    private boolean originalEnableHttp;
    private boolean originalEnableHttps;
    private int originalHttpPort;
    private int originalHttpsPort;

    @BeforeEach
    public void setUp() {
        originalEnableHttp = Config.enable_http;
        originalEnableHttps = Config.enable_https;
        originalHttpPort = Config.http_port;
        originalHttpsPort = Config.https_port;
        Config.http_port = 18030;
        Config.https_port = 18443;
    }

    @AfterEach
    public void tearDown() {
        Config.enable_http = originalEnableHttp;
        Config.enable_https = originalEnableHttps;
        Config.http_port = originalHttpPort;
        Config.https_port = originalHttpsPort;
    }

    @Test
    public void testPlainListenerOnly() {
        Config.enable_http = true;
        Config.enable_https = false;

        Assertions.assertFalse(FrontendHttpEndpoint.useHttps());
        Assertions.assertEquals("http", FrontendHttpEndpoint.getScheme());
        Assertions.assertEquals(18030, FrontendHttpEndpoint.getPort());
        Assertions.assertEquals("http://fe1.example.com:18030", FrontendHttpEndpoint.getUrlPrefix("fe1.example.com"));
        Assertions.assertEquals(18030, FrontendHttpEndpoint.getAnnouncedPort());
    }

    @Test
    public void testBothListenersKeepPlainHttpBetweenFrontends() {
        Config.enable_http = true;
        Config.enable_https = true;

        Assertions.assertFalse(FrontendHttpEndpoint.useHttps());
        Assertions.assertEquals("http://fe1.example.com:18030", FrontendHttpEndpoint.getUrlPrefix("fe1.example.com"));
        Assertions.assertEquals(18030, FrontendHttpEndpoint.getAnnouncedPort());
    }

    @Test
    public void testHttpsListenerOnly() {
        Config.enable_http = false;
        Config.enable_https = true;

        Assertions.assertTrue(FrontendHttpEndpoint.useHttps());
        Assertions.assertEquals("https", FrontendHttpEndpoint.getScheme());
        Assertions.assertEquals(18443, FrontendHttpEndpoint.getPort());
        Assertions.assertEquals("https://fe1.example.com:18443", FrontendHttpEndpoint.getUrlPrefix("fe1.example.com"));
        Assertions.assertEquals(18443, FrontendHttpEndpoint.getAnnouncedPort());
    }

    @Test
    public void testUrlPrefixWithIpv6HostAndGivenPort() {
        Config.enable_http = false;
        Config.enable_https = true;

        Assertions.assertEquals("https://[fe80::1]:18443", FrontendHttpEndpoint.getUrlPrefix("fe80::1"));
        Assertions.assertEquals("https://10.0.0.1:9443", FrontendHttpEndpoint.getUrlPrefix("10.0.0.1", 9443));
    }
}
