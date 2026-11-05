// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.authentication;

import com.starrocks.catalog.UserIdentity;
import com.starrocks.common.Config;
import com.starrocks.common.util.CryptoTestExtension;
import com.starrocks.common.util.TlsTestSupport;
import com.starrocks.mysql.privilege.AuthPlugin;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSimpleBindRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import org.bouncycastle.crypto.fips.FipsOperationError;
import org.bouncycastle.crypto.fips.FipsStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Provider;
import java.security.Security;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LDAPFipsCoverageTest {
    private static final String BASE = "dc=test";
    private static final String SERVICE = "cn=bind," + BASE;
    private static final String USER = "uid=alice," + BASE;
    private static final UserIdentity IDENTITY = UserIdentity.createEphemeralUserIdent("alice", "%");
    private static final Map<String, String> SAVED_PROPERTIES = new HashMap<>();
    @TempDir
    static Path directory;
    private static TlsTestSupport fixtures;

    @BeforeAll
    static void setup() throws Exception {
        for (String property : new String[] {"javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType",
                "javax.net.ssl.trustStorePassword"}) {
            SAVED_PROPERTIES.put(property, System.getProperty(property));
        }
        fixtures = new TlsTestSupport(directory);
    }

    @AfterAll
    static void restore() {
        SAVED_PROPERTIES.forEach((property, value) -> {
            if (value == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, value);
            }
        });
    }

    private InMemoryDirectoryServer directory(AtomicInteger binds) throws Exception {
        SSLContext peer = fixtures.peerContext("valid");
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
        config.setSchema(null);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPSConfig("ldaps", InetAddress.getLoopbackAddress(),
                0, peer.getServerSocketFactory(), peer.getSocketFactory()));
        config.addAdditionalBindCredentials(SERVICE, fixtures.password);
        config.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
            @Override
            public void processSimpleBindRequest(InMemoryInterceptedSimpleBindRequest request) {
                binds.incrementAndGet();
            }
        });
        InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
        server.startListening();
        server.add("dn: " + BASE, "objectClass: domain", "dc: test");
        server.add("dn: " + USER, "objectClass: inetOrgPerson", "uid: alice", "cn: Alice", "sn: Test",
                "userPassword: " + fixtures.password, "memberOf: cn=analysts," + BASE);
        server.add("dn: cn=analysts," + BASE, "objectClass: groupOfNames", "cn: analysts", "member: " + USER);
        return server;
    }

    private LDAPAuthProvider login(int port, String userDn, String pattern) {
        return new LDAPAuthProvider("localhost", port, true, fixtures.trust.toString(), fixtures.password,
                SERVICE, fixtures.password, BASE, "uid", userDn, pattern,
                LdapGroupSource.MEMBEROF, "memberOf", fixtures.type);
    }

    private AccessControlContext authenticate(AuthenticationProvider provider) throws Exception {
        AccessControlContext context = new AccessControlContext();
        provider.authenticate(context, IDENTITY, (fixtures.password + "\0").getBytes(StandardCharsets.UTF_8));
        assertEquals(USER, context.getDistinguishedName());
        return context;
    }

    private LDAPGroupProvider groups(int port) {
        return new LDAPGroupProvider("groups", Map.of(
                LDAPGroupProvider.LDAP_LDAP_CONN_URL, "ldaps://localhost:" + port,
                LDAPGroupProvider.LDAP_PROP_ROOT_DN_KEY, SERVICE,
                LDAPGroupProvider.LDAP_PROP_ROOT_PWD_KEY, fixtures.password,
                LDAPGroupProvider.LDAP_PROP_BASE_DN_KEY, BASE,
                LDAPGroupProvider.LDAP_GROUP_FILTER, "(objectClass=groupOfNames)",
                LDAPGroupProvider.LDAP_PROP_CONN_TIMEOUT_MS_KEY, "2000",
                LDAPGroupProvider.LDAP_PROP_CONN_READ_TIMEOUT_MS_KEY, "2000"));
    }

    @Test
    void directSearchAndDnPatternBindsReadMemberOfAndReconnect() throws Exception {
        AtomicInteger binds = new AtomicInteger();
        try (InMemoryDirectoryServer server = directory(binds)) {
            int port = server.getListenPort();
            assertEquals(Set.of(), authenticate(login(port, USER, "")).getMemberOfGroups());
            assertEquals(1, binds.get());
            LDAPAuthProvider search = login(port, "", "");
            assertEquals(Set.of("analysts"), authenticate(search).getMemberOfGroups());
            assertEquals(3, binds.get());
            LDAPAuthProvider pattern = login(port, "", "uid=${USER},ou=missing," + BASE + ";uid=${USER}," + BASE);
            assertEquals(Set.of("analysts"), authenticate(pattern).getMemberOfGroups());
            assertEquals(5, binds.get());
            assertEquals(Set.of("analysts"), search.resolveMemberOfGroupsForUser("alice").memberOfGroups());
            server.closeAllConnections(false);
            assertEquals(Set.of("analysts"), authenticate(search).getMemberOfGroups());
            assertEquals(8, binds.get());
        }
    }

    @Test
    void globalConfigurationAndIntegrationOverridesUseTheSameVerifiedTransport() throws Exception {
        try (InMemoryDirectoryServer server = directory(new AtomicInteger())) {
            Map<String, String> properties = new HashMap<>();
            properties.put("authentication_ldap_simple_server_host", "localhost");
            properties.put("authentication_ldap_simple_server_port", Integer.toString(server.getListenPort()));
            properties.put("authentication_ldap_simple_ssl_conn_allow_insecure", "false");
            properties.put("authentication_ldap_simple_ssl_conn_trust_store_path", fixtures.trust.toString());
            properties.put("authentication_ldap_simple_ssl_conn_trust_store_pwd", fixtures.password);
            properties.put("authentication_ldap_simple_ssl_conn_trust_store_type", fixtures.type);
            properties.put("authentication_ldap_simple_bind_root_dn", SERVICE);
            properties.put("authentication_ldap_simple_bind_root_pwd", fixtures.password);
            properties.put("authentication_ldap_simple_bind_base_dn", BASE);
            properties.put("authentication_ldap_simple_user_search_attr", "uid");
            properties.put("authentication_ldap_simple_bind_dn_pattern", "");
            properties.put("authentication_ldap_simple_group_source", "memberof");
            properties.put("authentication_ldap_simple_memberof_attr", "memberOf");
            Map<Field, Object> previous = new HashMap<>();
            try {
                for (Map.Entry<String, String> property : properties.entrySet()) {
                    Field field = Config.class.getField(property.getKey());
                    previous.put(field, field.get(null));
                    Object value = property.getValue();
                    if (field.getType() == int.class) {
                        value = Integer.parseInt(property.getValue());
                    } else if (field.getType() == boolean.class) {
                        value = Boolean.parseBoolean(property.getValue());
                    }
                    field.set(null, value);
                }
                assertEquals(Set.of("analysts"), authenticate(
                        AuthPlugin.Server.AUTHENTICATION_LDAP_SIMPLE.getProvider(null)).getMemberOfGroups());
                assertEquals(Set.of("analysts"), authenticate(new SimpleLDAPSecurityIntegration("global", Map.of())
                        .getAuthenticationProvider()).getMemberOfGroups());
                Config.authentication_ldap_simple_server_port = 1;
                assertEquals(Set.of("analysts"), authenticate(new SimpleLDAPSecurityIntegration("override", properties)
                        .getAuthenticationProvider()).getMemberOfGroups());
            } finally {
                for (Map.Entry<Field, Object> entry : previous.entrySet()) {
                    entry.getKey().set(null, entry.getValue());
                }
            }
        }
    }

    @Test
    void missingProvidersStopBindsRefreshesCachedGroupsAndCachedSocketFactories() throws Exception {
        AtomicInteger binds = new AtomicInteger();
        try (InMemoryDirectoryServer server = directory(binds)) {
            int port = server.getListenPort();
            LDAPAuthProvider login = login(port, "", "");
            LDAPGroupProvider groups = groups(port);
            groups.refreshGroups();
            assertEquals(Set.of("analysts"), groups.getGroup(IDENTITY, USER));
            SSLContext context = SslUtils.createSSLContext(Optional.of(fixtures.trust.toFile()),
                    Optional.of(fixtures.password), fixtures.type);
            assertEquals("BCJSSE", context.getProvider().getName());
            LdapSslSocketFactory.setSslContextForCurrentThread(context);
            SocketFactory cached = LdapSslSocketFactory.getDefault();
            try (Socket socket = cached.createSocket("localhost", port)) {
                ((javax.net.ssl.SSLSocket) socket).startHandshake();
            }
            for (String name : new String[] {"BCFIPS", "BCJSSE", "StarRocksPKIX"}) {
                Provider provider = Security.getProvider(name);
                int prior = binds.get();
                Security.removeProvider(name);
                try {
                    assertThrows(Exception.class, () -> authenticate(login));
                    assertThrows(IllegalStateException.class, () -> login.resolveMemberOfGroupsForUser("alice"));
                    assertThrows(IllegalStateException.class,
                            () -> groups.createDirContextOnConnection(SERVICE, fixtures.password));
                    groups.refreshGroups();
                    assertThrows(IllegalStateException.class, () -> groups.getGroup(IDENTITY, USER));
                    assertThrows(IllegalStateException.class, LdapSslSocketFactory::getDefault);
                    assertThrows(IllegalStateException.class, () -> cached.createSocket("localhost", port));
                    assertEquals(prior, binds.get());
                } finally {
                    Security.addProvider(provider);
                }
                server.closeAllConnections(false);
                assertEquals(Set.of("analysts"), authenticate(login).getMemberOfGroups());
                groups.refreshGroups();
                assertEquals(Set.of("analysts"), groups.getGroup(IDENTITY, USER));
            }
        }
    }

    @Test
    void moduleErrorStopsPlainAndTlsBindsAndCachedObjects() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = directory.resolve("module-error.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, ModuleErrorWorker.class.getName(), fixtures.pemTrust.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, "Module error worker timed out");
        assertEquals(0, process.exitValue(), Files.readString(log));
    }

    public static class ModuleErrorWorker {
        public static void main(String[] args) throws Exception {
            new CryptoTestExtension().beforeAll(null);
            SSLContext context = SslUtils.createSSLContext(Optional.of(Path.of(args[0]).toFile()),
                    Optional.empty(), "PEM");
            LdapSslSocketFactory.setSslContextForCurrentThread(context);
            SocketFactory cached = LdapSslSocketFactory.getDefault();
            LDAPGroupProvider groups = new LDAPGroupProvider("cached", Map.of(
                    LDAPGroupProvider.LDAP_LDAP_CONN_URL, "ldap://localhost:1"));
            groups.setUserToGroupCache(Map.of(USER, Set.of("analysts")));
            assertEquals(Set.of("analysts"), groups.getGroup(IDENTITY, USER));

            Method fail = FipsStatus.class.getDeclaredMethod("moveToErrorStatus", String.class);
            fail.setAccessible(true);
            assertThrows(InvocationTargetException.class, () -> fail.invoke(null, "LDAP module error fixture"));
            assertThrows(FipsOperationError.class, () -> cached.createSocket("localhost", 1));
            assertThrows(FipsOperationError.class, LdapSslSocketFactory::getDefault);
            assertThrows(FipsOperationError.class, () -> groups.getGroup(IDENTITY, USER));
            assertThrows(FipsOperationError.class, groups::refreshGroups);
            for (boolean tls : new boolean[] {false, true}) {
                LDAPAuthProvider login = new LDAPAuthProvider("localhost", 1, tls, "", "", SERVICE,
                        "fixture-password", BASE, "uid", USER, "");
                assertThrows(FipsOperationError.class, () -> login.checkPassword(USER, "fixture-password"));
                assertThrows(FipsOperationError.class, () -> login.findUserDNByRoot("alice"));
            }
        }
    }
}
