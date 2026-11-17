// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import org.bouncycastle.asn1.ocsp.BasicOCSPResponse;
import org.bouncycastle.asn1.ocsp.CertID;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.ocsp.OCSPResponse;
import org.bouncycastle.asn1.ocsp.ResponseBytes;
import org.bouncycastle.asn1.ocsp.SingleResponse;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.PKIXCertPathChecker;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.security.auth.x500.X500Principal;

/** Checks supplied OCSP bindings against the issuer in the path selected by BCFIPS. */
final class OcspResponsePolicy {
    private OcspResponsePolicy() {
    }

    static boolean removeUnboundResponses(PKIXParameters parameters, CertPath path, TrustAnchor anchor) {
        boolean changed = false;
        List<PKIXCertPathChecker> checkers = new ArrayList<>(parameters.getCertPathCheckers());
        for (int checkerIndex = 0; checkerIndex < checkers.size(); checkerIndex++) {
            PKIXCertPathChecker checker = checkers.get(checkerIndex);
            if (!(checker instanceof PKIXRevocationChecker)) {
                continue;
            }
            PKIXRevocationChecker revocation = (PKIXRevocationChecker) checker;
            Map<X509Certificate, byte[]> responses = new HashMap<>(revocation.getOcspResponses());
            boolean removed = false;
            for (int index = 0; index < path.getCertificates().size(); index++) {
                X509Certificate certificate = (X509Certificate) path.getCertificates().get(index);
                if (!responses.containsKey(certificate)
                        || (certificate.getBasicConstraints() >= 0
                        && revocation.getOptions().contains(PKIXRevocationChecker.Option.ONLY_END_ENTITY))) {
                    continue;
                }
                X509Certificate issuer = index + 1 < path.getCertificates().size()
                        ? (X509Certificate) path.getCertificates().get(index + 1) : anchor.getTrustedCert();
                X500Principal name = issuer == null ? anchor.getCA() : issuer.getSubjectX500Principal();
                PublicKey key = issuer == null ? anchor.getCAPublicKey() : issuer.getPublicKey();
                if (!matches(responses.get(certificate), certificate, name, key)) {
                    responses.remove(certificate);
                    changed = true;
                    removed = true;
                }
            }
            if (removed) {
                // BC's cloned checker retains internal state referring to the original
                // checker. Use a fresh instance so the retry cannot reuse its old response.
                checkers.set(checkerIndex, freshChecker(revocation, responses));
            }
        }
        if (changed) {
            parameters.setCertPathCheckers(checkers);
        }
        return changed;
    }

    private static PKIXRevocationChecker freshChecker(PKIXRevocationChecker original,
                                                      Map<X509Certificate, byte[]> responses) {
        try {
            var checker = (PKIXRevocationChecker) CertPathValidator.getInstance("PKIX", "BCFIPS").getRevocationChecker();
            if (original.getClass() != checker.getClass()) {
                throw new IllegalArgumentException("Cannot retry an unbound OCSP response with a custom revocation checker");
            }
            checker.setOcspResponder(original.getOcspResponder());
            checker.setOcspResponderCert(original.getOcspResponderCert());
            checker.setOcspExtensions(original.getOcspExtensions());
            checker.setOcspResponses(responses);
            checker.setOptions(original.getOptions());
            return checker;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot initialize BCFIPS revocation checking", e);
        }
    }

    private static boolean matches(byte[] encoded, X509Certificate certificate, X500Principal issuer,
                                   PublicKey issuerKey) {
        try {
            OCSPResponse response = OCSPResponse.getInstance(encoded);
            ResponseBytes bytes = response.getResponseBytes();
            if (response.getResponseStatus().getIntValue() != 0 || bytes == null
                    || !OCSPObjectIdentifiers.id_pkix_ocsp_basic.equals(bytes.getResponseType())) {
                return false;
            }
            BasicOCSPResponse basic = BasicOCSPResponse.getInstance(bytes.getResponse().getOctets());
            byte[] key = SubjectPublicKeyInfo.getInstance(issuerKey.getEncoded()).getPublicKeyData().getBytes();
            for (var entry : basic.getTbsResponseData().getResponses()) {
                CertID id = SingleResponse.getInstance(entry).getCertID();
                if (!certificate.getSerialNumber().equals(id.getSerialNumber().getValue())) {
                    continue;
                }
                MessageDigest digest = MessageDigest.getInstance(id.getHashAlgorithm().getAlgorithm().getId(), "BCFIPS");
                if (MessageDigest.isEqual(id.getIssuerNameHash().getOctets(), digest.digest(issuer.getEncoded()))
                        && MessageDigest.isEqual(id.getIssuerKeyHash().getOctets(), digest.digest(key))) {
                    return true;
                }
            }
            return false;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
