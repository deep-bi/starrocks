// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import com.starrocks.connector.hadoop.HadoopExt;
import com.starrocks.connector.share.security.FipsAesCtrCryptoCodec;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.CipherSuite;
import org.apache.hadoop.crypto.CryptoCodec;
import org.apache.hadoop.crypto.CryptoInputStream;
import org.apache.hadoop.crypto.CryptoOutputStream;
import org.apache.hadoop.fs.FileSystem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;

/** Exercises Hadoop's actual codec factory and filesystem configuration hook. */
public final class HadoopCryptoPolicyTest {
    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "healthy" : args[0];
        if (mode.equals("healthy")) {
            exercise();
            return;
        }
        CryptoCodec cached = mode.equals("active-fault") ? codec(new Configuration()) : null;
        org.apache.hadoop.crypto.Encryptor cachedEncryptor = null;
        org.apache.hadoop.crypto.Decryptor cachedDecryptor = null;
        if (cached != null) {
            cached.generateSecureRandom(new byte[32]);
            cachedEncryptor = cached.createEncryptor();
            cachedEncryptor.init(new byte[16], new byte[16]);
            cachedDecryptor = cached.createDecryptor();
            cachedDecryptor.init(new byte[16], new byte[16]);
        }
        if (mode.equals("missing-provider")) {
            Security.removeProvider("BCFIPS");
        } else {
            Class<?> status = Class.forName("org.bouncycastle.crypto.fips.FipsStatus");
            var fault = status.getDeclaredMethod("moveToErrorStatus", String.class);
            fault.setAccessible(true);
            try {
                fault.invoke(null, "isolated Hadoop crypto test error");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                // The module may throw while entering its error state.
            }
        }
        for (int retry = 0; retry < 2; retry++) {
            expectFailure(() -> codec(new Configuration()).generateSecureRandom(new byte[32]));
            if (cached != null) {
                CryptoCodec active = cached;
                org.apache.hadoop.crypto.Encryptor activeEncryptor = cachedEncryptor;
                org.apache.hadoop.crypto.Decryptor activeDecryptor = cachedDecryptor;
                expectFailure(() -> active.generateSecureRandom(new byte[32]));
                expectFailure(() -> active.createEncryptor().init(new byte[16], new byte[16]));
                expectFailure(() -> active.createDecryptor().init(new byte[16], new byte[16]));
                expectFailure(() -> activeEncryptor.encrypt(ByteBuffer.allocate(16), ByteBuffer.allocate(32)));
                expectFailure(() -> activeDecryptor.decrypt(ByteBuffer.allocate(16), ByteBuffer.allocate(32)));
            }
        }
        System.out.println("Hadoop crypto rejected " + mode + " without fallback");
    }

    public static void exercise() throws Exception {
        // Test both packaged core-site.xml and the native/catalog path without default resources.
        if (CryptoCodec.getInstance(new Configuration(), CipherSuite.SM4_CTR_NOPADDING) != null) {
            throw new AssertionError("Packaged Hadoop configuration enabled SM4");
        }
        try (CryptoCodec selected = codec(new Configuration())) {
            roundTrip(selected);
        }
        Configuration nativeConf = new Configuration(false);
        try (FileSystem ignored = FileSystem.newInstance(URI.create("file:///"), nativeConf);
                CryptoCodec selected = codec(nativeConf)) {
            roundTrip(selected);
        }
        Configuration defaultsOnly = new Configuration(false);
        defaultsOnly.addResource("core-default.xml");
        HadoopExt.getInstance().rewriteConfiguration(defaultsOnly);
        try (CryptoCodec selected = codec(defaultsOnly)) {
            roundTrip(selected);
        }
        for (String[] override : new String[][] {
                {"hadoop.security.crypto.codec.classes.aes.ctr.nopadding",
                        "org.apache.hadoop.crypto.OpensslAesCtrCryptoCodec"},
                {"hadoop.security.crypto.codec.classes.aes.ctr.nopadding",
                        FipsAesCtrCryptoCodec.class.getName() + ",org.apache.hadoop.crypto.JceAesCtrCryptoCodec"},
                {"hadoop.security.crypto.codec.classes.sm4.ctr.nopadding",
                        "org.apache.hadoop.crypto.OpensslSm4CtrCryptoCodec"},
                {"hadoop.security.crypto.cipher.suite", "SM4/CTR/NoPadding"},
                {"hadoop.security.crypto.jce.provider", "SUN"},
                {"hadoop.security.java.secure.random.algorithm", "SHA1PRNG"},
                {"hadoop.security.crypto.jce.provider.auto-add", "true"}}) {
            Configuration invalid = new Configuration();
            invalid.set(override[0], override[1]);
            try (FileSystem ignored = FileSystem.newInstance(URI.create("file:///"), invalid)) {
                throw new AssertionError("Filesystem accepted unapproved crypto setting " + override[0]);
            } catch (IllegalArgumentException expected) {
                if (!expected.getMessage().contains(override[0])) {
                    throw expected;
                }
            }
        }
        Configuration allowed = new Configuration();
        HadoopExt.getInstance().rewriteConfiguration(allowed);
        if (CryptoCodec.getInstance(allowed, CipherSuite.SM4_CTR_NOPADDING) != null) {
            throw new AssertionError("Hadoop selected an SM4 codec");
        }
        System.out.println("Hadoop AES-CTR, BCFIPS randomness and configuration rejection passed");
    }

    private static CryptoCodec codec(Configuration conf) throws Exception {
        CryptoCodec selected = CryptoCodec.getInstance(conf);
        if (selected == null) {
            throw new IllegalStateException("No approved Hadoop codec available");
        }
        if (selected.getClass() != FipsAesCtrCryptoCodec.class) {
            throw new AssertionError("Hadoop selected " + selected.getClass());
        }
        Field field = FipsAesCtrCryptoCodec.class.getDeclaredField("random");
        field.setAccessible(true);
        SecureRandom random = (SecureRandom) field.get(selected);
        if (!random.getProvider().getName().equals("BCFIPS") || !random.getAlgorithm().equals("DEFAULT")) {
            throw new AssertionError("Hadoop RNG selected " + random.getProvider());
        }
        Field cipherField = selected.createEncryptor().getClass().getDeclaredField("cipher");
        cipherField.setAccessible(true);
        javax.crypto.Cipher cipher = (javax.crypto.Cipher) cipherField.get(selected.createEncryptor());
        if (!cipher.getProvider().getName().equals("BCFIPS")) {
            throw new AssertionError("Hadoop cipher selected " + cipher.getProvider());
        }
        return selected;
    }

    private static void roundTrip(CryptoCodec codec) throws Exception {
        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        codec.generateSecureRandom(key);
        codec.generateSecureRandom(iv);
        byte[] plain = new byte[8193];
        Arrays.fill(plain, (byte) 42);
        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        try (CryptoOutputStream output = new CryptoOutputStream(encrypted, codec, 4096, key, iv)) {
            output.write(plain);
        }
        try (CryptoInputStream input = new CryptoInputStream(
                new ByteArrayInputStream(encrypted.toByteArray()), codec, 4096, key, iv)) {
            if (!Arrays.equals(plain, input.readAllBytes())) {
                throw new AssertionError("Hadoop AES-CTR round trip failed");
            }
        }
    }

    private interface Operation {
        void run() throws Exception;
    }

    private static void expectFailure(Operation operation) throws Exception {
        try {
            operation.run();
        } catch (Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.security.NoSuchProviderException
                        || cause instanceof java.security.NoSuchAlgorithmException
                        || cause.getClass().getName().startsWith("org.bouncycastle.crypto.fips.Fips")
                        || (cause instanceof IllegalStateException
                        && "No approved Hadoop codec available".equals(cause.getMessage()))) {
                    return;
                }
            }
            throw failure;
        }
        throw new AssertionError("Hadoop crypto operation succeeded after provider failure");
    }
}
