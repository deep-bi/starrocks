// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.cert.CRL;
import java.security.cert.CRLException;
import java.security.cert.CertPath;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathBuilderResult;
import java.security.cert.CertPathBuilderSpi;
import java.security.cert.CertPathChecker;
import java.security.cert.CertPathParameters;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertPathValidatorResult;
import java.security.cert.CertPathValidatorSpi;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateFactorySpi;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.PKIXCertPathValidatorResult;
import java.security.cert.PKIXParameters;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Adds certificate policy checks while delegating parsing and cryptographic validation to BCFIPS. */
public final class FipsCertificateProvider extends Provider {
    public static final String NAME = "StarRocksPKIX";

    public FipsCertificateProvider() {
        super(NAME, "1.0", "Certificate policy for the pinned BCFIPS module");
        putService(new Service(this, "CertificateFactory", "X.509", Factory.class.getName(),
                List.of("X509"), Map.of()));
        putService(new Service(this, "CertPathBuilder", "PKIX", Builder.class.getName(), List.of(), Map.of()));
        putService(new Service(this, "CertPathValidator", "PKIX", Validator.class.getName(), List.of(), Map.of()));
    }

    public static final class Builder extends CertPathBuilderSpi {
        private final CertPathBuilder delegate = CertPathBuilder.getInstance("PKIX", "BCFIPS");

        public Builder() throws NoSuchAlgorithmException, NoSuchProviderException {
        }

        @Override
        public CertPathBuilderResult engineBuild(CertPathParameters parameters)
                throws CertPathBuilderException, InvalidAlgorithmParameterException {
            PKIXParameters checked = CertificatePolicy.checkedParameters(parameters);
            while (true) {
                PKIXCertPathBuilderResult result = (PKIXCertPathBuilderResult) delegate.build(checked);
                if (!OcspResponsePolicy.removeUnboundResponses(checked, result.getCertPath(), result.getTrustAnchor())) {
                    return result;
                }
                // Do not return a path accepted using an unrelated supplied response. Retry
                // without that response so the configured OCSP/CRL policy remains effective.
            }
        }

        @Override
        public CertPathChecker engineGetRevocationChecker() {
            return delegate.getRevocationChecker();
        }
    }

    public static final class Validator extends CertPathValidatorSpi {
        private final CertPathValidator delegate = CertPathValidator.getInstance("PKIX", "BCFIPS");

        public Validator() throws NoSuchAlgorithmException, NoSuchProviderException {
        }

        @Override
        public CertPathValidatorResult engineValidate(CertPath path, CertPathParameters parameters)
                throws CertPathValidatorException, InvalidAlgorithmParameterException {
            PKIXParameters checked = CertificatePolicy.checkedParameters(parameters);
            while (true) {
                PKIXCertPathValidatorResult result = (PKIXCertPathValidatorResult) delegate.validate(path, checked);
                if (!OcspResponsePolicy.removeUnboundResponses(checked, path, result.getTrustAnchor())) {
                    return result;
                }
            }
        }

        @Override
        public CertPathChecker engineGetRevocationChecker() {
            return delegate.getRevocationChecker();
        }
    }

    public static final class Factory extends CertificateFactorySpi {
        private final CertificateFactory delegate = CertificateFactory.getInstance("X.509", "BCFIPS");

        public Factory() throws CertificateException, NoSuchProviderException {
        }

        @Override
        public Certificate engineGenerateCertificate(InputStream input) throws CertificateException {
            return delegate.generateCertificate(input);
        }

        @Override
        public Collection<? extends Certificate> engineGenerateCertificates(InputStream input) throws CertificateException {
            return delegate.generateCertificates(input);
        }

        @Override
        public CRL engineGenerateCRL(InputStream input) throws CRLException {
            return delegate.generateCRL(input);
        }

        @Override
        public Collection<? extends CRL> engineGenerateCRLs(InputStream input) throws CRLException {
            return delegate.generateCRLs(input);
        }

        @Override
        public CertPath engineGenerateCertPath(InputStream input) throws CertificateException {
            return delegate.generateCertPath(input);
        }

        @Override
        public CertPath engineGenerateCertPath(InputStream input, String encoding) throws CertificateException {
            return delegate.generateCertPath(input, encoding);
        }

        @Override
        public CertPath engineGenerateCertPath(List<? extends Certificate> certificates) throws CertificateException {
            return delegate.generateCertPath(certificates);
        }

        @Override
        public Iterator<String> engineGetCertPathEncodings() {
            return delegate.getCertPathEncodings();
        }
    }
}
