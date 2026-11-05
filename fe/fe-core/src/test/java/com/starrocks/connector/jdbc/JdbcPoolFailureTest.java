// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.jdbc;

import com.starrocks.catalog.JDBCResource;
import com.starrocks.common.Config;
import com.starrocks.common.util.CryptoTestExtension;
import com.starrocks.connector.iceberg.jdbc.IcebergJdbcCatalog;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import mockit.Mock;
import mockit.MockUp;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.jdbc.JdbcCatalog;
import org.apache.iceberg.jdbc.JdbcClientPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.Provider;
import java.security.Security;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(CryptoTestExtension.class)
public class JdbcPoolFailureTest {
    @Test
    public void metadataReusesCachedConnectionsButGuardsPoolCreationAndReplacements() throws Exception {
        int oldMinimum = Config.jdbc_minimum_idle_connections;
        Config.jdbc_minimum_idle_connections = 0;
        try (Peer peer = new Peer()) {
            Map<String, String> properties = Map.of(JDBCResource.DRIVER_CLASS, "org.postgresql.Driver",
                    JDBCResource.URI, peer.url(), JDBCResource.USER, "review", JDBCResource.PASSWORD, "test-password");
            JDBCMetadata metadata = new JDBCMetadata(properties, "review");
            try {
                try (var connection = metadata.getConnection()) {
                    assertFalse(connection.isClosed());
                }
                int opened = peer.connections.get();
                for (String name : List.of("BCFIPS", "BCJSSE", "StarRocksPKIX")) {
                    Provider provider = Security.getProvider(name);
                    Security.removeProvider(name);
                    try {
                        for (int retry = 0; retry < 3; retry++) {
                            try (var connection = metadata.getConnection()) {
                                assertFalse(connection.isClosed());
                            }
                        }
                        assertThrows(IllegalStateException.class, () -> new JDBCMetadata(properties, "review"));
                        assertEquals(opened, peer.connections.get());
                    } finally {
                        Security.addProvider(provider);
                    }
                }
                try (var connection = metadata.getConnection()) {
                    assertFalse(connection.isClosed());
                }
                assertEquals(opened, peer.connections.get(), "The healthy connection should have been reused");
                var field = JDBCMetadata.class.getDeclaredField("dataSource");
                field.setAccessible(true);
                HikariDataSource dataSource = (HikariDataSource) field.get(metadata);
                HikariPool hikariPool = (HikariPool) dataSource.getHikariPoolMXBean();
                Provider provider = Security.getProvider("BCFIPS");
                Security.removeProvider("BCFIPS");
                try {
                    dataSource.getHikariPoolMXBean().softEvictConnections();
                    // Use a short timeout while Hikari retries background connection creation.
                    for (int retry = 0; retry < 2; retry++) {
                        SQLException failure = assertThrows(SQLException.class, () -> hikariPool.getConnection(350));
                        assertInstanceOf(IllegalStateException.class, failure.getCause());
                        assertEquals(opened, peer.connections.get());
                    }
                } finally {
                    Security.addProvider(provider);
                }
                try (var connection = metadata.getConnection()) {
                    assertFalse(connection.isClosed());
                }
                assertEquals(opened + 1, peer.connections.get());
            } finally {
                metadata.shutdown();
            }
        } finally {
            Config.jdbc_minimum_idle_connections = oldMinimum;
        }
    }

