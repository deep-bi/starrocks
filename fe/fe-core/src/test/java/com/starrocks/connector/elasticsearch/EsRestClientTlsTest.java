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
package com.starrocks.connector.elasticsearch;

import com.starrocks.common.util.TlsTestSupport;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.Provider;
import java.security.Security;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsRestClientTlsTest {
    @TempDir
    static Path directory;
    private static TlsTestSupport fixtures;
    private static final Map<String, String> SAVED_PROPERTIES = new HashMap<>();

    @BeforeAll
    static void setup() throws Exception {
        for (String property : new String[] {"javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType",
                "javax.net.ssl.trustStorePassword"}) {
            SAVED_PROPERTIES.put(property, System.getProperty(property));
        }
        fixtures = new TlsTestSupport(directory);
        resetClient();
    }

    @AfterAll
    static void restoreProperties() {
        SAVED_PROPERTIES.forEach((property, value) -> {
            if (value == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, value);
            }
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        resetClient();
    }

    private static OkHttpClient cachedClient() throws Exception {
        Field field = EsRestClient.class.getDeclaredField("sslNetworkClient");
        field.setAccessible(true);
        return (OkHttpClient) field.get(null);
    }

    private static void resetClient() throws Exception {
        OkHttpClient client = cachedClient();
        if (client != null) {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
        Field field = EsRestClient.class.getDeclaredField("sslNetworkClient");
        field.setAccessible(true);
        field.set(null, null);
    }

    private static HttpsServer server(String certificate) throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(fixtures.peerContext(certificate)));
        server.createContext("/", exchange -> {
            byte[] response = "{\"index\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void trustedHttpsUsesBouncyCastleWithEitherSslFlag() throws Exception {
        HttpsServer server = server("valid");
        try {
            for (boolean sslEnabled : new boolean[] {true, false}) {
                EsRestClient client = new EsRestClient(
                        new String[] {"https://localhost:" + server.getAddress().getPort()}, "", "", sslEnabled);
                assertEquals("{\"index\":{}}", client.getMapping("index"));
            }
            assertTrue(cachedClient().sslSocketFactory().getClass().getName().startsWith("org.bouncycastle."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsExpiredUntrustedAndWrongHostCertificates() throws Exception {
        for (String certificate : new String[] {"expired", "untrusted", "wronghost"}) {
            HttpsServer server = server(certificate);
            try {
                EsRestClient client = new EsRestClient(
                        new String[] {"https://localhost:" + server.getAddress().getPort()}, "", "", true);
                assertThrows(StarRocksConnectorException.class, () -> client.getMapping("index"), certificate);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void allowsConfiguredPlaintextButRejectsHttpsDowngrade() throws Exception {
        AtomicInteger plaintextRequests = new AtomicInteger();
        HttpServer plaintext = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        plaintext.createContext("/", exchange -> {
            plaintextRequests.incrementAndGet();
            byte[] response = "{\"index\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        plaintext.start();
        String insecureUrl = "http://localhost:" + plaintext.getAddress().getPort();
        HttpsServer redirect = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        redirect.setHttpsConfigurator(new HttpsConfigurator(fixtures.peerContext("valid")));
        redirect.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", insecureUrl + "/index/_mapping");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        redirect.start();
        try {
            assertEquals("{\"index\":{}}", new EsRestClient(new String[] {insecureUrl}, "", "").getMapping("index"));
            EsRestClient client = new EsRestClient(
                    new String[] {"https://localhost:" + redirect.getAddress().getPort()}, "", "", true);
            assertThrows(StarRocksConnectorException.class, () -> client.getMapping("index"));
            assertEquals(1, plaintextRequests.get());
        } finally {
            redirect.stop(0);
            plaintext.stop(0);
        }
    }

    @Test
    void invalidTrustStoreFailsBeforeSendingRequest() {
        System.setProperty("javax.net.ssl.trustStore", directory.resolve("missing.pem").toString());
        try {
            EsRestClient client = new EsRestClient(new String[] {"https://localhost:1"}, "", "", true);
            StarRocksConnectorException failure = assertThrows(StarRocksConnectorException.class,
                    () -> client.getMapping("index"));
            assertEquals("Failed to configure Elasticsearch TLS", failure.getMessage());
        } finally {
            System.setProperty("javax.net.ssl.trustStore", fixtures.pemTrust.toString());
        }
    }

    @Test
    void missingProvidersFailWithoutCreatingFallbackClient() throws Exception {
        for (String name : new String[] {"BCFIPS", "BCJSSE"}) {
            Provider provider = Security.getProvider(name);
            Security.removeProvider(name);
            try {
                EsRestClient client = new EsRestClient(new String[] {"https://localhost:1"}, "", "", true);
                StarRocksConnectorException failure = assertThrows(StarRocksConnectorException.class,
                        () -> client.getMapping("index"));
                assertEquals("Failed to configure Elasticsearch TLS", failure.getMessage());
                assertTrue(failure.getCause().getMessage().contains(name));
                assertNull(cachedClient());
            } finally {
                Security.addProvider(provider);
            }
        }
    }
}
