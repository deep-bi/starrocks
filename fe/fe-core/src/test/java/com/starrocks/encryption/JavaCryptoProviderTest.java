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
package com.starrocks.encryption;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.starrocks.authentication.AuthenticationException;
import com.starrocks.authentication.OpenIdConnectVerifier;
import com.starrocks.common.Config;
import com.starrocks.common.util.CryptoUtils;
import com.starrocks.mysql.MysqlPassword;
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.proto.EncryptionAlgorithmPB;
import com.starrocks.proto.EncryptionKeyPB;
import org.apache.parquet.crypto.AesGcmDecryptor;
import org.apache.parquet.crypto.AesGcmEncryptor;
import org.apache.parquet.crypto.AesMode;
import org.apache.parquet.crypto.ModuleCipherFactory;
import org.bouncycastle.crypto.CryptoServicesRegistrar;
import org.bouncycastle.crypto.fips.FipsStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Provider;
import java.security.Security;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.crypto.KeyGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCryptoProviderTest {
    @Test
    void persistedKeysAreReimportedInAFreshJvm(@TempDir Path directory) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root.getParent() != null && !Files.isRegularFile(root.resolve("conf/java-fips.security"))) {
            root = root.getParent();
        }
        Path security = root.resolve("conf/java-fips.security");
        assertTrue(Files.isRegularFile(security), "Production security overlay was not found");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String operation : new String[] {"write", "restore"}) {
            Path log = directory.resolve(operation + ".log");
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Djava.security.properties=" + security,
                    "-Dorg.bouncycastle.fips.approved_only=true", "-Dorg.bouncycastle.native.cpu_variant=java",
                    "-cp", classpath, RestartWorker.class.getName(), operation, directory.toString())
                    .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            assertTrue(finished, "Restart worker timed out: " + operation);
            assertEquals(0, process.exitValue(), Files.readString(log));
        }
        NormalKey master = (NormalKey) EncryptionKey.createFromSpec(Files.readString(directory.resolve("master.spec")));
        byte[] wrapped = Files.readAllBytes(directory.resolve("new-wrap.bin"));
        AesGcmDecryptor legacy = (AesGcmDecryptor) ModuleCipherFactory.getDecryptor(AesMode.GCM, master.getPlainKey());
        assertArrayEquals(Files.readAllBytes(directory.resolve("new-key.bin")),
                legacy.decrypt(wrapped, 0, wrapped.length, null));
    }

    public static class RestartWorker {
        public static void main(String[] args) throws Exception {
            assertTrue(FipsStatus.isReady());
            assertTrue(CryptoServicesRegistrar.isInApprovedOnlyMode());
            assertEquals("BCFIPS", KeyGenerator.getInstance("AES").getProvider().getName());
            assertEquals("BCFIPS", CryptoUtils.secureRandom().getProvider().getName());
            Path directory = Path.of(args[1]);
            KeyMgr manager = new KeyMgr();
            if ("write".equals(args[0])) {
                NormalKey master = NormalKey.createRandom();
                master.setId(KeyMgr.DEFAULT_MASTER_KYE_ID);
                NormalKey key = (NormalKey) master.generateKey();
                key.setId(2);
                EncryptionKeyPB masterPb = new EncryptionKeyPB();
                EncryptionKeyPB keyPb = new EncryptionKeyPB();
                master.toPB(masterPb, manager);
                key.toPB(keyPb, manager);
                Files.writeString(directory.resolve("master.json"), GsonUtils.GSON.toJson(masterPb));
                Files.writeString(directory.resolve("key.json"), GsonUtils.GSON.toJson(keyPb));
                Files.writeString(directory.resolve("master.spec"), master.toSpec());
                Files.write(directory.resolve("original-key.bin"), key.getPlainKey());
            } else {
                manager.replayAddKey(GsonUtils.GSON.fromJson(Files.readString(directory.resolve("master.json")),
                        EncryptionKeyPB.class));
                manager.replayAddKey(GsonUtils.GSON.fromJson(Files.readString(directory.resolve("key.json")),
                        EncryptionKeyPB.class));
                Config.default_master_key = Files.readString(directory.resolve("master.spec"));
                manager.initDefaultMasterKey();
                NormalKey master = (NormalKey) manager.getKeyById(KeyMgr.DEFAULT_MASTER_KYE_ID);
                NormalKey key = (NormalKey) manager.getKeyById(2);
                master.decryptKey(key);
                assertArrayEquals(Files.readAllBytes(directory.resolve("original-key.bin")), key.getPlainKey());
                NormalKey next = (NormalKey) master.generateKey();
                assertEquals(44, next.getEncryptedKey().length);
                assertFalse(Arrays.equals(Arrays.copyOf(key.getEncryptedKey(), 12),
                        Arrays.copyOf(next.getEncryptedKey(), 12)));
                Files.write(directory.resolve("new-wrap.bin"), next.getEncryptedKey());
                Files.write(directory.resolve("new-key.bin"), next.getPlainKey());
            }
        }
    }

    @Test
    void wrappingReadsBothFormatsAndRejectsTampering() {
        byte[] parent = EncryptionUtil.genRandomKey(16);
        byte[] key = EncryptionUtil.genRandomKey(16);
        byte[] wrapped = EncryptionUtil.wrapKey(parent, EncryptionAlgorithmPB.AES_128, key);
        assertEquals(44, wrapped.length);
        assertArrayEquals(key, EncryptionUtil.unwrapKey(parent, EncryptionAlgorithmPB.AES_128, wrapped));
        AesGcmDecryptor parquet = (AesGcmDecryptor) ModuleCipherFactory.getDecryptor(AesMode.GCM, parent);
        assertArrayEquals(key, parquet.decrypt(wrapped, 0, wrapped.length, null));
        AesGcmEncryptor legacy = (AesGcmEncryptor) ModuleCipherFactory.getEncryptor(AesMode.GCM, parent);
        byte[] old = legacy.encrypt(false, key, null);
        assertArrayEquals(key, EncryptionUtil.unwrapKey(parent, EncryptionAlgorithmPB.AES_128, old));
        byte[] second = EncryptionUtil.wrapKey(parent, EncryptionAlgorithmPB.AES_128, key);
        assertFalse(Arrays.equals(Arrays.copyOf(wrapped, 12), Arrays.copyOf(second, 12)));
        wrapped[wrapped.length - 1] ^= 1;
        assertThrows(RuntimeException.class, () -> EncryptionUtil.unwrapKey(parent, EncryptionAlgorithmPB.AES_128, wrapped));
        assertThrows(RuntimeException.class, () -> EncryptionUtil.unwrapKey(parent, EncryptionAlgorithmPB.AES_128, new byte[4]));
        assertThrows(RuntimeException.class, () -> EncryptionUtil.wrapKey(new byte[5], EncryptionAlgorithmPB.AES_128, key));
    }

    @Test
    void mysqlProtocolAndStoredHashStayCompatible() {
        assertEquals("*2470C0C06DEE42FD1618BB99005ADCA2EC9D1E19",
                new String(MysqlPassword.makeScrambledPassword("password"), StandardCharsets.US_ASCII));
        byte[] challenge = MysqlPassword.createRandomString();
        byte[] reply = MysqlPassword.scramble(challenge, "password");
        byte[] stored = MysqlPassword.getSaltFromPassword(MysqlPassword.makeScrambledPassword("password"));
        assertTrue(MysqlPassword.checkScramble(reply, challenge, stored));
        reply[0] ^= 1;
        assertFalse(MysqlPassword.checkScramble(reply, challenge, stored));
        assertEquals("BCFIPS", CryptoUtils.messageDigest("SHA-1").getProvider().getName());
        assertEquals("BCFIPS", CryptoUtils.secureRandom().getProvider().getName());
    }

    @Test
    void explicitDigestCannotFallThroughToSun() throws Exception {
        assertEquals("SUN", java.security.MessageDigest.getInstance("MD5", "SUN").getProvider().getName());
        assertThrows(IllegalStateException.class, () -> CryptoUtils.messageDigest("MD5"));
    }

    @Test
    void missingProviderFailsWithoutOrdinaryCryptoRetry() {
        byte[] parent = EncryptionUtil.genRandomKey(16);
        Provider core = Security.getProvider("BCFIPS");
        Security.removeProvider("BCFIPS");
        try {
            assertThrows(IllegalStateException.class, () -> EncryptionUtil.genRandomKey(16));
            assertThrows(IllegalStateException.class,
                    () -> EncryptionUtil.wrapKey(parent, EncryptionAlgorithmPB.AES_128, parent));
            assertThrows(IllegalStateException.class, MysqlPassword::createRandomString);
            assertThrows(IllegalStateException.class, () -> MysqlPassword.makeScrambledPassword("password"));
        } finally {
            Security.addProvider(core);
        }
    }

    @Test
    void rsaJwtVerificationAndInvalidSignatures() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "SunRsaSign");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).keyID("test").build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),
                new JWTClaimsSet.Builder().subject("test-user").build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate()));
        OpenIdConnectVerifier.verify(jwt.serialize(), "test-user", new JWKSet(key), "sub", null, null);
        RSAKey wrong = new RSAKey.Builder((RSAPublicKey) generator.generateKeyPair().getPublic()).keyID("test").build();
        assertThrows(AuthenticationException.class,
                () -> OpenIdConnectVerifier.verify(jwt.serialize(), "test-user", new JWKSet(wrong), "sub", null, null));
        Provider core = Security.getProvider("BCFIPS");
        Security.removeProvider("BCFIPS");
        try {
            assertThrows(AuthenticationException.class,
                    () -> OpenIdConnectVerifier.verify(jwt.serialize(), "test-user", new JWKSet(key), "sub", null, null));
        } finally {
            Security.addProvider(core);
        }
    }
}
