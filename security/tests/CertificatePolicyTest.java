// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.GeneralSubtree;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.NameConstraints;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathChecker;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Runs the production trust and certificate-path services with locally signed adversarial chains. */
public final class CertificatePolicyTest {
    private static final char[] PASSWORD = "certificate-policy-test".toCharArray();

    public static void main(String[] args) throws Exception {
        Fixture fixture = new Fixture();
        // Configure the actual default context before its first initialization in this JVM.
        Path trustStore = Files.createTempFile("starrocks-certificate-policy-", ".bcfks");
        try {
            try (var output = Files.newOutputStream(trustStore)) {
                fixture.trustStore.store(output, PASSWORD);
            }
            System.setProperty("javax.net.ssl.trustStore", trustStore.toString());
            System.setProperty("javax.net.ssl.trustStoreType", "BCFKS");
            System.setProperty("javax.net.ssl.trustStoreProvider", "BCFIPS");
            System.setProperty("javax.net.ssl.trustStorePassword", new String(PASSWORD));
            exercise(fixture);
            SSLContext context = SSLContext.getDefault();
            assertTlsProvider(context);
            for (Chain chain : fixture.chains) {
                checkTls(fixture, context, chain, "default TLS context");
            }
            System.out.println("Default BCJSSE context enforced certificate restrictions during TLS handshakes");
        } finally {
            Files.deleteIfExists(trustStore);
        }
    }

    public static void exercise() throws Exception {
        exercise(new Fixture());
    }

    private static void exercise(Fixture fixture) throws Exception {
        for (String provider : List.of("", "BCJSSE")) {
            TrustManagerFactory factory = provider.isEmpty()
                    ? TrustManagerFactory.getInstance("PKIX") : TrustManagerFactory.getInstance("PKIX", provider);
            if (!factory.getProvider().getName().equals("BCJSSE")) {
                throw new AssertionError("Unexpected trust manager provider: " + factory.getProvider());
            }
            factory.init(fixture.trustStore);
            X509TrustManager manager = (X509TrustManager) factory.getTrustManagers()[0];
            SSLContext context = provider.isEmpty()
                    ? SSLContext.getInstance("TLS") : SSLContext.getInstance("TLS", provider);
            context.init(null, factory.getTrustManagers(), null);
            assertTlsProvider(context);
            for (Chain chain : fixture.chains) {
                check(chain.accepted, chain.name + " server trust", CertificateException.class,
                        () -> manager.checkServerTrusted(chain.certificates, "ECDHE_RSA"));
                check(chain.accepted, chain.name + " client trust", CertificateException.class,
                        () -> manager.checkClientTrusted(chain.certificates, "RSA"));
                checkTls(fixture, context, chain, provider.isEmpty() ? "selected TLS context" : "explicit TLS context");
            }
        }
        for (Chain chain : fixture.chains) {
            PKIXParameters validation = new PKIXParameters(Set.of(new TrustAnchor(fixture.root, null)));
            validation.setRevocationEnabled(false);
            var path = CertificateFactory.getInstance("X.509").generateCertPath(List.of(chain.certificates));
            check(chain.accepted, chain.name + " PKIX validator", CertPathValidatorException.class,
                    () -> CertPathValidator.getInstance("PKIX").validate(path, validation));
            X509CertSelector selector = new X509CertSelector();
            selector.setCertificate(chain.certificates[0]);
            PKIXBuilderParameters building = new PKIXBuilderParameters(validation.getTrustAnchors(), selector);
            building.setRevocationEnabled(false);
            building.addCertStore(CertStore.getInstance("Collection",
                    new CollectionCertStoreParameters(List.of(chain.certificates))));
            check(chain.accepted, chain.name + " PKIX builder", CertPathBuilderException.class,
                    () -> CertPathBuilder.getInstance("PKIX").build(building));
        }
        checkAnchors(fixture);
        checkCallerPolicy(fixture);
        System.out.println("BCJSSE client/server trust and PKIX building/validation enforced certificate restrictions");
    }

