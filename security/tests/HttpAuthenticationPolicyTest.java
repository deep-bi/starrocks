// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import com.starrocks.http.HttpUtils;
import com.starrocks.connector.share.iceberg.IcebergAwsClientFactory;
import com.starrocks.sql.optimizer.cost.feature.CostPredictor;
import com.sun.net.httpserver.HttpServer;
import org.apache.paimon.plugin.PluginLoader;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.SdkClientConfiguration;
import software.amazon.awssdk.core.client.config.SdkClientOption;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.Security;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledExecutorService;

/** Uses the actual FE client and Paimon's isolated Hadoop/AWS HTTP factory. */
public final class HttpAuthenticationPolicyTest {
    private static final String BASIC = "Basic c3ludGhldGljLXVzZXI6c3ludGhldGljLXBhc3N3b3Jk";
    private static final List<String> CHALLENGES = List.of(
            "Digest realm=\"test\", nonce=\"synthetic-nonce\", algorithm=MD5, qop=\"auth\"",
            "Digest realm=\"test\", nonce=\"synthetic-nonce\", algorithm=MD5-sess, qop=\"auth\"",
            "NTLM", "Negotiate", "Kerberos", "Basic realm=\"test\"");

    public static void main(String[] args) throws Exception {
        String component = args[0];
        String mode = args[1];
        Security.getProviders();
        if (!MessageDigest.getInstance("MD5").getProvider().getName().equals("SUN")) {
            throw new AssertionError("The regression must retain SUN MD5 for checksums");
        }
        if (mode.equals("readiness-fault")) {
            failModule();
            boolean initializationFailed = false;
            for (int retry = 0; retry < 2; retry++) {
                try (Client client = create(component, "127.0.0.1", 3128)) {
                    client.checkPolicy();
                } catch (Throwable failure) {
                    // The JVM remembers the first module-induced initialization failure.
                    if (initializationFailed && failedInitializer(failure)) {
                        continue;
                    }
                    requireModuleFailure(failure);
                    initializationFailed = true;
                }
            }
        } else {
            try (Client client = create(component, "127.0.0.1", 3128)) {
                client.checkPolicy();
                if (mode.equals("active-fault")) {
                    exchange(component, true);
                    for (int retry = 0; retry < 2; retry++) {
                        client.checkPolicy();
                    }
                } else if (mode.equals("healthy")) {
                    exchange(component, false);
                } else {
                    throw new IllegalArgumentException("Unknown mode: " + mode);
                }
            }
        }
        System.out.println(component + " HTTP authentication rejects Digest/NTLM/Negotiate/Kerberos"
                + " and retains Basic: " + mode);
    }

