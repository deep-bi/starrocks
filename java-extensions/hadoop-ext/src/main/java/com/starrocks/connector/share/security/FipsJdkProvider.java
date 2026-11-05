// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

package com.starrocks.connector.share.security;

import java.security.Provider;
import java.util.List;
import java.util.Map;

/** Retains the pinned JDK's entropy and compatibility services without SHA/signature fallback. */
public final class FipsJdkProvider extends Provider {
    public FipsJdkProvider() {
        // Preserve the SUN name required by the module's configured entropy source and JDK consumers.
        // This is a restricted service registration, not the full JDK SUN provider or a validated module.
        super("SUN", "1.0", "Restricted JDK entropy and compatibility services for Java FIPS");
        putService(new Service(this, "SecureRandom", "NativePRNGNonBlocking",
                "sun.security.provider.NativePRNG$NonBlocking", List.of(), Map.of("ThreadSafe", "true")));
        // Retain MD5 only for nonsecurity checksums used by existing dependencies. Authentication must reject it.
        add("MessageDigest", "MD5", "sun.security.provider.MD5");
        add("CertStore", "Collection", "sun.security.provider.certpath.CollectionCertStore");
        add("CertStore", "com.sun.security.IndexedCollection", "sun.security.provider.certpath.IndexedCollectionCertStore");
        add("Policy", "JavaPolicy", "sun.security.provider.PolicySpiFile");
        add("Configuration", "JavaLoginConfig", "sun.security.provider.ConfigFile$Spi");
    }

    private void add(String type, String algorithm, String implementation) {
        // Provider.Service instantiates these pinned JDK classes inside java.base, without module exports.
        putService(new Service(this, type, algorithm, implementation, List.of(), Map.of()));
    }
}
