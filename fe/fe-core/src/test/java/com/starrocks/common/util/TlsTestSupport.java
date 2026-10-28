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
package com.starrocks.common.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Temporary credentials and independent TLS peers. */
public class TlsTestSupport {
    public final Path directory;
    public final String password = UUID.randomUUID().toString();
    public final String type = "BCFKS";
    public final Path trust;
    public final Path pemTrust;

    public TlsTestSupport(Path directory) throws Exception {
        this.directory = directory;
        keytool("-genkeypair", "-alias", "ca", "-keystore", file("ca.jks"), "-dname", "CN=Test CA",
                "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA", "-validity", "30", "-ext", "bc:c");
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", file("ca.jks"), "-file", file("ca.pem"));
        KeyStore ca = jks(directory.resolve("ca.jks"));
        KeyStore trusted = KeyStore.getInstance(type, "BCFIPS");
        trusted.load(null, null);
        trusted.setCertificateEntry("ca", ca.getCertificate("ca"));
        trust = directory.resolve("trust." + type.toLowerCase(java.util.Locale.ROOT));
        try (OutputStream out = Files.newOutputStream(trust)) {
            trusted.store(out, password.toCharArray());
        }
        pemTrust = directory.resolve("ca.pem");
        // Surefire records system properties. Never put the generated credential in them.
        // Application clients read public PEM anchors without a password.
        System.setProperty("javax.net.ssl.trustStore", pemTrust.toString());
        System.setProperty("javax.net.ssl.trustStoreType", "PEM");
        System.clearProperty("javax.net.ssl.trustStorePassword");
    }

    public Path serverStore(String name) throws Exception {
        Path result = directory.resolve(name + "." + type.toLowerCase(java.util.Locale.ROOT));
        if (Files.exists(result)) {
            return result;
        }
        String hostname = name.equals("wronghost") ? "wrong.invalid" : "localhost";
        keytool("-genkeypair", "-alias", "server", "-keystore", file(name + "-source.jks"),
                "-dname", "CN=" + hostname, "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA",
                "-validity", "30", "-ext", "SAN=dns:" + hostname);
        if (!name.equals("untrusted")) {
            keytool("-certreq", "-alias", "server", "-keystore", file(name + "-source.jks"), "-file", file(name + ".csr"));
            List<String> signing = new ArrayList<>(List.of("-gencert", "-alias", "ca", "-keystore", file("ca.jks"),
                    "-infile", file(name + ".csr"), "-outfile", file(name + ".crt"), "-sigalg", "SHA256withRSA",
                    "-validity", name.equals("expired") ? "1" : "30", "-ext", "SAN=dns:" + hostname,
                    "-ext", "KU=digitalSignature,keyEncipherment", "-ext", "EKU=serverAuth"));
            if (name.equals("expired")) {
                signing.addAll(List.of("-startdate", "-10d"));
            }
            keytool(signing.toArray(new String[0]));
            keytool("-importcert", "-alias", "ca", "-keystore", file(name + "-source.jks"), "-file", file("ca.pem"));
            keytool("-importcert", "-alias", "server", "-keystore", file(name + "-source.jks"),
                    "-file", file(name + ".crt"));
        }
        KeyStore source = jks(directory.resolve(name + "-source.jks"));
        KeyStore target = KeyStore.getInstance(type, "BCFIPS");
        target.load(null, null);
        target.setKeyEntry("server", source.getKey("server", password.toCharArray()), password.toCharArray(),
                source.getCertificateChain("server"));
        try (OutputStream out = Files.newOutputStream(result)) {
            target.store(out, password.toCharArray());
        }
        return result;
    }

    // The negative-certificate peer is deliberately independent of the application's TLS provider.
    public SSLContext peerContext(String name) throws Exception {
        serverStore(name);
        KeyManagerFactory keys = KeyManagerFactory.getInstance("SunX509", "SunJSSE");
        keys.init(jks(directory.resolve(name + "-source.jks")), password.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS", "SunJSSE");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    public KeyStore jks(Path file) throws Exception {
        KeyStore store = KeyStore.getInstance("JKS", "SUN");
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, password.toCharArray());
        }
        return store;
    }

    private String file(String name) {
        return directory.resolve(name).toString();
    }

    private void keytool(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        command.addAll(Arrays.asList(arguments));
        command.addAll(List.of("-storetype", "JKS", "-storepass:env", "SR_TLS_TEST_PASSWORD",
                "-keypass:env", "SR_TLS_TEST_PASSWORD", "-noprompt"));
        Path log = directory.resolve("keytool.log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("SR_TLS_TEST_PASSWORD", password);
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Test keytool timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Test keytool failed: " + Files.readString(log));
        }
    }
}
