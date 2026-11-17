// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import org.postgresql.Driver;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.SocketFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

/** Completes the actual PostgreSQL SCRAM exchange with an in-memory protocol peer. */
public final class PostgresAuthenticationTest {
    private static final String PASSWORD = "synthetic-test-password";
    private static Fixture fixture;

    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://localhost/review?sslmode=disable&gssEncMode=disable&requireAuth=scram-sha-256";
        Driver driver = new Driver();
        String mode = args[0];
        if (mode.equals("healthy") || mode.equals("active-fault")) {
            for (var provider : Arrays.asList(MessageDigest.getInstance("SHA-256").getProvider(),
                    Mac.getInstance("HmacSHA256").getProvider(),
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").getProvider(), new SecureRandom().getProvider())) {
                if (!provider.getName().equals("BCFIPS")) {
                    throw new AssertionError("Unexpected SCRAM provider: " + provider.getName());
                }
            }
            fixture = new Fixture(false);
            Throwable success = connect(driver, url);
            if (!success.getMessage().contains("fixture authenticated") || !fixture.proofVerified) {
                throw new AssertionError("Complete SCRAM exchange failed", success);
            }
            fixture = new Fixture(true);
            Throwable badSignature = connect(driver, url);
            if (!fixture.proofVerified || badSignature.getMessage().contains("fixture authenticated")) {
                throw new AssertionError("Invalid SCRAM server signature was accepted", badSignature);
            }
        }
        if (mode.equals("healthy")) {
            System.out.println("PostgreSQL complete SCRAM proof and server-signature verification use BCFIPS");
            return;
        }
        if (mode.equals("missing-provider")) {
            Security.removeProvider("BCFIPS");
        } else {
            Method fault = Class.forName("org.bouncycastle.crypto.fips.FipsStatus")
                    .getDeclaredMethod("moveToErrorStatus", String.class);
            fault.setAccessible(true);
            try {
                fault.invoke(null, "isolated PostgreSQL authentication test error");
            } catch (InvocationTargetException expected) {
                // The error transition can itself report failure.
            }
        }
        for (int retry = 0; retry < 3; retry++) {
            fixture = new Fixture(false);
            Throwable failure = connect(driver, url);
            if (fixture.hasProof() || !moduleFailure(failure)) {
                throw new AssertionError("SCRAM did not fail before its password proof after " + mode, failure);
            }
        }
        System.out.println("PostgreSQL rejected " + mode + " on reused driver and retries before its password proof");
    }

