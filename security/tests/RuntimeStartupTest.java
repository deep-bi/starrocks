// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import com.starrocks.utils.loader.ChildFirstClassLoader;

import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.net.ssl.SSLContext;

/** Executed both from java and through the actual libhdfs JNI creation/attachment path. */
public final class RuntimeStartupTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && (args[0].equals("readiness-fault") || args[0].equals("active-fault"))) {
            if (args[0].equals("active-fault")) {
                exercise();
            }
            // Change only this isolated JVM's module state, never the signed provider JAR.
            Class<?> status = Class.forName("org.bouncycastle.crypto.fips.FipsStatus");
            Method fault = status.getDeclaredMethod("moveToErrorStatus", String.class);
            fault.setAccessible(true);
            try {
                fault.invoke(null, "isolated module test error state");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                // The module can report the transition by throwing its operation error.
            }
            for (int retry = 0; retry < 2; retry++) {
                for (String digest : List.of("SHA-1", "SHA-256", "SHA-384", "SHA-512")) {
                    expectModuleFailure(() -> MessageDigest.getInstance(digest).digest(new byte[32]));
                }
                expectModuleFailure(() -> SecureRandom.getInstance("DEFAULT", "BCFIPS").nextBytes(new byte[32]));
                expectModuleFailure(() -> MessageDigest.getInstance("SHA-256", "BCFIPS").digest(new byte[32]));
                expectModuleFailure(() -> Class.forName("org.apache.commons.codec.digest.HmacUtils")
                        .getMethod("getHmacSha256", byte[].class).invoke(null, new byte[32]));
                expectModuleFailure(() -> SSLContext.getInstance("TLS").init(null, null, null));
            }
            return;
        }
        if (args.length > 0 && args[0].equals("remove-provider")) {
            Security.removeProvider("BCFIPS");
            try {
                SecureRandom.getInstance("DEFAULT", "BCFIPS");
                throw new AssertionError("Explicit provider operation fell back");
            } catch (java.security.NoSuchProviderException expected) {
                System.out.println("Missing explicit provider rejected");
            }
            for (String digest : List.of("SHA-1", "SHA-256", "SHA-384", "SHA-512")) {
                expectModuleFailure(() -> MessageDigest.getInstance(digest).digest(new byte[32]));
            }
            return;
        }
        exercise();
        FutureTask<Void> task = new FutureTask<>(() -> { exercise(); return null; });
        Thread worker = new Thread(task, "startup-validation-worker");
        worker.start();
        task.get();
        System.out.println("Java runtime, worker and extension checks passed");
    }

    private interface Operation {
        void run() throws Exception;
    }

    private static void expectModuleFailure(Operation operation) throws Exception {
        try {
            operation.run();
        } catch (Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.security.NoSuchProviderException
                        || cause instanceof java.security.NoSuchAlgorithmException
                        || cause.getClass().getName().startsWith("org.bouncycastle.crypto.fips.Fips")) {
                    System.out.println("Module error propagated without fallback");
                    return;
                }
            }
            throw failure;
        }
        throw new AssertionError("Failed module operation succeeded");
    }

    public static void exercise() throws Exception {
        SaslPolicyTest.exercise();
        HadoopCryptoPolicyTest.exercise();
        CertificatePolicyTest.exercise();
        OcspRevocationPolicyTest.exercise();
        if (!new SecureRandom().getProvider().getName().equals("BCFIPS")
                || !MessageDigest.getInstance("SHA-256").getProvider().getName().equals("BCFIPS")
                || !SSLContext.getDefault().getProvider().getName().equals("BCJSSE")) {
            throw new AssertionError("Unexpected default service providers");
        }
        var crypto = Security.getProvider("BCFIPS");
        var tls = Security.getProvider("BCJSSE");
        Class<?> registrar = Class.forName("org.bouncycastle.crypto.CryptoServicesRegistrar");
        Class<?> nativeServices = Class.forName("org.bouncycastle.crypto.NativeServices");
        Object nativeStatus = registrar.getMethod("getNativeServices").invoke(null);
        if (!Boolean.TRUE.equals(registrar.getMethod("isInApprovedOnlyMode").invoke(null))
                || !Boolean.TRUE.equals(tls.getClass().getMethod("isFipsMode").invoke(tls))
                || Boolean.TRUE.equals(nativeServices.getMethod("isInstalled").invoke(nativeStatus))
                || Boolean.TRUE.equals(nativeServices.getMethod("isEnabled").invoke(nativeStatus))
                || crypto.getClass().getClassLoader() != RuntimeStartupTest.class.getClassLoader()
                || !KeyStore.getInstance("JKS").getProvider().getName().equals("BCFIPS")
                || !javax.net.ssl.TrustManagerFactory.getInstance("PKIX").getProvider().getName().equals("BCJSSE")) {
            throw new AssertionError("Unexpected provider mode, native services, loading or trust provider");
        }
        // Only the pinned JDK services required for entropy and nonsecurity compatibility remain registered.
        var jdk = Security.getProvider("SUN");
        Set<String> jdkServices = jdk.getServices().stream()
                .map(service -> service.getType() + "." + service.getAlgorithm()).collect(Collectors.toSet());
        if (!jdk.getClass().getName().equals("com.starrocks.connector.share.security.FipsJdkProvider")
                || !jdkServices.equals(Set.of("SecureRandom.NativePRNGNonBlocking", "MessageDigest.MD5",
                "CertStore.Collection", "CertStore.com.sun.security.IndexedCollection",
                "Policy.JavaPolicy", "Configuration.JavaLoginConfig"))) {
            throw new AssertionError("Unexpected retained JDK services: " + jdkServices);
        }
        SecureRandom.getInstance("NativePRNGNonBlocking", "SUN").generateSeed(32);
        java.security.cert.CertStore.getInstance("Collection",
                new java.security.cert.CollectionCertStoreParameters(), "SUN");
        java.security.cert.CertStore.getInstance("com.sun.security.IndexedCollection",
                new java.security.cert.CollectionCertStoreParameters(), "SUN");
        java.security.Policy.getInstance("JavaPolicy", null, "SUN");
        javax.security.auth.login.Configuration.getInstance("JavaLoginConfig", null, "SUN");
        if (!java.util.HexFormat.of().formatHex(MessageDigest.getInstance("MD5", "SUN").digest())
                .equals("d41d8cd98f00b204e9800998ecf8427e")) {
            throw new AssertionError("JDK nonsecurity MD5 checksum failed");
        }
        // Exercise the provider's restriction directly, with no application key-size precheck.
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA", "BCFIPS");
        try {
            rsa.initialize(1024);
            rsa.generateKeyPair();
            throw new AssertionError("BCFIPS accepted RSA-1024 key generation");
        } catch (Error expected) {
            if (!expected.getClass().getName().equals("org.bouncycastle.crypto.fips.FipsUnapprovedOperationError")) {
                throw expected;
            }
        }
        rsa.initialize(2048);
        rsa.generateKeyPair();
        // Commons Codec is used by the packaged dependency stack and selects JCA defaults.
        Mac mac = (Mac) Class.forName("org.apache.commons.codec.digest.HmacUtils")
                .getMethod("getHmacSha256", byte[].class).invoke(null, new byte[32]);
        if (!mac.getProvider().getName().equals("BCFIPS") || mac.doFinal(new byte[16]).length != 32) {
            throw new AssertionError("Dependency HMAC did not use BCFIPS");
        }
        // Run an actual Hadoop extension operation on this JVM, without a remote cloud service.
        Class<?> configuration = Class.forName("org.apache.hadoop.conf.Configuration");
        Object conf = configuration.getConstructor(boolean.class).newInstance(false);
        Class<?> applier = Class.forName("com.starrocks.connector.share.credential.CloudConfigurationApplier");
        applier.getMethod("applyCloudConfiguration", Map.class, configuration).invoke(null,
                Map.of("aws.s3.use_aws_sdk_default_behavior", "true"), conf);
        if (!"org.apache.hadoop.fs.s3a.S3AFileSystem".equals(configuration.getMethod("get", String.class)
                .invoke(conf, "fs.s3a.impl"))) {
            throw new AssertionError("Extension configuration operation failed");
        }
        String readers = System.getenv("STARROCKS_TEST_READER_ROOT");
        if (readers == null) {
            return;
        }
        for (String name : List.of("hive-reader", "hudi-reader", "iceberg-metadata-reader",
                "kudu-reader", "odps-reader", "paimon-reader")) {
            Path reader = Path.of(readers, name, "target", "starrocks-" + name + ".jar");
            if (!Files.isRegularFile(reader)) {
                throw new AssertionError("Missing reader JAR: " + reader);
            }
            List<URL> urls = new ArrayList<>();
            try (var files = Files.walk(reader.getParent())) {
                for (Path path : files.filter(p -> p.toString().endsWith(".jar")).toList()) {
                    urls.add(path.toUri().toURL());
                }
            }
            try (ChildFirstClassLoader loader = new ChildFirstClassLoader(
                    urls.toArray(URL[]::new), RuntimeStartupTest.class.getClassLoader())) {
                for (String type : List.of("org.bouncycastle.crypto.CryptoServicesRegistrar", "org.bouncycastle.asn1.cms.ContentInfo",
                        "org.bouncycastle.cert.X509CertificateHolder",
                        "org.bouncycastle.jsse.provider.BouncyCastleJsseProvider")) {
                    if (loader.loadClass(type) != Class.forName(type)
                            || Collections.list(loader.getResources(type.replace('.', '/') + ".class")).size() != 1) {
                        throw new AssertionError("Private or duplicate class: " + reader + ": " + type);
                    }
                }
                Mac childMac = (Mac) loader.loadClass("org.apache.commons.codec.digest.HmacUtils")
                        .getMethod("getHmacSha256", byte[].class).invoke(null, new byte[32]);
                if (!childMac.getProvider().getName().equals("BCFIPS")) {
                    throw new AssertionError("Extension dependency selected an ordinary provider");
                }
            }
            // Match ScannerHelper: preload the shared extension before the reader and Hadoop JARs.
            urls.add(0, Path.of(readers, "hadoop-ext/target/starrocks-hadoop-ext.jar").toUri().toURL());
            try (var files = Files.list(Path.of(readers, "common-runtime/target/common-runtime-lib"))) {
                for (Path path : files.filter(p -> p.toString().endsWith(".jar")).toList()) {
                    urls.add(path.toUri().toURL());
                }
            }
            try (var files = Files.list(Path.of(readers, "hadoop-lib/target/hadoop-lib"))) {
                for (Path path : files.filter(p -> p.toString().endsWith(".jar")).toList()) {
                    urls.add(path.toUri().toURL());
                }
            }
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            // Hadoop's shutdown hooks still load classes from this reader at JVM exit.
            // Match the production reader lifetime instead of closing its loader early.
            ChildFirstClassLoader loader = new ChildFirstClassLoader(
                    urls.toArray(URL[]::new), RuntimeStartupTest.class.getClassLoader());
            try {
                Thread.currentThread().setContextClassLoader(loader);
                Class<?> confType = loader.loadClass("org.apache.hadoop.conf.Configuration");
                Object readerConf = confType.getConstructor(boolean.class).newInstance(false);
                Class<?> fs = loader.loadClass("org.apache.hadoop.fs.FileSystem");
                try (AutoCloseable ignored = (AutoCloseable) fs.getMethod("newInstance", java.net.URI.class, confType)
                        .invoke(null, java.net.URI.create("file:///"), readerConf)) {
                    Class<?> codecType = loader.loadClass("org.apache.hadoop.crypto.CryptoCodec");
                    Object codec = codecType.getMethod("getInstance", confType).invoke(null, readerConf);
                    if (codec == null || !codec.getClass().getName().equals(
                            "com.starrocks.connector.share.security.FipsAesCtrCryptoCodec")) {
                        throw new AssertionError("Reader selected an unapproved Hadoop codec: " + name);
                    }
                    codecType.getMethod("generateSecureRandom", byte[].class).invoke(codec, new byte[32]);
                    var randomField = codec.getClass().getDeclaredField("random");
                    randomField.setAccessible(true);
                    if (!((SecureRandom) randomField.get(codec)).getProvider().getName().equals("BCFIPS")) {
                        throw new AssertionError("Reader Hadoop randomness bypassed BCFIPS: " + name);
                    }
                    ((AutoCloseable) codec).close();
                }
                assertS3ProxyPolicy(loader, confType, readerConf);
                assertDefaultAwsPolicy(loader);
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    private static void assertS3ProxyPolicy(ClassLoader loader, Class<?> confType, Object conf) throws Exception {
        Class<?> helper = loader.loadClass("org.apache.hadoop.fs.s3a.impl.NetworkBinding");
        Class<?> extension = loader.loadClass("com.starrocks.connector.share.security.FipsAesCtrCryptoCodec");
        if (!helper.getProtectionDomain().getCodeSource().getLocation()
                .equals(extension.getProtectionDomain().getCodeSource().getLocation())) {
            throw new AssertionError("S3A did not load its HTTP policy from the shared Hadoop extension");
        }
        Object builder = loader.loadClass("org.apache.hadoop.fs.s3a.impl.AWSClientConfig")
                .getMethod("createHttpClientBuilder", confType).invoke(null, conf);
        try (AutoCloseable client = (AutoCloseable) loader.loadClass("software.amazon.awssdk.http.SdkHttpClient$Builder")
                .getMethod("build").invoke(builder)) {
            Object delegate = field(field(client, "httpClient"), "delegate");
            Object registry = field(delegate, "authSchemeRegistry");
            Method lookup = loader.loadClass("org.apache.http.config.Lookup").getMethod("lookup", String.class);
            if (lookup.invoke(registry, "basic") == null || lookup.invoke(registry, "digest") != null
                    || lookup.invoke(registry, "ntlm") != null) {
                throw new AssertionError("S3A HTTP authentication policy was not installed");
            }
        }
    }

    private static void assertDefaultAwsPolicy(ClassLoader loader) throws Exception {
        Class<?> builderType = loader.loadClass("software.amazon.awssdk.services.s3.S3ClientBuilder");
        Object builder = loader.loadClass("software.amazon.awssdk.services.s3.S3Client").getMethod("builder").invoke(null);
        Class<?> regionType = loader.loadClass("software.amazon.awssdk.regions.Region");
        builderType.getMethod("region", regionType).invoke(builder, regionType.getField("US_EAST_1").get(null));
        Class<?> credentialsType = loader.loadClass("software.amazon.awssdk.auth.credentials.AwsCredentials");
        Object credentials = loader.loadClass("software.amazon.awssdk.auth.credentials.AwsBasicCredentials")
                .getMethod("create", String.class, String.class).invoke(null, "synthetic", "synthetic");
        Object provider = loader.loadClass("software.amazon.awssdk.auth.credentials.StaticCredentialsProvider")
                .getMethod("create", credentialsType).invoke(null, credentials);
        builderType.getMethod("credentialsProvider", loader.loadClass(
                "software.amazon.awssdk.auth.credentials.AwsCredentialsProvider")).invoke(builder, provider);
        try (AutoCloseable service = (AutoCloseable) builderType.getMethod("build").invoke(builder)) {
            Object configuration = field(service, "clientConfiguration");
            Class<?> optionType = loader.loadClass("software.amazon.awssdk.core.client.config.SdkClientOption");
            Object option = optionType.getField("SYNC_HTTP_CLIENT").get(null);
            Object transport = configuration.getClass().getMethod("option",
                    loader.loadClass("software.amazon.awssdk.core.client.config.ClientOption")).invoke(configuration, option);
            Object delegate = field(field(transport, "httpClient"), "delegate");
            Object registry = field(delegate, "authSchemeRegistry");
            Method lookup = loader.loadClass("org.apache.http.config.Lookup").getMethod("lookup", String.class);
            if (lookup.invoke(registry, "basic") == null || lookup.invoke(registry, "digest") != null
                    || lookup.invoke(registry, "ntlm") != null) {
                throw new AssertionError("Reader AWS default transport has unrestricted authentication");
            }
        }
    }

    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
}
