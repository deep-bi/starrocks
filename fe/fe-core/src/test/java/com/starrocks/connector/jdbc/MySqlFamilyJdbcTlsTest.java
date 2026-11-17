// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.jdbc;

import com.mysql.cj.ServerVersion;
import com.mysql.cj.conf.ConnectionUrl;
import com.mysql.cj.conf.DefaultPropertySet;
import com.mysql.cj.log.NullLogger;
import com.mysql.cj.protocol.ExportControlled;
import com.mysql.cj.protocol.SocketConnection;
import com.starrocks.common.util.CryptoTestExtension;
import com.starrocks.common.util.TlsTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mariadb.jdbc.Configuration;
import org.mariadb.jdbc.export.ExceptionFactory;
import org.mariadb.jdbc.plugin.tls.main.DefaultTlsSocketPlugin;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(CryptoTestExtension.class)
public class MySqlFamilyJdbcTlsTest {
    @TempDir
    Path directory;

    @Test
    public void actualDriverTlsPathsValidateBcjsseTrustAndHostname() throws Exception {
        var peer = Executors.newSingleThreadExecutor();
        try (var ignored = TlsTestSupport.useFipsProviders()) {
            TlsTestSupport fixtures = new TlsTestSupport(directory);
            assertEquals("BCJSSE", SSLContext.getInstance("TLS").getProvider().getName());
            for (String certificate : new String[] {"valid", "wronghost", "untrusted", "expired"}) {
                SSLContext serverContext = fixtures.peerContext(certificate);
                for (boolean mysql : new boolean[] {false, true}) {
                    try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory()
                            .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                        server.setSoTimeout(5000);
                        var received = peer.submit(() -> {
                            try (SSLSocket socket = (SSLSocket) server.accept()) {
                                socket.setSoTimeout(5000);
                                socket.startHandshake();
                                return socket.getInputStream().read();
                            } catch (java.io.IOException expected) {
                                return -1;
                            }
                        });
                        try (Socket raw = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                            raw.setSoTimeout(5000);
                            if (certificate.equals("valid")) {
                                try (SSLSocket tls = tls(mysql, raw, fixtures, server.getLocalPort())) {
                                    assertTrue(tls instanceof org.bouncycastle.jsse.BCSSLSocket);
                                    tls.getOutputStream().write(42);
                                    tls.getOutputStream().flush();
                                }
                            } else {
                                assertThrows(Exception.class, () -> tls(mysql, raw, fixtures, server.getLocalPort()),
                                        (mysql ? "MySQL " : "MariaDB ") + certificate);
                            }
                        }
                        assertEquals(certificate.equals("valid") ? 42 : -1, received.get(6, TimeUnit.SECONDS),
                                (mysql ? "MySQL " : "MariaDB ") + certificate);
                    }
                }
            }
        } finally {
            peer.shutdownNow();
        }
    }

    private static SSLSocket tls(boolean mysql, Socket raw, TlsTestSupport fixtures, int port) throws Exception {
        Properties properties = new Properties();
        if (!mysql) {
            properties.setProperty("sslMode", "verify-full");
            properties.setProperty("serverSslCert", fixtures.pemTrust.toString());
            properties.setProperty("trustStoreType", "BCFKS");
            String url = "jdbc:mariadb://localhost:" + port + "/review";
            Configuration conf = Configuration.parse(url, properties);
            DefaultTlsSocketPlugin plugin = new DefaultTlsSocketPlugin();
            SSLSocket socket = plugin.createSocket(raw, plugin.getSocketFactory(conf, new ExceptionFactory(conf, null)));
            socket.startHandshake();
            plugin.verify("localhost", socket.getSession(), 0);
            return socket;
        }
        properties.setProperty("sslMode", "VERIFY_IDENTITY");
        properties.setProperty("trustCertificateKeyStoreUrl", fixtures.trust.toUri().toString());
        properties.setProperty("trustCertificateKeyStoreType", fixtures.type);
        properties.setProperty("trustCertificateKeyStorePassword", fixtures.password);
        properties.setProperty("sslContextProvider", "BCJSSE");
        properties.setProperty("KeyManagerFactoryProvider", "BCJSSE");
        properties.setProperty("trustManagerFactoryProvider", "BCJSSE");
        properties.setProperty("keyStoreProvider", "BCFIPS");
        properties.setProperty("fipsCompliantJsse", "true");
        String url = "jdbc:mysql://localhost:" + port + "/review";
        Properties effective = ConnectionUrl.getConnectionUrlInstance(url, properties).getMainHost().exposeAsProperties();
        DefaultPropertySet settings = new DefaultPropertySet();
        settings.initializeProperties(effective);
        SocketConnection connection = (SocketConnection) Proxy.newProxyInstance(SocketConnection.class.getClassLoader(),
                new Class<?>[] {SocketConnection.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getPropertySet": return settings;
                        case "getHost": return "localhost";
                        case "getPort": return port;
                        case "getExceptionInterceptor": return null;
                        default: throw new AssertionError(method.getName());
                    }
                });
        return (SSLSocket) ExportControlled.performTlsHandshake(raw, connection, ServerVersion.parseVersion("8.0.36"),
                new NullLogger("fips-test"));
    }
}
