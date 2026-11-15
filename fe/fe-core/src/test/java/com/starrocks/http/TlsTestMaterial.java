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

package com.starrocks.http;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * A self-signed server certificate with its key in a PKCS12 file, for tests that need a real TLS listener.
 */
public final class TlsTestMaterial {
    public static final String PASSWORD = "changeit";

    private final File keyStoreFile;
    private final X509Certificate certificate;

    private TlsTestMaterial(File keyStoreFile, X509Certificate certificate) {
        this.keyStoreFile = keyStoreFile;
        this.certificate = certificate;
    }

    /**
     * Creates a certificate that is valid for the given DNS names only. It has no IP address entries.
     */
    public static TlsTestMaterial create(String... dnsNames) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();

        X500Name subject = new X500Name("CN=" + dnsNames[0]);
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, new BigInteger(64, new SecureRandom()), Date.from(now.minus(1, ChronoUnit.MINUTES)),
                Date.from(now.plus(2, ChronoUnit.DAYS)), subject, keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        GeneralName[] names = Arrays.stream(dnsNames)
                .map(name -> new GeneralName(GeneralName.dNSName, name))
                .toArray(GeneralName[]::new);
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(builder.build(signer).getEncoded()));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("fe", keyPair.getPrivate(), PASSWORD.toCharArray(), new Certificate[] {certificate});
        File file = File.createTempFile("fe-https-test", ".p12");
        file.deleteOnExit();
        try (OutputStream out = new FileOutputStream(file)) {
            keyStore.store(out, PASSWORD.toCharArray());
        }
        return new TlsTestMaterial(file, certificate);
    }

    public File getKeyStoreFile() {
        return keyStoreFile;
    }

    /**
     * A trust manager that trusts this certificate and nothing else.
     */
    public X509TrustManager getTrustManager() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("fe", certificate);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        for (TrustManager trustManager : factory.getTrustManagers()) {
            if (trustManager instanceof X509TrustManager) {
                return (X509TrustManager) trustManager;
            }
        }
        throw new IllegalStateException("no X509TrustManager");
    }

    /**
     * A client TLS context that trusts this certificate and nothing else.
     */
    public SSLContext newTrustingContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {getTrustManager()}, null);
        return context;
    }
}
