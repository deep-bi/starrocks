// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;

/** Adapts JDBC fixtures to the existing libhdfs harness without rebuilding native code. */
public final class RuntimeStartupTest {
    private static final AtomicInteger CALLS = new AtomicInteger();

    public static void exercise() throws Throwable {
        String test = System.getenv("STARROCKS_TEST_JDBC_CLASS");
        String mode = System.getenv("STARROCKS_TEST_JDBC_MODE");
        int call = CALLS.getAndIncrement();
        // Faults are permanent. Inject them only on the attached native worker.
        if (call == 0 && !"healthy".equals(mode)) {
            return;
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(RuntimeStartupTest.class.getClassLoader());
        try {
            Class.forName(test).getMethod("main", String[].class).invoke(null, (Object) new String[] {mode});
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
        System.out.println("JDBC fixture executed on embedded thread " + Thread.currentThread().getName());
    }
}