    private static void checkAnchors(Fixture fixture) throws Exception {
        Chain valid = fixture.chains.get(0);
        for (int tag : List.of(GeneralName.rfc822Name, GeneralName.uniformResourceIdentifier)) {
            for (boolean permitted : List.of(false, true)) {
                GeneralSubtree[] subtrees = {new GeneralSubtree(new GeneralName(tag, "bank.com"))};
                NameConstraints constraints = new NameConstraints(
                        permitted ? subtrees : null, permitted ? null : subtrees);
                X509Certificate constrainedRoot = fixture.certificate("CN=PolicyRoot", "CN=PolicyRoot",
                        fixture.rootKey.getPublic(), fixture.rootKey.getPrivate(), true, constraints, null);
                for (TrustAnchor restricted : List.of(new TrustAnchor(fixture.root, constraints.getEncoded()),
                        new TrustAnchor(constrainedRoot, null))) {
                    for (boolean mixed : List.of(false, true)) {
                        Set<TrustAnchor> anchors = mixed ? Set.of(restricted, new TrustAnchor(fixture.root, null))
                                : Set.of(restricted);
                        PKIXParameters validation = new PKIXParameters(anchors);
                        validation.setRevocationEnabled(false);
                        var path = CertificateFactory.getInstance("X.509")
                                .generateCertPath(List.of(valid.certificates));
                        check(mixed, "constrained trust anchor validator", InvalidAlgorithmParameterException.class,
                                () -> CertPathValidator.getInstance("PKIX").validate(path, validation));
                        X509CertSelector selector = new X509CertSelector();
                        selector.setCertificate(valid.certificates[0]);
                        PKIXBuilderParameters building = new PKIXBuilderParameters(anchors, selector);
                        building.setRevocationEnabled(false);
                        building.addCertStore(CertStore.getInstance("Collection",
                                new CollectionCertStoreParameters(List.of(valid.certificates))));
                        check(mixed, "constrained trust anchor builder", InvalidAlgorithmParameterException.class,
                                () -> CertPathBuilder.getInstance("PKIX").build(building));
                        if (validation.getTrustAnchors().size() != anchors.size()
                                || building.getTrustAnchors().size() != anchors.size()) {
                            throw new AssertionError("Certificate policy modified the caller's trust anchors");
                        }
                    }
                }
            }
        }
    }

    private static void checkCallerPolicy(Fixture fixture) throws Exception {
        Chain valid = fixture.chains.get(0);
        var path = CertificateFactory.getInstance("X.509").generateCertPath(List.of(valid.certificates));
        X509CertSelector selector = new X509CertSelector();
        selector.setCertificate(valid.certificates[0]);
        PKIXBuilderParameters parameters = new PKIXBuilderParameters(
                Set.of(new TrustAnchor(fixture.root, null)), selector);
        parameters.setRevocationEnabled(false);
        parameters.addCertStore(CertStore.getInstance("Collection",
                new CollectionCertStoreParameters(List.of(valid.certificates))));
        AtomicInteger calls = new AtomicInteger();
        parameters.addCertPathChecker(new PKIXCertPathChecker() {
            @Override
            public void init(boolean forward) { }

            @Override
            public boolean isForwardCheckingSupported() {
                return false;
            }

            @Override
            public Set<String> getSupportedExtensions() {
                return Set.of();
            }

            @Override
            public void check(java.security.cert.Certificate certificate, Collection<String> unresolved)
                    throws CertPathValidatorException {
                calls.incrementAndGet();
                throw new CertPathValidatorException("Caller-supplied test policy rejection");
            }
        });
        check(false, "caller validator policy", CertPathValidatorException.class,
                () -> CertPathValidator.getInstance("PKIX").validate(path, parameters));
        if (calls.getAndSet(0) == 0) {
            throw new AssertionError("Validator dropped the caller's certificate policy");
        }
        check(false, "caller builder policy", CertPathBuilderException.class,
                () -> CertPathBuilder.getInstance("PKIX").build(parameters));
        if (calls.get() == 0 || parameters.getCertPathCheckers().size() != 1) {
            throw new AssertionError("Builder dropped the caller's policy or changed the supplied parameters");
        }
    }

    private static void checkTls(Fixture fixture, SSLContext client, Chain chain, String source) throws Exception {
        // Both supported protocol versions must reach certificate authentication.
        for (String protocol : List.of("TLSv1.2", "TLSv1.3")) {
            check(chain.accepted, chain.name + " " + source + " " + protocol, SSLException.class,
                    () -> handshake(client, fixture.serverContext(chain), protocol));
        }
    }

    private static void assertTlsProvider(SSLContext context) {
        if (!context.getProvider().getName().equals("BCJSSE")) {
            throw new AssertionError("TLS bypassed BCJSSE: " + context.getProvider());
        }
    }

