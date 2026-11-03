// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.azurebfs.AbfsConfiguration;
import org.apache.hadoop.fs.s3a.impl.NetworkBinding;
import org.apache.hadoop.security.ssl.DelegatingSSLSocketFactory;
import org.apache.paimon.plugin.PluginLoader;
import software.amazon.awssdk.http.apache.ApacheHttpClient;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;

/** Exercises the Hadoop factories separately from Azure's Netty transport. */
public final class HadoopTlsPolicyTest {
    private static final ClassLoader PAIMON_LOADER = new PluginLoader("paimon-plugin-s3").submoduleClassLoader();

    public static void main(String[] args) throws Exception {
        assertNativeBindingAbsent();
        String mode = args.length == 0 ? "healthy" : args[0];
        List<ContextFactory> factories = List.of(HadoopTlsPolicyTest::abfsDefault,
                HadoopTlsPolicyTest::s3aDefault, HadoopTlsPolicyTest::paimonDefault);
        if (mode.equals("readiness-fault")) {
            Security.getProviders();
            failModule();
            for (int retry = 0; retry < 2; retry++) {
                for (ContextFactory factory : factories) {
                    expectModuleFailure(() -> startHandshake(factory.create()));
                }
                assertExplicitNativeRejected();
            }
            return;
        }

        List<SSLContext> cached = new ArrayList<>();
        for (ContextFactory factory : factories) {
            SSLContext context = factory.create();
            startHandshake(context);
            cached.add(context);
        }
        assertExplicitNativeRejected();
        if (mode.equals("active-fault")) {
            failModule();
            for (int retry = 0; retry < 2; retry++) {
                for (SSLContext context : cached) {
                    expectModuleFailure(() -> startHandshake(context));
                }
                for (ContextFactory factory : factories) {
                    expectModuleFailure(() -> startHandshake(factory.create()));
                }
                assertExplicitNativeRejected();
            }
            return;
        }
        if (!mode.equals("healthy")) {
            throw new IllegalArgumentException("Expected healthy, readiness-fault or active-fault");
        }
        System.out.println("Hadoop ABFS/S3A and isolated Paimon TLS selected BCJSSE; native TLS was rejected");
    }

    private static SSLContext abfsDefault() throws Exception {
        // This is the configuration and factory invocation used by AbfsClient.
        AbfsConfiguration configuration = new AbfsConfiguration(new Configuration(false),
                "account.dfs.core.windows.net");
        DelegatingSSLSocketFactory.resetDefaultFactory();
        DelegatingSSLSocketFactory.initializeDefaultFactory(configuration.getPreferredSSLFactoryOption());
        return selectedContext();
    }

    private static SSLContext s3aDefault() throws Exception {
        DelegatingSSLSocketFactory.resetDefaultFactory();
        NetworkBinding.bindSSLChannelMode(new Configuration(false), ApacheHttpClient.builder());
        return selectedContext();
    }

    private static SSLContext selectedContext() throws Exception {
        DelegatingSSLSocketFactory factory = DelegatingSSLSocketFactory.getDefaultFactory();
        if (!factory.getProviderName().startsWith("BCJSSE-")) {
            throw new AssertionError("Hadoop TLS selected " + factory.getProviderName());
        }
        var field = DelegatingSSLSocketFactory.class.getDeclaredField("ctx");
        field.setAccessible(true);
        return (SSLContext) field.get(factory);
    }

    private static SSLContext paimonDefault() throws Exception {
        Object factory = paimonFactory("Default");
        var field = factory.getClass().getDeclaredField("ctx");
        field.setAccessible(true);
        return (SSLContext) field.get(factory);
    }

