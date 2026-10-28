// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.jdbc;

import com.starrocks.catalog.JDBCResource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JDBCAuthenticationPolicyTest {
    @Test
    public void rejectsOracleDowngradeBeforeOpeningPool() {
        for (String setting : new String[] {"oracle.jdbc.allowedLogonVersion=12", "oracle.net.useJCEAPI=false"}) {
            Map<String, String> properties = Map.of(
                    JDBCResource.DRIVER_CLASS, "oracle.jdbc.OracleDriver",
                    JDBCResource.URI, "jdbc:oracle:thin:@//127.0.0.1:1521/review?" + setting,
                    JDBCResource.USER, "review-user",
                    JDBCResource.PASSWORD, "synthetic-review-password");
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> new JDBCMetadata(properties, "review"));
            assertTrue(rejected.getMessage().contains("Java FIPS requires oracle."), rejected.toString());
        }
    }

    @Test
    public void rejectsPostgresMd5BeforeOpeningPool() {
        Map<String, String> properties = Map.of(
                JDBCResource.DRIVER_CLASS, "org.postgresql.Driver",
                JDBCResource.URI, "jdbc:postgresql://127.0.0.1/review?requireAuth=md5",
                JDBCResource.USER, "review-user",
                JDBCResource.PASSWORD, "synthetic-review-password");
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> new JDBCMetadata(properties, "review"));
        assertTrue(rejected.getMessage().contains("forbids PostgreSQL MD5"));
    }

    @Test
    public void rejectsMariaDbEd25519BeforeOpeningPool() {
        assertMariaDbRejected("org.mariadb.jdbc.Driver", "jdbc:mariadb://127.0.0.1/review");
        // FE substitutes MariaDB for configured MySQL metadata drivers too.
        assertMariaDbRejected("com.mysql.cj.jdbc.Driver", "jdbc:mysql://127.0.0.1/review");
    }

    private static void assertMariaDbRejected(String driver, String url) {
        Map<String, String> properties = Map.of(
                JDBCResource.DRIVER_CLASS, driver,
                JDBCResource.URI, url + "?restrictedAuth=client_ed25519",
                JDBCResource.USER, "review-user",
                JDBCResource.PASSWORD, "synthetic-review-password");
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> new JDBCMetadata(properties, "review"));
        assertTrue(rejected.getMessage().contains("forbids MariaDB authentication plugin"));
    }
}
