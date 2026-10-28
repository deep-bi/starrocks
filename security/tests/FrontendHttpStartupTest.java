// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.internal.AbstractAwsSigner;
import software.amazon.awssdk.auth.signer.internal.SigningAlgorithm;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

public final class FrontendHttpStartupTest {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("signer-fault") || args[0].equals("signer-active-fault")) {
            if (args[0].equals("signer-active-fault")) {
                signRequest();
            }
            var status = Class.forName("org.bouncycastle.crypto.fips.FipsStatus");
            var fault = status.getDeclaredMethod("moveToErrorStatus", String.class);
            fault.setAccessible(true);
            try {
                fault.invoke(null, "isolated signing test error state");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                // Some transitions report the module error immediately.
            }
            try {
                signRequest();
                throw new AssertionError("AWS signer accepted a failed module");
            } catch (Throwable failure) {
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof java.security.NoSuchAlgorithmException
                            || cause.getClass().getName().startsWith("org.bouncycastle.crypto.fips.Fips")) {
                        System.out.println("AWS signing rejected a failed module");
                        return;
                    }
                }
                throw failure;
            }
        }
        signRequest();
        var digest = AbstractAwsSigner.class.getDeclaredField("SHA256_MESSAGE_DIGEST");
        digest.setAccessible(true);
        var selected = (MessageDigest) ((ThreadLocal<?>) digest.get(null)).get();
        if (!selected.getProvider().getName().equals("BCFIPS")
                || !SigningAlgorithm.HmacSHA256.getMac().getProvider().getName().equals("BCFIPS")) {
            throw new AssertionError("AWS signing did not select BCFIPS");
        }
        if (args[0].equals("signer")) {
            System.out.println("AWS signing used BCFIPS SHA-256 and HMAC");
            return;
        }
        URL url = new URL(args[0]);
        if (!SSLContext.getDefault().getProvider().getName().equals("BCJSSE")) {
            throw new AssertionError("Default TLS did not select BCJSSE");
        }
        // No application SSL context, socket factory or trust loader on this dependency path.
        try (var client = HttpClients.createSystem()) {
            if (args.length > 1 && args[1].equals("reject")) {
                try (var response = client.execute(new HttpGet(url.toURI()))) {
                    throw new AssertionError("Default dependency TLS accepted an invalid peer");
                } catch (IOException expected) {
                    boolean certificateFailure = false;
                    for (Throwable cause = expected; cause != null; cause = cause.getCause()) {
                        certificateFailure |= cause instanceof SSLException || cause instanceof CertificateException;
                        if (String.valueOf(cause.getMessage()).contains("No hostname specified")) {
                            throw new AssertionError("Dependency TLS lost its peer hostname", expected);
                        }
                    }
                    if (!certificateFailure) {
                        throw expected;
                    }
                    System.out.println("Default dependency TLS rejected an invalid peer");
                }
                return;
            }
            try (var response = client.execute(new HttpGet(url.toURI()))) {
                if (response.getStatusLine().getStatusCode() != 200) {
                    throw new AssertionError("Default dependency TLS failed");
                }
            }
        }
        System.out.println("Default dependency TLS passed against the actual HTTPS listener");
    }

    private static void signRequest() {
        var request = SdkHttpFullRequest.builder().uri(URI.create("https://s3.example.invalid/bucket/key"))
                .method(SdkHttpMethod.GET).build();
        var parameters = Aws4SignerParams.builder().awsCredentials(AwsBasicCredentials.create("test-access", "test-secret"))
                .signingName("s3").signingRegion(Region.US_EAST_1).build();
        if (Aws4Signer.create().sign(request, parameters).firstMatchingHeader("Authorization").isEmpty()) {
            throw new AssertionError("AWS request was not signed");
        }
    }
}
