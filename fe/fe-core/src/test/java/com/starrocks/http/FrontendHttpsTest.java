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

import com.starrocks.common.Config;
import com.starrocks.common.Pair;
import com.starrocks.common.util.Util;
import com.starrocks.http.rest.HttpSSLContextLoader;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.NodeMgr;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import mockit.Mock;
import mockit.MockUp;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;

import static com.starrocks.load.streamload.StreamLoadHttpHeader.HTTP_ENABLE_BATCH_WRITE;

/**
 * Sends real HTTPS requests to an FE HTTPS listener, with the client code that FEs use to call each other.
 * The listener's certificate is valid for "localhost" only, so a request to 127.0.0.1 must fail the host
 * name check.
 */
public class FrontendHttpsTest extends StarRocksHttpTestCase {
    private static final String HEALTH_PATH = "/api/health";
    private static final String HEALTH_FIELD = "online_backend_num";

    private static TlsTestMaterial tls;
    private static HttpServer httpsServer;
    private static int httpsPort;

    private static SSLContext savedDefaultContext;
    private static SSLSocketFactory savedUrlConnectionFactory;
    private static boolean savedEnableHttps;
    private static boolean savedEnableHttp;
    private static int savedHttpsPort;
    private static String savedKeystoreLocation;
    private static String savedKeystorePassword;
    private static String savedKeyPassword;
    private static String savedKeystoreType;

    @BeforeAll
    public static void startHttpsListener() throws Exception {
        tls = TlsTestMaterial.create("localhost");

        savedEnableHttps = Config.enable_https;
        savedEnableHttp = Config.enable_http;
        savedHttpsPort = Config.https_port;
        savedKeystoreLocation = Config.ssl_keystore_location;
        savedKeystorePassword = Config.ssl_keystore_password;
        savedKeyPassword = Config.ssl_key_password;
        savedKeystoreType = Config.ssl_keystore_type;

        Config.enable_https = true;
        Config.ssl_keystore_location = tls.getKeyStoreFile().getAbsolutePath();
        Config.ssl_keystore_password = TlsTestMaterial.PASSWORD;
        Config.ssl_key_password = TlsTestMaterial.PASSWORD;
        Config.ssl_keystore_type = "PKCS12";
        resetServerTlsContext();

        httpsServer = new HttpServer(0, true);
        httpsServer.setup();
        httpsServer.start();
        int retry = 0;
        while (!httpsServer.isStarted()) {
            Thread.sleep(500);
            if (++retry > 60) {
                throw new IllegalStateException("HTTPS listener failed to start within 30 seconds");
            }
        }
        httpsPort = httpsServer.getPort();

        // FEs call each other with the JVM default TLS settings. Make them trust the test certificate.
        savedDefaultContext = SSLContext.getDefault();
        savedUrlConnectionFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        SSLContext trusting = tls.newTrustingContext();
        SSLContext.setDefault(trusting);
        HttpsURLConnection.setDefaultSSLSocketFactory(trusting.getSocketFactory());
    }

    @AfterAll
    public static void stopHttpsListener() throws Exception {
        if (savedDefaultContext != null) {
            SSLContext.setDefault(savedDefaultContext);
            HttpsURLConnection.setDefaultSSLSocketFactory(savedUrlConnectionFactory);
        }
        if (httpsServer != null) {
            httpsServer.shutDown();
        }
        Config.enable_https = savedEnableHttps;
        Config.enable_http = savedEnableHttp;
        Config.https_port = savedHttpsPort;
        Config.ssl_keystore_location = savedKeystoreLocation;
        Config.ssl_keystore_password = savedKeystorePassword;
        Config.ssl_key_password = savedKeyPassword;
        Config.ssl_keystore_type = savedKeystoreType;
        resetServerTlsContext();
    }

    private static void resetServerTlsContext() throws Exception {
        Field initialized = HttpSSLContextLoader.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        initialized.set(null, false);
        Field sslContext = HttpSSLContextLoader.class.getDeclaredField("sslContext");
        sslContext.setAccessible(true);
        sslContext.set(null, null);
    }

    @Override
    protected void doSetUp() {
        // The FE serves HTTPS only, so the FEs call each other on the HTTPS listener.
        Config.enable_http = false;
        Config.https_port = httpsPort;
    }

    @AfterEach
    public void restoreListenerConfig() {
        Config.enable_http = savedEnableHttp;
        Config.https_port = savedHttpsPort;
    }

    private static HttpRequest healthRequest() {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, HEALTH_PATH);
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        return request;
    }

    @Test
    public void testUrlConnectionReachesHttpsListener() throws Exception {
        String url = FrontendHttpEndpoint.getUrlPrefix("localhost") + HEALTH_PATH;
        Assertions.assertEquals("https://localhost:" + httpsPort + HEALTH_PATH, url);

        String body = Util.getResultForUrl(url, null, 5000, 5000);
        Assertions.assertTrue(body.contains(HEALTH_FIELD), body);
    }

    @Test
    public void testUrlConnectionChecksHostName() {
        String url = FrontendHttpEndpoint.getUrlPrefix("127.0.0.1") + HEALTH_PATH;
        Assertions.assertThrows(SSLException.class, () -> Util.getResultForUrl(url, null, 5000, 5000));
    }

    @Test
    public void testNettyClientReachesHttpsListener() {
        String body = StarRocksHttpClient.redirect("localhost", httpsPort, true, healthRequest());
        Assertions.assertTrue(body.contains(HEALTH_FIELD), body);
    }

    @Test
    public void testNettyClientChecksHostName() {
        String body = StarRocksHttpClient.redirect("127.0.0.1", httpsPort, true, healthRequest());
        Assertions.assertFalse(body.contains(HEALTH_FIELD), body);
    }

    @Test
    public void testNettyClientStillReachesPlainListener() {
        String body = StarRocksHttpClient.redirect("localhost", HTTP_PORT, healthRequest());
        Assertions.assertTrue(body.contains(HEALTH_FIELD), body);
    }

    @Test
    public void testHttpUtilsChecksHostName() {
        String url = "https://127.0.0.1:" + httpsPort + HEALTH_PATH;
        HttpRequestException e = Assertions.assertThrows(HttpRequestException.class, () -> HttpUtils.get(url, null));
        Assertions.assertTrue(e.getCause() instanceof SSLException, String.valueOf(e.getCause()));
    }

    @Test
    public void testLeaderRedirectKeepsHttpsOverTls() throws Exception {
        new MockUp<GlobalStateMgr>() {
            @Mock
            public boolean isLeader() {
                return false;
            }
        };
        new MockUp<NodeMgr>() {
            @Mock
            public Pair<String, Integer> getLeaderIpAndHttpPort() {
                return new Pair<>("192.0.0.9", 8030);
            }
        };

        OkHttpClient client = new OkHttpClient.Builder()
                .sslSocketFactory(tls.newTrustingContext().getSocketFactory(), tls.getTrustManager())
                .followRedirects(false)
                .build();
        String path = String.format("/api/%s/%s/_stream_load", DB_NAME, TABLE_NAME);
        Request request = new Request.Builder()
                .addHeader(AUTH_KEY, rootAuth)
                .addHeader("Expect", "100-continue")
                .addHeader(HTTP_ENABLE_BATCH_WRITE, "true")
                .put(RequestBody.create(new byte[0]))
                .url("https://localhost:" + httpsPort + path)
                .build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(307, response.code(), response.message());
            Assertions.assertEquals("https://192.0.0.9:" + httpsPort + path, response.header("Location"));
        }
    }
}