    private static void check(boolean accepted, String label, Class<? extends Exception> rejection,
                              Operation operation) throws Exception {
        try {
            operation.run();
        } catch (Exception failure) {
            if (!accepted && rejection.isInstance(failure)) {
                if (failure instanceof SSLException && !hasCertificateCause(failure)) {
                    throw new AssertionError(label + " failed before certificate validation", failure);
                }
                return;
            }
            throw new AssertionError(label + " unexpectedly failed", failure);
        }
        if (!accepted) {
            throw new AssertionError(label + " accepted a restricted chain");
        }
    }

    private static boolean hasCertificateCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CertificateException) {
                return true;
            }
        }
        return false;
    }

    private static void handshake(SSLContext clientContext, SSLContext serverContext, String protocol)
            throws Exception {
        SSLEngine client = clientContext.createSSLEngine("peer.example", 443);
        SSLEngine server = serverContext.createSSLEngine();
        client.setUseClientMode(true);
        server.setUseClientMode(false);
        client.setEnabledProtocols(new String[] {protocol});
        server.setEnabledProtocols(new String[] {protocol});
        var parameters = client.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        client.setSSLParameters(parameters);
        client.beginHandshake();
        server.beginHandshake();
        ByteBuffer clientToServer = ByteBuffer.allocate(65536);
        ByteBuffer serverToClient = ByteBuffer.allocate(65536);
        for (int step = 0; step < 100; step++) {
            transfer(client, server, clientToServer);
            transfer(server, client, serverToClient);
            if (client.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                    && server.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                if (!client.getSession().getProtocol().equals(protocol)) {
                    throw new AssertionError("TLS handshake did not negotiate " + protocol);
                }
                return;
            }
        }
        throw new AssertionError("TLS handshake did not finish");
    }

    private static void transfer(SSLEngine sender, SSLEngine receiver, ByteBuffer network) throws Exception {
        runTasks(sender);
        if (sender.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
            SSLEngineResult result = sender.wrap(ByteBuffer.allocate(0), network);
            if (result.getStatus() != SSLEngineResult.Status.OK) {
                throw new AssertionError("Unexpected TLS wrap status: " + result);
            }
        }
        network.flip();
        while (network.hasRemaining()
                || receiver.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN) {
            runTasks(receiver);
            int before = network.remaining();
            SSLEngineResult result = receiver.unwrap(network, ByteBuffer.allocate(65536));
            if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW
                    || (network.remaining() == before
                    && result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP)) {
                break;
            }
            if (result.getStatus() != SSLEngineResult.Status.OK || network.remaining() == before) {
                throw new AssertionError("Unexpected TLS unwrap status: " + result);
            }
        }
        network.compact();
        runTasks(receiver);
    }

    private static void runTasks(SSLEngine engine) {
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) {
            task.run();
        }
    }

    private interface Operation {
        void run() throws Exception;
    }

    private record Chain(String name, X509Certificate[] certificates, boolean accepted) { }

    private static final class Fixture {
        private final KeyPair rootKey = key();
        private final KeyPair intermediateKey = key();
        private final KeyPair leafKey = key();
        private final X509Certificate root;
        private final KeyStore trustStore;
        private final List<Chain> chains = new ArrayList<>();
        private final Map<Chain, SSLContext> serverContexts = new HashMap<>();
        private int serial;

        private Fixture() throws Exception {
            root = certificate("CN=PolicyRoot", "CN=PolicyRoot", rootKey.getPublic(), rootKey.getPrivate(),
                    true, null, null);
            trustStore = KeyStore.getInstance("BCFKS", "BCFIPS");
            trustStore.load(null, null);
            trustStore.setCertificateEntry("root", root);
            add("unconstrained valid", null, null, true);
            add("unconstrained email valid", null,
                    new GeneralName(GeneralName.rfc822Name, "user@allowed.example"), true);
            add("unconstrained URI valid", null,
                    new GeneralName(GeneralName.uniformResourceIdentifier, "https://allowed.example/a"), true);
            GeneralName email = new GeneralName(GeneralName.rfc822Name, "bank.com");
            add("email excluded name", email, new GeneralName(GeneralName.rfc822Name, "ceo@bank.com"), false);
            add("email trailing dot bypass", email, new GeneralName(GeneralName.rfc822Name, "ceo@bank.com."), false);
            add("email constraint restriction", email,
                    new GeneralName(GeneralName.rfc822Name, "user@allowed.example"), false);
            GeneralName uri = new GeneralName(GeneralName.uniformResourceIdentifier, "bank.com");
            add("URI excluded host", uri,
                    new GeneralName(GeneralName.uniformResourceIdentifier, "https://bank.com/a"), false);
            add("URI trailing dot bypass", uri,
                    new GeneralName(GeneralName.uniformResourceIdentifier, "https://bank.com./a"), false);
            add("URI constraint restriction", uri,
                    new GeneralName(GeneralName.uniformResourceIdentifier, "https://allowed.example/a"), false);
            addWithConstraints("email permitted constraint restriction",
                    new NameConstraints(new GeneralSubtree[] {new GeneralSubtree(email)}, null),
                    new GeneralName(GeneralName.rfc822Name, "ceo@bank.com"), false);
            addWithConstraints("URI permitted constraint restriction",
                    new NameConstraints(new GeneralSubtree[] {new GeneralSubtree(uri)}, null),
                    new GeneralName(GeneralName.uniformResourceIdentifier, "https://bank.com/a"), false);
            GeneralName dns = new GeneralName(GeneralName.dNSName, "blocked.example");
            add("DNS constraint valid", dns, null, true);
            add("DNS excluded name", dns, new GeneralName(GeneralName.dNSName, "blocked.example"), false);
            add("DNS excluded trailing dot", dns, new GeneralName(GeneralName.dNSName, "blocked.example."), false);
            add("DNS trailing dot constraint", new GeneralName(GeneralName.dNSName, "blocked.example."),
                    new GeneralName(GeneralName.dNSName, "blocked.example"), false);
            add("DNS unconstrained trailing dot", null,
                    new GeneralName(GeneralName.dNSName, "blocked.example."), false);
        }

        private void add(String name, GeneralName excluded, GeneralName alternative, boolean accepted)
                throws Exception {
            NameConstraints constraints = excluded == null ? null
                    : new NameConstraints(null, new GeneralSubtree[] {new GeneralSubtree(excluded)});
            addWithConstraints(name, constraints, alternative, accepted);
        }

        private void addWithConstraints(String name, NameConstraints constraints, GeneralName alternative,
                                        boolean accepted) throws Exception {
            String subject = "CN=Intermediate" + serial;
            X509Certificate intermediate = certificate("CN=PolicyRoot", subject, intermediateKey.getPublic(),
                    rootKey.getPrivate(), true, constraints, null);
            List<GeneralName> names = new ArrayList<>();
            names.add(new GeneralName(GeneralName.dNSName, "peer.example"));
            if (alternative != null) {
                names.add(alternative);
            }
            X509Certificate leaf = certificate(subject, "CN=peer.example", leafKey.getPublic(),
                    intermediateKey.getPrivate(), false, null, new GeneralNames(names.toArray(GeneralName[]::new)));
            chains.add(new Chain(name, new X509Certificate[] {leaf, intermediate}, accepted));
        }

        private X509Certificate certificate(String issuer, String subject, PublicKey publicKey, PrivateKey signer,
                                            boolean ca, NameConstraints constraints, GeneralNames names)
                throws Exception {
            long now = System.currentTimeMillis();
            var builder = new JcaX509v3CertificateBuilder(new X500Name(issuer), BigInteger.valueOf(++serial),
                    new Date(now - 60000), new Date(now + 86400000), new X500Name(subject), publicKey);
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(ca ? KeyUsage.keyCertSign | KeyUsage.cRLSign
                    : KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            if (constraints != null) {
                builder.addExtension(Extension.nameConstraints, true, constraints);
            }
            if (names != null) {
                builder.addExtension(Extension.subjectAlternativeName, false, names);
                builder.addExtension(Extension.extendedKeyUsage, false,
                        new ExtendedKeyUsage(new KeyPurposeId[] {
                                KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
            }
            return new JcaX509CertificateConverter().setProvider("BCFIPS").getCertificate(builder.build(
                    new JcaContentSignerBuilder("SHA256withRSA").setProvider("BCFIPS").build(signer)));
        }

        private SSLContext serverContext(Chain chain) throws Exception {
            if (serverContexts.containsKey(chain)) {
                return serverContexts.get(chain);
            }
            KeyStore store = KeyStore.getInstance("BCFKS", "BCFIPS");
            store.load(null, null);
            store.setKeyEntry("server", leafKey.getPrivate(), PASSWORD, chain.certificates);
            KeyManagerFactory factory = KeyManagerFactory.getInstance("PKIX", "BCJSSE");
            factory.init(store, PASSWORD);
            SSLContext context = SSLContext.getInstance("TLS", "BCJSSE");
            context.init(factory.getKeyManagers(), null, null);
            serverContexts.put(chain, context);
            return context;
        }

        private static KeyPair key() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "BCFIPS");
            generator.initialize(2048);
            return generator.generateKeyPair();
        }
    }
}
