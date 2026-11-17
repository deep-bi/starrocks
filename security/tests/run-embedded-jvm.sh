#!/usr/bin/env bash
# Copyright 2021-present StarRocks, Inc. All rights reserved.
# Licensed under the Apache License, Version 2.0
set -euo pipefail

if [[ $# != 2 ]]; then
    echo "Usage: $0 <JAVA_HOME> <new temporary work directory>" >&2
    exit 1
fi
export JAVA_HOME=$1
work_dir=$2
root=$(cd "$(dirname "$0")/../.." && pwd)
mkdir "$work_dir"
work_dir=$(cd "$work_dir" && pwd)
source_dir="$root/thirdparty/src/hadoop-3.4.2-src/hadoop-hdfs-project/hadoop-hdfs-native-client/src/main/native/libhdfs"
cp -R "$source_dir" "$work_dir/libhdfs"
# Source trees from the previous callback implementation must be re-extracted first.
if rg -q 'starrocksCheckJavaSecurity' "$work_dir/libhdfs/jni_helper.c"; then
    echo "Re-extract libhdfs before testing the callback-free build" >&2
    exit 1
fi
make -C "$work_dir/libhdfs" clean
make -C "$work_dir/libhdfs" -j2
cc -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" "$root/security/tests/native-jvm-startup.c" \
    "$work_dir/libhdfs/libhdfs.a" -L"$JAVA_HOME/lib/server" -Wl,-rpath,"$JAVA_HOME/lib/server" \
    -ljvm -lpthread -ldl -o "$work_dir/native-jvm"
mkdir "$work_dir/classes"
"$JAVA_HOME/bin/javac" --release 17 -proc:none \
    -cp "$root/java-extensions/java-utils/target/starrocks-java-utils.jar:$root/java-extensions/hadoop-ext/target/starrocks-hadoop-ext.jar:$root/java-extensions/hadoop-lib/target/hadoop-lib/*" \
    -d "$work_dir/classes" "$root/security/tests/RuntimeStartupTest.java" "$root/security/tests/SaslPolicyTest.java" \
    "$root/security/tests/HadoopCryptoPolicyTest.java" "$root/security/tests/CertificatePolicyTest.java" "$root/security/tests/OcspRevocationPolicyTest.java"
"$JAVA_HOME/bin/jar" --create --file "$work_dir/runtime-tests.jar" -C "$work_dir/classes" .
export CLASSPATH="$work_dir/runtime-tests.jar:$root/conf:$root/java-extensions/java-utils/target/starrocks-java-utils.jar:$root/java-extensions/hadoop-ext/target/starrocks-hadoop-ext.jar:$root/java-extensions/hadoop-lib/target/hadoop-lib/*"
# Use the shipped backend JVM settings, including the expanded security configuration path.
export STARROCKS_HOME=$root
source "$root/bin/common.sh"
export_env_from_conf "$root/conf/be.conf" || exit 1
export LIBHDFS_OPTS="$JAVA_OPTS"
export STARROCKS_TEST_READER_ROOT="$root/java-extensions"
"$work_dir/native-jvm"
"$JAVA_HOME/bin/java" $LIBHDFS_OPTS RuntimeStartupTest
# Isolated JVM faults exercise readiness/error propagation without changing a signed JAR
"$JAVA_HOME/bin/java" $LIBHDFS_OPTS RuntimeStartupTest remove-provider
"$JAVA_HOME/bin/java" $LIBHDFS_OPTS RuntimeStartupTest readiness-fault
"$JAVA_HOME/bin/java" $LIBHDFS_OPTS RuntimeStartupTest active-fault

# Exercise the real BE/CN launch scripts with the same libhdfs harness as their executable.
# This tests their final options/classpath, it is not a full BE or CN service startup.
service="$work_dir/service"
mkdir -p "$service/bin" "$service/conf" "$service/security" "$service/lib/jni-packages" "$service/lib/hadoop"
cp "$root/bin/"{start_be.sh,start_cn.sh,start_backend.sh,common.sh} "$service/bin/"
cp "$root/security/"{check-java-fips.py,java-fips-pinned-dependencies.json} "$service/security/"
cp "$root/conf/"{java-fips.security,core-site.xml,hadoop_env.sh,be.conf,cn.conf} "$service/conf/"
echo 'JAVA_OPTS="${JAVA_OPTS} -Xmx512m"' >> "$service/conf/be.conf"
echo 'JAVA_OPTS="${JAVA_OPTS} -Xmx512m"' >> "$service/conf/cn.conf"
cp "$work_dir/native-jvm" "$service/lib/starrocks_be"
cp -R "$root/java-extensions/hadoop-lib/target/hadoop-lib" "$service/lib/hadoop/common"
cp "$root/java-extensions/java-utils/target/starrocks-java-utils.jar" \
    "$root/java-extensions/hadoop-ext/target/starrocks-hadoop-ext.jar" \
    "$work_dir/runtime-tests.jar" "$service/lib/jni-packages/"
unset CLASSPATH LIBHDFS_OPTS JAVA_OPTS
bash "$service/bin/start_be.sh" --logconsole
bash "$service/bin/start_cn.sh" --logconsole
