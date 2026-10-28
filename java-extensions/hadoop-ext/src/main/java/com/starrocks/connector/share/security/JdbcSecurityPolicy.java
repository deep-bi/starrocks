// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import java.sql.Driver;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;

/** Applies the same JDBC authentication restrictions before FE and embedded pools open connections. */
public final class JdbcSecurityPolicy {
    private JdbcSecurityPolicy() {
    }

    public static String configure(String driverClassName, String url, Properties properties) {
        url = PostgresJdbcPolicy.rejectMd5Authentication(driverClassName, url);
        url = MariaDbJdbcPolicy.restrictAuthentication(driverClassName, url);
        return OracleJdbcPolicy.configure(driverClassName, url, properties);
    }

    static boolean matches(String driverClassName, String url, String database) {
        return url.startsWith("jdbc:" + database + ":")
                || driverClassName.toLowerCase(Locale.ROOT).contains(database);
    }

    static Driver loadDriver(String driverClassName) throws ReflectiveOperationException {
        // Match Hikari's context classloader, including externally supplied scanner drivers.
        return Class.forName(driverClassName, true, Thread.currentThread().getContextClassLoader())
                .asSubclass(Driver.class).getDeclaredConstructor().newInstance();
    }

    static String property(Driver driver, String url, String name, String database) throws SQLException {
        // Let the driver resolve URL precedence and reject drivers that silently ignore the policy.
        return Arrays.stream(driver.getPropertyInfo(url, new Properties()))
                .filter(property -> name.equals(property.name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Java FIPS requires a " + database + " JDBC driver supporting " + name)).value;
    }
}
