#!/usr/bin/env python3
"""Prepare the disposable SQL IT cluster and validate its test reports"""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import sys
import time
import xml.etree.ElementTree as ET


REPO = Path(__file__).resolve().parents[2]


def properties(path, values):
    lines = path.read_text().splitlines()
    keys = re.compile(r"^\s*(" + "|".join(re.escape(key) for key in values) + r")\s*=")
    text = "\n".join(line for line in lines if not keys.match(line)) + "\n"
    text += "".join(f"{key} = {value}\n" for key, value in values.items())
    # Node trees share their immutable package files through hard links
    temporary = path.with_suffix(".it-tmp")
    temporary.write_text(text)
    temporary.replace(path)


def heap_options(path, maximum):
    values = {}
    for key in ("JAVA_OPTS", "JAVA_OPTS_FOR_JDK_11", "JAVA_OPTS_FOR_JDK_9_AND_LATER"):
        matches = re.findall(r"^\s*" + key + r"\s*=\s*(.+)$", path.read_text(), re.M)
        if not matches and key != "JAVA_OPTS":
            continue
        options = matches[-1].strip().strip('"\'') if matches else ""
        options = re.sub(r"-Xm[sx]\S+", "", options)
        # Arrow Flight SQL uses direct-buffer access on Java 17
        if "--add-opens=java.base/java.nio=" not in options:
            options += " --add-opens=java.base/java.nio=ALL-UNNAMED"
        values[key] = f'"{options} -Xms256m -Xmx{maximum}"'
    return values


def prepare(package, state, mode="shared_nothing"):
    backend = "cn" if mode == "shared_data" else "be"
    for node in ("fe", *(backend + str(i) for i in range(1, 4))):
        component = "fe" if node == "fe" else "be"
        target = state / node
        shutil.copytree(package / component, target, copy_function=os.link)
        for directory in ("log", "meta" if node == "fe" else "storage"):
            (target / directory).mkdir(exist_ok=True)
        conf = target / "conf" / f"{'fe' if node == 'fe' else backend}.conf"
        settings = heap_options(conf, "2g" if component == "fe" else "512m")
        settings.update(priority_networks="", sys_log_level="INFO")
        if component == "fe":
            settings.update(run_mode=mode, default_replication_num="3", enable_udf="true",
                            http_port="8030", rpc_port="9020", query_port="9030", edit_log_port="9010",
                            arrow_flight_port="9408", meta_dir="/opt/starrocks/fe/meta")
            if mode == "shared_data":
                settings.update(cloud_native_meta_port="6090", enable_load_volume_from_conf="true",
                                cloud_native_storage_type="S3", aws_s3_path="starrocks-sql-it",
                                aws_s3_endpoint="http://s3:9000", aws_s3_region="us-east-1",
                                aws_s3_access_key="starrocks", aws_s3_secret_key="starrocks-test-secret",
                                aws_s3_use_instance_profile="false", aws_s3_use_aws_sdk_default_behavior="false")
        else:
            settings.update(mem_limit="75%", storage_root_path="/opt/starrocks/be/storage",
                            be_port="9060", be_http_port="8040", heartbeat_service_port="9050",
                            brpc_port="8060", starlet_port="9070", arrow_flight_port="9415",
                            jit_lru_cache_size="4194304", jit_lru_object_cache_size="4194304")
        properties(conf, settings)


def connect(host):
    import pymysql
    return pymysql.connect(host=host, port=9030, user="root", password="", autocommit=True,
                           connect_timeout=5, read_timeout=30, write_timeout=30,
                           cursorclass=pymysql.cursors.DictCursor)


def wait_for_cluster(host, backends, register, mode="shared_nothing"):
    node_type = "COMPUTE NODE" if mode == "shared_data" else "BACKEND"
    show_nodes = "SHOW " + node_type + "S"
    deadline = time.monotonic() + 240
    error = "FE unavailable"
    while time.monotonic() < deadline:
        try:
            with connect(host) as connection, connection.cursor() as cursor:
                cursor.execute("SELECT 1")
                if register:
                    cursor.execute(show_nodes)
                    existing = {row.get("IP", row.get("Host")) for row in cursor.fetchall()}
                    for backend in backends:
                        if backend not in existing:
                            cursor.execute("ALTER SYSTEM ADD " + node_type + " %s", (backend + ":9050",))
                cursor.execute(show_nodes)
                rows = cursor.fetchall()
                if len(rows) != 3 or not all(str(row["Alive"]).lower() == "true" for row in rows):
                    raise RuntimeError("Waiting for all three " + node_type + "S to become alive")
                if register:
                    if mode == "shared_data":
                        cursor.execute('ALTER STORAGE VOLUME builtin_storage_volume SET '
                                       '("aws.s3.enable_path_style_access"="true", "aws.s3.enable_ssl"="false")')
                    storage = ("'storage_volume'='builtin_storage_volume'" if mode == "shared_data"
                               else "'replication_num'='3'")
                    cursor.execute("CREATE DATABASE IF NOT EXISTS sql_it_startup")
                    cursor.execute("CREATE TABLE IF NOT EXISTS sql_it_startup.probe (id INT) "
                                   "DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 "
                                   "PROPERTIES (" + storage + ")")
                    cursor.execute("INSERT INTO sql_it_startup.probe VALUES (1)")
                    cursor.execute("SELECT COUNT(*) AS n FROM sql_it_startup.probe")
                    if cursor.fetchone()["n"] < 1:
                        raise RuntimeError("Startup SQL probe returned no rows")
                    cursor.execute("DROP DATABASE sql_it_startup FORCE")
                print("FE and three " + node_type + "S are ready (" + mode + ")")
                return
        except Exception as exc:
            error = str(exc)
            time.sleep(2)
    raise RuntimeError("Cluster readiness failed: " + error)


