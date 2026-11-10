// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import com.sun.net.httpserver.HttpServer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.s3a.S3AUtils;
import org.apache.hadoop.fs.s3a.impl.AWSClientConfig;
import org.apache.hadoop.fs.s3a.impl.NetworkBinding;
import org.apache.hadoop.security.ssl.DelegatingSSLSocketFactory;
import org.apache.http.HttpHost;
import org.apache.http.HttpVersion;
import org.apache.http.auth.AuthOption;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Lookup;
import org.apache.http.impl.client.ProxyAuthenticationStrategy;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.BasicHttpResponse;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.apache.ApacheHttpClient;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the authentication registry and challenge selection of Hadoop's actual AWS clients. */
public final class S3ProxyAuthenticationTest {
    private static final String USER = "synthetic-user";
    private static final String PASSWORD = "synthetic-password";
    private static final HttpHost PROXY = new HttpHost("proxy.example.invalid", 3128);

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "healthy" : args[0];
        Security.getProviders();
        if (!MessageDigest.getInstance("MD5").getProvider().getName().equals("SUN")) {
            throw new AssertionError("The regression must retain nonsecurity SUN MD5 checksums");
        }
        assertBindingFailurePropagation();
        if (mode.equals("readiness-fault")) {
            failModule();
            assertFreshClientPolicy();
            return;
        }

