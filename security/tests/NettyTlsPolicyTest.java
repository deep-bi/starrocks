// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import com.azure.core.http.HttpClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import io.netty.handler.ssl.SslContext;
import reactor.netty.http.client.HttpClientConfig;
import reactor.netty.tcp.SslProvider;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

/** Run in separate JVMs with the shipped security overlay and JVM options. */
public final class NettyTlsPolicyTest {
    // All OpenSsl class namespaces found in the shipped FE and Java extension JARs.
    private static final List<String> NETTY_PACKAGES = List.of("io.netty", "io.grpc.netty.shaded.io.netty",
            "com.google.cloud.hadoop.repackaged.gcs.io.grpc.netty.shaded.io.netty", "org.apache.kudu.shaded.io.netty");

    public static void main(String[] args) throws Exception {
        assertNativeTlsDisabled();
        String mode = args.length == 0 ? "healthy" : args[0];
        if (mode.equals("readiness-fault")) {
            // Fault an initialized module before any TLS context has been created.
            Security.getProviders();
            failModule();
            expectModuleFailure(() -> startHandshake("io.netty", azureContext(azureClient())));
            // Azure already attempted unshaded Netty initialization. Each other copy
            // needs its first initialization attempt in this failed-module state.
            for (String prefix : NETTY_PACKAGES.subList(1, NETTY_PACKAGES.size())) {
                expectModuleFailure(() -> startHandshake(prefix, defaultContext(prefix)));
            }
            return;
        }

        // Match AzBlobFileSystem's default builder without a test-only TLS context.
        List<Operation> cachedHandshakes = new ArrayList<>();
        SslContext azureContext = azureContext(azureClient());
        cachedHandshakes.add(() -> startHandshake("io.netty", azureContext));
        for (String prefix : NETTY_PACKAGES) {
            Object context = defaultContext(prefix);
            cachedHandshakes.add(() -> startHandshake(prefix, context));
        }
        for (Operation handshake : cachedHandshakes) {
            handshake.run();
        }
        if (mode.equals("active-fault")) {
            failModule();
            for (int retry = 0; retry < 2; retry++) {
                for (Operation handshake : cachedHandshakes) {
                    expectModuleFailure(handshake);
                }
                for (String prefix : NETTY_PACKAGES) {
                    expectModuleFailure(() -> startHandshake(prefix, defaultContext(prefix)));
                }
            }
            return;
        }
        if (!mode.equals("healthy")) {
            throw new IllegalArgumentException("Expected healthy, readiness-fault or active-fault");
        }
        System.out.println("Azure and Netty selected BCJSSE. Native TLS is disabled");
    }

    private static HttpClient azureClient() {
        return new BlobContainerClientBuilder().endpoint("https://account.blob.core.windows.net/container")
                .buildClient().getHttpPipeline().getHttpClient();
    }

    private static SslContext azureContext(HttpClient client) throws Exception {
        // Azure applies HTTPS lazily when it sees a request URL. Inspect the same default
        // provider Reactor selects at that point, including its cached SSL context.
        var field = client.getClass().getDeclaredField("nettyClient");
        field.setAccessible(true);
        var reactorClient = (reactor.netty.http.client.HttpClient) field.get(client);
        var method = Class.forName("reactor.netty.http.client.HttpClientSecure")
                .getDeclaredMethod("defaultSslProvider", HttpClientConfig.class);
        method.setAccessible(true);
        return ((SslProvider) method.invoke(null, reactorClient.configuration())).getSslContext();
    }

    private static void assertNativeTlsDisabled() throws Exception {
        for (String prefix : NETTY_PACKAGES) {
            if (Boolean.TRUE.equals(Class.forName(prefix + ".handler.ssl.OpenSsl")
                    .getMethod("isAvailable").invoke(null))) {
                throw new AssertionError("Netty native TLS is available outside BCFIPS: " + prefix);
            }
        }
    }

    private static Object defaultContext(String prefix) throws Exception {
        Class<?> builder = Class.forName(prefix + ".handler.ssl.SslContextBuilder");
        return builder.getMethod("build").invoke(builder.getMethod("forClient").invoke(null));
    }

    private static void startHandshake(String prefix, Object context) throws Exception {
        Class<?> jdkContext = Class.forName(prefix + ".handler.ssl.JdkSslContext");
        if (!jdkContext.isInstance(context)
                || !((SSLContext) jdkContext.getMethod("context").invoke(context))
                        .getProvider().getName().equals("BCJSSE")) {
            throw new AssertionError("Dependency TLS bypassed BCJSSE: " + context.getClass().getName());
        }
        Class<?> allocator = Class.forName(prefix + ".buffer.ByteBufAllocator");
        Object defaultAllocator = Class.forName(prefix + ".buffer.UnpooledByteBufAllocator").getField("DEFAULT").get(null);
        SSLEngine engine = (SSLEngine) jdkContext.getMethod("newEngine", allocator, String.class, int.class)
                .invoke(context, defaultAllocator, "localhost", 443);
        engine.beginHandshake();
        var result = engine.wrap(ByteBuffer.allocate(0), ByteBuffer.allocate(65536));
        if (result.bytesProduced() == 0) {
            throw new AssertionError("Dependency TLS did not produce a ClientHello");
        }
    }

    private static void failModule() throws Exception {
        var method = Class.forName("org.bouncycastle.crypto.fips.FipsStatus")
                .getDeclaredMethod("moveToErrorStatus", String.class);
        method.setAccessible(true);
        try {
            method.invoke(null, "isolated Netty TLS policy test error state");
        } catch (InvocationTargetException expected) {
            // The transition can itself throw the module's operation error.
        }
    }

    private interface Operation {
        void run() throws Exception;
    }

    private static void expectModuleFailure(Operation operation) throws Exception {
        try {
            operation.run();
        } catch (Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause.getClass().getName().startsWith("org.bouncycastle.crypto.fips.Fips")
                        || cause instanceof java.security.NoSuchAlgorithmException) {
                    assertNativeTlsDisabled();
                    System.out.println("Netty TLS rejected a failed module without native fallback");
                    return;
                }
            }
            throw failure;
        }
        throw new AssertionError("Netty TLS accepted a failed module");
    }
}
