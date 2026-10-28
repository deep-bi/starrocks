// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.Configuration;
import org.mariadb.jdbc.plugin.authentication.AuthenticationPluginLoader;
import org.postgresql.Driver;

import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MariaDbJdbcPolicyTest {
    private static final String URL = "jdbc:mariadb://127.0.0.1/review";
    private static final String ALLOWED = "mysql_native_password,caching_sha2_password";

    @Test
    public void restrictsRealDriverPluginSelection() throws Exception {
        Configuration configuration = Configuration.parse(secure(URL));
        assertEquals(ALLOWED, configuration.restrictedAuth());
        for (String plugin : new String[] {"mysql_native_password", "caching_sha2_password"}) {
            assertEquals(plugin, AuthenticationPluginLoader.get(plugin, configuration).type());
        }
        for (String plugin : new String[] {"client_ed25519", "auth_gssapi_client", "dialog", "mysql_clear_password"}) {
            SQLException rejected = assertThrows(SQLException.class,
                    () -> AuthenticationPluginLoader.get(plugin, configuration));
            assertTrue(rejected.getMessage().contains("doesn't permit requested plugin"), rejected.toString());
        }
    }

    @Test
    public void retainsEnforceableStricterPolicy() throws Exception {
        String url = secure(URL + "?restrictedAuth=mysql_native_password");
        assertEquals("mysql_native_password", Configuration.parse(url).restrictedAuth());
        assertThrows(SQLException.class,
                () -> AuthenticationPluginLoader.get("caching_sha2_password", Configuration.parse(url)));
        // MariaDB always sends native-password in its initial handshake, before restrictedAuth.
        assertThrows(IllegalArgumentException.class, () -> secure(URL + "?restrictedAuth=caching_sha2_password"));
    }

    @Test
    public void rejectsUnsupportedAndMalformedPolicies() {
        for (String policy : new String[] {"client_ed25519", "auth_gssapi_client", "dialog", "mysql_clear_password",
                "mysql_native_password,client_ed25519", "MYSQL_NATIVE_PASSWORD", ",", "mysql_native_password,",
                "mysql_native_password, caching_sha2_password", "mysql_native_password%2Ccaching_sha2_password"}) {
            assertThrows(IllegalArgumentException.class, () -> secure(URL + "?restrictedAuth=" + policy), policy);
        }
        assertThrows(IllegalArgumentException.class,
                () -> secure(URL + "?restrictedAuth=" + ALLOWED + "&restrictedAuth=client_ed25519"));
    }

    @Test
    public void usesActualDriverOptionPrecedence() throws Exception {
        assertEquals(ALLOWED, Configuration.parse(secure(URL + "?restrictedAuth=")).restrictedAuth());
        assertEquals(ALLOWED, Configuration.parse(secure(URL + "?restrictedAuth=client_ed25519&restrictedAuth="
                + ALLOWED)).restrictedAuth());
        assertEquals(ALLOWED, Configuration.parse(secure(URL + "?RestrictedAuth=" + ALLOWED)).restrictedAuth());
        Properties overrides = new Properties();
        overrides.setProperty("restrictedAuth", "client_ed25519");
        assertEquals(ALLOWED, Configuration.parse(secure(URL), overrides).restrictedAuth());
        assertThrows(IllegalArgumentException.class,
                () -> MariaDbJdbcPolicy.restrictAuthentication(ChangingPolicyDriver.class.getName(), URL));
    }

    @Test
    public void rejectsDriversWithoutRestrictionSupport() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> MariaDbJdbcPolicy.restrictAuthentication(LegacyDriver.class.getName(), URL));
        assertTrue(rejected.getMessage().contains("supporting restrictedAuth"));
    }

    @Test
    public void leavesOtherDriversUnchanged() {
        String postgres = "jdbc:postgresql://127.0.0.1/review";
        assertEquals(postgres, MariaDbJdbcPolicy.restrictAuthentication("org.postgresql.Driver", postgres));
        String mysql = "jdbc:mysql://127.0.0.1/review";
        assertEquals(mysql, MariaDbJdbcPolicy.restrictAuthentication("com.mysql.cj.jdbc.Driver", mysql));
    }

    private static String secure(String url) {
        return JdbcSecurityPolicy.configure("org.mariadb.jdbc.Driver", url, new Properties());
    }

    public static final class LegacyDriver extends Driver {
        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) {
            return new DriverPropertyInfo[0];
        }
    }

    public static final class ChangingPolicyDriver extends Driver {
        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) {
            return new DriverPropertyInfo[] {new DriverPropertyInfo("restrictedAuth",
                    url.contains("?") ? "client_ed25519" : null)};
        }
    }
}
