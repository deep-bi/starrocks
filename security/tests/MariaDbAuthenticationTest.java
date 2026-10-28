// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import com.starrocks.connector.share.security.MariaDbJdbcPolicy;
import org.mariadb.jdbc.Configuration;
import org.mariadb.jdbc.client.Context;
import org.mariadb.jdbc.client.impl.StandardReadableByteBuf;
import org.mariadb.jdbc.client.socket.Reader;
import org.mariadb.jdbc.client.socket.impl.PacketWriter;
import org.mariadb.jdbc.client.util.MutableByte;
import org.mariadb.jdbc.message.client.HandshakeResponse;
import org.mariadb.jdbc.plugin.AuthenticationPlugin;
import org.mariadb.jdbc.plugin.Credential;
import org.mariadb.jdbc.plugin.authentication.AuthenticationPluginLoader;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Security;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/** Actual driver handshake and authentication plugins under the production security overlay. */
public final class MariaDbAuthenticationTest {
    private static final String USER = "test-user";
    private static final String PASSWORD = "synthetic-test-password";
    private static final byte[] SEED = "0123456789abcdefghij\0".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Configuration config = Configuration.parse(MariaDbJdbcPolicy.restrictAuthentication(
                "org.mariadb.jdbc.Driver", "jdbc:mariadb://localhost/review"));
        if (mode.equals("healthy") || mode.equals("active-fault")) {
            healthy(config);
        }
        if (mode.equals("healthy")) {
            System.out.println("MariaDB initial handshake and authentication switches use BCFIPS");
            return;
        }
        if (mode.equals("missing-provider")) {
            Security.removeProvider("BCFIPS");
        } else {
            Method fault = Class.forName("org.bouncycastle.crypto.fips.FipsStatus")
                    .getDeclaredMethod("moveToErrorStatus", String.class);
            fault.setAccessible(true);
            try {
                fault.invoke(null, "isolated MariaDB authentication test error");
            } catch (InvocationTargetException expected) {
                // The transition itself can report the module error.
            }
        }
        for (int retry = 0; retry < 3; retry++) {
            rejectUnsupportedPlugins(config);
            for (String operation : List.of("initial", "mysql_native_password", "caching_sha2_password")) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try {
                    authenticate(config, operation, output);
                    throw new AssertionError("Authentication succeeded after " + mode + ": " + operation);
                } catch (Throwable failure) {
                    if (!moduleFailure(failure)) {
                        throw failure;
                    }
                    if (output.size() != 0) {
                        throw new AssertionError("Authentication bytes sent after " + mode + ": " + operation);
                    }
                }
            }
        }
        System.out.println("MariaDB rejected " + mode + " and retries without sending authentication bytes");
    }

    private static void healthy(Configuration config) throws Exception {
        for (String algorithm : List.of("SHA-1", "SHA-256", "SHA-512")) {
            if (!MessageDigest.getInstance(algorithm).getProvider().getName().equals("BCFIPS")) {
                throw new AssertionError("Unexpected digest provider: " + algorithm);
            }
        }
        rejectUnsupportedPlugins(config);
        for (String operation : List.of("initial", "mysql_native_password", "caching_sha2_password")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            authenticate(config, operation, output);
            byte[] packet = output.toByteArray();
            byte[] expected = response(operation.equals("caching_sha2_password") ? "SHA-256" : "SHA-1");
            int offset = 4; // MySQL packet header.
            if (operation.equals("initial")) {
                offset += 32 + USER.getBytes(StandardCharsets.UTF_8).length + 1;
                if ((packet[offset++] & 0xff) != expected.length) {
                    throw new AssertionError("Incorrect initial password response length");
                }
            }
            if (!Arrays.equals(expected, Arrays.copyOfRange(packet, offset, offset + expected.length))) {
                throw new AssertionError("Driver password bytes changed: " + operation);
            }
        }
    }

    private static byte[] response(String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm, "BCFIPS");
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
        return first;
    }

    private static void rejectUnsupportedPlugins(Configuration config) throws Exception {
        for (String plugin : List.of("client_ed25519", "auth_gssapi_client", "dialog", "mysql_clear_password")) {
            try {
                AuthenticationPluginLoader.get(plugin, config);
                throw new AssertionError("Unsupported authentication plugin accepted: " + plugin);
            } catch (SQLException expected) {
                if (!"08004".equals(expected.getSQLState())) {
                    throw expected;
                }
            }
        }
    }

    private static void authenticate(Configuration config, String operation, ByteArrayOutputStream output)
            throws Exception {
        PacketWriter writer = new PacketWriter(output, 0, null, new MutableByte(), new MutableByte());
        writer.initPacket();
        if (operation.equals("initial")) {
            Context context = (Context) Proxy.newProxyInstance(Context.class.getClassLoader(),
                    new Class<?>[] {Context.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("hasServerCapability")) {
                            return (long) arguments[0] == 32768L; // Secure-connection response length byte.
                        }
                        if (method.getName().equals("hasClientCapability")) {
                            return false;
                        }
                        throw new AssertionError("Unexpected context operation: " + method.getName());
                    });
            new HandshakeResponse(new Credential(USER, PASSWORD), "mysql_native_password",
                    Arrays.copyOf(SEED, 20), config, "localhost", 32768L, (byte) 45).encode(writer, context);
        } else {
            Reader reader = (Reader) Proxy.newProxyInstance(Reader.class.getClassLoader(), new Class<?>[] {Reader.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("readReusablePacket")) {
                            return new StandardReadableByteBuf(new byte[] {0});
                        }
                        throw new AssertionError("Unexpected reader operation: " + method.getName());
                    });
            AuthenticationPlugin plugin = AuthenticationPluginLoader.get(operation, config);
            plugin.initialize(PASSWORD, SEED, config);
            plugin.process(writer, reader, null);
        }
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
