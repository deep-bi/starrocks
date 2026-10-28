// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import java.sql.Driver;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** Selects Oracle's PBKDF2 authentication protocol and JCE cryptographic implementations. */
public final class OracleJdbcPolicy {
    private static final Map<String, String> REQUIRED = Map.of(
            "oracle.jdbc.allowedLogonVersion", "12a",
            "oracle.net.useJCEAPI", "true");

    private OracleJdbcPolicy() {
    }

    public static String configure(String driverClassName, String url, Properties properties) {
        if (!JdbcSecurityPolicy.matches(driverClassName, url, "oracle")) {
            return url;
        }
        if (!url.startsWith("jdbc:oracle:thin:")) {
            throw new IllegalArgumentException("Java FIPS requires the Oracle JDBC Thin driver");
        }
        try {
            Driver driver = JdbcSecurityPolicy.loadDriver(driverClassName);
            Set<String> supported = Arrays.stream(driver.getPropertyInfo(url, new Properties()))
                    .map(property -> property.name).collect(Collectors.toSet());
            if (!supported.containsAll(REQUIRED.keySet())) {
                throw new IllegalArgumentException("Oracle JDBC driver must support allowedLogonVersion and useJCEAPI");
            }
            // Oracle URL properties override the Properties passed by Hikari. Resolve them
            // with the actual driver's parser before applying the required settings.
            Class<?> resolver = Class.forName("oracle.net.resolver.EZConnectResolver", true,
                    driver.getClass().getClassLoader());
            Object parsed = resolver.getMethod("newInstance", String.class).invoke(null, url);
            Properties effective = new Properties();
            effective.putAll(properties);
            effective.putAll((Properties) resolver.getMethod("getProperties").invoke(parsed));
            String resolved = (String) resolver.getMethod("getResolvedUrl").invoke(parsed);
            if (resolved == null) {
                throw new IllegalArgumentException("Cannot resolve Oracle JDBC URL for authentication policy");
            }
            for (var required : REQUIRED.entrySet()) {
                String value = effective.getProperty(required.getKey());
                if (value != null && !required.getValue().equals(value)) {
                    throw new IllegalArgumentException("Java FIPS requires " + required.getKey() + "=" + required.getValue());
                }
                effective.setProperty(required.getKey(), required.getValue());
            }
            // Resolving the resulting URL again must not restore a conflicting property.
            Object reparsed = resolver.getMethod("newInstance", String.class).invoke(null, resolved);
            Properties overrides = (Properties) resolver.getMethod("getProperties").invoke(reparsed);
            for (var required : REQUIRED.entrySet()) {
                String value = overrides.getProperty(required.getKey());
                if (value != null && !required.getValue().equals(value)) {
                    throw new IllegalArgumentException("Cannot enforce Oracle JDBC authentication policy");
                }
            }
            properties.putAll(effective);
            return resolved;
        } catch (ReflectiveOperationException | SQLException e) {
            throw new IllegalArgumentException("Cannot enforce Oracle JDBC authentication policy", e);
        }
    }
}
