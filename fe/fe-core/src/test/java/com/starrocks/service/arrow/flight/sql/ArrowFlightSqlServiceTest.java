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

package com.starrocks.service.arrow.flight.sql;

import com.starrocks.common.Config;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.apache.arrow.driver.jdbc.shaded.org.bouncycastle.asn1.x500.X500Name;
import org.apache.arrow.driver.jdbc.shaded.org.bouncycastle.asn1.x509.BasicConstraints;
import org.apache.arrow.driver.jdbc.shaded.org.bouncycastle.asn1.x509.Extension;
import org.apache.arrow.driver.jdbc.shaded.org.bouncycastle.cert.X509CertificateHolder;
import org.apache.arrow.driver.jdbc.shaded.org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.apache.arrow.driver.jdbc.shaded.org.bouncycastle.operator.ContentSigner;
import org.apache.arrow.driver.jdbc.shaded.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.apache.arrow.flight.FlightServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class ArrowFlightSqlServiceTest {

    @AfterEach
    public void resetTlsConfig() {
        Config.arrow_flight_ssl_enable = false;
        Config.arrow_flight_ssl_certificate_path = "";
        Config.arrow_flight_ssl_private_key_path = "";
    }

    @Test
    public void testDisable() {
        ArrowFlightSqlService service = new ArrowFlightSqlService(-1);
        service.start();
        service.stop();
    }

    /**
     * Mock {@link FlightServer.Builder#build()}.
     */
    @Test
    public void testEnable(@Mocked FlightServer server) throws IOException, InterruptedException {
        new MockUp<FlightServer.Builder>() {
            @Mock
            public FlightServer build() {
                return server;
            }
        };

        new Expectations() {
            {
                server.start();
                result = server;
                times = 1;
            }

            {
                server.shutdown();
                times = 1;
            }

            {
                server.awaitTermination();
                times = 1;
            }

            {
                server.awaitTermination(anyLong, TimeUnit.SECONDS);
                result = true;
                times = 1;
            }
        };

        ArrowFlightSqlService service = new ArrowFlightSqlService(1234);
        service.start();
        service.stop();
    }

    @Test
    public void testEnableTls(@Mocked FlightServer server) throws Exception {
        new MockUp<FlightServer.Builder>() {
            @Mock
            public FlightServer build() {
                return server;
            }
        };

        new Expectations() {
            {
                server.start();
                result = server;
                times = 1;
            }

            {
                server.shutdown();
                times = 1;
            }

            {
                server.awaitTermination();
            }

            {
                server.awaitTermination(anyLong, TimeUnit.SECONDS);
                result = true;
                times = 1;
            }
        };

        configureTls();
        ArrowFlightSqlService service = new ArrowFlightSqlService(1234);
        service.start();
        service.stop();
    }

    @Test
    public void testEnableTlsMissingCertFilesThrows() {
        Config.arrow_flight_ssl_enable = true;
        Config.arrow_flight_ssl_certificate_path = "/nonexistent/cert.pem";
        Config.arrow_flight_ssl_private_key_path = "/nonexistent/key.pem";

        assertThrows(IllegalArgumentException.class, () -> new ArrowFlightSqlService(1234));
    }

    private static void configureTls() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=localhost");
        Instant now = Instant.now();
        Date notBefore = Date.from(now.minus(1, ChronoUnit.MINUTES));
        Date notAfter = Date.from(now.plus(2, ChronoUnit.DAYS));

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, new BigInteger(64, new SecureRandom()), notBefore, notAfter, subject, kp.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(holder.getEncoded()));

        File dir = Files.createTempDirectory("arrow-flight-ssl-").toFile();
        dir.deleteOnExit();
        File certFile = new File(dir, "cert.pem");
        File keyFile = new File(dir, "key.pem");
        writePem("CERTIFICATE", cert.getEncoded(), certFile);
        writePem("PRIVATE KEY", kp.getPrivate().getEncoded(), keyFile);

        Config.arrow_flight_ssl_enable = true;
        Config.arrow_flight_ssl_certificate_path = certFile.getAbsolutePath();
        Config.arrow_flight_ssl_private_key_path = keyFile.getAbsolutePath();
    }

    private static void writePem(String type, byte[] der, File file) throws IOException {
        file.deleteOnExit();
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        String content = "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.US_ASCII)) {
            writer.write(content);
        }
    }
}