    private static Client create(String component, String host, int port) throws Exception {
        if (component.equals("fe")) {
            return new Client(HttpUtils.getInstance(), null, host, port);
        }
        if (component.equals("cost")) {
            var predictor = CostPredictor.getServiceBasedCostPredictor();
            ((ScheduledExecutorService) field(predictor, "DAEMON")).shutdownNow();
            return new Client(field(predictor, "httpClient"), null, host, port);
        }
        if (component.startsWith("aws-") || component.equals("iceberg")) {
            System.setProperty("http.proxyHost", host);
            System.setProperty("http.proxyPort", Integer.toString(port));
            System.setProperty("http.proxyUser", "synthetic-user");
            System.setProperty("http.proxyPassword", "synthetic-password");
            var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic"));
            AutoCloseable service;
            switch (component) {
                case "aws-s3":
                    service = S3Client.builder().region(Region.US_EAST_1).credentialsProvider(credentials).build();
                    break;
                case "aws-sts":
                    service = StsClient.builder().region(Region.US_EAST_1).credentialsProvider(credentials).build();
                    break;
                case "aws-glue":
                    service = GlueClient.builder().region(Region.US_EAST_1).credentialsProvider(credentials).build();
                    break;
                case "iceberg":
                    IcebergAwsClientFactory factory = new IcebergAwsClientFactory();
                    factory.initialize(Map.of("aws.s3.access_key", "synthetic", "aws.s3.secret_key", "synthetic",
                            "aws.s3.region", "us-east-1"));
                    service = factory.s3();
                    break;
                default:
                    throw new IllegalArgumentException(component);
            }
            var configuration = (SdkClientConfiguration) field(service, "clientConfiguration");
            Object transport = configuration.option(SdkClientOption.SYNC_HTTP_CLIENT);
            return new Client(field(field(transport, "httpClient"), "delegate"), service, host, port, true);
        }
        if (!component.equals("paimon")) {
            throw new IllegalArgumentException("Unknown component: " + component);
        }
        ClassLoader loader = new PluginLoader("paimon-plugin-s3").submoduleClassLoader();
        Class<?> confType = loader.loadClass("org.apache.hadoop.conf.Configuration");
        Object conf = confType.getConstructor(boolean.class).newInstance(false);
        for (var entry : Map.of("fs.s3a.proxy.host", host, "fs.s3a.proxy.port", Integer.toString(port),
                "fs.s3a.proxy.username", "synthetic-user", "fs.s3a.proxy.password", "synthetic-password").entrySet()) {
            confType.getMethod("set", String.class, String.class).invoke(conf, entry.getKey(), entry.getValue());
        }
        Object config = loader.loadClass("org.apache.hadoop.fs.s3a.S3AUtils")
                .getMethod("createAwsConf", confType, String.class).invoke(null, conf, "bucket");
        Class<?> settingsType = loader.loadClass("com.amazonaws.http.settings.HttpClientSettings");
        Object settings = settingsType.getMethod("adapt", loader.loadClass("com.amazonaws.ClientConfiguration"))
                .invoke(null, config);
        Class<?> factoryType = loader.loadClass("com.amazonaws.http.apache.client.impl.ApacheHttpClientFactory");
        if (factoryType.getClassLoader() != loader) {
            throw new AssertionError("Paimon must use its private AWS factory");
        }
        Object sdk = factoryType.getMethod("create", settingsType).invoke(factoryType.getConstructor().newInstance(), settings);
        return new Client(field(sdk, "delegate"), sdk, host, port);
    }

    private static final class Client implements AutoCloseable {
        private final Object client;
        private final Object sdk;
        private final ClassLoader loader;
        private final String host;
        private final int port;
        private final boolean awsV2;

        Client(Object client, Object sdk, String host, int port) {
            this(client, sdk, host, port, false);
        }

        Client(Object client, Object sdk, String host, int port, boolean awsV2) {
            this.client = client;
            this.sdk = sdk;
            this.loader = client.getClass().getClassLoader();
            this.host = host;
            this.port = port;
            this.awsV2 = awsV2;
        }

        private Class<?> type(String name) throws ClassNotFoundException {
            return loader.loadClass("org.apache.http." + name);
        }

        private Object context() throws Exception {
            Object context = type("client.protocol.HttpClientContext").getMethod("create").invoke(null);
            call(context, "setAuthSchemeRegistry", new Class<?>[] {type("config.Lookup")}, field(client, "authSchemeRegistry"));
            Object credentials = field(client, "credentialsProvider");
            if (sdk == null) {
                credentials = type("impl.client.BasicCredentialsProvider").getConstructor().newInstance();
                Object value = type("auth.UsernamePasswordCredentials").getConstructor(String.class, String.class)
                        .newInstance("synthetic-user", "synthetic-password");
                call(credentials, "setCredentials", new Class<?>[] {type("auth.AuthScope"), type("auth.Credentials")},
                        type("auth.AuthScope").getField("ANY").get(null), value);
            }
            call(context, "setCredentialsProvider", new Class<?>[] {type("client.CredentialsProvider")}, credentials);
            return context;
        }

