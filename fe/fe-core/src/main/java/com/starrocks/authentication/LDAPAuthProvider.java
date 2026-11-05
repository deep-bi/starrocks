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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.starrocks.common.Config;
import com.starrocks.common.util.NetUtils;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.UserIdentity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.PartialResultException;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.net.ssl.SSLContext;

public class LDAPAuthProvider implements AuthenticationProvider {
    private static final Logger LOG = LogManager.getLogger(LDAPAuthProvider.class);
    private final String ldapServerHost;
    private final int ldapServerPort;
    private final boolean useSSL;
    private final String trustStorePath;
    private final String trustStorePwd;
    private final String ldapBindRootDN;
    private final String ldapBindRootPwd;
    private final String ldapBindBaseDN;
    private final String ldapSearchFilter;
    private final String ldapUserDN;

    public LDAPAuthProvider(String ldapServerHost,
                            int ldapServerPort,
                            boolean useSSL,
                            String trustStorePath,
                            String trustStorePwd,
                            String ldapBindRootDN,
                            String ldapBindRootPwd,
                            String ldapBindBaseDN,
                            String ldapSearchFilter,
                            String ldapUserDN) {
        this.ldapServerHost = ldapServerHost;
        this.ldapServerPort = ldapServerPort;
        this.useSSL = useSSL;
        this.trustStorePath = trustStorePath;
        this.trustStorePwd = trustStorePwd;
        this.ldapBindRootDN = ldapBindRootDN;
        this.ldapBindRootPwd = ldapBindRootPwd;
        this.ldapBindBaseDN = ldapBindBaseDN;
        this.ldapSearchFilter = ldapSearchFilter;
        this.ldapUserDN = ldapUserDN;
    }

    @Override
    public void authenticate(ConnectContext context, UserIdentity userIdentity, byte[] authResponse)
            throws AuthenticationException {
        // clear password terminate string: the MySQL clear-password frame appends a trailing \0, while
        // HTTP Basic sends the bare password and may even send an empty one
        byte[] clearPassword = authResponse;
        if (authResponse.length > 0 && authResponse[authResponse.length - 1] == 0) {
            clearPassword = Arrays.copyOf(authResponse, authResponse.length - 1);
        }
        String password = new String(clearPassword, StandardCharsets.UTF_8);

        // a credential this FE has bound successfully within authentication_ldap_simple_auth_success_cache_ttl_second is
        // accepted without contacting the directory; the cached DN is what the group providers resolve against
        String cacheKey = successfulBindCacheKey(userIdentity.getUser());
        String cachedDistinguishedName = SuccessfulBindCache.lookup(cacheKey, password);
        if (cachedDistinguishedName != null) {
            LOG.debug("LDAP bind for user {} answered from the successful bind cache", userIdentity.getUser());
            context.setDistinguishedName(cachedDistinguishedName);
            return;
        }

        String distinguishedName;
        try {
            if (!Strings.isNullOrEmpty(ldapUserDN)) {
                distinguishedName = ldapUserDN;
            } else {
                distinguishedName = findUserDNByRoot(userIdentity.getUser());
            }
            Preconditions.checkNotNull(distinguishedName);
            checkPassword(distinguishedName, password);
        } catch (AuthenticationException e) {
            // already classified (empty password, user not found): pass it through
            LOG.warn("check password failed for user: {}", userIdentity.getUser(), e);
            throw e;
        } catch (javax.naming.AuthenticationException e) {
            // the directory answered and rejected the credential: a definitive wrong password
            LOG.warn("check password failed for user: {}", userIdentity.getUser(), e);
            throw new AuthenticationException(e.getMessage());
        } catch (Exception e) {
            // anything else means no answer (connection refused, timeout, TLS failure); mark it transient so a
            // caller that caches rejections does not hold a valid credential back until the directory recovers
            LOG.warn("cannot reach the directory while authenticating user: {}", userIdentity.getUser(), e);
            throw new AuthenticationException(e.getMessage()).asTransient();
        }

        // set distinguished name to auth context
        context.setDistinguishedName(distinguishedName);
        SuccessfulBindCache.remember(cacheKey, password, distinguishedName);
    }

