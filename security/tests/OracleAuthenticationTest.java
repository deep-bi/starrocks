// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import oracle.security.o5logon.O5Logon;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.MessageDigestSpi;
import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Runs the bundled Oracle driver's 12a authentication helper with MD5 operations forbidden. */
public final class OracleAuthenticationTest {
    private static final String PASSWORD = "synthetic-oracle-review-password";
    private static final byte[] SALT = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
    private static final byte[] SESSION_SALT = HexFormat.of().parseHex("ffeeddccbbaa99887766554433221100");
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private static final int ITERATIONS = 4096;

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        byte[] salt = Arrays.copyOf(SALT, SALT.length + "AUTH_PBKDF2_SPEEDY_KEY".length());
        System.arraycopy("AUTH_PBKDF2_SPEEDY_KEY".getBytes(StandardCharsets.US_ASCII), 0,
                salt, SALT.length, salt.length - SALT.length);
        byte[] verifier = pbkdf(PASSWORD.toCharArray(), salt, 512);
        MessageDigest sha = MessageDigest.getInstance("SHA-512", "BCFIPS");
        sha.update(verifier);
        byte[] key = Arrays.copyOf(sha.digest(SALT), 32);
        byte[] serverKey = new byte[48];
        Arrays.fill(serverKey, (byte) 0x37);
        byte[] challenge = asciiHex(aes(Cipher.ENCRYPT_MODE, key, serverKey));
        Security.insertProviderAt(new Provider("RejectAuthenticationMD5", "1.0", "Test MD5 guard") {
            {
                put("MessageDigest.MD5", RejectMd5.class.getName());
            }
        }, 1);
        O5Logon cached = null;
        if (mode.equals("healthy") || mode.equals("active-fault")) {
            cached = new O5Logon(null, true, true);
            authenticate(cached, challenge, key, serverKey, true);
        }
        if (mode.equals("healthy")) {
            System.out.println("Oracle 12a authentication and PBKDF2 session key passed without MD5");
            return;
        }
        if (mode.equals("missing-provider")) {
            Security.removeProvider("BCFIPS");
        } else if (mode.equals("readiness-fault") || mode.equals("active-fault")) {
            var fault = Class.forName("org.bouncycastle.crypto.fips.FipsStatus")
                    .getDeclaredMethod("moveToErrorStatus", String.class);
            fault.setAccessible(true);
            try {
                fault.invoke(null, "isolated Oracle authentication test");
            } catch (InvocationTargetException expected) {
                if (!org.bouncycastle.crypto.fips.FipsStatus.isErrorStatus()) {
                    throw expected;
                }
            }
        } else {
            throw new IllegalArgumentException(mode);
        }
        for (int retry = 0; retry < 3; retry++) {
            try {
                authenticate(cached == null ? new O5Logon(null, true, true) : cached,
                        challenge, key, serverKey, false);
                throw new AssertionError("Oracle authentication succeeded without a usable module");
            } catch (Exception | LinkageError | InternalError | org.bouncycastle.crypto.fips.FipsOperationError expected) {
                requireProviderFailure(expected);
                System.out.println("Oracle authentication rejected " + mode + " retry " + retry);
            }
        }
    }

    private static void authenticate(O5Logon helper, byte[] challenge, byte[] key, byte[] serverKey,
                                     boolean verify) throws Exception {
        byte[] clientChallenge = new byte[challenge.length];
        byte[] passwordResponse = new byte[512];
        int[] passwordLength = {0};
        try {
            helper.generateOAuthResponse(18453, asciiHex(SALT), "REVIEW", PASSWORD,
                    PASSWORD.getBytes(StandardCharsets.UTF_8), challenge, clientChallenge, passwordResponse,
                    passwordLength, false, (byte) 2, asciiHex(SESSION_SALT), ITERATIONS, ITERATIONS,
                    new byte[512], new int[] {0});
        } catch (Exception | Error failure) {
            if (passwordLength[0] != 0) {
                throw new AssertionError("Oracle emitted a password response after module failure", failure);
            }
            throw failure;
        }
        if (passwordLength[0] == 0 || helper.getO5LogonKey().length != 32) {
            throw new AssertionError("Oracle did not generate a 12a response");
        }
        if (verify) {
            byte[] clientKey = aes(Cipher.DECRYPT_MODE, key,
                    HEX.parseHex(new String(clientChallenge, StandardCharsets.US_ASCII)));
            byte[] sessionInput = Arrays.copyOf(clientKey, 64);
            System.arraycopy(serverKey, 0, sessionInput, 32, 32);
            byte[] expected = pbkdf(HEX.formatHex(sessionInput).toCharArray(), SESSION_SALT, 256);
            if (!Arrays.equals(expected, helper.getO5LogonKey())) {
                throw new AssertionError("Oracle session key does not match BCFIPS PBKDF2-HMAC-SHA512");
            }
        }
    }

    private static byte[] pbkdf(char[] password, byte[] salt, int bits) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
        if (!factory.getProvider().getName().equals("BCFIPS")) {
            throw new AssertionError("Oracle PBKDF2 must select BCFIPS");
        }
        return factory.generateSecret(new PBEKeySpec(password, salt, ITERATIONS, bits)).getEncoded();
    }

    private static byte[] aes(int mode, byte[] key, byte[] input) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding", "BCFIPS");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        return cipher.doFinal(input);
    }

    private static byte[] asciiHex(byte[] bytes) {
        return HEX.formatHex(bytes).getBytes(StandardCharsets.US_ASCII);
    }

    private static void requireProviderFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getName().startsWith("org.bouncycastle.crypto.fips.Fips")
                    || cause instanceof java.security.NoSuchAlgorithmException
                    || cause instanceof java.security.NoSuchProviderException
                    || "Resource A missing.".equals(cause.getMessage())
                    || (cause instanceof NoClassDefFoundError
                    && "Could not initialize class oracle.security.o5logon.O5Logon".equals(cause.getMessage()))) {
                return;
            }
        }
        throw new AssertionError("Unexpected Oracle authentication failure", failure);
    }

    public static final class RejectMd5 extends MessageDigestSpi {
        @Override
        protected void engineUpdate(byte input) {
            throw new AssertionError("Oracle authentication attempted MD5");
        }

        @Override
        protected void engineUpdate(byte[] input, int offset, int length) {
            throw new AssertionError("Oracle authentication attempted MD5");
        }

        @Override
        protected byte[] engineDigest() {
            throw new AssertionError("Oracle authentication attempted MD5");
        }

        @Override
        protected void engineReset() {
            throw new AssertionError("Oracle authentication attempted MD5");
        }
    }
}