        void checkPolicy() throws Exception {
            Object registry = field(client, "authSchemeRegistry");
            for (String scheme : List.of("digest", "ntlm", "negotiate", "kerberos")) {
                if (call(registry, "lookup", new Class<?>[] {String.class}, scheme) != null) {
                    throw new AssertionError("Unapproved authentication remains available: " + scheme);
                }
            }
            Object peer = type("HttpHost").getConstructor(String.class, int.class).newInstance(host, port);
            Object version = type("HttpVersion").getField("HTTP_1_1").get(null);
            Object context = context();
            for (boolean proxy : List.of(false, true)) {
                Object strategy = type(proxy ? "impl.client.ProxyAuthenticationStrategy" : "impl.client.TargetAuthenticationStrategy")
                        .getField("INSTANCE").get(null);
                for (String challenge : CHALLENGES) {
                    Object response = type("message.BasicHttpResponse")
                            .getConstructor(type("ProtocolVersion"), int.class, String.class)
                            .newInstance(version, proxy ? 407 : 401, "Authentication Required");
                    call(response, "addHeader", new Class<?>[] {String.class, String.class},
                            proxy ? "Proxy-Authenticate" : "WWW-Authenticate", challenge);
                    Object challenges = call(strategy, "getChallenges",
                            new Class<?>[] {type("HttpHost"), type("HttpResponse"), type("protocol.HttpContext")},
                            peer, response, context);
                    Queue<?> options = (Queue<?>) call(strategy, "select",
                            new Class<?>[] {Map.class, type("HttpHost"), type("HttpResponse"), type("protocol.HttpContext")},
                            challenges, peer, response, context);
                    if (!challenge.startsWith("Basic")) {
                        if (!options.isEmpty()) {
                            throw new AssertionError("Accepted authentication challenge: " + challenge);
                        }
                    } else {
                        if (options.size() != 1) {
                            throw new AssertionError("Basic authentication is unavailable");
                        }
                        Object option = options.remove();
                        Object scheme = call(option, "getAuthScheme", new Class<?>[0]);
                        Object credentials = call(option, "getCredentials", new Class<?>[0]);
                        Object request = type("message.BasicHttpRequest").getConstructor(String.class, String.class)
                                .newInstance("GET", "/");
                        Object header = call(scheme, "authenticate", new Class<?>[] {type("auth.Credentials"), type("HttpRequest")},
                                credentials, request);
                        if (!BASIC.equals(call(header, "getValue", new Class<?>[0]))) {
                            throw new AssertionError("Basic credentials changed");
                        }
                    }
                }
            }
        }

        int get(boolean proxy, boolean userInfo) throws Exception {
            if (awsV2) {
                var configuration = (SdkClientConfiguration) field(sdk, "clientConfiguration");
                SdkHttpClient transport = configuration.option(SdkClientOption.SYNC_HTTP_CLIENT);
                var response = transport.prepareRequest(HttpExecuteRequest.builder()
                        .request(SdkHttpFullRequest.builder().protocol("http").host("s3.example.invalid")
                                .method(SdkHttpMethod.GET).encodedPath("/").build()).build()).call();
                if (response.responseBody().isPresent()) {
                    response.responseBody().get().close();
                }
                return response.httpResponse().statusCode();
            }
            String uri = proxy ? "http://s3.example.invalid/" : "http://"
                    + (userInfo ? "synthetic-user:synthetic-password@" : "") + host + ":" + port + "/";
            Object request = type("client.methods.HttpGet").getConstructor(String.class).newInstance(uri);
            Object context = userInfo ? null : context();
            if (proxy && sdk == null) {
                Object builder = type("client.config.RequestConfig").getMethod("custom").invoke(null);
                Object peer = type("HttpHost").getConstructor(String.class, int.class).newInstance(host, port);
                call(builder, "setProxy", new Class<?>[] {type("HttpHost")}, peer);
                call(context, "setRequestConfig", new Class<?>[] {type("client.config.RequestConfig")},
                        call(builder, "build", new Class<?>[0]));
            }
            Object response = call(client, "execute", new Class<?>[] {type("client.methods.HttpUriRequest"),
                    type("protocol.HttpContext")}, request, context);
            try {
                return (Integer) call(call(response, "getStatusLine", new Class<?>[0]), "getStatusCode", new Class<?>[0]);
            } finally {
                ((AutoCloseable) response).close();
            }
        }

