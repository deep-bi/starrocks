// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.jdbc;

import com.starrocks.common.util.CryptoTestExtension;
import com.starrocks.common.util.TlsTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.sql.DriverManager;
import java.sql.SQLException;
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
public class PostgresJdbcTlsTest {
    @TempDir
    Path directory;

    @Test
    public void driverRejectsUnverifiedTlsBeforePostgresStartup() throws Exception {
        SSLContext originalContext = SSLContext.getDefault();
        var peer = Executors.newSingleThreadExecutor();
        try (var ignored = TlsTestSupport.useFipsProviders()) {
            TlsTestSupport fixtures = new TlsTestSupport(directory);
            KeyStore trust = KeyStore.getInstance("JKS", "SUN");
            trust.load(null, null);
            trust.setCertificateEntry("ca", fixtures.jks(directory.resolve("ca.jks")).getCertificate("ca"));
            Path trustFile = directory.resolve("public-trust.jks");
            try (OutputStream output = Files.newOutputStream(trustFile)) {
                trust.store(output, new char[0]);
            }
            System.setProperty("javax.net.ssl.trustStore", trustFile.toString());
            System.setProperty("javax.net.ssl.trustStoreType", "JKS");
            System.clearProperty("javax.net.ssl.trustStorePassword");
            SSLContext clientContext = SSLContext.getInstance("TLS", "BCJSSE");
            clientContext.init(null, null, null);
            SSLContext.setDefault(clientContext);
            assertEquals("BCJSSE", SSLContext.getDefault().getProvider().getName());
            for (String certificate : new String[] {"valid", "wronghost", "untrusted", "expired"}) {
                SSLContext serverContext = fixtures.peerContext(certificate);
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
                    Properties properties = new Properties();
                    properties.setProperty("user", "review");
                    properties.setProperty("password", "synthetic-postgres-password");
                    properties.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
                    String url = "jdbc:postgresql://localhost:"
                            + server.getLocalPort() + "/review?sslmode=verify-full&sslNegotiation=direct"
                            + "&connectTimeout=3&socketTimeout=3";
                    // The independent peer stops after TLS and the PostgreSQL startup packet.
                    assertThrows(SQLException.class, () -> DriverManager.getConnection(url, properties));
                    int firstByte = received.get(6, TimeUnit.SECONDS);
                    if (certificate.equals("valid")) {
                        assertTrue(firstByte >= 0, "Verified TLS did not send a PostgreSQL startup packet");
                        assertEquals("BCJSSE", SSLContext.getDefault().getProvider().getName());
                    } else {
                        assertEquals(-1, firstByte, certificate);
                    }
                }
            }
        } finally {
            peer.shutdownNow();
            SSLContext.setDefault(originalContext);
        }
    }
}
