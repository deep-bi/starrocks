// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenIdentifier;
import org.apache.hadoop.ipc.protobuf.RpcHeaderProtos.RpcSaslProto.SaslAuth;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.FastSaslClientFactory;
import org.apache.hadoop.security.FastSaslServerFactory;
import org.apache.hadoop.security.SaslRpcClient;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Security;
import java.util.List;
import java.util.Map;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslClientFactory;

/** Run in an isolated JVM with the production security overlay and pinned provider JARs. */
public final class SaslPolicyTest {
    private static final CallbackHandler CALLBACKS = callbacks -> {
        throw new AssertionError("A disabled SASL mechanism requested credentials");
    };

    public static void main(String[] args) throws Exception {
        if (!MessageDigest.getInstance("SHA-256", "BCFIPS").getProvider().getName().equals("BCFIPS")
                || !Boolean.TRUE.equals(Class.forName("org.bouncycastle.crypto.CryptoServicesRegistrar")
                        .getMethod("isInApprovedOnlyMode").invoke(null))) {
            throw new AssertionError("Test requires BCFIPS approved-only mode");
        }
        exercise();

        // Alter only this isolated JVM's error state, never the signed provider artifact.
        Method fail = Class.forName("org.bouncycastle.crypto.fips.FipsStatus")
                .getDeclaredMethod("moveToErrorStatus", String.class);
        fail.setAccessible(true);
        try {
            fail.invoke(null, "isolated SASL policy test");
        } catch (InvocationTargetException expected) {
            if (!expected.getCause().getClass().getName().startsWith("org.bouncycastle.crypto.fips.Fips")) {
                throw expected;
            }
        }
        exercise();
        System.out.println("JDK/Hadoop SASL and delegation-token rejection passed before and after module error");
    }

    public static void exercise() throws Exception {
        Configuration conf = new Configuration(false);
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 8020);
        UserGroupInformation user = UserGroupInformation.createRemoteUser("sasl-policy-test");
        user.addToken(new Token<DelegationTokenIdentifier>(new byte[]{1}, new byte[32],
                DelegationTokenIdentifier.HDFS_DELEGATION_KIND, SecurityUtil.buildTokenService(address)));
        SaslRpcClient rpc = new SaslRpcClient(user, ClientProtocol.class, address, conf);
        SaslAuth auth = SaslAuth.newBuilder().setMethod("TOKEN").setMechanism("DIGEST-MD5")
                .setProtocol("hdfs").setServerId("0").build();
        Method getToken = SaslRpcClient.class.getDeclaredMethod("getServerToken", SaslAuth.class);
        getToken.setAccessible(true);
        if (getToken.invoke(rpc, auth) == null) {
            throw new AssertionError("RPC test did not supply a selectable delegation token");
        }
        Method select = SaslRpcClient.class.getDeclaredMethod("selectSaslClient", List.class);
        select.setAccessible(true);
        exercise(rpc, auth, select);
    }

    private static void exercise(SaslRpcClient rpc, SaslAuth auth, Method select) throws Exception {
        // Hadoop RPC and HDFS data transfer call factories directly. A test using only
        // Sasl.createSaslClient misses their bypass of jdk.sasl.disabledMechanisms.
        FastSaslClientFactory clients = new FastSaslClientFactory(Map.of());
        FastSaslServerFactory servers = new FastSaslServerFactory(Map.of());
        SaslClientFactory filtered = (SaslClientFactory) Security.getProvider("StarRocksSASL")
                .getService("SaslClientFactory", "PLAIN").newInstance(null);
        for (String mechanism : List.of("DIGEST-MD5", "CRAM-MD5", "NTLM")) {
            String[] names = {mechanism};
            if (Sasl.createSaslClient(names, null, "hdfs", "0", Map.of(), CALLBACKS) != null
                    || Sasl.createSaslServer(mechanism, "hdfs", "0", Map.of(), CALLBACKS) != null
                    || clients.createSaslClient(names, null, "hdfs", "0", Map.of(), CALLBACKS) != null
                    || filtered.createSaslClient(names, null, "hdfs", "0", Map.of(), CALLBACKS) != null
                    || servers.createSaslServer(mechanism, "hdfs", "0", Map.of(), CALLBACKS) != null
                    || List.of(filtered.getMechanismNames(Map.of())).contains(mechanism)) {
                throw new AssertionError("Unapproved SASL mechanism remains available: " + mechanism);
            }
        }
        // PLAIN and EXTERNAL contain no cryptographic primitive. Retain their original
        // payloads and policy handling. Callers still require an approved secure transport.
        CallbackHandler credentials = callbacks -> {
            for (var callback : callbacks) {
                if (callback instanceof NameCallback name) {
                    name.setName("test-user");
                } else if (callback instanceof PasswordCallback password) {
                    password.setPassword("test-password".toCharArray());
                } else {
                    throw new AssertionError("Unexpected SASL callback: " + callback);
                }
            }
        };
        SaslClient plain = clients.createSaslClient(new String[]{"CRAM-MD5", "PLAIN"}, null,
                "test", "localhost", Map.of(), credentials);
        if (plain == null || !plain.getMechanismName().equals("PLAIN")
                || !new String(plain.evaluateChallenge(new byte[0]), StandardCharsets.UTF_8)
                        .equals("\0test-user\0test-password")
                || clients.createSaslClient(new String[]{"PLAIN"}, null, "test", "localhost",
                        Map.of(Sasl.POLICY_NOPLAINTEXT, "true"), credentials) != null) {
            throw new AssertionError("PLAIN payload or plaintext policy changed");
        }
        SaslClient external = clients.createSaslClient(new String[]{"EXTERNAL"}, "test-user",
                "test", "localhost", Map.of(), CALLBACKS);
        if (external == null || !new String(external.evaluateChallenge(new byte[0]), StandardCharsets.UTF_8)
                .equals("test-user")) {
            throw new AssertionError("EXTERNAL payload changed");
        }
        if (Sasl.createSaslClient(new String[]{"PLAIN"}, null, "test", "localhost", Map.of(), credentials) == null
                || Sasl.createSaslClient(new String[]{"EXTERNAL"}, "test-user", "test", "localhost",
                        Map.of(), CALLBACKS) == null) {
            throw new AssertionError("Noncryptographic mechanisms unavailable through standard SASL API");
        }
        try {
            select.invoke(rpc, List.of(auth));
            throw new AssertionError("RPC delegation-token negotiation succeeded without an approved mechanism");
        } catch (InvocationTargetException expected) {
            if (!(expected.getCause() instanceof AccessControlException)
                    || !expected.getCause().getMessage().contains("Client cannot authenticate via:[TOKEN]")) {
                throw expected;
            }
        }
    }
}
