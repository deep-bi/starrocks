// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import org.bouncycastle.jcajce.util.JcaJceHelper;
import org.bouncycastle.jcajce.util.NamedJcaJceHelper;
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider;

import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;

/** Keeps BCJSSE cryptography in BCFIPS and applies the shared certificate path policy. */
public final class FipsTlsCryptoProvider extends JcaTlsCryptoProvider {
    private final JcaJceHelper helper = new NamedJcaJceHelper("BCFIPS") {
        @Override
        public CertificateFactory createCertificateFactory(String algorithm)
                throws CertificateException, NoSuchProviderException {
            // BCJSSE selects its path builder using the certificate factory's provider.
            return CertificateFactory.getInstance(algorithm, FipsCertificateProvider.NAME);
        }
    };

    public FipsTlsCryptoProvider() {
        super.setProvider("BCFIPS");
    }

    @Override
    public JcaJceHelper getHelper() {
        return helper;
    }
}
