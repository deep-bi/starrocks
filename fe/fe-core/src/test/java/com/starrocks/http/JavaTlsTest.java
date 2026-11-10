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

import com.starrocks.authentication.JwkMgr;
import com.starrocks.common.Config;
import com.starrocks.common.util.CryptoUtils;
import com.starrocks.common.util.TlsTestSupport;
import com.starrocks.common.util.TlsUtils;
import com.starrocks.http.rest.HttpSSLContextLoader;
import com.starrocks.mysql.ssl.SSLContextLoader;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaTlsTest {
    @TempDir
    static Path directory;
    private static TlsTestSupport fixtures;
    private static final Map<String, String> SAVED_PROPERTIES = new HashMap<>();

    @BeforeAll
    static void setupProviders() throws Exception {
        for (String property : new String[] {"javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType",
                "javax.net.ssl.trustStorePassword"}) {
            SAVED_PROPERTIES.put(property, System.getProperty(property));
        }
        fixtures = new TlsTestSupport(directory);
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

    @BeforeEach
    void configureStores() throws Exception {
        Config.enable_https = true;
        Config.ssl_force_secure_transport = false;
        Config.ssl_keystore_type = fixtures.type;
        Config.ssl_keystore_location = fixtures.serverStore("valid").toString();
        Config.ssl_keystore_password = fixtures.password;
        Config.ssl_key_password = fixtures.password;
        Config.ssl_truststore_type = fixtures.type;
        Config.ssl_truststore_location = fixtures.trust.toString();
        Config.ssl_truststore_password = fixtures.password;
        Config.ssl_cipher_whitelist = "";
        Config.ssl_cipher_blacklist = "";
        resetHttps();
    }

    private static void resetHttps() throws Exception {
        Field initialized = HttpSSLContextLoader.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        initialized.set(null, false);
    }

    @Test
    void bothProductionLoadersUseExpectedProviderAndHandshake() throws Exception {
        SSLContextLoader.load();
        SSLContext mysql = SSLContextLoader.getSslContext();
        JdkSslContext https = (JdkSslContext) HttpSSLContextLoader.getSslContext();
        String expected = "BCJSSE";
        assertEquals(expected, mysql.getProvider().getName());
        assertTrue(Arrays.asList(SSLContextLoader.newServerEngine().getEnabledProtocols()).contains("TLSv1.3"));
        assertEquals(expected, https.context().getProvider().getName());
        assertTrue(SSLContextLoader.newServerEngine().getClass().getName().startsWith("org.bouncycastle."));
        assertTrue(https.newEngine(UnpooledByteBufAllocator.DEFAULT)
                .getClass().getName().startsWith("org.bouncycastle."));
        for (SSLContext server : new SSLContext[] {mysql, https.context()}) {
            handshake(server);
        }
        assertEquals("BCJSSE", CryptoUtils.keyManagerFactory().getProvider().getName());
        assertEquals("BCJSSE", CryptoUtils.trustManagerFactory().getProvider().getName());
        assertTrue(Arrays.stream(SSLContextLoader.newServerEngine().getEnabledCipherSuites())
                .allMatch(c -> c.contains("_GCM_")));
    }

    private void handshake(SSLContext server) throws Exception {
        try (SSLServerSocket listener = (SSLServerSocket) server.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(10000);
            CompletableFuture<Integer> received = CompletableFuture.supplyAsync(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.setSoTimeout(10000);
                    return socket.getInputStream().read();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            SSLContext client = TlsUtils.clientContext();
            try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket("localhost", listener.getLocalPort())) {
                socket.setSoTimeout(10000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                socket.getOutputStream().write(42);
                socket.getOutputStream().flush();
            }
            assertEquals(42, received.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsWrongStorePasswordThroughBothLoaders() throws Exception {
        Config.ssl_keystore_password = "incorrect-store-password";
        assertBothReject();
    }

    @Test
    void rejectsWrongPrivateKeyPasswordThroughBothLoaders() throws Exception {
        Config.ssl_key_password = "incorrect-private-password";
        assertBothReject();
    }

    @Test
    void rejectsWrongTypeAndCorruptStoresThroughBothLoaders() throws Exception {
        Config.ssl_keystore_type = "not-a-store";
        assertBothReject();
        Config.ssl_keystore_type = fixtures.type;
        Path corrupt = directory.resolve("corrupt-store");
        Files.writeString(corrupt, "invalid store");
        Config.ssl_keystore_location = corrupt.toString();
        assertBothReject();
    }

    @Test
    void fipsRejectsJksPrivateKeysAndMissingProviders() throws Exception {
        Config.ssl_keystore_type = "JKS";
        Config.ssl_keystore_location = directory.resolve("valid-source.jks").toString();
        assertBothReject();
        configureStores();
        for (String name : new String[] {"BCJSSE", "BCFIPS"}) {
            Provider provider = Security.getProvider(name);
            Security.removeProvider(name);
            try {
                assertBothReject();
            } finally {
                Security.addProvider(provider);
            }
        }
    }

    @Test
    void fipsRejectsEmptyPrivateStoresExpiredKeysAndEmptyCipherPolicy() throws Exception {
        Config.ssl_keystore_location = fixtures.trust.toString();
        assertBothReject();
        Config.ssl_keystore_location = fixtures.serverStore("expired").toString();
        assertBothReject();
        configureStores();
        Config.ssl_cipher_whitelist = "TLS_RSA_WITH_NULL_SHA";
        assertBothReject();
    }

    @Test
    void separateTrustFormatsAndPasswords() throws Exception {
        KeyStore ca = fixtures.jks(directory.resolve("ca.jks"));
        KeyStore trust = KeyStore.getInstance("JKS", "SUN");
        trust.load(null, null);
        trust.setCertificateEntry("ca", ca.getCertificate("ca"));
        Path jks = directory.resolve("certificate-trust.jks");
        try (java.io.OutputStream out = Files.newOutputStream(jks)) {
            trust.store(out, fixtures.password.toCharArray());
        }
        assertNotNull(TlsUtils.trustStore(jks.toString(), "JKS", fixtures.password));
        assertNotNull(TlsUtils.trustStore(fixtures.pemTrust.toString(), "PEM", null));
        assertThrows(Exception.class, () -> TlsUtils.trustStore(jks.toString(), "JKS", "incorrect-password"));
        assertThrows(Exception.class, () -> TlsUtils.trustStore(fixtures.trust.toString(), fixtures.type, "incorrect-password"));
        assertThrows(Exception.class, () -> TlsUtils.trustStore(directory.resolve("valid-source.jks").toString(),
                "JKS", fixtures.password));
        assertThrows(Exception.class, () -> TlsUtils.trustStore(fixtures.trust.toString(), "PKCS12", fixtures.password));
    }

    private void assertBothReject() throws Exception {
        assertThrows(Exception.class, SSLContextLoader::load);
        assertNull(SSLContextLoader.getSslContext());
        resetHttps();
        assertThrows(Exception.class, HttpSSLContextLoader::getSslContext);
    }

    @Test
    void outboundHttpsRejectsExpiredUntrustedAndWrongHost() throws Exception {
        for (String certificate : new String[] {"valid", "expired", "untrusted", "wronghost"}) {
            HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(fixtures.peerContext(certificate)));
            server.createContext("/", exchange -> {
                byte[] body = "{\"keys\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            try {
                String url = "https://localhost:" + server.getAddress().getPort() + "/";
                if (certificate.equals("valid")) {
                    assertEquals("{\"keys\":[]}", HttpUtils.get(url, Map.of()));
                    assertEquals(0, new JwkMgr().getJwkSet(url).getKeys().size());
                } else {
                    assertThrows(HttpRequestException.class, () -> HttpUtils.get(url, Map.of()), certificate);
                    assertThrows(Exception.class, () -> new JwkMgr().getJwkSet(url), certificate);
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void jwksDistinguishesMalformedContentFromHttpFailures() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int status = exchange.getRequestURI().getPath().equals("/unavailable") ? 503 : 200;
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort();
            JwkMgr manager = new JwkMgr();
            assertThrows(ParseException.class, () -> manager.getJwkSet(url + "/malformed"));
            IOException failure = assertThrows(IOException.class, () -> manager.getJwkSet(url + "/unavailable"));
            assertTrue(failure.getCause() instanceof HttpRequestException);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void jwksUsesHttpProxyAndHonorsNonProxyHosts() throws Exception {
        AtomicInteger directRequests = new AtomicInteger();
        AtomicInteger proxyRequests = new AtomicInteger();
        byte[] body = "{\"keys\":[]}".getBytes(StandardCharsets.UTF_8);
        HttpServer origin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        origin.createContext("/", exchange -> {
            directRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        origin.start();
        String url = "http://localhost:" + origin.getAddress().getPort() + "/";
        HttpServer proxy = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        proxy.createContext("/", exchange -> {
            proxyRequests.incrementAndGet();
            if (exchange.getRequestURI().getPath().equals("/redirect")) {
                exchange.getResponseHeaders().add("Location", url);
                exchange.sendResponseHeaders(302, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        proxy.start();
        Map<String, String> saved = new HashMap<>();
        for (String name : new String[] {"http.proxyHost", "http.proxyPort", "http.nonProxyHosts"}) {
            saved.put(name, System.getProperty(name));
        }
        try {
            System.setProperty("http.proxyHost", "localhost");
            System.setProperty("http.proxyPort", Integer.toString(proxy.getAddress().getPort()));
            System.setProperty("http.nonProxyHosts", "");
            JwkMgr manager = new JwkMgr();
            assertEquals(0, manager.getJwkSet(url).getKeys().size());
            assertEquals(0, directRequests.get());
            assertEquals(1, proxyRequests.get());
            assertEquals("{\"keys\":[]}", HttpUtils.get(url, Map.of()));
            assertEquals(1, directRequests.get());
            assertEquals(1, proxyRequests.get());
            System.setProperty("http.nonProxyHosts", "localhost");
            assertEquals(0, manager.getJwkSet(url).getKeys().size());
            assertEquals(2, directRequests.get());
            assertEquals(1, proxyRequests.get());
            assertEquals(0, manager.getJwkSet("http://jwks.invalid/redirect").getKeys().size());
            assertEquals(3, directRequests.get());
            assertEquals(2, proxyRequests.get());
        } finally {
            saved.forEach((name, value) -> {
                if (value == null) {
                    System.clearProperty(name);
                } else {
                    System.setProperty(name, value);
                }
            });
            proxy.stop(0);
            origin.stop(0);
        }
    }

    @Test
    void jwksUsesHttpsProxyTunnel() throws Exception {
        SSLContext peer = fixtures.peerContext("valid");
        Map<String, String> saved = new HashMap<>();
        for (String name : new String[] {"https.proxyHost", "https.proxyPort", "http.nonProxyHosts"}) {
            saved.put(name, System.getProperty(name));
        }
        try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
                try (Socket transport = proxy.accept()) {
                    transport.setSoTimeout(10000);
                    BufferedReader connect = new BufferedReader(
                            new InputStreamReader(transport.getInputStream(), StandardCharsets.US_ASCII));
                    assertEquals("CONNECT localhost:1 HTTP/1.1", connect.readLine());
                    while (!connect.readLine().isEmpty()) {
                        // Consume the CONNECT headers before switching to TLS
                    }
                    transport.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    try (SSLSocket tls = (SSLSocket) peer.getSocketFactory().createSocket(transport, "localhost", 1, true)) {
                        tls.setUseClientMode(false);
                        tls.startHandshake();
                        BufferedReader request = new BufferedReader(
                                new InputStreamReader(tls.getInputStream(), StandardCharsets.US_ASCII));
                        assertEquals("GET /jwks HTTP/1.1", request.readLine());
                        while (!request.readLine().isEmpty()) {
                            // Consume the request headers before sending the JWKS response
                        }
                        tls.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n"
                                + "Connection: close\r\n\r\n{\"keys\":[]}").getBytes(StandardCharsets.US_ASCII));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            System.setProperty("https.proxyHost", "localhost");
            System.setProperty("https.proxyPort", Integer.toString(proxy.getLocalPort()));
            System.setProperty("http.nonProxyHosts", "");
            assertEquals(0, new JwkMgr().getJwkSet("https://localhost:1/jwks").getKeys().size());
            served.get(10, TimeUnit.SECONDS);
        } finally {
            saved.forEach((name, value) -> {
                if (value == null) {
                    System.clearProperty(name);
                } else {
                    System.setProperty(name, value);
                }
            });
        }
    }

    @Test
    void httpsMissingKeyStoreFailsInsteadOfReturningPlaintextContext() {
        Config.ssl_keystore_location = "";
        assertThrows(IllegalArgumentException.class, HttpSSLContextLoader::getSslContext);
    }

    @Test
    void requiredTlsRejectsMissingCredentials() throws Exception {
        Config.ssl_force_secure_transport = true;
        Config.ssl_keystore_location = "";
        assertBothReject();
    }

    @Test
    void optionalMysqlTlsDoesNotRequireCredentials() throws Exception {
        Config.ssl_keystore_location = "";
        SSLContextLoader.load();
        assertNull(SSLContextLoader.getSslContext());
    }

    @Test
    void fipsAllowsConfiguredHttpButRejectsHttpsDowngrade() throws Exception {
        AtomicInteger plaintextRequests = new AtomicInteger();
        HttpServer plaintext = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        plaintext.createContext("/", exchange -> {
            plaintextRequests.incrementAndGet();
            byte[] response = "{\"keys\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        plaintext.start();
        String insecureUrl = "http://localhost:" + plaintext.getAddress().getPort() + "/";
        HttpsServer redirect = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        redirect.setHttpsConfigurator(new HttpsConfigurator(fixtures.peerContext("valid")));
        redirect.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", insecureUrl);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        redirect.start();
        try {
            assertEquals("{\"keys\":[]}", HttpUtils.get(insecureUrl, Map.of()));
            assertEquals(0, new JwkMgr().getJwkSet(insecureUrl).getKeys().size());
            assertThrows(HttpRequestException.class,
                    () -> HttpUtils.get("https://localhost:" + redirect.getAddress().getPort() + "/", Map.of()));
            assertThrows(IOException.class,
                    () -> new JwkMgr().getJwkSet("https://localhost:" + redirect.getAddress().getPort() + "/"));
            assertEquals(2, plaintextRequests.get());
        } finally {
            redirect.stop(0);
            plaintext.stop(0);
        }
    }


    @Test
    void productionNettyContextServesHttpsRequest() throws Exception {
        SslContext tls = HttpSSLContextLoader.getSslContext();
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Channel listener = null;
        try {
            listener = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(tls.newHandler(channel.alloc()), new HttpServerCodec(),
                                    new SimpleChannelInboundHandler<HttpObject>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext context, HttpObject request) {
                                            if (request instanceof HttpRequest) {
                                                DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                                                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                                        Unpooled.wrappedBuffer(new byte[] {'o', 'k'}));
                                                response.headers().setInt("Content-Length", 2);
                                                context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                                            }
                                        }
                                    });
                        }
                    }).bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
            int port = ((InetSocketAddress) listener.localAddress()).getPort();
            assertEquals("ok", HttpUtils.get("https://localhost:" + port + "/", Map.of()));
        } finally {
            if (listener != null) {
                listener.close().sync();
            }
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
    }

}
