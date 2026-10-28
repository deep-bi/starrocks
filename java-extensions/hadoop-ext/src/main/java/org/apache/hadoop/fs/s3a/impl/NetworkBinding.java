/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.s3a.impl;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import com.starrocks.connector.share.security.HttpAuthenticationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.apache.ApacheHttpClient;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.ssl.DelegatingSSLSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;

import static org.apache.hadoop.fs.s3a.Constants.DEFAULT_ENDPOINT;
import static org.apache.hadoop.fs.s3a.Constants.DEFAULT_SSL_CHANNEL_MODE;
import static org.apache.hadoop.fs.s3a.Constants.ENDPOINT;
import static org.apache.hadoop.fs.s3a.Constants.SSL_CHANNEL_MODE;

/**
 * Configures network settings when communicating with AWS services.
 */
public final class NetworkBinding {

  private static final Logger LOG =
          LoggerFactory.getLogger(NetworkBinding.class);

  private NetworkBinding() {
  }

  /**
   * Configures the {@code SSLConnectionSocketFactory} used by the AWS SDK.
   * A custom Socket Factory can be set using the method
   * {@code setSslSocketFactory()}.
   * Uses the unshaded AWS HTTP transport shipped by StarRocks. The TLS and
   * authentication policies are required and binding errors must propagate.
   * @param conf the {@link Configuration} used to get the client specified
   *             value of {@code SSL_CHANNEL_MODE}
   * @param httpClientBuilder the http client builder.
   * @throws IOException if there is an error while initializing the
   * {@code SSLSocketFactory}.
   */
  public static void bindSSLChannelMode(Configuration conf,
      ApacheHttpClient.Builder httpClientBuilder) throws IOException {

    // Validate that SSL_CHANNEL_MODE is set to a valid value.
    String channelModeString = conf.getTrimmed(
            SSL_CHANNEL_MODE, DEFAULT_SSL_CHANNEL_MODE.name());
    DelegatingSSLSocketFactory.SSLChannelMode channelMode = null;
    for (DelegatingSSLSocketFactory.SSLChannelMode mode :
            DelegatingSSLSocketFactory.SSLChannelMode.values()) {
      if (mode.name().equalsIgnoreCase(channelModeString)) {
        channelMode = mode;
      }
    }
    if (channelMode == null) {
      throw new IllegalArgumentException(channelModeString +
              " is not a valid value for " + SSL_CHANNEL_MODE);
    }

    // Digest and NTLM can use nonapproved cryptography retained for checksums,
    // including cached authentication after a module error. Restrict negotiation
    // regardless of where Hadoop or the SDK obtained proxy credentials.
    httpClientBuilder.authSchemeProviderRegistry(HttpAuthenticationPolicy.schemes());
    DelegatingSSLSocketFactory.initializeDefaultFactory(channelMode);
    httpClientBuilder.socketFactory(new SSLConnectionSocketFactory(DelegatingSSLSocketFactory.getDefaultFactory(),
        SSLConnectionSocketFactory.getDefaultHostnameVerifier()));
  }

  /**
   * Is this an AWS endpoint? looks at end of FQDN.
   * @param endpoint endpoint
   * @return true if the endpoint matches the requirements for an aws endpoint.
   */
  public static boolean isAwsEndpoint(final String endpoint) {
    return (endpoint.isEmpty()
        || endpoint.endsWith(".amazonaws.com")
        || endpoint.endsWith(".amazonaws.com.cn"));
  }

  /**
   * Interface used to bind to the socket factory, allows the code which
   * works with the shaded AWS libraries to exist in their own class.
   */
  interface ConfigureAWSSocketFactory {
    void configureSocketFactory(ApacheHttpClient.Builder httpClientBuilder,
        DelegatingSSLSocketFactory.SSLChannelMode channelMode)
        throws IOException;
  }

  /**
   * Given an S3 bucket region as returned by a bucket location query,
   * fix it into a form which can be used by other AWS commands.
   * <p>
   * <a href="https://forums.aws.amazon.com/thread.jspa?messageID=796829">
   * https://forums.aws.amazon.com/thread.jspa?messageID=796829</a>
   * </p>
   * See also {@code com.amazonaws.services.s3.model.Region.fromValue()}
   * for its conversion logic.
   * @param region region from S3 call.
   * @return the region to use in AWS services.
   */
  public static String fixBucketRegion(final String region) {
    return region == null || region.equals("US")
        ? "us-east-1"
        : region;
  }

  /**
   * Log the dns address associated with s3 endpoint. If endpoint is
   * not set in the configuration, the {@code Constants#DEFAULT_ENDPOINT}
   * will be used.
   * @param conf input configuration.
   */
  public static void logDnsLookup(Configuration conf) {
    String endPoint = conf.getTrimmed(ENDPOINT, DEFAULT_ENDPOINT);
    String hostName = endPoint;
    if (!endPoint.isEmpty() && LOG.isDebugEnabled()) {
      // Updating the hostname if there is a scheme present.
      if (endPoint.contains("://")) {
        try {
          URI uri = new URI(endPoint);
          hostName = uri.getHost();
        } catch (URISyntaxException e) {
          LOG.debug("Got URISyntaxException, ignoring");
        }
      }
      LOG.debug("Bucket endpoint : {}, Hostname : {}, DNSAddress : {}",
              endPoint,
              hostName,
              NetUtils.normalizeHostName(hostName));
    }
  }
}
