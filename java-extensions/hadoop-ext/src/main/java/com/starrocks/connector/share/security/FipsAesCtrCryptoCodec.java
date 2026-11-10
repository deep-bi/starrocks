// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.JceAesCtrCryptoCodec;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;

/** Hadoop's JCE AES-CTR implementation with explicit BCFIPS selection and no RNG fallback. */
public final class FipsAesCtrCryptoCodec extends JceAesCtrCryptoCodec {
    private static final Map<String, String> SETTINGS = Map.of(
            "hadoop.security.crypto.codec.classes.aes.ctr.nopadding", FipsAesCtrCryptoCodec.class.getName(),
            "hadoop.security.crypto.codec.classes.sm4.ctr.nopadding", "",
            "hadoop.security.crypto.cipher.suite", "AES/CTR/NoPadding",
            "hadoop.security.crypto.jce.provider", "BCFIPS",
            "hadoop.security.crypto.jce.provider.auto-add", "false",
            "hadoop.security.java.secure.random.algorithm", "DEFAULT");

    private Configuration conf;
    private SecureRandom random;

    /** Called before filesystem creation, including configurations supplied by catalogs or JNI. */
    public static void configure(Configuration conf) {
        SETTINGS.forEach((name, expected) -> {
            String value = conf.getTrimmed(name);
            String[] sources = conf.getPropertySources(name);
            // Replace Hadoop's built-in defaults, but reject incompatible deployment/catalog overrides.
            boolean hadoopDefault = sources != null && sources.length > 0
                    && Arrays.stream(sources).allMatch("core-default.xml"::equals);
            if (value != null && !expected.equals(value) && !hadoopDefault) {
                throw new IllegalArgumentException("Java FIPS requires " + name + "=" + expected);
            }
        });
        SETTINGS.forEach(conf::set);
    }

    @Override
    public void setConf(Configuration conf) {
        configure(conf);
        this.conf = conf;
        try {
            // The upstream setConf catches provider/algorithm errors and falls back to new SecureRandom().
            random = SecureRandom.getInstance("DEFAULT", "BCFIPS");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Hadoop requires BCFIPS secure randomness", e);
        }
    }

    @Override
    public Configuration getConf() {
        return conf;
    }

    @Override
    public String getProvider() {
        return "BCFIPS";
    }

    @Override
    public void generateSecureRandom(byte[] bytes) {
        random.nextBytes(bytes);
    }
}
