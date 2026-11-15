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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

public class BaseRequestTest {

    private static HttpRequest httpRequest() {
        return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/health");
    }

    @Test
    public void testIsSecureWithTlsHandlerInPipeline() throws Exception {
        // HttpServer adds an SslHandler in front of the HTTP codec on the HTTPS listener only.
        SSLEngine engine = SSLContext.getDefault().createSSLEngine();
        engine.setUseClientMode(false);
        ChannelInboundHandlerAdapter last = new ChannelInboundHandlerAdapter();
        EmbeddedChannel channel = new EmbeddedChannel(new SslHandler(engine), last);
        try {
            ChannelHandlerContext ctx = channel.pipeline().context(last);
            Assertions.assertTrue(new BaseRequest(ctx, httpRequest()).isSecure());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testIsNotSecureOnPlainPipeline() {
        ChannelInboundHandlerAdapter last = new ChannelInboundHandlerAdapter();
        EmbeddedChannel channel = new EmbeddedChannel(last);
        try {
            ChannelHandlerContext ctx = channel.pipeline().context(last);
            Assertions.assertFalse(new BaseRequest(ctx, httpRequest()).isSecure());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testIsNotSecureWithoutContext() {
        Assertions.assertFalse(new BaseRequest(null, httpRequest()).isSecure());
    }
}
