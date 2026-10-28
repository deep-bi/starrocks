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
package com.starrocks.authentication;

import com.starrocks.common.util.TlsTestSupport;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.naming.directory.DirContext;
import javax.net.ssl.SSLContext;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LdapTlsTest {
    @TempDir
    static Path directory;
    private static TlsTestSupport fixtures;
    private static final String DN = "cn=bind,dc=test";

    @BeforeAll
    static void setup() throws Exception {
        fixtures = new TlsTestSupport(directory);
    }

    private LDAPAuthProvider login(int port, boolean secure) {
        return new LDAPAuthProvider("localhost", port, secure, fixtures.trust.toString(), fixtures.password,
                DN, fixtures.password, "dc=test", "uid", DN, "", LdapGroupSource.GROUP_PROVIDER, "memberOf", fixtures.type);
    }

    private LDAPGroupProvider groups(int port, boolean secure, String scheme) {
        Map<String, String> properties = new HashMap<>();
        properties.put(LDAPGroupProvider.LDAP_LDAP_CONN_URL, scheme + "://localhost:" + port);
        properties.put(LDAPGroupProvider.LDAP_SSL_CONN_ALLOW_INSECURE, Boolean.toString(!secure));
        properties.put(LDAPGroupProvider.LDAP_SSL_CONN_TRUST_STORE_PATH, fixtures.trust.toString());
        properties.put(LDAPGroupProvider.LDAP_SSL_CONN_TRUST_STORE_PWD, fixtures.password);
        properties.put(LDAPGroupProvider.LDAP_SSL_CONN_TRUST_STORE_TYPE, fixtures.type);
        properties.put(LDAPGroupProvider.LDAP_PROP_CONN_TIMEOUT_MS_KEY, "2000");
        properties.put(LDAPGroupProvider.LDAP_PROP_CONN_READ_TIMEOUT_MS_KEY, "2000");
        return new LDAPGroupProvider("test", properties);
    }

    @Test
    void bothLdapPathsVerifyTrustExpiryAndHostname() throws Exception {
        for (String certificate : new String[] {"valid", "expired", "untrusted", "wronghost"}) {
            SSLContext context = fixtures.peerContext(certificate);
            InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=test");
            config.setListenerConfigs(InMemoryListenerConfig.createLDAPSConfig("ldaps", InetAddress.getLoopbackAddress(),
                    0, context.getServerSocketFactory(), context.getSocketFactory()));
            config.addAdditionalBindCredentials(DN, fixtures.password);
            try (InMemoryDirectoryServer server = new InMemoryDirectoryServer(config)) {
                server.startListening();
                LDAPAuthProvider login = login(server.getListenPort(), true);
                LDAPGroupProvider groups = groups(server.getListenPort(), true, "ldaps");
                if (certificate.equals("valid")) {
                    login.checkPassword(DN, fixtures.password);
                    DirContext connection = groups.createDirContextOnConnection(DN, fixtures.password);
                    connection.close();
                } else {
                    assertThrows(Exception.class, () -> login.checkPassword(DN, fixtures.password), certificate);
                    assertThrows(Exception.class, () -> groups.createDirContextOnConnection(DN, fixtures.password), certificate);
                }
            }
        }
    }

    @Test
    void configuredPlainLdapRemainsAvailable() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=test");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", InetAddress.getLoopbackAddress(),
                0, null));
        config.addAdditionalBindCredentials(DN, fixtures.password);
        try (InMemoryDirectoryServer server = new InMemoryDirectoryServer(config)) {
            server.startListening();
            login(server.getListenPort(), false).checkPassword(DN, fixtures.password);
            DirContext connection = groups(server.getListenPort(), false, "ldap")
                    .createDirContextOnConnection(DN, fixtures.password);
            connection.close();
        }
    }
}
