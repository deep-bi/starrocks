// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import org.junit.jupiter.api.Test;
import org.postgresql.Driver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import javax.net.SocketFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PostgresJdbcPolicyTest {
    private static final String URL = "jdbc:postgresql://127.0.0.1/review?sslmode=disable&gssEncMode=disable";

    @Test
    public void rejectsMd5ChallengeBeforeSendingPassword() throws Exception {
        byte[] challenge = challenge(5, new byte[] {1, 2, 3, 4});
        // Establish that the same real driver/fixture sends a password without the policy.
        connect(URL, challenge);
        assertTrue(passwordResponse().startsWith("md5"));

        SQLException rejected = connect(secure(URL), challenge);
        assertTrue(rejected.getMessage().contains("not allowed by requireAuth"), rejected.toString());
        assertEquals("", passwordResponse(), "The driver must reject MD5 before computing/sending a response");
    }

    @Test
    public void retainsScramNegotiationAndStricterPolicies() throws Exception {
        String strict = secure(URL + "&requireAuth=scram-sha-256");
        assertEquals("scram-sha-256", Driver.parseURL(strict, new Properties()).getProperty("requireAuth"));
        connect(strict, challenge(10, "SCRAM-SHA-256\0\0".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(passwordResponse().startsWith("SCRAM-SHA-256\0"));
        // This fixture ends after the initial SASL response; it does not qualify a full SCRAM exchange.
    }

    @Test
    public void rejectsUrlOverridesThatEnableMd5() {
        for (String query : new String[] {"md5", "MD5", "Md5", "scram-sha-256,md5", "%6dd5", "!password", "", ","}) {
            assertThrows(IllegalArgumentException.class, () -> secure(URL + "&requireAuth=" + query), query);
        }
        assertThrows(IllegalArgumentException.class,
                () -> secure(URL + "&requireAuth=!md5&requireAuth=md5"));
        // The driver's last URL option wins, including against lower-priority connection properties.
        Properties overrides = new Properties();
        overrides.setProperty("requireAuth", "md5");
        assertEquals("!md5", Driver.parseURL(secure(URL + "&requireAuth=md5&requireAuth=%21md5"), overrides)
                .getProperty("requireAuth"));
        assertEquals("!md5,!password", Driver.parseURL(secure(URL + "&requireAuth=%21md5,%21password"), overrides)
                .getProperty("requireAuth"));
    }

    @Test
    public void rejectsDriversWithoutRequireAuthSupport() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> PostgresJdbcPolicy.rejectMd5Authentication(LegacyDriver.class.getName(), URL));
        assertTrue(rejected.getMessage().contains("supporting requireAuth"));
    }

    @Test
    public void leavesOtherDatabaseDriversUnchanged() {
        String mysql = "jdbc:mariadb://localhost/db?requireAuth=md5";
        assertEquals(mysql, PostgresJdbcPolicy.rejectMd5Authentication("org.mariadb.jdbc.Driver", mysql));
    }

    private static String secure(String url) {
        return JdbcSecurityPolicy.configure("org.postgresql.Driver", url, new Properties());
    }

    private static SQLException connect(String url, byte[] challenge) {
        ChallengeSocketFactory.CHALLENGE.set(challenge);
        ChallengeSocketFactory.OUTPUT.set(new ByteArrayOutputStream());
        Properties properties = new Properties();
        properties.setProperty("user", "review-user");
        properties.setProperty("password", "synthetic-review-password");
        properties.setProperty("socketFactory", ChallengeSocketFactory.class.getName());
        return assertThrows(SQLException.class, () -> new Driver().connect(url, properties));
    }

    private static byte[] challenge(int authenticationType, byte[] body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte('R');
        output.writeInt(8 + body.length);
        output.writeInt(authenticationType);
        output.write(body);
        return bytes.toByteArray();
    }

    private static String passwordResponse() throws IOException {
        DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(ChallengeSocketFactory.OUTPUT.get().toByteArray()));
        int startupLength = input.readInt();
        input.skipNBytes(startupLength - 4L);
        if (input.available() == 0) {
            return "";
        }
        assertEquals('p', input.readUnsignedByte());
        int length = input.readInt();
        return new String(input.readNBytes(length - 4), StandardCharsets.US_ASCII);
    }

    public static final class LegacyDriver extends Driver {
        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) {
            return new DriverPropertyInfo[0];
        }
    }

    /** Feeds PostgreSQL wire challenges to the real driver without opening network sockets. */
    public static final class ChallengeSocketFactory extends SocketFactory {
        private static final ThreadLocal<byte[]> CHALLENGE = new ThreadLocal<>();
        private static final ThreadLocal<ByteArrayOutputStream> OUTPUT = new ThreadLocal<>();

        @Override
        public Socket createSocket() {
            return new Socket() {
                private final InputStream input = new ByteArrayInputStream(CHALLENGE.get());

                @Override
                public boolean isConnected() {
                    return true;
                }

                @Override
                public InputStream getInputStream() {
                    return input;
                }

                @Override
                public OutputStream getOutputStream() {
                    return OUTPUT.get();
                }

                @Override
                public int getSendBufferSize() {
                    return 8192;
                }

                @Override
                public void setTcpNoDelay(boolean enabled) {
                }

                @Override
                public void setKeepAlive(boolean enabled) {
                }

                @Override
                public void setSoTimeout(int timeout) {
                }
            };
        }

        @Override
        public Socket createSocket(String host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localAddress, int localPort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(InetAddress host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(InetAddress host, int port, InetAddress localAddress, int localPort) {
            throw new UnsupportedOperationException();
        }
    }
}
