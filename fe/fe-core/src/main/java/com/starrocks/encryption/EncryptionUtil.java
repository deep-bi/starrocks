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

import com.starrocks.common.util.CryptoUtils;
import com.starrocks.proto.EncryptionAlgorithmPB;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class EncryptionUtil {

    static byte[] genRandomKey(int len) {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES", CryptoUtils.fipsProvider());
            generator.init(Math.multiplyExact(len, 8), CryptoUtils.secureRandom());
            return generator.generateKey().getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("BCFIPS AES key generation failed", e);
        }
    }

    static byte[] wrapKey(byte[] parentPlainKey, EncryptionAlgorithmPB algorithm, byte[] plainKey) {
        switch (algorithm) {
            case AES_128:
                return wrapFipsKey(parentPlainKey, plainKey);
            default:
                throw new IllegalArgumentException("Unsupported encryption algorithm:" + algorithm);
        }
    }

    static byte[] unwrapKey(byte[] parentPlainKey, EncryptionAlgorithmPB algorithm, byte[] encryptedKey) {
        switch (algorithm) {
            case AES_128:
                return unwrapFipsKey(parentPlainKey, encryptedKey);
            default:
                throw new IllegalArgumentException("Unsupported encryption algorithm:" + algorithm);
        }
    }

    private static byte[] wrapFipsKey(byte[] parent, byte[] plainKey) {
        requireAes128(parent);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", CryptoUtils.fipsProvider());
            // Import the key bytes into this JVM's module; no provider key or cipher is persisted across restarts.
            // Let the module generate the 96-bit nonce internally, as required by its security policy.
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(parent, "AES"), CryptoUtils.secureRandom());
            byte[] ciphertext = cipher.doFinal(plainKey);
            byte[] iv = cipher.getIV();
            if (iv.length != 12 || ciphertext.length != plainKey.length + 16) {
                throw new IllegalStateException("BCFIPS GCM parameters do not match the stored key format");
            }
            // Parquet encrypt(false, ...) and BE use nonce || ciphertext || 128-bit tag.
            byte[] wrapped = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, wrapped, 0, iv.length);
            System.arraycopy(ciphertext, 0, wrapped, iv.length, ciphertext.length);
            return wrapped;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("BCFIPS key wrapping failed", e);
        }
    }

    private static byte[] unwrapFipsKey(byte[] parent, byte[] wrapped) {
        requireAes128(parent);
        if (wrapped.length < 28) {
            throw new IllegalArgumentException("Wrapped key is shorter than its nonce and authentication tag");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", CryptoUtils.fipsProvider());
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(parent, "AES"), new GCMParameterSpec(128, wrapped, 0, 12));
            return cipher.doFinal(wrapped, 12, wrapped.length - 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("BCFIPS key unwrapping failed", e);
        }
    }

    private static void requireAes128(byte[] key) {
        if (key == null || key.length != 16) {
            throw new IllegalArgumentException("AES_128 requires a 16-byte parent key");
        }
    }

}
