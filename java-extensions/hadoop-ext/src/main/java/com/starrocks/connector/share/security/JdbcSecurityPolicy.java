// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import javax.sql.DataSource;

/** Checks the shared FIPS runtime before JDBC pools create physical connections. */
public final class JdbcSecurityPolicy {
    private JdbcSecurityPolicy() {
    }

    /** Hikari also opens replacement connections on background threads. */
    public static DataSource guard(DataSource dataSource) {
        return (DataSource) Proxy.newProxyInstance(JdbcSecurityPolicy.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        FipsTlsCryptoProvider.checkReady();
                    }
                    try {
                        return method.invoke(dataSource, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
