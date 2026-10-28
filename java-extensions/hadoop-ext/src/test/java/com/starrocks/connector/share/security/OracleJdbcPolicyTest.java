// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import oracle.net.resolver.EZConnectResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OracleJdbcPolicyTest {
    private static final String DRIVER = "oracle.jdbc.OracleDriver";
    private static final String URL = "jdbc:oracle:thin:@//localhost:1521/review";

    @Test
    public void selectsPbkdf2AndJceForAllThinUrlForms() {
        for (String url : List.of(URL, "jdbc:oracle:thin:@localhost:1521:review", "jdbc:oracle:thin:@REVIEW",
                "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=localhost)(PORT=1521))"
                        + "(CONNECT_DATA=(SERVICE_NAME=review)))")) {
            Properties properties = new Properties();
            properties.setProperty("oracle.jdbc.ReadTimeout", "5000");
            String resolved = JdbcSecurityPolicy.configure(DRIVER, url, properties);
            // Reproduce the driver's connect() merge, where URL options override Properties.
            properties.putAll(EZConnectResolver.newInstance(resolved).getProperties());
            assertEquals("12a", properties.getProperty("oracle.jdbc.allowedLogonVersion"));
            assertEquals("true", properties.getProperty("oracle.net.useJCEAPI"));
            assertEquals("5000", properties.getProperty("oracle.jdbc.ReadTimeout"));
        }
    }

    @Test
    public void rejectsAuthenticationAndCryptoDowngrades() {
        for (String setting : List.of("oracle.jdbc.allowedLogonVersion=8", "oracle.jdbc.allowedLogonVersion=12",
                "oracle.jdbc.allowedLogonVersion=\"12\"", "oracle.net.useJCEAPI=false",
                "oracle.net.useJCEAPI=true&oracle.net.useJCEAPI=false")) {
            assertThrows(IllegalArgumentException.class,
                    () -> JdbcSecurityPolicy.configure(DRIVER, URL + "?" + setting, new Properties()), setting);
        }
        Properties properties = new Properties();
        properties.setProperty("oracle.net.useJCEAPI", "false");
        assertThrows(IllegalArgumentException.class, () -> JdbcSecurityPolicy.configure(DRIVER, URL, properties));
    }

    @Test
    public void preservesSafeUrlPropertiesAndOverridesWeakerSystemDefaults() {
        String name = "oracle.jdbc.allowedLogonVersion";
        String original = System.getProperty(name);
        System.setProperty(name, "8");
        try {
            Properties properties = new Properties();
            String resolved = JdbcSecurityPolicy.configure(DRIVER,
                    URL + "?oracle.jdbc.allowedLogonVersion=\"12a\"&oracle.net.useJCEAPI=true"
                            + "&oracle.jdbc.ReadTimeout=5000", properties);
            assertTrue(resolved.startsWith("jdbc:oracle:thin:@"));
            assertEquals("12a", properties.getProperty(name));
            assertEquals("5000", properties.getProperty("oracle.jdbc.ReadTimeout"));
        } finally {
            if (original == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, original);
            }
        }
    }

    @Test
    public void rejectsNativeOracleAndLeavesOtherDriversUnchanged() {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcSecurityPolicy.configure(DRIVER, "jdbc:oracle:oci:@REVIEW", new Properties()));
        String url = "jdbc:postgresql://localhost/review";
        Properties properties = new Properties();
        assertEquals(url, OracleJdbcPolicy.configure("org.postgresql.Driver", url, properties));
        assertTrue(properties.isEmpty());
    }
}
