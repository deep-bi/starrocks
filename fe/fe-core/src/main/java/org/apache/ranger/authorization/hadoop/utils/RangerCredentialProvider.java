// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

// Overrides Ranger 2.9.0's credential reader: configured store failures must not
// become null passwords and cause RangerRESTClient to omit TLS key/trust managers.
package org.apache.ranger.authorization.hadoop.utils;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.alias.CredentialProvider;
import org.apache.hadoop.security.alias.CredentialProviderFactory;

import java.io.IOException;
import java.util.List;

public final class RangerCredentialProvider {
    private static final RangerCredentialProvider CRED_PROVIDER = new RangerCredentialProvider();

    protected RangerCredentialProvider() {
    }

    public static RangerCredentialProvider getInstance() {
        return CRED_PROVIDER;
    }

    public String getCredentialString(String providerPath, String alias) {
        if (providerPath == null || providerPath.isBlank()) {
            return null;
        }
        if (alias == null || alias.isBlank()) {
            throw new IllegalStateException("Configured Ranger credential store requires an alias");
        }
        for (CredentialProvider provider : getCredentialProviders(providerPath)) {
            try {
                CredentialProvider.CredentialEntry entry = provider.getCredentialEntry(alias);
                if (entry != null && entry.getCredential() != null && entry.getCredential().length > 0) {
                    return new String(entry.getCredential());
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read configured Ranger credential: " + alias, e);
            }
        }
        throw new IllegalStateException("Configured Ranger credential is missing or empty: " + alias);
    }

    List<CredentialProvider> getCredentialProviders(String providerPath) {
        if (providerPath == null || providerPath.isBlank()) {
            return null;
        }
        Configuration conf = new Configuration();
        conf.set(CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH, providerPath);
        try {
            return CredentialProviderFactory.getProviders(conf);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load configured Ranger credential store", e);
        }
    }
}
