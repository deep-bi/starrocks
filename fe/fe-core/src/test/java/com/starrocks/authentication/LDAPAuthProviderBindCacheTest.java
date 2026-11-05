// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.authentication;

import com.google.common.base.Ticker;
import com.starrocks.common.Config;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.UserIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The cache of successful binds in {@link LDAPAuthProvider}, enabled by
 * {@code authentication_ldap_simple_auth_success_cache_ttl_second}.
 *
 * <p>Every login builds a new provider, as {@code AuthPlugin} and {@code SimpleLDAPSecurityIntegration} do, so
 * these tests only pass if the cache outlives the provider. The directory is a map; no LDAP server is needed.
 */
class LDAPAuthProviderBindCacheTest {
    private static final String HOST = "ldap.example.com";
    private static final String PASSWORD = "ldap_password";

    private final FakeTicker ticker = new FakeTicker();
    private final Directory directory = new Directory();
    private int savedTtl;
    private int savedCapacity;

    /** Lets a test move time forward instead of sleeping through a TTL. */
    private static final class FakeTicker extends Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advanceSeconds(long seconds) {
            nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
        }
    }

    /** What the directory holds and how often it was contacted, shared by the providers each login builds. */
    private static final class Directory {
        private final Map<String, String> passwordByDN = new HashMap<>();
        private int searches;
        private int binds;
        private boolean unreachable;
    }

    private static String dnOf(String user) {
        return "uid=" + user + ",ou=People,dc=example,dc=com";
    }

    private static LDAPAuthProvider provider(Directory directory, String host, String userDN) {
        return new LDAPAuthProvider(host, 389, false, null, null,
                "cn=admin,dc=example,dc=com", "root_pwd", "dc=example,dc=com", "uid", userDN) {
            @Override
            protected String findUserDNByRoot(String user) {
                directory.searches++;
                return dnOf(normalizeUsername(user));
            }

            @Override
            protected void checkPassword(String dn, String password) throws Exception {
                directory.binds++;
                if (directory.unreachable) {
                    throw new javax.naming.CommunicationException(host + ":389 connection refused");
                }
                if (!password.equals(directory.passwordByDN.get(dn))) {
                    throw new javax.naming.AuthenticationException("[LDAP: error code 49 - Invalid Credentials]");
                }
            }
        };
    }

    private ConnectContext login(String host, String userDN, String user, String authResponse)
            throws AuthenticationException {
        ConnectContext context = new ConnectContext();
        provider(directory, host, userDN).authenticate(context, UserIdentity.createEphemeralUserIdent(user, "%"),
                authResponse.getBytes(StandardCharsets.UTF_8));
        return context;
    }

    private ConnectContext login(String user, String authResponse) throws AuthenticationException {
        return login(HOST, null, user, authResponse);
    }

    @BeforeEach
    void setUp() {
        savedTtl = Config.authentication_ldap_simple_auth_success_cache_ttl_second;
        savedCapacity = Config.authentication_ldap_simple_auth_success_cache_capacity;
        Config.authentication_ldap_simple_auth_success_cache_ttl_second = 300;
        Config.authentication_ldap_simple_auth_success_cache_capacity = 1024;
        // also empties the process-wide cache, so no case sees another one's entries
        LDAPAuthProvider.setSuccessfulBindCacheTicker(ticker);
        directory.passwordByDN.put(dnOf("alice"), PASSWORD);
        directory.passwordByDN.put(dnOf("bob"), PASSWORD);
    }

    @AfterEach
    void tearDown() {
        Config.authentication_ldap_simple_auth_success_cache_ttl_second = savedTtl;
        Config.authentication_ldap_simple_auth_success_cache_capacity = savedCapacity;
        LDAPAuthProvider.setSuccessfulBindCacheTicker(Ticker.systemTicker());
    }

    @Test
    void testZeroTtlDisablesTheCache() throws Exception {
        Config.authentication_ldap_simple_auth_success_cache_ttl_second = 0;

        for (int attempt = 0; attempt < 3; attempt++) {
            login("alice", PASSWORD);
        }
        Assertions.assertEquals(3, directory.binds);
        Assertions.assertEquals(3, directory.searches);
    }

    @Test
    void testRepeatedLoginIsAnsweredFromTheCache() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            ConnectContext context = login("alice", PASSWORD);
            // the group providers resolve against this DN, so a cache hit has to set it exactly like a bind does
            Assertions.assertEquals(dnOf("alice"), context.getDistinguishedName());
        }
        Assertions.assertEquals(1, directory.binds, "only the first login may reach the directory");
        Assertions.assertEquals(1, directory.searches, "the DN lookup is skipped on a hit as well");
    }

    @Test
    void testMysqlFrameAndHttpBasicShareOneEntry() throws Exception {
        // the MySQL clear-password frame ends with \0, HTTP Basic and the load RPCs send the bare password
        login("alice", PASSWORD + "\0");
        login("alice", PASSWORD);

        Assertions.assertEquals(1, directory.binds);
    }

    @Test
    void testUsernameCaseSharesOneEntry() throws Exception {
        // the DN search lowercases the username, so these all bind as the same directory account
        for (String spelling : new String[] {"alice", "ALICE", "Alice"}) {
            ConnectContext context = login(spelling, PASSWORD);
            Assertions.assertEquals(dnOf("alice"), context.getDistinguishedName());
        }
        Assertions.assertEquals(1, directory.binds);
    }

    @Test
    void testWrongPasswordGoesToTheDirectoryAndKeepsTheEntry() throws Exception {
        login("alice", PASSWORD);

        AuthenticationException e = Assertions.assertThrows(AuthenticationException.class,
                () -> login("alice", "wrong_password"));
        Assertions.assertFalse(e.isTransientFailure());
        Assertions.assertEquals(2, directory.binds, "a password that does not match the entry must be bound");

        // a rejected guess must not evict the valid entry, or guessing would force every login back to LDAP
        login("alice", PASSWORD);
        Assertions.assertEquals(2, directory.binds);
    }

    @Test
    void testPasswordChangeInTheDirectory() throws Exception {
        login("alice", PASSWORD);
        directory.passwordByDN.put(dnOf("alice"), "new_password");

        // the price of the cache: the old password keeps working until its entry expires
        login("alice", PASSWORD);
        Assertions.assertEquals(1, directory.binds);

        // the new password is bound and replaces the entry, after which the old one is checked and refused
        login("alice", "new_password");
        Assertions.assertEquals(2, directory.binds);
        Assertions.assertThrows(AuthenticationException.class, () -> login("alice", PASSWORD));
        Assertions.assertEquals(3, directory.binds);
    }

    @Test
    void testEntryExpiresAfterWriteNotAfterLastUse() throws Exception {
        Config.authentication_ldap_simple_auth_success_cache_ttl_second = 60;

        login("alice", PASSWORD);
        ticker.advanceSeconds(50);
        login("alice", PASSWORD);
        Assertions.assertEquals(1, directory.binds);

        // 20 seconds after the last use but 70 after the bind: a client logging in every few seconds must still
        // reach the directory once per TTL, otherwise a disabled account would never be noticed
        ticker.advanceSeconds(20);
        login("alice", PASSWORD);
        Assertions.assertEquals(2, directory.binds);
    }

    @Test
    void testDifferentLdapSettingsDoNotShareEntries() throws Exception {
        login(HOST, null, "alice", PASSWORD);
        login("other-ldap.example.com", null, "alice", PASSWORD);
        // a native user whose DN is stored, as CREATE USER ... IDENTIFIED WITH authentication_ldap_simple AS 'dn'
        login(HOST, dnOf("alice"), "alice", PASSWORD);
        Assertions.assertEquals(3, directory.binds);

        login(HOST, null, "alice", PASSWORD);
        login("other-ldap.example.com", null, "alice", PASSWORD);
        login(HOST, dnOf("alice"), "alice", PASSWORD);
        Assertions.assertEquals(3, directory.binds);

        // re-pointing a user at another DN, as ALTER USER does, must not reuse the entry of the old DN
        AuthenticationException e = Assertions.assertThrows(AuthenticationException.class,
                () -> login(HOST, dnOf("bob_admin"), "alice", PASSWORD));
        Assertions.assertFalse(e.isTransientFailure());
        Assertions.assertEquals(4, directory.binds);
    }

    @Test
    void testEmptyPasswordIsNeverCached() throws Exception {
        // a directory that allows unauthenticated binds accepts an empty password for any DN; checkPassword
        // refuses one before binding, and the cache must not become a second way in
        directory.passwordByDN.put(dnOf("anon"), "");

        login("anon", "");
        login("anon", "\0");
        Assertions.assertEquals(2, directory.binds);
    }

    @Test
    void testUnreachableDirectory() throws Exception {
        directory.unreachable = true;
        AuthenticationException e = Assertions.assertThrows(AuthenticationException.class,
                () -> login("alice", PASSWORD));
        Assertions.assertTrue(e.isTransientFailure());

        directory.unreachable = false;
        login("alice", PASSWORD);
        Assertions.assertEquals(2, directory.binds, "a failed bind must not be cached as a success");

        // within the TTL a cached login does not depend on the directory being reachable
        directory.unreachable = true;
        login("alice", PASSWORD);
        Assertions.assertEquals(2, directory.binds);

        // but nothing is served past the TTL
        ticker.advanceSeconds(301);
        Assertions.assertThrows(AuthenticationException.class, () -> login("alice", PASSWORD));
        Assertions.assertEquals(3, directory.binds);
    }

    @Test
    void testTurningTheCacheOffFlushesIt() throws Exception {
        login("alice", PASSWORD);

        Config.authentication_ldap_simple_auth_success_cache_ttl_second = 0;
        login("alice", PASSWORD);
        Assertions.assertEquals(2, directory.binds);

        // back to the same TTL: the entry made before the switch must be gone, not merely hidden
        Config.authentication_ldap_simple_auth_success_cache_ttl_second = 300;
        login("alice", PASSWORD);
        Assertions.assertEquals(3, directory.binds);
        login("alice", PASSWORD);
        Assertions.assertEquals(3, directory.binds);
    }

    @Test
    void testADifferentValueDiscardsOldEntriesBeforeTheyAreUsed() throws Exception {
        login("alice", PASSWORD);

        // the documented way to flush: any other value, with no login in between, already hides the old entry
        Config.authentication_ldap_simple_auth_success_cache_ttl_second = 301;
        login("alice", PASSWORD);
        Assertions.assertEquals(2, directory.binds);

        Config.authentication_ldap_simple_auth_success_cache_capacity = 2048;
        login("alice", PASSWORD);
        Assertions.assertEquals(3, directory.binds);

        login("alice", PASSWORD);
        Assertions.assertEquals(3, directory.binds);
    }

    @Test
    void testCapacityBoundsTheCache() throws Exception {
        Config.authentication_ldap_simple_auth_success_cache_capacity = 1;

        login("alice", PASSWORD);
        login("bob", PASSWORD);
        login("alice", PASSWORD);
        Assertions.assertEquals(3, directory.binds, "bob's entry must have evicted alice's");
    }

    @Test
    void testInvalidateForgetsEveryEntry() throws Exception {
        login("alice", PASSWORD);
        LDAPAuthProvider.invalidateSuccessfulBindCache();
        login("alice", PASSWORD);
        Assertions.assertEquals(2, directory.binds);
    }
}
