// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import java.sql.Driver;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;

/** Restricts MariaDB authentication plugins that perform cryptography outside the selected provider. */
public final class MariaDbJdbcPolicy {
    private static final String RESTRICTED_AUTH = "restrictedAuth";
    private static final String DEFAULT_AUTH = "mysql_native_password,caching_sha2_password";
    private static final Set<String> ALLOWED_AUTH = Set.of("mysql_native_password", "caching_sha2_password");

    private MariaDbJdbcPolicy() {
    }

    public static String restrictAuthentication(String driverClassName, String url) {
        if (!JdbcSecurityPolicy.matches(driverClassName, url, "mariadb")) {
            return url;
        }
        try {
            Driver driver = JdbcSecurityPolicy.loadDriver(driverClassName);
            String policy = JdbcSecurityPolicy.property(driver, url, RESTRICTED_AUTH, "MariaDB");
            if (policy == null) {
                policy = DEFAULT_AUTH;
            }
            for (String plugin : policy.split(",", -1)) {
                if (!ALLOWED_AUTH.contains(plugin)) {
                    throw new IllegalArgumentException("Java FIPS forbids MariaDB authentication plugin: " + plugin);
                }
            }
            // The pinned driver computes native-password in its initial handshake before
            // consulting restrictedAuth. Do not claim to enforce a policy excluding it.
            if (!Arrays.asList(policy.split(",")).contains("mysql_native_password")) {
                throw new IllegalArgumentException(
                        "MariaDB restrictedAuth must permit mysql_native_password for the initial handshake");
            }
            // MariaDB does not URL-decode option values; commas must remain literal.
            // Its URL options override connection properties, including Hikari's properties.
            String restrictedUrl = url + (url.contains("?") ? "&" : "?") + RESTRICTED_AUTH + "=" + policy;
            // Differently cased duplicate names can override the appended option. Reparse with
            // the actual driver and fail closed unless its effective policy is exactly ours.
            if (!policy.equals(JdbcSecurityPolicy.property(driver, restrictedUrl, RESTRICTED_AUTH, "MariaDB"))) {
                throw new IllegalArgumentException("Cannot enforce MariaDB restrictedAuth policy");
            }
            return restrictedUrl;
        } catch (ReflectiveOperationException | SQLException e) {
            throw new IllegalArgumentException("Cannot enforce MariaDB JDBC authentication policy", e);
        }
    }
}