def configure(state, host, mysql_host):
    text = (REPO / "test/conf/sr.conf").read_text()
    text = text.replace("host = 127.0.0.1", "host = " + host, 1)
    if mysql_host:
        # Disposable credentials confined to the job's Docker network
        values = {"external_mysql_ip": mysql_host, "external_mysql_port": "3306",
                  "external_mysql_user": "root", "external_mysql_password": "starrocks-it",
                  "jdbc_url": "https://repo.maven.apache.org/maven2/com/mysql/mysql-connector-j/26.10.0/"
                              "mysql-connector-j-26.10.0.jar"}
        for key, value in values.items():
            text = re.sub(r"(?m)^(\s*" + key + r"\s*=)[^\n]*", lambda m: m[1] + " " + value, text)
    (state / "sr.conf").write_text(text)


def collect(args):
    os.chdir(REPO / "test")
    sys.path.insert(0, str(REPO / "test"))
    os.environ.update(config_path=str(args.state / "sr.conf"), record_mode="false", sql_dir="None",
                      attr=("!native," if args.mode == "shared_data" else "!cloud,") +
                           ("!sequential" if args.phase == "concurrent" else "sequential"),
                      file_filter=".*", case_filter=".*", require_all="True", log_filtered="True",
                      exclude_components=args.exclude_components, exclude_datasets=args.exclude_datasets,
                      SQL_IT_CASE_MANIFEST=str(args.reports / f"{args.phase}-cases.json"))
    from lib.choose_cases import choose_cases
    cases = choose_cases(False).case_list
    if not cases:
        raise RuntimeError("No cases selected for " + args.phase)
    print(f"Selected {len(cases)} {args.phase} cases")


def report(reports, phase):
    manifest = json.loads((reports / f"{phase}-cases.json").read_text())
    root = ET.parse(reports / f"{phase}.xml").getroot()
    suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
    counts = {key: sum(int(suite.get(key, "0")) for suite in suites) for key in ("tests", "failures", "errors")}
    counts["skipped"] = sum(int(suite.get("skipped", suite.get("skip", "0"))) for suite in suites)
    cases = list(root.iter("testcase"))
    for counter, element in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
        counts[counter] = max(counts[counter], sum(case.find(element) is not None for case in cases))
    distinct = {(case.get("classname"), case.get("name")) for case in cases}
    expected = len(manifest["selected"])
    filtered = sum(len(cases) for cases in manifest["filtered"].values())
    summary = (f"{phase}: selected={expected}, distinct={len(distinct)}, executions={counts['tests']}, failures={counts['failures']}, "
               f"errors={counts['errors']}, runtime skips={counts['skipped']}, dependency exclusions={filtered}")
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
            stream.write("- " + summary + "\n")
    if (not expected or len(distinct) < expected or counts["tests"] < expected
            or counts["failures"] or counts["errors"] or counts["skipped"]):
        raise RuntimeError("SQL IT did not complete successfully; inspect the test reports")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "configure", "ready", "collect", "report"))
    parser.add_argument("--state", type=Path, default=Path(os.environ.get("SQL_IT_STATE", "/tmp/sql-it")))
    parser.add_argument("--package", type=Path)
    parser.add_argument("--host")
    parser.add_argument("--backends", nargs="*", default=[])
    parser.add_argument("--register", action="store_true")
    parser.add_argument("--mode", choices=("shared_nothing", "shared_data"),
                        default=os.environ.get("SQL_IT_MODE", "shared_nothing"))
    parser.add_argument("--mysql-host", default="")
    parser.add_argument("--phase", choices=("concurrent", "sequential"))
    parser.add_argument("--reports", type=Path, default=Path(os.environ.get("SQL_IT_REPORTS", "/tmp/sql-it-reports")))
    parser.add_argument("--exclude-components", default=os.environ.get("EXCLUDE_COMPONENTS", ""))
    parser.add_argument("--exclude-datasets", default=os.environ.get("EXCLUDE_DATASETS", ""))
    args = parser.parse_args()
    args.state = args.state.resolve()
    args.reports = args.reports.resolve()
    args.reports.mkdir(parents=True, exist_ok=True)
    if args.command == "prepare":
        prepare(args.package, args.state, args.mode)
    elif args.command == "configure":
        configure(args.state, args.host, args.mysql_host)
    elif args.command == "ready":
        wait_for_cluster(args.host, args.backends, args.register, args.mode)
    elif args.command == "collect":
        collect(args)
    else:
        report(args.reports, args.phase)


if __name__ == "__main__":
    main()
