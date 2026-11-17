#!/usr/bin/env bash
# Copyright 2021-present StarRocks, Inc. All rights reserved.
# Licensed under the Apache License, Version 2.0
set -euo pipefail

if [[ $# != 4 && $# != 5 ]]; then
    echo "Usage: $0 <JAVA_HOME> <new temporary work directory> <mysql-connector-j-8.2.0.jar> <existing native-jvm harness> [fixture class]" >&2
    exit 1
fi
export JAVA_HOME=$1
work_dir=$2
mysql_driver=$3
native_harness=$4
tests=(PostgresAuthenticationTest MariaDbAuthenticationTest MySqlAuthenticationTest OracleAuthenticationTest)
if [[ $# == 5 ]]; then
    case $5 in
        PostgresAuthenticationTest|MariaDbAuthenticationTest|MySqlAuthenticationTest|OracleAuthenticationTest)
            tests=("$5") ;;
        *) echo "Unknown JDBC authentication fixture: $5" >&2; exit 1 ;;
    esac
fi
root=$(cd "$(dirname "$0")/../.." && pwd)
mkdir "$work_dir"
work_dir=$(cd "$work_dir" && pwd)
export CLASSPATH="$work_dir:$root/conf:$root/java-extensions/hadoop-ext/target/classes:$root/java-extensions/hadoop-ext/target/starrocks-hadoop-ext.jar:$root/fe/fe-server/target/lib/*:$mysql_driver"
"$JAVA_HOME/bin/javac" --release 17 -proc:none -cp "$CLASSPATH" -d "$work_dir" \
    "$root/security/tests/PostgresAuthenticationTest.java" "$root/security/tests/MariaDbAuthenticationTest.java" \
    "$root/security/tests/MySqlAuthenticationTest.java" "$root/security/tests/OracleAuthenticationTest.java" \
    "$root/security/tests/jdbc-auth/RuntimeStartupTest.java"
export STARROCKS_HOME=$root
source "$root/bin/common.sh"
export_env_from_conf "$root/conf/be.conf" || exit 1
export LIBHDFS_OPTS="$JAVA_OPTS"
options=()
for option in $JAVA_OPTS; do
    if [[ $option == -D* ]]; then options+=("$option"); fi
done
for test in "${tests[@]}"; do
    for mode in healthy readiness-fault active-fault missing-provider; do
        "$JAVA_HOME/bin/java" "${options[@]}" -cp "$CLASSPATH" "$test" "$mode"
        STARROCKS_TEST_JDBC_CLASS="$test" STARROCKS_TEST_JDBC_MODE="$mode" "$native_harness"
    done
done