        public void close() throws Exception {
            if (awsV2) {
                ((AutoCloseable) sdk).close();
                return;
            }
            if (sdk != null) {
                Object manager = call(sdk, "getHttpClientConnectionManager", new Class<?>[0]);
                loader.loadClass("com.amazonaws.http.IdleConnectionReaper")
                        .getMethod("removeConnectionManager", type("conn.HttpClientConnectionManager"))
                        .invoke(null, manager);
                ((AutoCloseable) client).close();
            }
        }
    }

    private static void exchange(String component, boolean injectFailure) throws Exception {
        AtomicReference<String> challenge = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        boolean proxy = component.equals("paimon") || component.equals("iceberg") || component.startsWith("aws-");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String header = exchange.getRequestHeaders().getFirst(proxy ? "Proxy-Authorization" : "Authorization");
            if (header == null) {
                exchange.getResponseHeaders().add(proxy ? "Proxy-Authenticate" : "WWW-Authenticate", challenge.get());
                exchange.sendResponseHeaders(proxy ? 407 : 401, -1);
            } else {
                authorization.set(header);
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        server.start();
        try (Client client = create(component, "127.0.0.1", server.getAddress().getPort())) {
            for (int round = 0; round < (injectFailure ? 2 : 1); round++) {
                if (round == 1) {
                    failModule();
                }
                for (String value : CHALLENGES) {
                    challenge.set(value);
                    authorization.set(null);
                    requests.set(0);
                    int status = client.get(proxy, !proxy);
                    if (value.startsWith("Basic")) {
                        if (status != 200 || requests.get() != 2 || !BASIC.equals(authorization.get())) {
                            throw new AssertionError("Basic authentication exchange failed");
                        }
                    } else if (status != (proxy ? 407 : 401) || requests.get() != 1 || authorization.get() != null) {
                        throw new AssertionError("Answered forbidden authentication challenge: " + value);
                    }
                }
            }
        } finally {
            server.stop(0);
        }
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static Object call(Object object, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = object.getClass().getMethod(name, types);
        method.setAccessible(true);
        return method.invoke(object, arguments);
    }

    private static void failModule() throws Exception {
        Method method = Class.forName("org.bouncycastle.crypto.fips.FipsStatus").getDeclaredMethod("moveToErrorStatus", String.class);
        method.setAccessible(true);
        try {
            method.invoke(null, "isolated HTTP authentication policy failure");
        } catch (InvocationTargetException expected) {
            requireModuleFailure(expected);
        }
        if (!org.bouncycastle.crypto.fips.FipsStatus.isErrorStatus()) {
            throw new AssertionError("Module fault was not injected");
        }
    }

    private static void requireModuleFailure(Throwable failure) throws Exception {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getName().startsWith("org.bouncycastle.crypto.fips.Fips")) {
                return;
            }
        }
        throw new AssertionError("Unexpected failure", failure);
    }

    private static boolean failedInitializer(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof NoClassDefFoundError && List.of(
                    "Could not initialize class com.starrocks.http.HttpUtils",
                    "Could not initialize class com.starrocks.sql.optimizer.cost.feature.CostPredictor$ServiceBasedCostPredictor",
                    "Could not initialize class org.bouncycastle.jsse.provider.DefaultSSLContextSpi$LazyManagers")
                    .contains(cause.getMessage())) {
                return true;
            }
        }
        return false;
    }
}
