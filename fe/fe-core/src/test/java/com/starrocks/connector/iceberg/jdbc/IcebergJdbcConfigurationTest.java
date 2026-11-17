// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.iceberg.jdbc;

import com.starrocks.common.util.CryptoTestExtension;
import mockit.Mock;
import mockit.MockUp;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.jdbc.JdbcCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(CryptoTestExtension.class)
public class IcebergJdbcConfigurationTest {
    @Test
    public void preservesUrlsAndDriverPropertiesWithoutRequiringAReviewedDriver() {
        AtomicReference<Map<String, String>> configured = new AtomicReference<>();
        new MockUp<JdbcCatalog>() {
            @Mock
            public void initialize(String name, Map<String, String> properties) {
                configured.set(new HashMap<>(properties));
            }
        };
        for (String url : List.of("jdbc:unreviewed://localhost/review", "jdbc:oracle:thin:@//localhost:1521/review",
                "jdbc:postgresql://localhost/review?requireAuth=md5&sslmode=prefer",
                "jdbc:mariadb://localhost/review?restrictedAuth=client_ed25519")) {
            Map<String, String> jdbcProperties = Map.of("jdbc.user", "review-user",
                    "jdbc.password", "synthetic-review-password", "jdbc.requireAuth", "scram-sha-256",
                    "jdbc.sslfactory", "example.CustomFactory", "jdbc.oracle.net.useJCEAPI", "false");
            Map<String, String> properties = new HashMap<>(jdbcProperties);
            properties.put("iceberg.catalog.uri", url);
            properties.put("iceberg.catalog.warehouse", "/tmp/iceberg-review");
            Map<String, String> original = new HashMap<>(properties);
            new IcebergJdbcCatalog("review", new Configuration(), properties);
            assertEquals(original, properties);
            assertEquals(url, configured.get().get(CatalogProperties.URI));
            assertEquals("/tmp/iceberg-review", configured.get().get(CatalogProperties.WAREHOUSE_LOCATION));
            Map<String, String> expectedJdbc = new HashMap<>(jdbcProperties);
            expectedJdbc.put("jdbc.init-catalog-tables", "false");
            Map<String, String> actualJdbc = new HashMap<>();
            configured.get().forEach((key, value) -> {
                if (key.startsWith("jdbc.")) {
                    actualJdbc.put(key, value);
                }
            });
            assertEquals(expectedJdbc, actualJdbc);
        }
    }
}
