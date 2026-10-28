#!/usr/bin/env python3
# Copyright 2021-present StarRocks, Inc. All rights reserved.
# Licensed under the Apache License, Version 2.0
"""Start an isolated, already assembled FE and exercise its real TLS listeners."""

import argparse
import http.client
import os
from pathlib import Path
import secrets
import socket
import ssl
import struct
import subprocess
import time


def java_options():
    config = Path(__file__).resolve().parents[2] / "conf/fe.conf"
    options = next(line for line in config.read_text().splitlines() if line.startswith("JAVA_OPTS="))
    return options.partition("=")[2].strip('"').replace("-Xmx8192m", "-Xmx1024m")


def port():
    with socket.socket() as sock:
        sock.bind(("", 0))
        return sock.getsockname()[1]


def packet(sock):
    def read(size):
        data = b""
        while len(data) < size:
            part = sock.recv(size - len(data))
            if not part:
                raise EOFError("MySQL connection closed")
            data += part
        return data
    header = read(4)
    return header[3], read(int.from_bytes(header[:3], "little"))


def send(sock, sequence, payload):
    sock.sendall(len(payload).to_bytes(3, "little") + bytes([sequence]) + payload)


def mysql(host, query_port, tls, context, require_tls=True):
    with socket.create_connection((host, query_port), timeout=10) as transport:
        transport.settimeout(10)
        sequence, greeting = packet(transport)
        assert sequence == 0 and greeting[0] == 10
        flags = 1 | 4 | 512 | 8192 | 32768 | 524288
        header = lambda f: struct.pack("<IIB", f, 16777216, 33) + b"\0" * 23
        active = transport
        if tls:
            flags |= 2048
            send(active, 1, header(flags))
            active = context.wrap_socket(transport, server_hostname=host)
        try:
            send(active, 2 if tls else 1, header(flags) + b"root\0\0mysql_native_password\0")
            sequence, response = packet(active)
            if not tls and require_tls:
                assert response[0] == 255 and int.from_bytes(response[1:3], "little") == 5205, response
                return
            if response[0] == 254:
                send(active, sequence + 1, b"")
                sequence, response = packet(active)
            assert response[0] == 0, response
            send(active, 0, b"\x03SELECT 1")
            _, response = packet(active)
            assert response[0] == 1, response  # one result column
        finally:
            if active is not transport:
                active.close()


