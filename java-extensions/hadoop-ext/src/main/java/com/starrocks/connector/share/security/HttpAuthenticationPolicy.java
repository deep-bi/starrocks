// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.impl.auth.BasicSchemeFactory;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;

/** Prevents HTTP authentication from using the MD5 service retained for checksums. */
public final class HttpAuthenticationPolicy {
    private HttpAuthenticationPolicy() {
    }

    public static Registry<AuthSchemeProvider> schemes() {
        return RegistryBuilder.<AuthSchemeProvider>create()
                .register(AuthSchemes.BASIC, new BasicSchemeFactory()).build();
    }

    public static HttpClientBuilder clientBuilder() {
        return HttpClients.custom().setDefaultAuthSchemeRegistry(schemes());
    }
}
