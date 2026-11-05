# Copyright 2021-present StarRocks, Inc. All rights reserved.
# Licensed under the Apache License, Version 2.0

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("runtime", ROOT / "security/check-java-fips.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


def configured_options(home, component):
    result = subprocess.run(["bash", "-c", '''source "$1/bin/common.sh"
export_env_from_conf "$1/conf/$2.conf"
printf '%s' "$JAVA_OPTS"
''', "bash", str(ROOT), component], env={"PATH": os.defpath, "STARROCKS_HOME": str(home)},
                            capture_output=True, text=True, check=True)
    return result.stdout


class JavaRuntimeTest(unittest.TestCase):
    def test_repository_configs_require_every_security_setting(self):
        home = Path("/srv/starrocks")
        config = home / "conf/java-fips.security"
        for component in ("fe", "be", "cn"):
            options = configured_options(home, component).split()
            runtime.check_options(options, config, require_all=True)
            for name in (*runtime.REQUIRED, "java.security.properties", "javax.net.ssl.trustStorePassword"):
                with self.subTest(component=component, missing=name), self.assertRaises(ValueError):
                    runtime.check_options([o for o in options if not o.startswith("-D" + name + "=")],
                                          config, require_all=True)

    def test_required_security_settings_reject_conflicts(self):
        for option in (
            "-Dsoftware.amazon.awssdk.http.service.impl=software.amazon.awssdk.http.apache.ApacheSdkHttpService",
            "-Dorg.bouncycastle.fips.approved_only=false",
            "-Dorg.bouncycastle.rsa.allow_sha1_sig=true", "-Dorg.bouncycastle.rsa.allow_sha1_sig=TrUe",
            "-Dio.netty.handler.ssl.noOpenSsl=false",
            "-Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=false",
            "-Dcom.google.cloud.hadoop.repackaged.gcs.io.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=false",
            "-Dorg.apache.kudu.shaded.io.netty.handler.ssl.noOpenSsl=false",
            "-Dorg.bouncycastle.native.cpu_variant=vaes", "-Djava.security.properties==/tmp/override",
            "-Djavax.net.ssl.trustStoreProvider=SUN", "-Djavax.net.ssl.trustStoreType=PEM",
            "-Djavax.net.ssl.keyStoreProvider=SUN", "-Djavax.net.ssl.keyStoreType=JKS",
            "-Djavax.net.ssl.trustStoreType=PKCS12", "-Djavax.net.ssl.trustStore=",
            "-Djavax.net.ssl.trustStore=NONE", "-Djavax.net.ssl.trustStorePassword=",
        ):
            with self.subTest(option=option), self.assertRaises(ValueError):
                runtime.check_options([option], Path("/srv/java-fips.security"))

    def test_unrelated_jvm_settings_remain_available(self):
        runtime.check_options(["-Xmx1g", "-XX:+UseG1GC", "-javaagent:/srv/metrics.jar",
                               "--add-opens=java.base/java.security=ALL-UNNAMED",
                               "-Djava.security.policy=/srv/udf.policy",
                               "-Dorg.bouncycastle.rsa.allow_sha1_sig=false",
                               "-Dorg.bouncycastle.fips.approved_only=true"], Path("/srv/java-fips.security"))

    def test_ldaps_hostname_override_matches_jndi_property_semantics(self):
        option = "-Dcom.sun.jndi.ldap.object.disableEndpointIdentification"
        for suffix in ("", "=", "=true", "=TrUe"):
            with self.subTest(suffix=suffix), self.assertRaisesRegex(ValueError, "hostname verification"):
                runtime.check_options([option + suffix], Path("/srv/java-fips.security"))
        for suffix in ("=false", "=FALSE", "=other"):
            with self.subTest(suffix=suffix):
                runtime.check_options([option + suffix], Path("/srv/java-fips.security"))

    def test_injected_options_are_checked_for_security_conflicts_only(self):
        config = Path("/srv/java-fips.security")
        for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
            with self.subTest(name=name), patch.dict(os.environ, {name: '-Xmx1g -Duser.timezone="UTC"'}, clear=True):
                self.assertEqual("UTC", runtime.check_options(runtime.environment_options("fe"), config)["user.timezone"])
            for option in ("-Dorg.bouncycastle.fips.approved_only=false",
                           "-Dio.netty.handler.ssl.noOpenSsl=false",
                           "-Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=false",
                           "-Dcom.sun.jndi.ldap.object.disableEndpointIdentification"):
                with self.subTest(name=name, option=option), patch.dict(os.environ, {name: option}, clear=True):
                    with self.assertRaises(ValueError):
                        runtime.check_options(runtime.environment_options("fe"), config)
        with patch.dict(os.environ, {"JDK_JAVA_OPTIONS": "-Dorg.bouncycastle.fips.approved_only=false"}, clear=True):
            self.assertEqual([], runtime.environment_options("backend"))

    def test_injected_options_follow_jvm_quoting_and_whitespace(self):
        config = Path("/srv/java-fips.security")
        for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
            for separator in ("\\ ", "\v", "\f"):
                options = "-Dtest.value=" + separator + "-Dorg.bouncycastle.fips.approved_only=false"
                with self.subTest(name=name, separator=separator), patch.dict(os.environ, {name: options}, clear=True):
                    with self.assertRaisesRegex(ValueError, "Conflicting JVM property"):
                        runtime.check_options(runtime.environment_options("fe"), config)
            with self.subTest(name=name), patch.dict(os.environ, {
                    name: '-Dtest.value="two\\ words" -javaagent:/srv/metrics.jar'}, clear=True):
                self.assertEqual(["-Dtest.value=two\\ words", "-javaagent:/srv/metrics.jar"],
                                 runtime.environment_options("fe"))

    def test_preflight_does_not_require_runtime_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            for component in ("fe", "backend"):
                with self.subTest(component=component), patch.dict(os.environ, {
                        "JAVA_HOME": str(Path(temporary) / "jdk-without-release-metadata")}, clear=True):
                    result = subprocess.run([
                        "python3", str(ROOT / "security/check-java-fips.py"),
                        "--home", temporary, "--component", component, "--preflight",
                    ], capture_output=True, text=True)
                    self.assertEqual(0, result.returncode, result.stderr)

    def test_backend_launchers_without_java(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            for directory in ("bin", "conf", "security", "lib", "jdk/bin"):
                (home / directory).mkdir(parents=True)
            for name in ("start_backend.sh", "start_be.sh", "start_cn.sh", "common.sh"):
                shutil.copy2(ROOT / "bin" / name, home / "bin" / name)
            for name in ("be.conf", "cn.conf", "hadoop_env.sh", "java-fips.security"):
                shutil.copy2(ROOT / "conf" / name, home / "conf" / name)
            for name in ("check-java-fips.py", "java-fips-pinned-dependencies.json"):
                shutil.copy2(ROOT / "security" / name, home / "security" / name)
            (home / "lib/starrocks_be").write_text('#!/bin/bash\necho "native backend reached"\n')
            (home / "lib/starrocks_be").chmod(0o755)
            (home / "jdk/bin/java").write_text('#!/bin/bash\necho \'openjdk version "17"\' >&2\n')
            (home / "jdk/bin/java").chmod(0o755)
            for component in ("be", "cn"):
                for java_home in ("", str(home / "jdk")):
                    with self.subTest(component=component, java_home=java_home):
                        result = subprocess.run([
                            "bash", str(home / "bin" / ("start_" + component + ".sh")), "--logconsole",
                        ], env={"PATH": os.defpath, "JAVA_HOME": java_home}, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
                        if java_home:
                            self.assertNotEqual(0, result.returncode, result.stdout)
                            self.assertIn("Java startup rejected:", result.stdout)
                            self.assertIn("Missing package path:", result.stdout)
                            self.assertNotIn("native backend reached", result.stdout)
                        else:
                            self.assertEqual(0, result.returncode, result.stdout)
                            self.assertIn("native backend reached", result.stdout)

    def test_actual_launchers_reject_before_service_jvm_runs(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            for directory in ("bin", "conf", "security", "lib/jni-packages", "jdk/bin"):
                (home / directory).mkdir(parents=True)
            for name in ("start_fe.sh", "start_backend.sh", "start_be.sh", "start_cn.sh", "common.sh"):
                shutil.copy2(ROOT / "bin" / name, home / "bin" / name)
            for name in ("check-java-fips.py", "java-fips-pinned-dependencies.json"):
                shutil.copy2(ROOT / "security" / name, home / "security" / name)
            (home / "jdk/bin/java").write_text('#!/bin/bash\nif [ "${!#}" = "-version" ]; then\n'
                                               '  echo \'openjdk version "17.0.13"\' >&2\n  exit 0\nfi\n'
                                               'touch "${STARROCKS_HOME}/java-was-run"\n')
            (home / "jdk/bin/java").chmod(0o755)
            argument_file = home / "options.txt"
            argument_file.write_text("-Dorg.bouncycastle.fips.approved_only=false\n")

            def assert_rejected(component, env, error):
                result = subprocess.run(["bash", str(home / "bin" / ("start_" + component + ".sh"))],
                                        env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn(error, result.stdout)
                self.assertFalse((home / "java-was-run").exists(), result.stdout)

            for component, setting in (("fe", "JAVA_OPTS_FOR_JDK_11"),
                                       ("be", "JAVA_OPTS_FOR_JDK_9_AND_LATER"),
                                       ("cn", "JAVA_OPTS_FOR_JDK_9_AND_LATER")):
                env = {key: value for key, value in os.environ.items() if key not in (
                    "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "LIBHDFS_OPTS",
                    "JAVA_OPTS_FOR_JDK_11", "JAVA_OPTS_FOR_JDK_9_AND_LATER")}
                env["JAVA_HOME"] = str(home / "jdk")
                configured = configured_options(home, component)
                config = home / "conf" / (component + ".conf")
                for options, error in (("-Dorg.bouncycastle.fips.approved_only=false", "Conflicting JVM property"),
                                       (configured + " -Dorg.bouncycastle.rsa.allow_sha1_sig=true",
                                        "Conflicting JVM property: org.bouncycastle.rsa.allow_sha1_sig"),
                                       ("-Dio.netty.handler.ssl.noOpenSsl=false", "Conflicting JVM property"),
                                       ("-Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=false",
                                        "Conflicting JVM property"),
                                       ("-Xmx1g", "Missing required JVM property"),
                                       ("-Dcom.sun.jndi.ldap.object.disableEndpointIdentification", "hostname verification"),
                                       ("-Dcom.sun.jndi.ldap.object.disableEndpointIdentification=", "hostname verification"),
                                       ("-Dcom.sun.jndi.ldap.object.disableEndpointIdentification=true", "hostname verification")):
                    with self.subTest(component=component, options=options):
                        config.write_text('JAVA_OPTS="' + configured + '"\n' + setting + '="' + options + '"\n')
                        assert_rejected(component, env, error)

                config.write_text('JAVA_OPTS="' + configured + '"\n')
                # Legacy values inherited from the environment retain separators that
                # the configuration loader may otherwise normalize away.
                for separator in ("\t", "\n", "\r", "\v", "\f", "\u00a0"):
                    options = configured.replace("approved_only=true ", "approved_only=true" + separator)
                    with self.subTest(component=component, inherited_separator=separator):
                        assert_rejected(component, dict(env, **{setting: options}), "must use spaces as separators")
                option_files = ("@" + str(argument_file), "-XX:VMOptionsFile=" + str(argument_file))
                for option_file in option_files:
                    with self.subTest(component=component, service_option_file=option_file):
                        assert_rejected(component, dict(env, **{setting: configured + " " + option_file}),
                                        "JVM option files are unsupported")

                injected = ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")
                if component == "fe":
                    injected += ("JDK_JAVA_OPTIONS",)
                for name in injected:
                    for options, error in ((f'"@{argument_file}"', "JVM option files are unsupported"),
                                           ("-Dorg.bouncycastle.rsa.allow_sha1_sig=true",
                                            "Conflicting JVM property: org.bouncycastle.rsa.allow_sha1_sig"),
                                           (f'-XX:VMOptionsFile="{argument_file}"', "JVM option files are unsupported"),
                                           ("-Dtest.value=\\ -Dorg.bouncycastle.fips.approved_only=false",
                                            "Conflicting JVM property")):
                        with self.subTest(component=component, environment=name, options=options):
                            assert_rejected(component, dict(env, **{name: options}), error)

    def test_external_configuration_without_application_jars(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            (home / "conf").mkdir()
            (home / "security").mkdir()
            shutil.copy2(ROOT / "security/check-java-fips.py", home / "security/check-java-fips.py")
            config = home / "conf/java-fips.security"
            original = (ROOT / "conf/java-fips.security").read_bytes()
            pins = json.loads((ROOT / "security/java-fips-pinned-dependencies.json").read_text())
            # Only launcher validation runs. Synthetic provider bytes never enter a JVM.
            for provider in pins["jars"].values():
                provider["sha256"] = hashlib.sha256(b"test provider").hexdigest()
            (home / "security/java-fips-pinned-dependencies.json").write_text(json.dumps(pins))
            for component, directory in (("fe", "lib"), ("be", "lib/hadoop/common"), ("cn", "lib/hadoop/common")):
                providers = home / directory
                providers.mkdir(parents=True, exist_ok=True)
                for name in pins["jars"]:
                    (providers / name).write_bytes(b"test provider")
                command = ["python3", str(home / "security/check-java-fips.py"), "--home", str(home),
                           "--component", "fe" if component == "fe" else "backend",
                           "--options=" + configured_options(home, component)]
                with patch.dict(os.environ, {}, clear=True):
                    config.write_bytes(original)
                    result = subprocess.run(command, capture_output=True, text=True)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual("", result.stdout)
                    config.write_bytes(original + b"\nsecurity.provider.1=SUN\n")
                    result = subprocess.run(command, capture_output=True, text=True)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("altered Java security configuration", result.stderr)
                    config.unlink()
                    result = subprocess.run(command, capture_output=True, text=True)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("Java startup rejected:", result.stderr)


if __name__ == "__main__":
    unittest.main()