def no_tls(home, java_home):
    host = socket.gethostbyname(socket.gethostname())
    ports = {name: port() for name in ("http_port", "query_port", "rpc_port", "edit_log_port")}
    settings = {"JAVA_OPTS": '"' + java_options() + '"', "meta_dir": str(home / "meta"),
                "enable_https": "false", "ssl_force_secure_transport": "false",
                "http_worker_threads_num": "2", "mysql_service_io_threads_num": "2",
                "arrow_flight_port": "-1", "priority_networks": host + "/32", **ports}
    (home / "conf/fe.conf").write_text("\n".join(str(k) + " = " + str(v) for k, v in settings.items()) + "\n")
    env = dict(os.environ, JAVA_HOME=str(java_home))
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "LIBHDFS_OPTS", "CLASSPATH"):
        env.pop(name, None)
    with (home / "log/listener-smoke.log").open("w") as log:
        process = subprocess.Popen(["bash", str(home / "bin/start_fe.sh"), "--logconsole"],
                                   env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("Ordinary FE startup failed, see listener-smoke.log")
                try:
                    for required in ("query_port", "http_port"):
                        with socket.create_connection(("127.0.0.1", ports[required]), timeout=0.5):
                            pass
                    break
                except OSError:
                    time.sleep(0.5)
            else:
                raise RuntimeError("Ordinary FE startup timed out")
            for address in ("127.0.0.1", host):
                connection = http.client.HTTPConnection(address, ports["http_port"], timeout=10)
                connection.request("GET", "/api/health")
                response = connection.getresponse()
                assert response.status == 200
                response.read()
                connection.close()
                mysql(address, ports["query_port"], False, None, require_tls=False)
            print("Actual FE with TLS disabled passed HTTP and plaintext MySQL on both addresses")
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--home", type=Path, required=True, help="Dedicated temporary FE distribution")
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--no-tls", action="store_true", help="Check startup with TLS disabled")
    args = parser.parse_args()
    home = args.home.resolve()
    assert str(home).startswith("/tmp/"), "Use an isolated /tmp distribution"
    if any((home / "meta").iterdir()):
        raise ValueError("Smoke test requires an empty metadata directory")
    if args.no_tls:
        no_tls(home, args.java_home)
        return
    env = dict(os.environ, JAVA_HOME=str(args.java_home), SR_TEST_STORE_PASSWORD=secrets.token_hex(16))
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "LIBHDFS_OPTS", "CLASSPATH"):
        env.pop(name, None)
    host = socket.gethostbyname(socket.gethostname())
    credential = home / "conf/test-server.bcfks"
    trust = home / "conf/test-ca.pem"
    jsse_trust = home / "conf/test-ca.jks"
    credential.unlink(missing_ok=True)
    trust.unlink(missing_ok=True)
    jsse_trust.unlink(missing_ok=True)
    keytool = str(args.java_home / "bin/keytool")
    with (home / "log/keytool.log").open("w") as log:
        subprocess.run([keytool, "-genkeypair", "-alias", "server", "-dname", "CN=localhost",
                        "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA", "-validity", "2",
                        "-ext", f"SAN=dns:localhost,ip:127.0.0.1,ip:{host}", "-ext", "EKU=serverAuth",
                        "-keystore", str(credential), "-storetype", "BCFKS", "-storepass:env", "SR_TEST_STORE_PASSWORD",
                        "-keypass:env", "SR_TEST_STORE_PASSWORD", "-providerclass",
                        "org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider", "-providerpath",
                        str(home / "lib/bc-fips-2.1.1.jar"), "-J-Dorg.bouncycastle.fips.approved_only=true",
                        "-J-Dorg.bouncycastle.native.cpu_variant=java"], env=env, stdout=log, stderr=log, check=True)
        subprocess.run([keytool, "-exportcert", "-rfc", "-alias", "server", "-keystore", str(credential),
                        "-storetype", "BCFKS", "-storepass:env", "SR_TEST_STORE_PASSWORD", "-file", str(trust),
                        "-providerclass", "org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider", "-providerpath",
                        str(home / "lib/bc-fips-2.1.1.jar"), "-J-Dorg.bouncycastle.fips.approved_only=true",
                        "-J-Dorg.bouncycastle.native.cpu_variant=java"], env=env, stdout=log, stderr=log, check=True)
        subprocess.run([keytool, "-importcert", "-noprompt", "-alias", "server", "-file", str(trust),
                        "-keystore", str(jsse_trust), "-storetype", "JKS", "-storepass:env", "SR_TEST_STORE_PASSWORD"],
                       env=env, stdout=log, stderr=log, check=True)
    ports = {name: port() for name in ("http_port", "https_port", "query_port", "rpc_port", "edit_log_port")}
    settings = {
        "JAVA_OPTS": f'"{java_options()} -Djavax.net.ssl.trustStore={jsse_trust} '
                     f'-Djavax.net.ssl.trustStorePassword={env["SR_TEST_STORE_PASSWORD"]}"',
        "meta_dir": str(home / "meta"), "enable_https": "true", "ssl_force_secure_transport": "true",
        "ssl_keystore_type": "BCFKS", "ssl_keystore_location": str(credential),
        "ssl_keystore_password": env["SR_TEST_STORE_PASSWORD"], "ssl_key_password": env["SR_TEST_STORE_PASSWORD"],
        "ssl_truststore_type": "PEM", "ssl_truststore_location": str(trust),
        "http_worker_threads_num": "2", "mysql_service_io_threads_num": "2", "arrow_flight_port": "-1",
        "priority_networks": host + "/32", **ports,
    }
    configuration = home / "conf/fe.conf"
    configuration.write_text("\n".join(str(k) + " = " + str(v) for k, v in settings.items()) + "\n")
    configuration.chmod(0o600)
    context = ssl.create_default_context(cafile=str(trust))
    client_classes = home / "client-test-classes"
    client_classes.mkdir(exist_ok=True)
    subprocess.run([str(args.java_home / "bin/javac"), "--release", "17", "-proc:none", "-cp", str(home / "lib/*"),
                    "-d", str(client_classes), str(Path(__file__).with_name("FrontendHttpStartupTest.java"))], check=True)
    log = (home / "log/listener-smoke.log").open("w")
    process = subprocess.Popen(["bash", str(home / "bin/start_fe.sh"), "--logconsole"],
                               env=env, stdout=log, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("FE startup failed, see " + str(home / "log/listener-smoke.log"))
            try:
                for required in ("query_port", "https_port"):
                    with socket.create_connection(("127.0.0.1", ports[required]), timeout=0.5):
                        pass
                break
            except OSError:
                time.sleep(0.5)
        else:
            raise RuntimeError("FE startup timed out")
        for address in ("127.0.0.1", host):
            https = http.client.HTTPSConnection(address, ports["https_port"], context=context, timeout=10)
            https.request("GET", "/api/health")
            response = https.getresponse()
            assert response.status == 200, (response.status, response.read())
            response.read()
            https.close()
            connection = http.client.HTTPConnection(address, ports["http_port"], timeout=10)
            connection.request("GET", "/api/health")
            response = connection.getresponse()
            assert response.status == 200
            response.read()
            connection.close()
            plaintext = http.client.HTTPConnection(address, ports["https_port"], timeout=3)
            try:
                plaintext.request("GET", "/api/health")
                plaintext.getresponse()
                raise AssertionError("HTTPS port accepted plaintext HTTP")
            except (ConnectionError, http.client.RemoteDisconnected):
                pass
            finally:
                plaintext.close()
            mysql(address, ports["query_port"], False, context, require_tls=not address.startswith("127."))
            mysql(address, ports["query_port"], True, context)
            client_command = [str(args.java_home / "bin/java"),
                              "-Dorg.bouncycastle.fips.approved_only=true",
                              "-Dorg.bouncycastle.native.cpu_variant=java", "-Dorg.bouncycastle.jca.enable_jks=true",
                              "-Djava.security.properties=" + str(home / "conf/java-fips.security"),
                              "-Djavax.net.ssl.trustStoreType=JKS", "-Djavax.net.ssl.trustStoreProvider=BCFIPS"]
            main_class = ["-cp", str(client_classes) + ":" + str(home / "lib/*"), "FrontendHttpStartupTest"]
            with (home / "log/frontend-client-smoke.log").open("a") as client_log:
                subprocess.run(client_command + ["-Djavax.net.ssl.trustStore=" + str(jsse_trust),
                                                "-Djavax.net.ssl.trustStorePassword=" + env["SR_TEST_STORE_PASSWORD"]]
                               + main_class + [f"https://{address}:{ports['https_port']}/api/health"],
                               env=env, stdout=client_log, stderr=client_log, check=True, timeout=30)
        with (home / "log/frontend-client-smoke.log").open("a") as client_log:
            for label, address, store, password in (
                ("wrong hostname", "127.0.0.2", jsse_trust, env["SR_TEST_STORE_PASSWORD"]),
                ("untrusted peer", "127.0.0.1", args.java_home / "lib/security/jssecacerts", "changeit"),
            ):
                subprocess.run(client_command + ["-Djavax.net.ssl.trustStore=" + str(store),
                                                "-Djavax.net.ssl.trustStorePassword=" + password]
                               + main_class + [f"https://{address}:{ports['https_port']}/api/health", "reject"],
                               env=env, stdout=client_log, stderr=client_log, check=True, timeout=30)
                print("Default dependency TLS rejected " + label)
        print("Actual FE HTTP, HTTPS and MySQL TLS passed at 127.0.0.1 and " + host)
    finally:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
        log.close()


if __name__ == "__main__":
    main()
