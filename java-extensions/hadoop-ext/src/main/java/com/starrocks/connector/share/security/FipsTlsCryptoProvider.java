// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import org.bouncycastle.crypto.CryptoServicesRegistrar;
import org.bouncycastle.crypto.fips.FipsStatus;
import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider;
import org.bouncycastle.jcajce.util.JcaJceHelper;
import org.bouncycastle.jcajce.util.NamedJcaJceHelper;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider;

import java.security.NoSuchProviderException;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;

/** Keeps BCJSSE cryptography in BCFIPS and applies the shared certificate path policy. */
public final class FipsTlsCryptoProvider extends JcaTlsCryptoProvider {
    /** Recheck cached clients before use, including operations that do not invoke JCA themselves. */
    public static void checkReady() {
        if (!(Security.getProvider(FipsCertificateProvider.NAME) instanceof FipsCertificateProvider)
                || !(Security.getProvider("BCFIPS") instanceof BouncyCastleFipsProvider)
                || !(Security.getProvider("BCJSSE") instanceof BouncyCastleJsseProvider jsse)
                || !jsse.isFipsMode() || !CryptoServicesRegistrar.isInApprovedOnlyMode()) {
            throw new IllegalStateException("LDAP and JDBC require approved-only BCFIPS and BCJSSE at runtime");
        }
        // Provider construction checks readiness. Avoid its startup lock on healthy pooled operations.
        if (FipsStatus.isErrorStatus()) {
            FipsStatus.isReady();
        }
    }

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
