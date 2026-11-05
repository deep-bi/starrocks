// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.UnknownStatus;
import org.bouncycastle.cert.ocsp.jcajce.JcaBasicOCSPRespBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Exercises supplied OCSP responses and CRL fallback through the production PKIX and BCJSSE services. */
public final class OcspRevocationPolicyTest {
    private final Date before = new Date(System.currentTimeMillis() - 60000);
    private final Date after = new Date(System.currentTimeMillis() + 3600000);
    private final KeyPair rootKey = keyPair();
    private final X509Certificate root = certificate("CN=OCSP Root", "CN=OCSP Root", 1, rootKey, true);
    private final X509Certificate leaf = certificate("CN=OCSP Root", "CN=OCSP Leaf", 2, keyPair(), false);

    private OcspRevocationPolicyTest() throws Exception {
    }

    public static void main(String[] args) throws Exception {
        exercise();
    }

    public static void exercise() throws Exception {
        OcspRevocationPolicyTest test = new OcspRevocationPolicyTest();
        byte[] good = test.response(test.root, test.leaf.getSerialNumber(), CertificateStatus.GOOD);
        byte[] wrongSerial = test.response(test.root, BigInteger.valueOf(99), CertificateStatus.GOOD);
        test.check("matching good response", good, true, false, false);
        test.check("revoked response", test.response(test.root, test.leaf.getSerialNumber(),
                new RevokedStatus(test.before, 1)), false, false, false);
        test.check("unknown response", test.response(test.root, test.leaf.getSerialNumber(),
                new UnknownStatus()), false, false, false);
        test.check("unrelated serial", wrongSerial, false, false, false);
        X509Certificate otherName = test.certificate("CN=Other Root", "CN=Other Root", 3, test.rootKey, true);
        test.check("unrelated issuer name", test.response(otherName, test.leaf.getSerialNumber(),
                CertificateStatus.GOOD), false, false, false);
        X509Certificate otherKey = test.certificate("CN=OCSP Root", "CN=OCSP Root", 4, keyPair(), true);
        test.check("unrelated issuer key", test.response(otherKey, test.leaf.getSerialNumber(),
                CertificateStatus.GOOD), false, false, false);
        test.check("CRL fallback permits valid certificate", wrongSerial, true, true, false);
        test.check("CRL fallback rejects revoked certificate", wrongSerial, false, true, true);
        System.out.println("OCSP bindings, revoked/unknown responses and CRL fallback passed for PKIX and BCJSSE");
    }

    private void check(String label, byte[] response, boolean accepted, boolean fallback, boolean crlRevoked)
            throws Exception {
        for (String operation : List.of("validator", "builder", "BCJSSE")) {
            X509CertSelector selector = new X509CertSelector();
            selector.setCertificate(leaf);
            PKIXBuilderParameters parameters = new PKIXBuilderParameters(Set.of(new TrustAnchor(root, null)), selector);
            parameters.setRevocationEnabled(false); // The explicit checker below still performs revocation checking.
            var validator = CertPathValidator.getInstance("PKIX");
            var builder = CertPathBuilder.getInstance("PKIX");
            if (!validator.getProvider().getName().equals("StarRocksPKIX")
                    || !builder.getProvider().getName().equals("StarRocksPKIX")) {
                throw new AssertionError("Test must use the production certificate policy");
            }
            var checker = (PKIXRevocationChecker) (operation.equals("builder")
                    ? builder.getRevocationChecker() : validator.getRevocationChecker());
            checker.setOptions(fallback ? Set.of() : Set.of(PKIXRevocationChecker.Option.NO_FALLBACK));
            checker.setOcspResponses(Map.of(leaf, response));
            parameters.addCertPathChecker(checker);
            var crlBuilder = new JcaX509v2CRLBuilder(root, before).setNextUpdate(after);
            if (crlRevoked) {
                crlBuilder.addCRLEntry(leaf.getSerialNumber(), before, 1);
            }
            var crl = new JcaX509CRLConverter().setProvider("BCFIPS").getCRL(crlBuilder.build(
                    new JcaContentSignerBuilder("SHA256withRSA").setProvider("BCFIPS").build(rootKey.getPrivate())));
            parameters.addCertStore(CertStore.getInstance("Collection",
                    new CollectionCertStoreParameters(fallback ? List.of(leaf, crl) : List.of(leaf))));
            boolean success;
            try {
                if (operation.equals("validator")) {
                    validator.validate(CertificateFactory.getInstance("X.509").generateCertPath(List.of(leaf)), parameters);
                } else if (operation.equals("builder")) {
                    builder.build(parameters);
                } else {
                    TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX", "BCJSSE");
                    factory.init(new CertPathTrustManagerParameters(parameters));
                    ((X509TrustManager) factory.getTrustManagers()[0])
                            .checkServerTrusted(new X509Certificate[] {leaf}, "ECDHE_RSA");
                }
                success = true;
            } catch (CertPathValidatorException | CertPathBuilderException | CertificateException expected) {
                success = false;
            }
            if (success != accepted) {
                throw new AssertionError(label + " " + operation + " accepted=" + success);
            }
            var original = (PKIXRevocationChecker) parameters.getCertPathCheckers().get(0);
            if (!Arrays.equals(response, original.getOcspResponses().get(leaf))) {
                throw new AssertionError("Certificate policy modified caller OCSP responses");
            }
        }
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "BCFIPS");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private X509Certificate certificate(String issuer, String subject, int serial, KeyPair key, boolean ca)
            throws Exception {
        var builder = new JcaX509v3CertificateBuilder(new X500Name(issuer), BigInteger.valueOf(serial), before, after,
                new X500Name(subject), key.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(ca ? KeyUsage.keyCertSign | KeyUsage.cRLSign : KeyUsage.digitalSignature));
        return new JcaX509CertificateConverter().setProvider("BCFIPS").getCertificate(builder.build(
                new JcaContentSignerBuilder("SHA256withRSA").setProvider("BCFIPS").build(rootKey.getPrivate())));
    }

    private byte[] response(X509Certificate issuer, BigInteger serial, CertificateStatus status) throws Exception {
        var digests = new JcaDigestCalculatorProviderBuilder().setProvider("BCFIPS").build();
        var id = new CertificateID(digests.get(CertificateID.HASH_SHA1), new JcaX509CertificateHolder(issuer), serial);
        var builder = new JcaBasicOCSPRespBuilder(rootKey.getPublic(), digests.get(CertificateID.HASH_SHA1));
        builder.addResponse(id, status, before, after, null);
        var signed = builder.build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BCFIPS")
                .build(rootKey.getPrivate()), new X509CertificateHolder[] {new JcaX509CertificateHolder(root)}, new Date());
        return new OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, signed).getEncoded();
    }
}
