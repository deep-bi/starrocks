// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralSubtree;
import org.bouncycastle.asn1.x509.NameConstraints;

import java.security.InvalidAlgorithmParameterException;
import java.security.cert.CertPathParameters;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.PKIXCertPathChecker;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Certificate restrictions for the pinned BCFIPS module, shared by PKIX building and validation. */
final class CertificatePolicy {
    private CertificatePolicy() {
    }

    static PKIXParameters checkedParameters(CertPathParameters parameters)
            throws InvalidAlgorithmParameterException {
        if (!(parameters instanceof PKIXParameters)) {
            throw new InvalidAlgorithmParameterException("PKIX parameters are required");
        }
        PKIXParameters checked = (PKIXParameters) ((PKIXParameters) parameters).clone();
        Set<TrustAnchor> supportedAnchors = new LinkedHashSet<>();
        Exception rejection = null;
        for (TrustAnchor anchor : checked.getTrustAnchors()) {
            try {
                if (anchor.getNameConstraints() != null) {
                    rejectUnsupportedConstraints(NameConstraints.getInstance(anchor.getNameConstraints()));
                }
                if (anchor.getTrustedCert() != null) {
                    checkCertificate(anchor.getTrustedCert());
                }
                supportedAnchors.add(anchor);
            } catch (CertPathValidatorException | IllegalArgumentException e) {
                rejection = e;
            }
        }
        if (supportedAnchors.isEmpty()) {
            throw new InvalidAlgorithmParameterException("No trust anchor has supported name constraints", rejection);
        }
        // An unrelated constrained anchor must not prevent building a path to a supported anchor.
        checked.setTrustAnchors(supportedAnchors);
        checked.addCertPathChecker(new NameConstraintsChecker());
        return checked;
    }

    private static void checkCertificate(X509Certificate certificate) throws CertPathValidatorException {
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            if (names != null) {
                for (List<?> name : names) {
                    if (Integer.valueOf(GeneralName.dNSName).equals(name.get(0))) {
                        rejectTrailingDotDnsName((String) name.get(1));
                    }
                }
            }
        } catch (CertificateException e) {
            throw new CertPathValidatorException("Invalid certificate subject alternative names", e);
        }
        byte[] encoded = certificate.getExtensionValue(Extension.nameConstraints.getId());
        if (encoded == null) {
            return;
        }
        try {
            byte[] constraints = ASN1OctetString.getInstance(encoded).getOctets();
            rejectUnsupportedConstraints(NameConstraints.getInstance(constraints));
        } catch (IllegalArgumentException e) {
            throw new CertPathValidatorException("Invalid certificate name constraints", e);
        }
    }

    private static void rejectTrailingDotDnsName(String name) throws CertPathValidatorException {
        if (name.endsWith(".")) {
            throw new CertPathValidatorException(
                    "DNS names ending in a dot are unsupported with the pinned BCFIPS module");
        }
    }

    private static void rejectUnsupportedConstraints(NameConstraints constraints) throws CertPathValidatorException {
        rejectUnsupportedSubtrees(constraints.getPermittedSubtrees());
        rejectUnsupportedSubtrees(constraints.getExcludedSubtrees());
    }

    private static void rejectUnsupportedSubtrees(GeneralSubtree[] subtrees) throws CertPathValidatorException {
        if (subtrees == null) {
            return;
        }
        for (GeneralSubtree subtree : subtrees) {
            int tag = subtree.getBase().getTagNo();
            if (tag == GeneralName.dNSName) {
                rejectTrailingDotDnsName(DERIA5String.getInstance(subtree.getBase().getName()).getString());
            }
            if (tag == GeneralName.rfc822Name || tag == GeneralName.uniformResourceIdentifier) {
                // The pinned module mishandles trailing dots in these constraints (CVE-2026-8763).
                // Reject affected paths rather than reimplementing the module's name matching.
                throw new CertPathValidatorException(
                        "Email and URI name constraints are unsupported with the pinned BCFIPS module");
            }
        }
    }

    private static final class NameConstraintsChecker extends PKIXCertPathChecker {
        @Override
        public void init(boolean forward) throws CertPathValidatorException {
            if (forward) {
                throw new CertPathValidatorException("Forward checking is unsupported");
            }
        }

        @Override
        public boolean isForwardCheckingSupported() {
            return false;
        }

        @Override
        public Set<String> getSupportedExtensions() {
            // BCFIPS still validates and resolves the extension's supported name forms.
            return Set.of();
        }

        @Override
        public void check(Certificate certificate, Collection<String> unresolvedCriticalExtensions)
                throws CertPathValidatorException {
            if (!(certificate instanceof X509Certificate)) {
                throw new CertPathValidatorException("An X.509 certificate is required");
            }
            checkCertificate((X509Certificate) certificate);
        }
    }
}
