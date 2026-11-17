// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import com.mysql.cj.CharsetSettings;
import com.mysql.cj.conf.DefaultPropertySet;
import com.mysql.cj.conf.PropertySet;
import com.mysql.cj.protocol.AuthenticationPlugin;
import com.mysql.cj.protocol.Protocol;
import com.mysql.cj.protocol.SocketConnection;
import com.mysql.cj.protocol.a.NativeAuthenticationProvider;
import com.mysql.cj.protocol.a.NativePacketPayload;
import com.mysql.cj.protocol.a.NativeServerSession;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Exercises Connector/J plugin selection, fast responses, TLS password branches and RSA rejection. */
public final class MySqlAuthenticationTest {
    private static final String PASSWORD = "synthetic-test-password";
    private static final byte[] SEED = "0123456789abcdefghij\0".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] args) throws Exception {
        Properties settings = new Properties();
        settings.setProperty("sslMode", "DISABLED");
        String disabled = "mysql_old_password,mysql_clear_password,authentication_ldap_sasl_client,"
                + "authentication_kerberos_client,authentication_oci_client,authentication_fido_client,"
                + "authentication_webauthn_client";
        settings.setProperty("disabledAuthenticationPlugins", disabled + ",sha256_password");
        Map<String, AuthenticationPlugin<NativePacketPayload>> plugins = plugins(settings, false, true);
        if (plugins.size() != 2 || !plugins.keySet().containsAll(
                List.of("mysql_native_password", "caching_sha2_password"))) {
            throw new AssertionError("Unexpected authentication plugin set: " + plugins.keySet());
        }
        String mode = args[0];
        if (mode.equals("healthy") || mode.equals("active-fault")) {
            for (String name : plugins.keySet()) {
                authenticate(plugins.get(name), name, false);
            }
            Properties tlsSettings = new Properties();
            tlsSettings.setProperty("sslMode", "VERIFY_IDENTITY");
            tlsSettings.setProperty("disabledAuthenticationPlugins", disabled);
            Map<String, AuthenticationPlugin<NativePacketPayload>> tlsPlugins = plugins(tlsSettings, true, true);
            authenticate(tlsPlugins.get("caching_sha2_password"), "caching_sha2_password", true);
            authenticate(tlsPlugins.get("sha256_password"), "sha256_password", true);
        }
        if (mode.equals("healthy")) {
            System.out.println("MySQL native/caching fast responses, full TLS branches and plaintext RSA rejection passed");
            return;
        }
        if (mode.equals("missing-provider")) {
            Security.removeProvider("BCFIPS");
        } else {
            Method fault = Class.forName("org.bouncycastle.crypto.fips.FipsStatus")
                    .getDeclaredMethod("moveToErrorStatus", String.class);
            fault.setAccessible(true);
            try {
                fault.invoke(null, "isolated MySQL authentication test error");
            } catch (InvocationTargetException expected) {
                // The error transition can itself report failure.
            }
        }
        for (int retry = 0; retry < 3; retry++) {
            for (String name : plugins.keySet()) {
                AuthenticationPlugin<NativePacketPayload> plugin = plugins.get(name);
                plugin.reset();
                plugin.setAuthenticationParameters("test-user", PASSWORD);
                List<NativePacketPayload> output = new ArrayList<>();
                try {
                    plugin.nextAuthenticationStep(new NativePacketPayload(SEED.clone()), output);
                    throw new AssertionError("Authentication succeeded after " + mode + ": " + name);
                } catch (Throwable failure) {
                    if (!moduleFailure(failure)) {
                        throw failure;
                    }
                    if (!output.isEmpty()) {
                        throw new AssertionError("Authentication response produced after " + mode + ": " + name);
                    }
                }
            }
        }
        System.out.println("MySQL rejected " + mode + " for cached plugins and retries");
    }

    private static void authenticate(AuthenticationPlugin<NativePacketPayload> plugin, String name, boolean tls)
            throws Exception {
        plugin.reset();
        plugin.setAuthenticationParameters("test-user", PASSWORD);
        List<NativePacketPayload> output = new ArrayList<>();
        plugin.nextAuthenticationStep(new NativePacketPayload(SEED.clone()), output);
        if (!name.equals("sha256_password")) {
            String algorithm = name.equals("mysql_native_password") ? "SHA-1" : "SHA-256";
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            if (!digest.getProvider().getName().equals("BCFIPS")) {
                throw new AssertionError("Unexpected digest provider");
            }
            byte[] first = digest.digest(PASSWORD.getBytes(StandardCharsets.UTF_8));
            byte[] second = digest.digest(first);
            if (algorithm.equals("SHA-1")) {
                digest.update(SEED, 0, 20);
                digest.update(second);
            } else {
                digest.update(second);
                digest.update(SEED, 0, 20);
            }
            byte[] mask = digest.digest();
            for (int i = 0; i < first.length; i++) {
                first[i] ^= mask[i];
            }
            if (!Arrays.equals(first, bytes(output))) {
                throw new AssertionError("Incorrect fast response: " + name);
            }
            if (name.equals("mysql_native_password")) {
                return;
            }
            if (!tls) {
                try {
                    plugin.nextAuthenticationStep(new NativePacketPayload(new byte[] {4}), output);
                    throw new AssertionError("Plaintext full authentication succeeded without an approved transport");
                } catch (com.mysql.cj.exceptions.CJException expected) {
                    if (!expected.getMessage().contains("Public Key Retrieval is not allowed") || !output.isEmpty()) {
                        throw expected;
                    }
                }
                return;
            }
            plugin.nextAuthenticationStep(new NativePacketPayload(new byte[] {4}), output);
        }
        if (tls) {
            if (!Arrays.equals((PASSWORD + "\0").getBytes(StandardCharsets.UTF_8), bytes(output))) {
                throw new AssertionError("Incorrect TLS password response");
            }
            return;
        }
        throw new AssertionError("Unreviewed plaintext full authentication");
    }

    private static byte[] bytes(List<NativePacketPayload> output) {
        if (output.size() != 1) {
            throw new AssertionError("Expected one response packet");
        }
        NativePacketPayload packet = output.get(0);
        return Arrays.copyOf(packet.getByteBuffer(), packet.getPayloadLength());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, AuthenticationPlugin<NativePacketPayload>> plugins(Properties settings, boolean tls,
                                                                                 boolean modern) throws Exception {
        PropertySet propertySet = new DefaultPropertySet();
        propertySet.initializeProperties(settings);
        NativeServerSession session = new NativeServerSession(propertySet);
        session.setCharsetSettings((CharsetSettings) Proxy.newProxyInstance(CharsetSettings.class.getClassLoader(),
                new Class<?>[] {CharsetSettings.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getPasswordCharacterEncoding")) {
                        return "UTF-8";
                    }
                    throw new AssertionError(method.getName());
                }));
        SocketConnection socket = (SocketConnection) Proxy.newProxyInstance(SocketConnection.class.getClassLoader(),
                new Class<?>[] {SocketConnection.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("isSSLEstablished")) {
                        return tls;
                    }
                    throw new AssertionError(method.getName());
                });
        Protocol<NativePacketPayload> protocol = (Protocol<NativePacketPayload>) Proxy.newProxyInstance(
                Protocol.class.getClassLoader(), new Class<?>[] {Protocol.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getPropertySet": return propertySet;
                        case "getServerSession": return session;
                        case "getSocketConnection": return socket;
                        case "getExceptionInterceptor": return null;
                        case "versionMeetsMinimum": return modern;
                        default: throw new AssertionError(method.getName());
                    }
                });
        NativeAuthenticationProvider provider = new NativeAuthenticationProvider();
        provider.init(protocol, propertySet, null);
        Method load = NativeAuthenticationProvider.class.getDeclaredMethod("loadAuthenticationPlugins");
        load.setAccessible(true);
        load.invoke(provider);
        Field field = NativeAuthenticationProvider.class.getDeclaredField("authenticationPlugins");
        field.setAccessible(true);
        Map<String, AuthenticationPlugin<NativePacketPayload>> plugins =
                (Map<String, AuthenticationPlugin<NativePacketPayload>>) field.get(provider);
        for (AuthenticationPlugin<NativePacketPayload> plugin : plugins.values()) {
            plugin.init(protocol, null);
        }
        return plugins;
    }

    private static boolean moduleFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.security.NoSuchAlgorithmException
                    || cause instanceof java.security.NoSuchProviderException
                    || cause.getClass().getName().startsWith("org.bouncycastle.crypto.fips.Fips")) {
                return true;
            }
        }
        return false;
    }
}
