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

package com.starrocks.http.meta;

import com.starrocks.common.Config;
import com.starrocks.ha.FrontendNodeType;
import com.starrocks.server.NodeMgr;
import com.starrocks.system.Frontend;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MetaServicePutActionTest {

    private boolean originalEnableHttp;

    @BeforeEach
    public void setUp() {
        originalEnableHttp = Config.enable_http;
        new MockUp<NodeMgr>() {
            @Mock
            public Frontend getFeByHost(String ipOrFqdn) {
                if ("10.0.0.1".equals(ipOrFqdn)) {
                    return new Frontend(FrontendNodeType.LEADER, "fe1", "fe1.example.com", 9010);
                }
                return null;
            }
        };
    }

    @AfterEach
    public void tearDown() {
        Config.enable_http = originalEnableHttp;
    }

    @Test
    public void testImageSourceHostOverHttpKeepsClientAddress() {
        Config.enable_http = true;
        Assertions.assertEquals("10.0.0.1", MetaService.PutAction.imageSourceHost("10.0.0.1"));
    }

    @Test
    public void testImageSourceHostOverHttpsUsesRegisteredName() {
        // The certificate of the sending FE is issued for the name it is registered with, not for its address.
        Config.enable_http = false;
        Assertions.assertEquals("fe1.example.com", MetaService.PutAction.imageSourceHost("10.0.0.1"));
    }

    @Test
    public void testImageSourceHostOverHttpsOfUnknownFrontend() {
        Config.enable_http = false;
        Assertions.assertEquals("10.0.0.2", MetaService.PutAction.imageSourceHost("10.0.0.2"));
    }
}
