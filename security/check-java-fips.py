#!/usr/bin/env python3
# Copyright 2021-present StarRocks, Inc. All rights reserved.
# Licensed under the Apache License, Version 2.0

"""Validate Java FIPS package contents or effective service startup settings."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import zipfile


PINS = json.loads(Path(__file__).with_name("java-fips-pinned-dependencies.json").read_text())
BC_CLASS = re.compile(r"(^|/)org/bouncycastle/.*\.class$")
BC_PROVIDERS = {"BouncyCastleProvider.class", "BouncyCastleFipsProvider.class", "BouncyCastleJsseProvider.class"}

REQUIRED = {
    "software.amazon.awssdk.http.service.impl": "com.starrocks.connector.share.security.FipsAwsHttpService",
    "org.bouncycastle.fips.approved_only": "true",
    "org.bouncycastle.native.cpu_variant": "java",
    # Azure/Reactor use Netty's default TLS provider, including on embedded JVMs.
    "io.netty.handler.ssl.noOpenSsl": "true",
    "io.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl": "true",
    "com.google.cloud.hadoop.repackaged.gcs.io.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl": "true",
    "org.apache.kudu.shaded.io.netty.handler.ssl.noOpenSsl": "true",
    "org.bouncycastle.jca.enable_jks": "true",
    # BCFIPS JKS is certificate-only. Convert global PEM/BCFKS anchors offline.
    "javax.net.ssl.trustStoreProvider": "BCFIPS",
    "javax.net.ssl.trustStoreType": "JKS",
}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_archive(stream, label, errors, depth=0):
    if depth > 8:
        errors.append(f"Nested archive limit exceeded: {label}")
        return
    try:
        with zipfile.ZipFile(stream) as archive:
            for entry in archive.infolist():
                if BC_CLASS.search(entry.filename) or entry.filename.rsplit("/", 1)[-1] in BC_PROVIDERS:
                    errors.append(f"Bundled or relocated BC class: {label}!{entry.filename}")
                    break
                if (entry.filename == "org/wildfly/openssl/OpenSSLProvider.class"
                        or entry.filename.endswith("/org/wildfly/openssl/OpenSSLProvider.class")):
                    errors.append(f"Unapproved native TLS binding: {label}!{entry.filename}")
                    break
            for entry in archive.infolist():
                if entry.filename.lower().endswith((".jar", ".zip")):
                    check_archive(io.BytesIO(archive.read(entry)), f"{label}!{entry.filename}", errors, depth + 1)
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        errors.append(f"Cannot inspect {label}: {error}")


def check_packages(roots, provider_dir=None):
    errors = []
    paths = set()
    for root in roots:
        if not root.exists():
            errors.append(f"Missing package path: {root}")
        elif root.is_file():
            paths.add(root.absolute())
        else:
            paths.update(p.absolute() for p in root.rglob("*") if p.is_file() and p.suffix.lower() == ".jar")
    if not paths:
        errors.append("No packaged JARs found")
    provider_dir = provider_dir.absolute() if provider_dir else None
    found = {}
    for path in sorted(paths):
        if path.name in PINS["jars"]:
            found.setdefault(path.name, []).append(path)
            if sha256(path) != PINS["jars"][path.name]["sha256"]:
                errors.append(f"SHA-256 mismatch: {path}")
            if provider_dir is None or path.parent != provider_dir:
                errors.append(f"Provider outside the service provider directory: {path}")
        else:
            check_archive(path, str(path), errors)
    if provider_dir:
        for name in PINS["jars"]:
            copies = found.get(name, [])
            if len(copies) != 1 or copies[0].parent != provider_dir:
                errors.append(f"Expected exactly one {provider_dir / name}, found {len(copies)} copies")
    return errors


def verify_provider(provider_dir, java_home):
    """Use the JDK signature verifier and the vendor's integrity/self-test tool."""
    env = dict(os.environ, LC_ALL="C")
    for name in PINS["jars"]:
        result = subprocess.run([str(java_home / "bin/jarsigner"), "-verify", str(provider_dir / name)],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, check=True)
        if "jar verified." not in result.stdout:
            raise ValueError(f"JAR signature verification failed for {name}: {result.stdout}")
    core = next(name for name, pin in PINS["jars"].items() if pin["artifact"] == "bc-fips")
    result = subprocess.run([
        str(java_home / "bin/java"), "-Dorg.bouncycastle.fips.approved_only=true",
        "-Dorg.bouncycastle.native.cpu_variant=java", "-cp", str(provider_dir / core),
        "org.bouncycastle.util.DumpInfo",
    ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, check=True)
    print(result.stdout, end="")
    if "FIPS Ready Status: READY" not in result.stdout:
        raise ValueError("BCFIPS integrity/self-test did not report READY")


def check_options(options, config, require_all=False):
    properties = {}
    expected = REQUIRED | {"javax.net.ssl.keyStoreProvider": "BCFIPS", "javax.net.ssl.keyStoreType": "BCFKS"}
    expected["org.bouncycastle.rsa.allow_sha1_sig"] = "false"
    for option in options:
        if option.startswith(("@", "-XX:VMOptionsFile=")):
            raise ValueError("JVM option files are unsupported: supply options directly")
        if not option.startswith("-D"):
            continue
        name, _, value = option[2:].partition("=")
        if name in expected and value != expected[name]:
            raise ValueError("Conflicting JVM property: " + name)
        if name == "java.security.properties" and (
                not value or value.startswith("=") or Path(value).resolve() != config.resolve()):
            raise ValueError("Conflicting JVM property: " + name)
        # JNDI also disables hostname verification when the property has no value.
        if name == "com.sun.jndi.ldap.object.disableEndpointIdentification" and (
                not value or value.lower() == "true"):
            raise ValueError("LDAPS requires hostname verification: " + name)
        if name in ("javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword") and (
                not value or (name == "javax.net.ssl.trustStore" and value == "NONE")):
            raise ValueError("Empty or disabled JSSE trust input: " + name)
        properties[name] = value
    if require_all:
        for name in REQUIRED | {"java.security.properties": str(config)}:
            if name not in properties:
                raise ValueError("Missing required JVM property: " + name)
        if not properties.get("javax.net.ssl.trustStorePassword"):
            raise ValueError("JSSE trust store requires a nonempty password")
    return properties


def environment_options(component, after_service=False):
    names = ["_JAVA_OPTIONS"] if after_service else ["JAVA_TOOL_OPTIONS"]
    if component == "fe" and not after_service:
        names.append("JDK_JAVA_OPTIONS")
    options = []
    for name in names:
        # JVM environment parsers support quotes, but do not treat backslashes as
        # shell escapes. They also split on vertical tabs and form feeds.
        parser = shlex.shlex(os.environ.get(name, ""), posix=True)
        parser.whitespace_split = True
        parser.whitespace = " \t\r\n\v\f"
        parser.commenters = ""
        parser.escape = ""
        options.extend(parser)
    return options


def check_trust_store(properties):
    location = properties.get("javax.net.ssl.trustStore")
    if location is None:
        java_home = properties.get("java.home", os.environ.get("JAVA_HOME"))
        if not java_home:
            raise ValueError("JAVA_HOME is required to locate the default JSSE trust store")
        security = Path(java_home) / "lib/security"
        location = security / "jssecacerts"
        if not location.exists():
            location = security / "cacerts"
    location = Path(location)
    try:
        with location.open("rb") as stream:
            header = stream.read(12)
    except OSError as error:
        raise ValueError(f"Cannot read JSSE trust store {location}: {error}") from error
    if (len(header) != 12 or header[:4] != b"\xfe\xed\xfe\xed"
            or int.from_bytes(header[4:8], "big") not in (1, 2)):
        raise ValueError(f"JSSE trust store must be JKS: {location}. "
                         "Convert public CA certificates to a certificate-only JKS store before starting")


def check_startup(home, component, options="", preflight=False):
    home = home.resolve()
    if any(c.isspace() for c in str(home)):
        raise ValueError("Whitespace in the java-fips installation path is unsupported")
    config = home / "conf/java-fips.security"
    # libhdfs splits only on literal spaces; accepting other separators here can
    # validate flags that the embedded JVM instead consumes as a property value.
    if any(c.isspace() and c != " " for c in options):
        raise ValueError("Service JVM options must use spaces as separators")
    service_options = [option for option in options.split(" ") if option]
    # _JAVA_OPTIONS overrides command-line and JNI options
    properties = check_options(environment_options(component) + service_options
                               + environment_options(component, after_service=True), config,
                               require_all=not preflight)
    if preflight:
        return
    provider_dir = home / ("lib" if component == "fe" else "lib/hadoop/common")
    if sha256(config) != PINS["security_properties_sha256"]:
        raise ValueError("Missing or altered Java security configuration")
    # Full dependency/nested-JAR inspection belongs to post-build validation. Startup verifies
    # only the selected provider artifacts, before the service JVM can use them.
    errors = check_packages([provider_dir / name for name in PINS["jars"]], provider_dir)
    if errors:
        raise ValueError("\n".join(errors))
    check_trust_store(properties)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", type=Path, help="JARs or directories in a single service package")
    parser.add_argument("--providers", type=Path, help="Directory owning the four unchanged provider JARs")
    parser.add_argument("--java-home", type=Path, help="Also run jarsigner and the BCFIPS integrity/self-test")
    parser.add_argument("--jdk-archive", type=Path, help="Verify the pinned Temurin archive SHA-256")
    parser.add_argument("--home", type=Path, help="Validate startup settings for an installed service")
    parser.add_argument("--component", choices=("fe", "backend"), help="Service JVM startup path")
    parser.add_argument("--preflight", action="store_true", help="Check injected JVM options before launch")
    parser.add_argument("--options", help="Final JVM options selected by the service launcher")
    args = parser.parse_args()
    if args.home:
        if not args.component:
            parser.error("--home requires --component")
        if args.paths or args.providers or args.java_home or args.jdk_archive:
            parser.error("startup validation cannot be combined with package or JDK archive validation")
        try:
            check_startup(args.home, args.component, args.options or "", args.preflight)
        except (OSError, ValueError, KeyError) as error:
            raise ValueError("Java startup rejected: " + str(error)) from error
        return
    if args.component or args.preflight or args.options is not None:
        parser.error("--component, --preflight and --options require --home")
    errors = []
    if args.jdk_archive:
        if sha256(args.jdk_archive) != PINS["jdk"]["sha256"]:
            errors.append(f"JDK SHA-256 mismatch: {args.jdk_archive}")
    if args.paths:
        errors.extend(check_packages(args.paths, args.providers))
    elif not args.jdk_archive:
        parser.error("provide package paths or --jdk-archive")
    if args.java_home and (not args.providers or not args.paths):
        parser.error("--java-home requires --providers and package paths")
    if errors:
        raise ValueError("\n".join(errors))
    if args.java_home:
        verify_provider(args.providers, args.java_home)
    print("Java FIPS artifact checks passed")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
