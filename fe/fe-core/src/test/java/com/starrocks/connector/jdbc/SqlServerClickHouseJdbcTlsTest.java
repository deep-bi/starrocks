// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.jdbc;

import com.starrocks.common.util.CryptoTestExtension;
import com.starrocks.common.util.TlsTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Security;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(CryptoTestExtension.class)
public class SqlServerClickHouseJdbcTlsTest {
    @TempDir
    Path directory;

    @Test
    public void driversVerifyTrustAndHostnameBeforeProtocolTraffic() throws Exception {
        String storeType = Security.getProperty("keystore.type");
        var peer = Executors.newSingleThreadExecutor();
        try (var ignored = TlsTestSupport.useFipsProviders()) {
            TlsTestSupport fixtures = new TlsTestSupport(directory);
            KeyStore trust = KeyStore.getInstance("JKS", "SUN");
            trust.load(null, null);
            trust.setCertificateEntry("ca", fixtures.jks(directory.resolve("ca.jks")).getCertificate("ca"));
            Path jksTrust = directory.resolve("jdbc-trust.jks");
            try (var output = Files.newOutputStream(jksTrust)) {
                trust.store(output, fixtures.password.toCharArray());
            }
            Security.setProperty("keystore.type", "BCFKS");
            assertEquals("BCJSSE", SSLContext.getInstance("TLS").getProvider().getName());
            assertEquals("BCJSSE", TrustManagerFactory.getInstance("PKIX").getProvider().getName());
            assertEquals("BCFIPS", new java.security.SecureRandom().getProvider().getName());
            System.setProperty("javax.net.ssl.trustStore", fixtures.trust.toString());
            System.setProperty("javax.net.ssl.trustStoreType", fixtures.type);
            System.setProperty("javax.net.ssl.trustStorePassword", fixtures.password);
            for (String family : new String[] {"sqlserver", "clickhouse"}) {
                for (String certificate : new String[] {"valid", "wronghost", "untrusted", "expired"}) {
                    SSLContext serverContext = fixtures.peerContext(certificate);
                    try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory()
                            .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                        server.setSoTimeout(5000);
                        var received = peer.submit(() -> {
                            try (SSLSocket socket = (SSLSocket) server.accept()) {
                                server.close();
                                socket.setSoTimeout(5000);
                                socket.startHandshake();
                                return socket.getInputStream().read();
                            } catch (java.io.IOException expected) {
                                return -1;
                            }
                        });
                        Properties properties = new Properties();
                        properties.setProperty("user", "review");
                        properties.setProperty("password", "synthetic-jdbc-password");
                        String url;
                        if (family.equals("sqlserver")) {
                            properties.setProperty("trustStore", jksTrust.toString());
                            properties.setProperty("trustStoreType", "JKS");
                            url = "jdbc:sqlserver://localhost:" + server.getLocalPort()
                                    + ";encrypt=strict;loginTimeout=2;socketTimeout=2000;connectRetryCount=0";
                        } else {
                            properties.setProperty("server_version", "23.8.1");
                            properties.setProperty("server_time_zone", "UTC");
                            properties.setProperty("sslrootcert", fixtures.pemTrust.toString());
                            properties.setProperty("ssl", "true");
                            properties.setProperty("socket_timeout", "2000");
                            properties.setProperty("connection_timeout", "2000");
                            properties.setProperty("retry", "0");
                            properties.setProperty("http_connection_provider", "HTTP_URL_CONNECTION");
                            properties.setProperty("sslmode", "strict");
                            url = "jdbc:clickhouse://localhost:" + server.getLocalPort() + "/review";
                        }
                        // The peer closes after TLS validation, before any database reply.
                        SQLException failure = assertThrows(SQLException.class, () -> {
                            try (var connection = DriverManager.getConnection(url, properties);
                                    var statement = connection.createStatement()) {
                                statement.executeQuery("SELECT 1");
                            }
                        });
                        int firstByte = received.get(6, TimeUnit.SECONDS);
                        if (certificate.equals("valid")) {
                            assertTrue(firstByte >= 0,
                                    family + " did not send protocol bytes after verified TLS: " + failure.getMessage());
                        } else {
                            assertEquals(-1, firstByte, family + " " + certificate);
                        }
                    }
                }
            }
        } finally {
            peer.shutdownNow();
            Security.setProperty("keystore.type", storeType);
        }
    }
}