    /**
     * Everything that decides what a bind through this provider returns, plus the canonical username the LDAP
     * search uses. A changed LDAP setting, a user re-pointed at another DN or a security integration with other
     * properties therefore never reuses an entry made under other settings, which is what invalidates the cache
     * on ALTER USER and on configuration changes. The root and trust store passwords are left out: they do not change
     * which credential the directory accepts.
     */
    private String successfulBindCacheKey(String user) {
        return Joiner.on('\u0000').useForNull("").join(ldapServerHost, ldapServerPort, useSSL, trustStorePath,
                ldapBindRootDN, ldapBindBaseDN, ldapSearchFilter, ldapUserDN, normalizeUsername(user));
    }

    /**
     * Forget every remembered bind. The cache is process-wide, so tests must reset it between cases; an operator
     * gets the same effect by setting either authentication_ldap_simple_auth_success_cache_* config to a different value.
     */
    public static void invalidateSuccessfulBindCache() {
        SuccessfulBindCache.reset();
    }

    @VisibleForTesting
    static void setSuccessfulBindCacheTicker(Ticker ticker) {
        SuccessfulBindCache.ticker = ticker;
        SuccessfulBindCache.reset();
    }

    /**
     * Remembers credentials this FE has just bound successfully, so a client that logs in over and over does not
     * open one or two directory connections per login. Stream Load is the case that matters: it authenticates at
     * the FE HTTP endpoint and again on the BE-&gt;FE transaction RPCs, for every load. Off unless
     * {@code authentication_ldap_simple_auth_success_cache_ttl_second} is positive.
     *
     * <ul>
     *   <li>An entry expires a fixed time after the bind that created it; using it does not extend it. A client
     *       logging in every few seconds still reaches the directory once per TTL, and that TTL is how long a
     *       disabled account or a changed password can keep working on this FE.</li>
     *   <li>One entry per user and LDAP configuration. A different password goes to the directory: if accepted it
     *       replaces the entry, if rejected the entry stays, so someone guessing passwords cannot push a valid
     *       user out of the cache.</li>
     *   <li>The password is never stored: the entry holds a PBKDF2 hash with a random per-entry salt, in memory
     *       only. Nothing is journaled, so every FE keeps its own cache.</li>
     *   <li>Both configs are read at every lookup: a different TTL or capacity rebuilds the cache empty before it
     *       is read, and a TTL of 0 stops using it. Going back to the same values before any LDAP login on this
     *       FE keeps the old entries, which still expire at their original time.</li>
     * </ul>
     */
    private static final class SuccessfulBindCache {
        private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
        private static final int KDF_ITERATIONS = 10000;
        private static final int KDF_KEY_BITS = 256;
        private static final int SALT_BYTES = 16;
        private static final SecureRandom RANDOM = new SecureRandom();

        private static volatile Ticker ticker = Ticker.systemTicker();
        private static volatile Cache<String, CachedBind> cache;
        private static volatile int cachedTtl = -1;
        private static volatile int cachedCapacity = -1;

        private static final class CachedBind {
            private final byte[] salt;
            private final byte[] passwordHash;
            private final String distinguishedName;

            private CachedBind(byte[] salt, byte[] passwordHash, String distinguishedName) {
                this.salt = salt;
                this.passwordHash = passwordHash;
                this.distinguishedName = distinguishedName;
            }
        }

        private static Cache<String, CachedBind> cache() {
            int ttl = Config.authentication_ldap_simple_auth_success_cache_ttl_second;
            int capacity = Config.authentication_ldap_simple_auth_success_cache_capacity;
            if (ttl <= 0 || capacity <= 0) {
                // turned off: drop what was remembered, so a later switch back on starts empty
                if (cache != null) {
                    reset();
                }
                return null;
            }
            Cache<String, CachedBind> current = cache;
            // both settings are mutable at runtime; rebuild when either changes, before any entry is read
            if (current == null || cachedTtl != ttl || cachedCapacity != capacity) {
                synchronized (SuccessfulBindCache.class) {
                    if (cache == null || cachedTtl != ttl || cachedCapacity != capacity) {
                        cache = CacheBuilder.newBuilder()
                                .expireAfterWrite(ttl, TimeUnit.SECONDS)
                                .maximumSize(capacity)
                                .ticker(ticker)
                                .build();
                        cachedTtl = ttl;
                        cachedCapacity = capacity;
                    }
                    current = cache;
                }
            }
            return current;
        }

