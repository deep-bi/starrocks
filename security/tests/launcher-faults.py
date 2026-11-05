#!/usr/bin/env python3
# Copyright 2021-present StarRocks, Inc. All rights reserved.
# Licensed under the Apache License, Version 2.0
"""Exercise fail-closed checks in a stopped, isolated FIPS FE distribution."""

import argparse
import os
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--home", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    args = parser.parse_args()
    home = args.home.resolve()
    assert str(home).startswith("/tmp/"), "Use a disposable /tmp distribution"
    pidfile = home / "bin/fe.pid"
    if pidfile.exists() and Path("/proc", pidfile.read_text().strip()).exists():
        raise ValueError("Stop the isolated FE before injecting faults")
    env = dict(os.environ, JAVA_HOME=str(args.java_home))
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "LIBHDFS_OPTS", "CLASSPATH"):
        env.pop(name, None)

    def rejected(label, changes=None):
        result = subprocess.run(["bash", str(home / "bin/start_fe.sh"), "--logconsole", "--", "-v"],
                                env=dict(env, **(changes or {})), stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=30)
        assert result.returncode != 0 and "Java startup rejected:" in result.stdout, label
        print(label + ": rejected")

    provider = home / "lib/bc-fips-2.1.1.jar"
    backup = provider.with_suffix(".saved")
    assert not backup.exists()
    provider.rename(backup)
    try:
        rejected("missing signed provider")
        # A synthetic archive replaces the pathname temporarily. Signed bytes stay in backup.
        with zipfile.ZipFile(provider, "w") as archive:
            archive.writestr("org/bouncycastle/crypto/fips/FipsStatus.class", b"test fault")
        rejected("provider hash mismatch")
    finally:
        provider.unlink(missing_ok=True)
        backup.rename(provider)

    overlay = home / "conf/java-fips.security"
    original = overlay.read_bytes()
    try:
        overlay.write_bytes(original + b"\nsecurity.provider.1=SUN\n")
        rejected("altered security overlay")
    finally:
        overlay.write_bytes(original)

    rejected("legacy approved-mode override", {"JAVA_OPTS_FOR_JDK_11": "-Dorg.bouncycastle.fips.approved_only=false"})
    rejected("native Netty TLS override", {"JAVA_OPTS_FOR_JDK_11": "-Dio.netty.handler.ssl.noOpenSsl=false"})
    rejected("unrestricted AWS transport", {
        "JAVA_OPTS_FOR_JDK_11": "-Dsoftware.amazon.awssdk.http.service.impl=software.amazon.awssdk.http.apache.ApacheSdkHttpService"})
    rejected("global PEM requires deployment conversion", {"JAVA_OPTS_FOR_JDK_11": "-Djavax.net.ssl.trustStoreType=PEM"})
    rejected("ordinary trust provider", {"JAVA_OPTS_FOR_JDK_11": "-Djavax.net.ssl.trustStoreProvider=SUN"})
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        rejected(name + " security conflict", {name: "-Dorg.bouncycastle.fips.approved_only=false"})
        rejected(name + " native TLS conflict", {name: "-Dio.netty.handler.ssl.noOpenSsl=false"})
    print("All launcher faults rejected, original signed provider and configuration restored")


if __name__ == "__main__":
    main()