    @Test
    public void icebergReusesCachedConnectionsButGuardsCreationAndReconnects() throws Exception {
        AtomicReference<Map<String, String>> configured = new AtomicReference<>();
        new MockUp<JdbcCatalog>() {
            @Mock
            public void initialize(String name, Map<String, String> properties) {
                configured.set(properties);
            }
        };
        try (Peer peer = new Peer()) {
            IcebergJdbcCatalog catalog = new IcebergJdbcCatalog("review", new Configuration(),
                    Map.of("uri", peer.url(), "warehouse", "/tmp/review",
                            "jdbc.user", "review", "jdbc.password", "test-password"));
            var delegateField = IcebergJdbcCatalog.class.getDeclaredField("delegate");
            delegateField.setAccessible(true);
            var builderField = JdbcCatalog.class.getDeclaredField("clientPoolBuilder");
            builderField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Function<Map<String, String>, JdbcClientPool> builder =
                    (Function<Map<String, String>, JdbcClientPool>) builderField.get(delegateField.get(catalog));
            Provider provider = Security.getProvider("BCFIPS");
            try (JdbcClientPool pool = builder.apply(configured.get())) {
                assertFalse(pool.run(java.sql.Connection::isClosed));
                int opened = peer.connections.get();
                for (String name : List.of("BCFIPS", "BCJSSE", "StarRocksPKIX")) {
                    Provider removed = Security.getProvider(name);
                    Security.removeProvider(name);
                    try (JdbcClientPool emptyPool = builder.apply(configured.get())) {
                        assertFalse(pool.run(java.sql.Connection::isClosed));
                        assertThrows(IllegalStateException.class, () -> emptyPool.run(java.sql.Connection::isClosed));
                        assertEquals(opened, peer.connections.get());
                    } finally {
                        Security.addProvider(removed);
                    }
                }
                AtomicInteger attempts = new AtomicInteger();
                assertFalse(pool.<Boolean>run(connection -> {
                    if (attempts.getAndIncrement() == 0) {
                        throw new SQLTransientException("fixture reconnect");
                    }
                    return connection.isClosed();
                }));
                assertEquals(opened + 1, peer.connections.get());
                // Iceberg preserves the original SQL error when reconnect itself fails.
                assertThrows(SQLException.class, () -> pool.run(connection -> {
                    Security.removeProvider("BCFIPS");
                    throw new SQLTransientException("fixture failure before reconnect");
                }));
                Security.addProvider(provider);
                assertEquals(opened + 1, peer.connections.get(), "A failed module must prevent replacement login");
            } finally {
                Security.addProvider(provider);
            }
        }
    }

    private static final class Peer implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        private final java.util.concurrent.ExecutorService workers = Executors.newCachedThreadPool();
        private final List<Socket> clients = new CopyOnWriteArrayList<>();
        private final AtomicInteger connections = new AtomicInteger();

        private Peer() throws IOException {
            workers.submit(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        clients.add(socket);
                        workers.submit(() -> serve(socket));
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
        }

        private String url() {
            return "jdbc:postgresql://localhost:" + server.getLocalPort()
                    + "/review?sslmode=disable&gssEncMode=disable&socketTimeout=3&assumeMinServerVersion=12"
                    + "&preferQueryMode=simple";
        }

        private void serve(Socket socket) {
            try (socket) {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                input.skipNBytes(input.readInt() - 4L);
                output.writeByte('R');
                output.writeInt(8);
                output.writeInt(3);
                output.flush();
                if (input.readByte() != 'p') {
                    throw new IOException("Expected password response");
                }
                input.skipNBytes(input.readInt() - 4L);
                connections.incrementAndGet();
                output.writeByte('R');
                output.writeInt(8);
                output.writeInt(0);
                for (var parameter : Map.of("server_version", "16.0", "client_encoding", "UTF8",
                        "standard_conforming_strings", "on", "integer_datetimes", "on").entrySet()) {
                    byte[] value = (parameter.getKey() + "\0" + parameter.getValue() + "\0")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    output.writeByte('S');
                    output.writeInt(value.length + 4);
                    output.write(value);
                }
                ready(output);
                while (input.read() == 'Q') {
                    String query = new String(input.readNBytes(input.readInt() - 4),
                            java.nio.charset.StandardCharsets.UTF_8);
                    if (query.startsWith("SHOW TRANSACTION ISOLATION LEVEL")) {
                        byte[] column = "transaction_isolation\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        output.writeByte('T');
                        output.writeInt(6 + column.length + 18);
                        output.writeShort(1);
                        output.write(column);
                        output.writeInt(0);
                        output.writeShort(0);
                        output.writeInt(25);
                        output.writeShort(-1);
                        output.writeInt(-1);
                        output.writeShort(0);
                        byte[] value = "read committed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        output.writeByte('D');
                        output.writeInt(10 + value.length);
                        output.writeShort(1);
                        output.writeInt(value.length);
                        output.write(value);
                        output.writeByte('C');
                        output.writeInt(9);
                        output.writeBytes("SHOW\0");
                    } else {
                        output.writeByte('I');
                        output.writeInt(4);
                    }
                    ready(output);
                }
            } catch (IOException closed) {
                // Closing the pool or fixture ends the connection.
            }
        }

        private static void ready(DataOutputStream output) throws IOException {
            output.writeByte('Z');
            output.writeInt(5);
            output.writeByte('I');
            output.flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket socket : clients) {
                socket.close();
            }
            workers.shutdownNow();
        }
    }
}
