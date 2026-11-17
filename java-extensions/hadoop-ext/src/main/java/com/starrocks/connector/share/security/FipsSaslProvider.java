// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.connector.share.security;

import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.ProviderException;
import java.util.Arrays;
import java.util.Map;
import java.util.ServiceLoader;
import javax.security.auth.callback.CallbackHandler;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslClientFactory;
import javax.security.sasl.SaslException;

/** Retains only SunSASL mechanisms that perform no cryptography. */
public final class FipsSaslProvider extends Provider {
    public FipsSaslProvider() {
        super("StarRocksSASL", "1.0", "PLAIN and EXTERNAL SASL without SunSASL cryptographic mechanisms");
        put("SaslClientFactory.PLAIN", PlainExternalFactory.class.getName());
        put("SaslClientFactory.EXTERNAL", PlainExternalFactory.class.getName());
    }

    public static final class PlainExternalFactory implements SaslClientFactory {
        private final SaslClientFactory delegate;

        public PlainExternalFactory() throws NoSuchAlgorithmException {
            // Load the JDK provider without registering it. Its combined client factory also
            // implements CRAM-MD5, so exposing the original factory service is insufficient.
            Provider jdkProvider = ServiceLoader.load(Provider.class, ClassLoader.getPlatformClassLoader())
                    .stream()
                    .filter(candidate -> candidate.type().getName().equals("com.sun.security.sasl.Provider"))
                    .findFirst()
                    .orElseThrow(() -> new ProviderException("The pinned JDK SunSASL provider is unavailable"))
                    .get();
            delegate = (SaslClientFactory) jdkProvider.getService("SaslClientFactory", "PLAIN").newInstance(null);
        }

        @Override
        public SaslClient createSaslClient(String[] mechanisms, String authorizationId, String protocol,
                                          String serverName, Map<String, ?> props, CallbackHandler callbackHandler)
                throws SaslException {
            return delegate.createSaslClient(allowed(mechanisms), authorizationId, protocol,
                    serverName, props, callbackHandler);
        }

        @Override
        public String[] getMechanismNames(Map<String, ?> props) {
            // Hadoop caches factories from this method and bypasses Sasl.createSaslClient,
            // including its jdk.sasl.disabledMechanisms enforcement.
            return allowed(delegate.getMechanismNames(props));
        }

        private static String[] allowed(String[] mechanisms) {
            return Arrays.stream(mechanisms)
                    .filter(name -> "PLAIN".equals(name) || "EXTERNAL".equals(name))
                    .toArray(String[]::new);
        }
    }
}
