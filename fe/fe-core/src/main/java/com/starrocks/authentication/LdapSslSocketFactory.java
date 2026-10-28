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

// This file is based on code available under the Apache license here:
//   https://github.com/trinodb/trino/blob/master/lib/trino-plugin-toolkit/src/main/java/io/trino/plugin/base/ldap/LdapSslSocketFactory.java
package com.starrocks.authentication;


import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

public final class LdapSslSocketFactory extends SocketFactory {

    private record ConnectionSettings(SSLContext context, int connectTimeout) {
    }

    private static final ThreadLocal<ConnectionSettings> SSL_CONTEXT = new ThreadLocal<>();

    private final SocketFactory socketFactory;
    private final int connectTimeout;

    public LdapSslSocketFactory(SocketFactory socketFactory) {
        this(socketFactory, 0);
    }

    private LdapSslSocketFactory(SocketFactory socketFactory, int connectTimeout) {
        this.socketFactory = requireNonNull(socketFactory, "socketFactory is null");
        this.connectTimeout = connectTimeout;
    }

    @Override
    public Socket createSocket()
            throws IOException {
        // JNDI's unconnected-socket path loses the BCJSSE peer hostname. Use its named
        // socket path instead, applying the configured timeout in createSocket(host, port).
        throw new IOException("FIPS LDAP requires a named socket");
    }

    @Override
    public Socket createSocket(String host, int port)
            throws IOException {
        Socket transport = new Socket();
        try {
            transport.connect(new InetSocketAddress(host, port), connectTimeout);
            return verifyHostname(((SSLSocketFactory) socketFactory).createSocket(transport, host, port, true));
        } catch (IOException | RuntimeException e) {
            transport.close();
            throw e;
        }
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException {
        return verifyHostname(socketFactory.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port)
            throws IOException {
        return verifyHostname(socketFactory.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return verifyHostname(socketFactory.createSocket(address, port, localAddress, localPort));
    }

    private static Socket verifyHostname(Socket socket) {
        SSLSocket ssl = (SSLSocket) socket;
        SSLParameters parameters = ssl.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("LDAPS");
        parameters.setProtocols(new String[] {"TLSv1.2", "TLSv1.3"});
        ssl.setSSLParameters(parameters);
        return socket;
    }

    // entry point per https://docs.oracle.com/javase/jndi/tutorial/ldap/security/ssl.html
    @SuppressWarnings({"unused", "MethodOverridesStaticMethodOfSuperclass"})
    public static SocketFactory getDefault() {
        ConnectionSettings settings = SSL_CONTEXT.get();
        checkState(settings != null, "SSLContext was not set");
        return new LdapSslSocketFactory(settings.context().getSocketFactory(), settings.connectTimeout());
    }

    public static void setSslContextForCurrentThread(SSLContext sslContext) {
        setSslContextForCurrentThread(sslContext, 0);
    }

    public static void setSslContextForCurrentThread(SSLContext sslContext, int connectTimeout) {
        SSL_CONTEXT.set(new ConnectionSettings(sslContext, connectTimeout));
    }
}
