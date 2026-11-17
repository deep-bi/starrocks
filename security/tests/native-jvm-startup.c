/* Copyright 2021-present StarRocks, Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0
 * Link to the production libhdfs, without the rest of the BE/native crypto stack.
 */
#include <jni.h>
#include <pthread.h>
#include <stdio.h>

extern JNIEnv *getJNIEnv(void);

static void *exercise(void *unused) {
    JNIEnv *env = getJNIEnv();
    if (!env) return (void *)1;
    jclass test = (*env)->FindClass(env, "RuntimeStartupTest");
    if (!test) goto failed;
    jmethodID method = (*env)->GetStaticMethodID(env, test, "exercise", "()V");
    if (!method) goto failed;
    (*env)->CallStaticVoidMethod(env, test, method);
    (*env)->DeleteLocalRef(env, test);
    if ((*env)->ExceptionCheck(env)) goto failed;
    return NULL;
failed:
    (*env)->ExceptionDescribe(env);
    return (void *)1;
}

int main(void) {
    /* First entry creates the JVM lazily, second attaches an independent native worker. */
    if (exercise(NULL)) return 1;
    pthread_t worker;
    void *result = NULL;
    if (pthread_create(&worker, NULL, exercise, NULL) || pthread_join(worker, &result) || result) return 1;
    puts("libhdfs lazy JVM, native worker and extension checks passed");
    return 0;
}
