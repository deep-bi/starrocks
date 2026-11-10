// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.internal.AbstractAwsSigner;
import software.amazon.awssdk.auth.signer.internal.SigningAlgorithm;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.security.MessageDigest;

public final class AwsSigningTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("signer") || args[0].equals("signer-fault")
                || args[0].equals("signer-active-fault"))) {
            throw new IllegalArgumentException("Expected signer, signer-fault or signer-active-fault");
        }
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
        System.out.println("AWS signing used BCFIPS SHA-256 and HMAC");
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
