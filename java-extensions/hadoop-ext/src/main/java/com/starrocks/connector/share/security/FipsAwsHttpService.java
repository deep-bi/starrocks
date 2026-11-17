// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import software.amazon.awssdk.http.SdkHttpService;
import software.amazon.awssdk.http.apache.ApacheHttpClient;

/** Applies the authentication policy to default AWS transports, including credential clients. */
public final class FipsAwsHttpService implements SdkHttpService {
    @Override
    public ApacheHttpClient.Builder createHttpClientBuilder() {
        return ApacheHttpClient.builder().authSchemeProviderRegistry(HttpAuthenticationPolicy.schemes());
    }
}
