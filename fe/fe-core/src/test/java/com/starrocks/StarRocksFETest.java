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
package com.starrocks;

import com.starrocks.common.CommandLineOptions;
import com.starrocks.common.Config;
import com.starrocks.http.HttpServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class StarRocksFETest {

    @Test
    public void testParseArgs() {
        CommandLineOptions options = StarRocksFE.parseArgs(new String[] {"-ht", "IP"});
        Assertions.assertEquals("IP", options.getHostType());
        options = StarRocksFE.parseArgs(new String[] {"--host_type", "FQDN"});
        Assertions.assertEquals("FQDN", options.getHostType());
        options = StarRocksFE.parseArgs(new String[] {"--cluster_snapshot"});
        Assertions.assertTrue(options.isStartFromSnapshot());
        options = StarRocksFE.parseArgs(new String[] {"-rs"});
        Assertions.assertTrue(options.isStartFromSnapshot());
        options = StarRocksFE.parseArgs(new String[] {"--version"});
        Assertions.assertTrue(options.isVersion());
        options = StarRocksFE.parseArgs(new String[] {"-v"});
        Assertions.assertTrue(options.isVersion());
        options = StarRocksFE.parseArgs(new String[] {"--helper", "192.168.3.1:9010"});
        Assertions.assertEquals("192.168.3.1:9010", options.getHelpers());
        options = StarRocksFE.parseArgs(new String[] {"-h", "192.168.3.1:9010"});
        Assertions.assertEquals("192.168.3.1:9010", options.getHelpers());
        options = StarRocksFE.parseArgs(new String[] {"-b", "-l"});
        Assertions.assertNotNull(options.getBdbToolOpts());
        options = StarRocksFE.parseArgs(new String[] {"-bdb", "-l"});
        Assertions.assertNotNull(options.getBdbToolOpts());
    }

    @Test
    public void testCreateHttpServersFollowsListenerSwitches() {
        boolean originalEnableHttp = Config.enable_http;
        boolean originalEnableHttps = Config.enable_https;
        try {
            Config.enable_http = true;
            Config.enable_https = false;
            List<HttpServer> servers = StarRocksFE.createHttpServers();
            Assertions.assertEquals(1, servers.size());
            Assertions.assertFalse(servers.get(0).isHttps());
            Assertions.assertEquals(Config.http_port, servers.get(0).getPort());

            Config.enable_https = true;
            servers = StarRocksFE.createHttpServers();
            Assertions.assertEquals(2, servers.size());
            Assertions.assertFalse(servers.get(0).isHttps());
            Assertions.assertTrue(servers.get(1).isHttps());
            Assertions.assertEquals(Config.https_port, servers.get(1).getPort());

            // With enable_http off, the FE serves the HTTPS listener only.
            Config.enable_http = false;
            servers = StarRocksFE.createHttpServers();
            Assertions.assertEquals(1, servers.size());
            Assertions.assertTrue(servers.get(0).isHttps());
            Assertions.assertEquals(Config.https_port, servers.get(0).getPort());
        } finally {
            Config.enable_http = originalEnableHttp;
            Config.enable_https = originalEnableHttps;
        }
    }
}