        static synchronized void reset() {
            cache = null;
            cachedTtl = -1;
            cachedCapacity = -1;
        }

        /** The DN bound for this key and password, or null when the directory has to be asked. */
        static String lookup(String key, String password) {
            if (password.isEmpty()) {
                return null;
            }
            Cache<String, CachedBind> current = cache();
            if (current == null) {
                return null;
            }
            CachedBind entry = current.getIfPresent(key);
            if (entry == null) {
                return null;
            }
            byte[] hash = hash(password, entry.salt);
            return hash != null && MessageDigest.isEqual(hash, entry.passwordHash) ? entry.distinguishedName : null;
        }

        static void remember(String key, String password, String distinguishedName) {
            // an empty password is an unauthenticated bind (RFC 4513 section 5.1.2), which many directories accept
            // for any DN; checkPassword refuses it, and the cache must never be what lets it through
            if (password.isEmpty()) {
                return;
            }
            Cache<String, CachedBind> current = cache();
            if (current == null) {
                return;
            }
            byte[] salt = new byte[SALT_BYTES];
            RANDOM.nextBytes(salt);
            byte[] hash = hash(password, salt);
            if (hash != null) {
                current.put(key, new CachedBind(salt, hash, distinguishedName));
            }
        }

        private static byte[] hash(String password, byte[] salt) {
            char[] chars = password.toCharArray();
            PBEKeySpec spec = new PBEKeySpec(chars, salt, KDF_ITERATIONS, KDF_KEY_BITS);
            try {
                return SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).getEncoded();
            } catch (GeneralSecurityException e) {
                // every JDK the FE runs on ships PBKDF2WithHmacSHA256; without it, skip caching rather than fail
                LOG.warn("cannot hash a password for the LDAP successful bind cache, not caching it", e);
                return null;
            } finally {
                spec.clearPassword();
                Arrays.fill(chars, '\0');
            }
        }
    }

    private String getURL() {
        if (useSSL) {
            return "ldaps://" + NetUtils.getHostPortInAccessibleFormat(ldapServerHost, ldapServerPort);
        } else {
            return "ldap://" + NetUtils.getHostPortInAccessibleFormat(ldapServerHost, ldapServerPort);
        }
    }

    private void setSSLContext(Hashtable<String, String> env) throws Exception {
        SSLContext sslContext = SslUtils.createSSLContext(
                Optional.empty(), /* For now, we don't support server to verify us(client). */
                Optional.empty(),
                Strings.isNullOrEmpty(trustStorePath) ? Optional.empty() : Optional.of(new File(trustStorePath)),
                Strings.isNullOrEmpty(trustStorePwd) ? Optional.empty() : Optional.of(trustStorePwd));
        LdapSslSocketFactory.setSslContextForCurrentThread(sslContext);
        // Refer to https://docs.oracle.com/javase/jndi/tutorial/ldap/security/ssl.html.
        env.put("java.naming.ldap.factory.socket", LdapSslSocketFactory.class.getName());
    }

    //bind to ldap server to check password
    protected void checkPassword(String dn, String password) throws Exception {
        if (Strings.isNullOrEmpty(password)) {
            throw new AuthenticationException("empty password is not allowed for simple authentication");
        }

        String url = getURL();
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_CREDENTIALS, password);
        env.put(Context.SECURITY_PRINCIPAL, dn);
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        // without both timeouts the bind falls back to the OS TCP timeout, so an unreachable directory can pin
        // the calling thread, which is now an HTTP worker or a thrift handler as well
        env.put("com.sun.jndi.ldap.connect.timeout",
                String.valueOf(Config.authentication_ldap_simple_conn_timeout_ms));
        env.put("com.sun.jndi.ldap.read.timeout",
                String.valueOf(Config.authentication_ldap_simple_conn_read_timeout_ms));
        if (useSSL) {
            setSSLContext(env);
        }

        DirContext ctx = null;
        try {
            //this will send a bind call to ldap server, throw exception if failed
            ctx = new InitialDirContext(env);
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception e) {
                }
            }
        }
    }

    //1. bind ldap server by root dn
    //2. search user
    //3. if match exactly one, return the user's actual DN
    protected String findUserDNByRoot(String user) throws Exception {
        if (Strings.isNullOrEmpty(ldapBindRootPwd)) {
            throw new AuthenticationException("empty password is not allowed for simple authentication");
        }

        String url = getURL();
        Hashtable<String, String> env = new Hashtable<>();
        //dn contains '=', so we should use ' or " to wrap the value in config file
        String rootDN = ldapBindRootDN;
        rootDN = trim(rootDN, "\"");
        rootDN = trim(rootDN, "'");
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_CREDENTIALS, ldapBindRootPwd);
        env.put(Context.SECURITY_PRINCIPAL, rootDN);
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        // without both timeouts the bind falls back to the OS TCP timeout, so an unreachable directory can pin
        // the calling thread, which is now an HTTP worker or a thrift handler as well
        env.put("com.sun.jndi.ldap.connect.timeout",
                String.valueOf(Config.authentication_ldap_simple_conn_timeout_ms));
        env.put("com.sun.jndi.ldap.read.timeout",
                String.valueOf(Config.authentication_ldap_simple_conn_read_timeout_ms));
        if (useSSL) {
            setSSLContext(env);
        }

        DirContext ctx = null;
        try {
            String baseDN = ldapBindBaseDN;
            baseDN = trim(baseDN, "\"");
            baseDN = trim(baseDN, "'");
            SearchControls sc = new SearchControls();
            sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
            // Normalize username for case-insensitive LDAP search
            // LDAP treats usernames as case-insensitive by default, aligning with Microsoft Active Directory
            String normalizedUser = normalizeUsername(user);
            // Escapes special characters in user input to prevent LDAP injection
            String safeUser = escapeLdapValue(normalizedUser);
            String searchFilter = "(" + ldapSearchFilter + "=" + safeUser + ")";
            ctx = new InitialDirContext(env);
            NamingEnumeration<SearchResult> results = ctx.search(baseDN, searchFilter, sc);

            String userDN = null;
            int matched = 0;

            try {
                while (results.hasMore()) {
                    matched++;
                    if (matched > 1) {
                        throw new AuthenticationException("searched more than one entry from ldap server for user " + user);
                    }

                    SearchResult result = results.next();
                    userDN = result.getNameInNamespace();
                }
            } catch (PartialResultException e) {
                LOG.warn("ldap search partial result exception", e);
            }

            if (matched != 1) {
                throw new AuthenticationException("ldap search matched user count " + matched);
            }

            return userDN;
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception e) {
                }
            }
        }
    }

    // trim prefix and suffix of target from src
    private static String trim(String src, String target) {
        if (src != null && target != null) {
            if (src.startsWith(target)) {
                src = src.substring(target.length());
            }
            if (src.endsWith(target)) {
                src = src.substring(0, src.length() - target.length());
            }
        }
        return src;
    }

    public static String escapeLdapValue(String value) {
        if (value == null) {
            return null;
        }

        value = value.replace("\\", "\\5c");
        value = value.replace("*", "\\2a");
        value = value.replace("(", "\\28");
        value = value.replace(")", "\\29");
        value = value.replace("|", "\\7c");
        value = value.replace("\\u0000", "\\00");
        return value;
    }

    /**
     * Normalize username for case-insensitive matching with LDAP/Active Directory.
     * LDAP treats usernames as case-insensitive by default, so we normalize to lowercase
     * to ensure consistent identity mapping regardless of input casing.
     *
     * @param username the original username
     * @return normalized username in lowercase
     */
    public static String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        return username.toLowerCase();
    }
}
