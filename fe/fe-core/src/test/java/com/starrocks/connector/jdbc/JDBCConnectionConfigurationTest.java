// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.jdbc;

import com.starrocks.catalog.JDBCResource;
import com.starrocks.common.util.CryptoTestExtension;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(CryptoTestExtension.class)
public class JDBCConnectionConfigurationTest {
    @Test
    public void preservesDriverAuthenticationAndTlsSettings() {
        AtomicReference<HikariConfig> configured = new AtomicReference<>();
        RuntimeException stop = new RuntimeException("Stop before opening a database connection");
        new MockUp<HikariDataSource>() {
            @Mock
            public void $init(HikariConfig config) {
                configured.set(config);
                throw stop;
            }
        };
        for (var entry : Map.of(
                "oracle.jdbc.OracleDriver", "jdbc:oracle:thin:@//localhost:1521/review?oracle.net.useJCEAPI=false",
                "oracle.jdbc.driver.OracleDriver", "jdbc:oracle:thin:@//localhost:1521/review",
                "org.postgresql.Driver", "jdbc:postgresql://localhost/review?requireAuth=md5&sslmode=prefer",
                "org.mariadb.jdbc.Driver", "jdbc:mariadb://localhost/review?restrictedAuth=client_ed25519").entrySet()) {
            Map<String, String> properties = Map.of(JDBCResource.DRIVER_CLASS, entry.getKey(),
                    JDBCResource.URI, entry.getValue(), JDBCResource.USER, "review-user",
                    JDBCResource.PASSWORD, "synthetic-review-password");
            assertSame(stop, assertThrows(RuntimeException.class, () -> new JDBCMetadata(properties, "review")));
            HikariConfig config = configured.get();
            assertEquals(entry.getKey(), config.getDriverClassName());
            assertEquals(entry.getValue(), config.getJdbcUrl());
            assertEquals("review-user", config.getUsername());
            assertEquals("synthetic-review-password", config.getPassword());
            assertTrue(config.getDataSourceProperties().isEmpty());
        }
    }
}
