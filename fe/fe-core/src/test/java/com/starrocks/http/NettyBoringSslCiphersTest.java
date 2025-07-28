package com.starrocks.http;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.net.ssl.SSLEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class NettyBoringSslCiphersTest {

    private static final List<String> TLS13_ALL = List.of(
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256"
    );

    private static final File cert = resourceFile("ssl/test-cert.pem");
    private static final File key = resourceFile("ssl/test-key.pem");

    private static boolean hasAnyTls12(List<String> suites) {
        return suites.stream().anyMatch(s -> s.contains("_WITH_"));
    }

    private static File resourceFile(String path) {
        try {
            return new File(Objects.requireNonNull(
                    Thread.currentThread().getContextClassLoader().getResource(path)
            ).toURI());
        } catch (Exception e) {
            throw new RuntimeException("Missing resource: " + path, e);
        }
    }

    @Test
    void boringSslIgnoresPrunedTls13ListAndStillEnablesAllThree() throws Exception {
        assumeTrue(OpenSsl.isAvailable(),
                () -> "OpenSSL not available: " + OpenSsl.unavailabilityCause());

        SslContext ctx = SslContextBuilder.forServer(cert, key)
                .ciphers(List.of("TLS_AES_128_GCM_SHA256"))
                .build();

        SSLEngine eng = ctx.newEngine(ByteBufAllocator.DEFAULT);
        List<String> enabled = Arrays.asList(eng.getEnabledCipherSuites());

        assertTrue(enabled.contains("TLS_AES_128_GCM_SHA256"));
        assertTrue(enabled.contains("TLS_AES_256_GCM_SHA384"));
        assertTrue(enabled.contains("TLS_CHACHA20_POLY1305_SHA256"));
    }

    @Test
    void boringSslNoTls13RequestedTls13Disabled() throws Exception {
        assumeTrue(OpenSsl.isAvailable(), () -> "OpenSSL not available: " + OpenSsl.unavailabilityCause());

        SslContext ctx = SslContextBuilder.forServer(cert, key)
                .ciphers(List.of("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"))
                .build();

        SSLEngine eng = ctx.newEngine(ByteBufAllocator.DEFAULT);
        List<String> enabled = Arrays.asList(eng.getEnabledCipherSuites());

        assertTrue(enabled.contains("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"));
        assertFalse(enabled.containsAll(TLS13_ALL));
    }

    @Test
    void boringSslOnlyTls13SubsetRequestedAllTls13PlusDefaultTls12() throws Exception {
        assumeTrue(OpenSsl.isAvailable(), () -> "OpenSSL not available: " + OpenSsl.unavailabilityCause());

        SslContext ctx = SslContextBuilder.forServer(cert, key)
                .ciphers(List.of("TLS_AES_256_GCM_SHA384"))
                .build();

        List<String> enabled = Arrays.asList(ctx.newEngine(ByteBufAllocator.DEFAULT).getEnabledCipherSuites());
        assertTrue(enabled.containsAll(TLS13_ALL));
        assertTrue(hasAnyTls12(enabled));
    }

    @Test
    void boringSslOnlyTls13AllRequestedStillAddsDefaultTls12() throws Exception {
        assumeTrue(OpenSsl.isAvailable(), () -> "OpenSSL not available: " + OpenSsl.unavailabilityCause());
        SslContext ctx = SslContextBuilder.forServer(cert, key)
                .ciphers(TLS13_ALL)
                .build();

        List<String> enabled = Arrays.asList(ctx.newEngine(ByteBufAllocator.DEFAULT).getEnabledCipherSuites());
        assertTrue(enabled.containsAll(TLS13_ALL));
        assertTrue(hasAnyTls12(enabled));
    }

    @Test
    void boringSslSubsetTls13PlusExplicitTls12NoExtraTls12Defaults() throws Exception {
        assumeTrue(OpenSsl.isAvailable(), () -> "OpenSSL not available: " + OpenSsl.unavailabilityCause());
        List<String> requestedTls12 = List.of("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");
        SslContext ctx = SslContextBuilder.forServer(cert, key)
                .ciphers(Stream.concat(Stream.of("TLS_AES_128_GCM_SHA256"), requestedTls12.stream()).toList())
                .build();

        List<String> enabled = Arrays.asList(ctx.newEngine(ByteBufAllocator.DEFAULT).getEnabledCipherSuites());
        assertTrue(enabled.containsAll(TLS13_ALL));
        assertTrue(enabled.containsAll(requestedTls12));
        long tls12Count = enabled.stream().filter(s -> s.contains("_WITH_")).count();
        assertEquals(requestedTls12.size(), tls12Count);
    }

    @Test
    void boringSslEmptyListDefaultsForTls12() throws Exception {
        assumeTrue(OpenSsl.isAvailable(), () -> "OpenSSL not available: " + OpenSsl.unavailabilityCause());
        SslContext ctx = SslContextBuilder.forServer(cert, key)
                .ciphers(Collections.emptyList())
                .build();

        List<String> enabled = Arrays.asList(ctx.newEngine(ByteBufAllocator.DEFAULT).getEnabledCipherSuites());
        assertFalse(enabled.containsAll(TLS13_ALL));
        assertTrue(hasAnyTls12(enabled));
    }

    @Test
    void boringSslOnlyTls12MultipleExactlyRequestedNoTls13() throws Exception {
        assumeTrue(OpenSsl.isAvailable(), () -> "OpenSSL not available: " + OpenSsl.unavailabilityCause());
        List<String> req12 = List.of(
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
        );

        SslContext ctx = SslContextBuilder.forServer(cert, key)
                .sslProvider(SslProvider.OPENSSL)
                .ciphers(req12)
                .build();

        List<String> enabled = Arrays.asList(ctx.newEngine(ByteBufAllocator.DEFAULT).getEnabledCipherSuites());
        assertIterableEquals(req12, enabled);
    }

}