    private static Object paimonFactory(String mode) throws Exception {
        Class<?> factory = PAIMON_LOADER.loadClass("org.apache.hadoop.security.ssl.DelegatingSSLSocketFactory");
        if (factory == DelegatingSSLSocketFactory.class || factory.getClassLoader() != PAIMON_LOADER) {
            throw new AssertionError("Paimon TLS did not use its isolated Hadoop factory");
        }
        // Reset Paimon's cached TLS factory before calling its Hadoop client builder.
        var instance = factory.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        Class<?> confType = PAIMON_LOADER.loadClass("org.apache.hadoop.conf.Configuration");
        Object conf = confType.getConstructor(boolean.class).newInstance(false);
        confType.getMethod("set", String.class, String.class).invoke(conf, "fs.s3a.ssl.channel.mode", mode);
        PAIMON_LOADER.loadClass("org.apache.hadoop.fs.s3a.impl.AWSClientConfig")
                .getMethod("createHttpClientBuilder", confType).invoke(null, conf);
        return factory.getMethod("getDefaultFactory").invoke(null);
    }

    private static void startHandshake(SSLContext context) throws Exception {
        if (!context.getProvider().getName().equals("BCJSSE")) {
            throw new AssertionError("Hadoop TLS bypassed BCJSSE");
        }
        var engine = context.createSSLEngine("localhost", 443);
        engine.setUseClientMode(true);
        engine.beginHandshake();
        if (engine.wrap(ByteBuffer.allocate(0), ByteBuffer.allocate(65536)).bytesProduced() == 0) {
            throw new AssertionError("Hadoop TLS did not produce a ClientHello");
        }
    }

    private static void assertNativeBindingAbsent() throws Exception {
        for (ClassLoader loader : List.of(HadoopTlsPolicyTest.class.getClassLoader(), PAIMON_LOADER)) {
            try {
                Class.forName("org.wildfly.openssl.OpenSSLProvider", false, loader);
                throw new AssertionError("Hadoop native TLS binding is on the service or Paimon classpath");
            } catch (ClassNotFoundException expected) {
                // A native implementation must not be available outside the selected Java module.
            }
        }
    }

    private static void assertExplicitNativeRejected() throws Exception {
        Configuration configuration = new Configuration(false);
        configuration.set("fs.s3a.ssl.channel.mode", "OpenSSL");
        DelegatingSSLSocketFactory.resetDefaultFactory();
        expectMissingNativeBinding(() -> NetworkBinding.bindSSLChannelMode(configuration, ApacheHttpClient.builder()));
        expectMissingNativeBinding(() -> paimonFactory("OpenSSL"));
    }

    private static void expectMissingNativeBinding(Operation operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("Explicit Hadoop OpenSSL mode was accepted");
        } catch (Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof NoClassDefFoundError && cause.getMessage() != null
                        && cause.getMessage().contains("org/wildfly/openssl/OpenSSLProvider")) {
                    return;
                }
            }
            throw failure;
        }
    }

    private static void failModule() throws Exception {
        var method = Class.forName("org.bouncycastle.crypto.fips.FipsStatus")
                .getDeclaredMethod("moveToErrorStatus", String.class);
        method.setAccessible(true);
        try {
            method.invoke(null, "isolated Hadoop TLS policy test error state");
        } catch (InvocationTargetException expected) {
            if (!expected.getCause().getClass().getName().startsWith("org.bouncycastle.crypto.fips.Fips")) {
                throw expected;
            }
        }
        if (!org.bouncycastle.crypto.fips.FipsStatus.isErrorStatus()) {
            throw new AssertionError("The module fault was not injected");
        }
    }

    private interface ContextFactory {
        SSLContext create() throws Exception;
    }

    private interface Operation {
        void run() throws Exception;
    }

    private static void expectModuleFailure(Operation operation) throws Exception {
        try {
            operation.run();
        } catch (Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                // A failed default-context initializer stays unusable on retry. The JVM
                // may retain its original FipsOperationError only in the error message.
                boolean failedContext = cause instanceof NoClassDefFoundError && cause.getMessage() != null
                        && cause.getMessage().startsWith("Could not initialize class org.bouncycastle.jsse.provider.");
                if (cause.getClass().getName().startsWith("org.bouncycastle.crypto.fips.Fips")
                        || cause instanceof java.security.NoSuchAlgorithmException
                        || failedContext) {
                    assertNativeBindingAbsent();
                    System.out.println("Hadoop TLS rejected a failed module without native fallback");
                    return;
                }
            }
            throw failure;
        }
        throw new AssertionError("Hadoop TLS accepted a failed module");
    }
}
