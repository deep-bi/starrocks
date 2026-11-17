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


import com.starrocks.common.util.TlsUtils;
import com.starrocks.connector.share.security.HttpAuthenticationPolicy;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ProtocolException;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLContext;

public class HttpUtils {

    private static final Logger LOG = LoggerFactory.getLogger(HttpUtils.class);


    private static class SingletonHolder {
        private static final CloseableHttpClient INSTANCE = getHttpClient(false);
    }

    public static CloseableHttpClient getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private static final PoolingHttpClientConnectionManager CLIENT_CONNECTION_MANAGER;

    private static CloseableHttpClient getHttpClient(boolean useSystemProxy) {
        Objects.requireNonNull(CLIENT_CONNECTION_MANAGER, "clientConnectionManager is not initialized");

        RequestConfig  requestConfig = RequestConfig.custom().setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                .setExpectContinueEnabled(Boolean.TRUE)
                .setConnectTimeout(5000)
                .setSocketTimeout(5000)
                .setConnectionRequestTimeout(5000)
                .setRedirectsEnabled(true)
                .build();

        return HttpAuthenticationPolicy.clientBuilder()
                .setConnectionManager(CLIENT_CONNECTION_MANAGER)
                .setConnectionManagerShared(useSystemProxy)
                .setRoutePlanner(useSystemProxy ? new SystemDefaultRoutePlanner(ProxySelector.getDefault()) : null)
                .setDefaultRequestConfig(requestConfig)
                .setRedirectStrategy(new DefaultRedirectStrategy() {
                    @Override
                    public HttpUriRequest getRedirect(HttpRequest request, HttpResponse response, HttpContext context)
                            throws ProtocolException {
                        HttpUriRequest redirect = super.getRedirect(request, response, context);
                        if ("https".equalsIgnoreCase(HttpClientContext.adapt(context).getTargetHost().getSchemeName())
                                && !"https".equalsIgnoreCase(redirect.getURI().getScheme())) {
                            throw new ProtocolException("HTTPS requests must not downgrade to HTTP");
                        }
                        return redirect;
                    }
                })
                .setRetryHandler(new DefaultHttpRequestRetryHandler(5, false))
                .build();
    }

    static {
        PoolingHttpClientConnectionManager poolingHttpClientConnectionManager;
        try {
            SSLContext sslContext = TlsUtils.clientContext();
            SSLConnectionSocketFactory socketFactory = new SSLConnectionSocketFactory(sslContext,
                    new String[] {"TLSv1.2", "TLSv1.3"}, null,
                    SSLConnectionSocketFactory.getDefaultHostnameVerifier());
            Registry<ConnectionSocketFactory> socketFactoryRegistry =
                    RegistryBuilder.<ConnectionSocketFactory>create()
                            .register("http", PlainConnectionSocketFactory.getSocketFactory())
                            .register("https", socketFactory)
                            .build();
            poolingHttpClientConnectionManager = new PoolingHttpClientConnectionManager(socketFactoryRegistry);
            poolingHttpClientConnectionManager.setDefaultMaxPerRoute(50);
            poolingHttpClientConnectionManager.setMaxTotal(100);

        } catch (GeneralSecurityException | IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        CLIENT_CONNECTION_MANAGER = poolingHttpClientConnectionManager;
        LOG.info(" initial http client successfully");
    }

    public static String get(String uri, Map<String, String> header) throws Exception {
        HttpGet httpGet = new HttpGet(uri);
        addHeaders(httpGet, header);
        return executeRequest(uri, httpGet, null, getInstance());
    }

    public static String getWithSystemProxy(String uri, Map<String, String> header) throws Exception {
        try (CloseableHttpClient httpClient = getHttpClient(true)) {
            HttpGet httpGet = new HttpGet(uri);
            addHeaders(httpGet, header);
            return executeRequest(uri, httpGet, null, httpClient);
        }
    }

    public static String post(String uri, AbstractHttpEntity entity, Map<String, String> header) throws Exception {
        HttpPost httpPost = new HttpPost(uri);
        httpPost.setEntity(entity);
        addHeaders(httpPost, header);
        return executeRequest(uri, httpPost, entity, getInstance());
    }

    private static void addHeaders(HttpRequestBase request, Map<String, String> headers) {
        if (headers != null && !headers.isEmpty()) {
            headers.forEach(request::addHeader);
        }
    }

    private static String executeRequest(String uri,
                                         HttpUriRequest request,
                                         AbstractHttpEntity entity,
                                         CloseableHttpClient httpClient) throws HttpRequestException {
        if (Objects.isNull(httpClient)) {
            LOG.error("HttpClient is null for uri: {}", uri);
            throw new HttpRequestException("HttpClient is null for uri: " + uri);
        }
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int code = response.getStatusLine().getStatusCode();
            String result = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (code == HttpStatus.SC_OK) {
                return result;
            } else {
                if (entity != null) {
                    String msg = String.format("Http request failed, url=%s, code=%d, body=%s, response=%s",
                            uri, code, entity, result);
                    LOG.error(msg);
                    throw new HttpRequestException(msg);
                } else {
                    String msg = String.format("Http request failed, url=%s, code=%d, response=%s",
                            uri, code, result);
                    LOG.error(msg);
                    throw new HttpRequestException(msg);
                }
            }
        } catch (IOException e) {
            String msg = "Http request exception, uri=" + uri;
            LOG.error(msg, e);
            throw new HttpRequestException(msg, e);
        }
    }

}