    private static Throwable connect(Driver driver, String url) {
        Properties properties = new Properties();
        properties.setProperty("user", "test-user");
        properties.setProperty("password", PASSWORD);
        // The transport is injected after configuration only to supply protocol bytes to the driver fixture.
        properties.setProperty("socketFactory", FixtureSocketFactory.class.getName());
        try {
            driver.connect(url, properties);
            throw new AssertionError("The fixture must stop after authentication");
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static final class Fixture extends InputStream {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final boolean badSignature;
        private ByteArrayInputStream input;
        private int stage;
        private String first;
        private String serverFirst;
        private boolean proofSent;
        private boolean proofVerified;

        private Fixture(boolean badSignature) throws IOException {
            this.badSignature = badSignature;
            input = new ByteArrayInputStream(auth(10, "SCRAM-SHA-256\0\0"));
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (input.available() == 0) {
                try {
                    advance();
                } catch (Exception failure) {
                    throw new IOException(failure);
                }
            }
            return input.read(bytes, offset, length);
        }

        private void advance() throws Exception {
            if (stage++ == 0) {
                byte[] initial = lastPassword();
                int start = 0;
                while (initial[start] != 0) {
                    start++;
                }
                first = new String(initial, start + 5, initial.length - start - 5, StandardCharsets.UTF_8);
                String nonce = first.substring(first.indexOf("r=") + 2);
                serverFirst = "r=" + nonce + "server,s="
                        + Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.US_ASCII))
                        + ",i=4096";
                input = new ByteArrayInputStream(auth(11, serverFirst));
            } else if (stage == 2) {
                String last = new String(lastPassword(), StandardCharsets.UTF_8);
                proofSent = last.startsWith("c=");
                if (!proofSent) {
                    throw new AssertionError("Missing SCRAM client final message");
                }
                String finalWithoutProof = last.substring(0, last.indexOf(",p="));
                byte[] authMessage = (first.substring(3) + "," + serverFirst + "," + finalWithoutProof)
                        .getBytes(StandardCharsets.UTF_8);
                byte[] salted = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", "BCFIPS")
                        .generateSecret(new PBEKeySpec(PASSWORD.toCharArray(),
                                "0123456789abcdef".getBytes(StandardCharsets.US_ASCII), 4096, 256)).getEncoded();
                byte[] clientKey = hmac(salted, "Client Key".getBytes(StandardCharsets.US_ASCII));
                byte[] storedKey = MessageDigest.getInstance("SHA-256", "BCFIPS").digest(clientKey);
                byte[] signature = hmac(storedKey, authMessage);
                for (int i = 0; i < signature.length; i++) {
                    signature[i] ^= clientKey[i];
                }
                proofVerified = Arrays.equals(signature, Base64.getDecoder().decode(last.substring(last.indexOf(",p=") + 3)));
                if (!proofVerified) {
                    throw new AssertionError("SCRAM proof did not match BCFIPS derivation");
                }
                byte[] serverKey = hmac(salted, "Server Key".getBytes(StandardCharsets.US_ASCII));
                byte[] serverSignature = hmac(serverKey, authMessage);
                if (badSignature) {
                    serverSignature[0] ^= 1;
                }
                ByteArrayOutputStream messages = new ByteArrayOutputStream();
                messages.write(auth(12, "v=" + Base64.getEncoder().encodeToString(serverSignature)));
                messages.write(auth(0, ""));
                byte[] error = "SFATAL\0C28000\0Mfixture authenticated\0\0".getBytes(StandardCharsets.UTF_8);
                DataOutputStream data = new DataOutputStream(messages);
                data.writeByte('E');
                data.writeInt(error.length + 4);
                data.write(error);
                input = new ByteArrayInputStream(messages.toByteArray());
            } else {
                input = new ByteArrayInputStream(new byte[0]);
            }
        }

        private boolean hasProof() {
            try {
                return new String(lastPassword(), StandardCharsets.UTF_8).startsWith("c=");
            } catch (IOException noResponse) {
                return false;
            }
        }

        private byte[] lastPassword() throws IOException {
            DataInputStream data = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
            data.skipNBytes(data.readInt() - 4);
            byte[] password = null;
            while (data.available() > 0) {
                int type = data.readUnsignedByte();
                int length = data.readInt() - 4;
                byte[] body = data.readNBytes(length);
                if (type == 'p') {
                    password = body;
                }
            }
            if (password == null) {
                throw new IOException("No client authentication message");
            }
            return password;
        }
    }

    private static byte[] hmac(byte[] key, byte[] message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256", "BCFIPS");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message);
    }

    private static byte[] auth(int type, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte('R');
        output.writeInt(body.length + 8);
        output.writeInt(type);
        output.write(body);
        return bytes.toByteArray();
    }

    public static final class FixtureSocketFactory extends SocketFactory {
        @Override
        public Socket createSocket() {
            return new Socket() {
                @Override public boolean isConnected() { return true; }
                @Override public InputStream getInputStream() { return fixture; }
                @Override public OutputStream getOutputStream() { return fixture.output; }
                @Override public int getSendBufferSize() { return 8192; }
                @Override public void setTcpNoDelay(boolean enabled) { }
                @Override public void setKeepAlive(boolean enabled) { }
                @Override public void setSoTimeout(int timeout) { }
            };
        }
        @Override public Socket createSocket(String host, int port) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) {
            throw new UnsupportedOperationException();
        }
        @Override public Socket createSocket(InetAddress host, int port) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) {
            throw new UnsupportedOperationException();
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
