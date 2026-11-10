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
classpath="$work_dir:$root/conf:$root/fe/fe-server/target/starrocks-fe.jar:$root/java-extensions/hadoop-ext/target/starrocks-hadoop-ext.jar:$root/fe/fe-server/target/lib/*"
"$JAVA_HOME/bin/javac" --release 17 -proc:none -cp "$classpath" -d "$work_dir" \
    "$root/security/tests/NettyTlsPolicyTest.java" "$root/security/tests/SaslPolicyTest.java" \
    "$root/security/tests/HadoopTlsPolicyTest.java" "$root/security/tests/HadoopCryptoPolicyTest.java" \
    "$root/security/tests/MariaDbAuthenticationTest.java" "$root/security/tests/CertificatePolicyTest.java" \
    "$root/security/tests/S3ProxyAuthenticationTest.java" "$root/security/tests/HttpAuthenticationPolicyTest.java" \
    "$root/security/tests/OcspRevocationPolicyTest.java" "$root/security/tests/OracleAuthenticationTest.java" \
    "$root/security/tests/FrontendHttpStartupTest.java"

# Use the shipped service settings, without the FE heap size or GC log paths.
export STARROCKS_HOME=$root
source "$root/bin/common.sh"
export_env_from_conf "$root/conf/fe.conf" || exit 1
options=()
for option in $JAVA_OPTS; do
    if [[ $option == -D* ]]; then
        options+=("$option")
    fi
done
for mode in healthy readiness-fault active-fault; do
    "$JAVA_HOME/bin/java" "${options[@]}" -cp "$classpath" NettyTlsPolicyTest "$mode"
    "$JAVA_HOME/bin/java" "${options[@]}" -cp "$classpath" HadoopTlsPolicyTest "$mode"
    "$JAVA_HOME/bin/java" "${options[@]}" -cp "$classpath" HadoopCryptoPolicyTest "$mode"
    "$JAVA_HOME/bin/java" "${options[@]}" -cp "$classpath" MariaDbAuthenticationTest "$mode"
    "$JAVA_HOME/bin/java" "${options[@]}" -cp "$classpath" S3ProxyAuthenticationTest "$mode"
    "$JAVA_HOME/bin/java" "${options[@]}" -cp "$classpath" OracleAuthenticationTest "$mode"
    for component in fe cost paimon paimon-v1 paimon-default aws-s3 aws-sts aws-glue iceberg; do
        "$JAVA_HOME/bin/java" "${options[@]}" -cp "$classpath" HttpAuthenticationPolicyTest "$component" "$mode"
    done
done
for mode in signer signer-fault signer-active-fault; do
    "$JAVA_HOME/bin/java" "${options[@]}" -cp "$classpath" FrontendHttpStartupTest "$mode"
done
"$JAVA_HOME/bin/java" "${options[@]}" -cp "$classpath" HadoopCryptoPolicyTest missing-provider
"$JAVA_HOME/bin/java" "${options[@]}" -cp "$classpath" MariaDbAuthenticationTest missing-provider
"$JAVA_HOME/bin/java" "${options[@]}" -cp "$classpath" SaslPolicyTest
"$JAVA_HOME/bin/java" "${options[@]}" -cp "$classpath" CertificatePolicyTest

"$JAVA_HOME/bin/java" "${options[@]}" -cp "$classpath" OcspRevocationPolicyTest
"$JAVA_HOME/bin/java" "${options[@]}" -cp "$classpath" OracleAuthenticationTest missing-provider
