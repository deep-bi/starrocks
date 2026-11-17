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
package com.starrocks.authorization.ranger;

import org.apache.hadoop.conf.Configuration;
import org.apache.ranger.authorization.hadoop.utils.RangerCredentialProvider;
import org.apache.ranger.plugin.util.RangerRESTClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangerCredentialProviderTest {
    private static final String SECRET = "synthetic-test-credential";

    @TempDir
    Path directory;

    @Test
    void absentOptionalCredentialsStillAllowClientInitialization() {
        RangerCredentialProvider provider = RangerCredentialProvider.getInstance();
        assertNull(provider.getCredentialString(null, "sslKeyStore"));
        assertNull(provider.getCredentialString("", "sslTrustStore"));
        RangerRESTClient client = new RangerRESTClient("https://localhost:6080", null, new Configuration(false));
        assertNotNull(client.getClient());
        client.getClient().destroy();
    }

    @Test
    void readableCredentialAndMissingAliasAreDistinguished() throws Exception {
        String path = legacyStore();
        RangerCredentialProvider provider = RangerCredentialProvider.getInstance();
        assertEquals(SECRET, provider.getCredentialString(path, "sslKeyStore"));
        assertThrows(IllegalStateException.class, () -> provider.getCredentialString(path, "missing"));
        assertThrows(IllegalStateException.class, () -> provider.getCredentialString(path, null));
    }

    @Test
    void unsupportedLegacyStoreStopsActualRangerClientAndRetries() throws Exception {
        String path = legacyStore();
        Provider sunJce = Security.getProvider("SunJCE");
        assertNotNull(sunJce);
        int position = Arrays.asList(Security.getProviders()).indexOf(sunJce) + 1;
        Security.removeProvider(sunJce.getName());
        try {
            // The shipped FIPS provider list excludes SunJCE/JCEKS. The existing file
            // must fail before Ranger substitutes default trust or omits its client key.
            assertThrows(java.security.KeyStoreException.class, () -> KeyStore.getInstance("JCEKS"));
            for (String store : new String[] {"keystore", "truststore"}) {
                RangerRESTClient client = configuredClient(store, path);
                for (int attempt = 0; attempt < 2; attempt++) {
                    IllegalStateException failure = assertThrows(IllegalStateException.class, client::getClient);
                    assertEquals("Cannot load configured Ranger credential store", failure.getMessage());
                    assertTrue(failure.getCause() instanceof java.io.IOException);
                }
            }
        } finally {
            Security.insertProviderAt(sunJce, position);
        }
    }

    @Test
    void corruptConfiguredStoreStopsActualRangerClient() throws Exception {
        Path store = directory.resolve("corrupt.jceks");
        Files.writeString(store, "not a credential store");
        RangerRESTClient client = configuredClient("truststore", providerPath(store));
        assertThrows(IllegalStateException.class, client::getClient);
    }

    private String legacyStore() throws Exception {
        Path path = directory.resolve("legacy.jceks");
        char[] password = "none".toCharArray(); // Hadoop's legacy credential-store default, fixture only.
        KeyStore store = KeyStore.getInstance("JCEKS", "SunJCE");
        store.load(null, password);
        store.setKeyEntry("sslKeyStore", new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "AES"),
                password, null);
        try (OutputStream output = Files.newOutputStream(path)) {
            store.store(output, password);
        }
        return providerPath(path);
    }

    private RangerRESTClient configuredClient(String store, String providerPath) throws Exception {
        Path xml = directory.resolve(store + ".xml");
        Configuration ssl = new Configuration(false);
        ssl.set("xasecure.policymgr.clientssl." + store + ".credential.file", providerPath);
        try (OutputStream output = Files.newOutputStream(xml)) {
            ssl.writeXml(output);
        }
        return new RangerRESTClient("https://localhost:6080", xml.toString(), new Configuration(false));
    }

    private static String providerPath(Path path) {
        return "jceks://file" + path.toAbsolutePath();
    }
}
