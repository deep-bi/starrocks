// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import java.sql.Driver;
import java.sql.SQLException;

/** Restricts PostgreSQL password authentication before a connection pool opens connections. */
public final class PostgresJdbcPolicy {
    private static final String REQUIRE_AUTH = "requireAuth";

    private PostgresJdbcPolicy() {
    }

    public static String rejectMd5Authentication(String driverClassName, String url) {
        if (!JdbcSecurityPolicy.matches(driverClassName, url, "postgresql")) {
            return url;
        }
        try {
            Driver driver = JdbcSecurityPolicy.loadDriver(driverClassName);
            String value = JdbcSecurityPolicy.property(driver, url, REQUIRE_AUTH, "PostgreSQL");
            if (value != null) {
                validatePolicy(value);
            } else {
                value = "!md5";
            }
            // Put the effective policy in the URL: PostgreSQL URL options override connection properties.
            // Pin service-file values here too, so a later file change cannot relax the checked policy.
            return url + (url.contains("?") ? "&" : "?") + REQUIRE_AUTH + "="
                    + java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (ReflectiveOperationException | SQLException e) {
            throw new IllegalArgumentException("Cannot enforce PostgreSQL JDBC authentication policy", e);
        }
    }

    private static void validatePolicy(String policy) {
        String[] methods = policy.split(",", -1);
        boolean denyList = methods[0].trim().startsWith("!");
        boolean rejectsMd5 = false;
        for (String value : methods) {
            String method = value.trim();
            if (method.isEmpty() || method.startsWith("!") != denyList) {
                throw new IllegalArgumentException("Invalid PostgreSQL requireAuth policy");
            }
            if (denyList) {
                rejectsMd5 |= method.equals("!md5");
            } else if (method.equalsIgnoreCase("md5")) {
                throw new IllegalArgumentException("Java FIPS forbids PostgreSQL MD5 password authentication");
            }
        }
        if (denyList && !rejectsMd5) {
            throw new IllegalArgumentException("Java FIPS requires PostgreSQL requireAuth to exclude MD5 authentication");
        }
    }
}