        List<SdkHttpClient> clients = new ArrayList<>();
        try {
            for (String channel : List.of("Default", "Default_JSSE", "Default_JSSE_with_GCM")) {
                Configuration conf = configuration(channel);
                clients.add(createClient(conf));
            }
            // Exercise Hadoop's bucket overrides rather than just direct SDK builder credentials.
            Configuration bucket = configuration("Default");
            bucket.unset("fs.s3a.proxy.username");
            bucket.unset("fs.s3a.proxy.password");
            bucket.set("fs.s3a.bucket.bucket.proxy.username", USER);
            bucket.set("fs.s3a.bucket.bucket.proxy.password", PASSWORD);
            clients.add(createClient(S3AUtils.propagateBucketOptions(bucket, "bucket")));

            // SDK fallback credentials must not restore its default Digest/NTLM registry.
            System.setProperty("http.proxyHost", PROXY.getHostName());
            System.setProperty("http.proxyPort", Integer.toString(PROXY.getPort()));
            System.setProperty("http.proxyUser", USER);
            System.setProperty("http.proxyPassword", PASSWORD);
            try {
                clients.add(createClient(new Configuration(false)));
            } finally {
                for (String name : List.of("http.proxyHost", "http.proxyPort", "http.proxyUser", "http.proxyPassword")) {
                    System.clearProperty(name);
                }
            }
            for (SdkHttpClient client : clients) {
                assertChallengePolicy(client);
            }
            if (mode.equals("active-fault")) {
                failModule();
                for (int retry = 0; retry < 2; retry++) {
                    for (SdkHttpClient client : clients) {
                        assertChallengePolicy(client);
                    }
                    assertFreshClientPolicy();
                }
            } else if (!mode.equals("healthy")) {
                throw new IllegalArgumentException("Expected healthy, readiness-fault or active-fault");
            } else {
                assertProxyExchange();
            }
        } finally {
            for (SdkHttpClient client : clients) {
                client.close();
            }
        }
        System.out.println("S3A proxy authentication rejects Digest and NTLM and retains Basic: " + mode);
    }

    private static Configuration configuration(String channel) {
        Configuration conf = new Configuration(false);
        conf.set("fs.s3a.ssl.channel.mode", channel);
        conf.set("fs.s3a.proxy.host", PROXY.getHostName());
        conf.setInt("fs.s3a.proxy.port", PROXY.getPort());
        conf.set("fs.s3a.proxy.username", USER);
        conf.set("fs.s3a.proxy.password", PASSWORD);
        return conf;
    }

    private static SdkHttpClient createClient(Configuration conf) throws Exception {
        DelegatingSSLSocketFactory.resetDefaultFactory();
        return AWSClientConfig.createHttpClientBuilder(conf)
                .proxyConfiguration(AWSClientConfig.createProxyConfiguration(conf, "bucket")).build();
    }

    @SuppressWarnings("unchecked")
    private static void assertChallengePolicy(SdkHttpClient sdk) throws Exception {
        Object client = field(field(sdk, "httpClient"), "delegate");
        Lookup<AuthSchemeProvider> registry = (Lookup<AuthSchemeProvider>) field(client, "authSchemeRegistry");
        for (String scheme : List.of("digest", "ntlm", "negotiate", "kerberos")) {
            if (registry.lookup(scheme) != null) {
                throw new AssertionError("S3A exposes an unsupported authentication scheme: " + scheme);
            }
        }
        HttpClientContext context = HttpClientContext.create();
        context.setAuthSchemeRegistry(registry);
        context.setCredentialsProvider((CredentialsProvider) field(client, "credentialsProvider"));
        context.setRequestConfig((RequestConfig) field(client, "defaultConfig"));
        for (String challenge : List.of(
                "Digest realm=\"test\", nonce=\"synthetic-nonce\", algorithm=MD5, qop=\"auth\"",
                "Digest realm=\"test\", nonce=\"synthetic-nonce\", algorithm=MD5-sess, qop=\"auth\"",
                "NTLM")) {
            if (!select(challenge, context).isEmpty()) {
                throw new AssertionError("S3A accepted a forbidden proxy authentication challenge");
            }
        }
        Queue<AuthOption> basic = select("Basic realm=\"test\"", context);
        if (basic.size() != 1 || !basic.peek().getAuthScheme().getSchemeName().equalsIgnoreCase("basic")) {
            throw new AssertionError("S3A no longer supports Basic proxy authentication");
        }
        AuthOption option = basic.remove();
        String header = option.getAuthScheme().authenticate(option.getCredentials(),
                new BasicHttpRequest("CONNECT", "s3.example.invalid:443")).getValue();
        if (!header.equals("Basic c3ludGhldGljLXVzZXI6c3ludGhldGljLXBhc3N3b3Jk")) {
            throw new AssertionError("S3A changed Basic proxy credentials");
        }
    }

    private static Queue<AuthOption> select(String challenge, HttpClientContext context) throws Exception {
        BasicHttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1, 407, "Proxy Authentication Required");
        response.addHeader("Proxy-Authenticate", challenge);
        ProxyAuthenticationStrategy strategy = ProxyAuthenticationStrategy.INSTANCE;
        return strategy.select(strategy.getChallenges(PROXY, response, context), PROXY, response, context);
    }

    private static void assertProxyExchange() throws Exception {
        AtomicReference<String> challenge = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            requests.incrementAndGet();
            String header = exchange.getRequestHeaders().getFirst("Proxy-Authorization");
            if (header != null) {
                authorization.set(header);
            }
            if (header == null) {
                exchange.getResponseHeaders().add("Proxy-Authenticate", challenge.get());
                exchange.sendResponseHeaders(407, -1);
            } else {
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        proxy.start();
        try {
            Configuration conf = configuration("Default");
            conf.set("fs.s3a.proxy.host", "127.0.0.1");
            conf.setInt("fs.s3a.proxy.port", proxy.getAddress().getPort());
            for (boolean digest : List.of(true, false)) {
                challenge.set(digest ? "Digest realm=\"test\", nonce=\"synthetic-nonce\", algorithm=MD5, qop=\"auth\""
                        : "Basic realm=\"test\"");
                authorization.set(null);
                requests.set(0);
                try (SdkHttpClient client = createClient(conf)) {
                    var response = client.prepareRequest(HttpExecuteRequest.builder()
                            .request(SdkHttpFullRequest.builder().protocol("http").host("s3.example.invalid")
                                    .method(SdkHttpMethod.GET).encodedPath("/").build()).build()).call();
                    if (response.responseBody().isPresent()) {
                        response.responseBody().get().close();
                    }
                    if (digest) {
                        if (response.httpResponse().statusCode() != 407 || requests.get() != 1
                                || authorization.get() != null) {
                            throw new AssertionError("S3A answered a proxy Digest challenge");
                        }
                    } else if (response.httpResponse().statusCode() != 200 || requests.get() != 2
                            || !"Basic c3ludGhldGljLXVzZXI6c3ludGhldGljLXBhc3N3b3Jk".equals(authorization.get())) {
                        throw new AssertionError("S3A did not complete Basic proxy authentication");
                    }
                }
            }
        } finally {
            proxy.stop(0);
        }
    }

    private static void assertBindingFailurePropagation() throws Exception {
        // Isolate the real binding class and remove a required authentication dependency.
        // Upstream's reflective helper catches linkage failures and silently restores SDK defaults.
        ClassLoader loader = new ClassLoader(S3ProxyAuthenticationTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("org.apache.http.config.RegistryBuilder")) {
                    throw new ClassNotFoundException("Injected missing authentication registry dependency");
                }
                if (name.equals(NetworkBinding.class.getName())
                        || name.equals("com.starrocks.connector.share.security.HttpAuthenticationPolicy")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) {
                            try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                                byte[] bytes = input.readAllBytes();
                                type = defineClass(name, bytes, 0, bytes.length);
                            } catch (java.io.IOException e) {
                                throw new ClassNotFoundException(name, e);
                            }
                        }
                        if (resolve) {
                            resolveClass(type);
                        }
                        return type;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
        try {
            loader.loadClass(NetworkBinding.class.getName())
                    .getMethod("bindSSLChannelMode", Configuration.class, ApacheHttpClient.Builder.class)
                    .invoke(null, new Configuration(false), ApacheHttpClient.builder());
        } catch (InvocationTargetException expected) {
            if (expected.getCause() instanceof NoClassDefFoundError
                    && expected.getCause().getMessage().contains("org/apache/http/config/RegistryBuilder")) {
                return;
            }
            throw expected;
        }
        throw new AssertionError("S3A ignored a missing authentication policy dependency");
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void assertFreshClientPolicy() throws Exception {
        try (SdkHttpClient client = createClient(configuration("Default"))) {
            assertChallengePolicy(client);
        } catch (Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause.getClass().getName().startsWith("org.bouncycastle.crypto.fips.Fips")) {
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
            method.invoke(null, "isolated S3A proxy authentication test error state");
        } catch (InvocationTargetException expected) {
            if (!expected.getCause().getClass().getName().startsWith("org.bouncycastle.crypto.fips.Fips")) {
                throw expected;
            }
        }
        if (!org.bouncycastle.crypto.fips.FipsStatus.isErrorStatus()) {
            throw new AssertionError("The module fault was not injected");
        }
    }
}
