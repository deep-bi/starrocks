#!/usr/bin/env python3
"""Focused checks for RC validation, test selection, and failure propagation

Run with the test/requirements.txt environment:
    python3 -m unittest discover -s .github/scripts -p 'test_*.py'
"""

import ast
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import rc_archive
import sql_it
import check_ut_reports


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_checksum_accepts_build_host_path_and_rejects_corruption(self):
        archive = self.root / "rc.tar.gz"
        archive.write_bytes(b"release candidate")
        checksum = self.root / "rc.sha256"
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        checksum.write_text(digest + "  /opt/build/rc.tar.gz\n")
        self.assertEqual(rc_archive.verify_checksum(archive, checksum), digest)
        archive.write_bytes(b"corrupt")
        with self.assertRaisesRegex(ValueError, "mismatch"):
            rc_archive.verify_checksum(archive, checksum)

    def test_rejects_archive_path_traversal(self):
        archive = self.root / "unsafe.tar.gz"
        with tarfile.open(archive, "w:gz") as out:
            member = tarfile.TarInfo("../outside")
            member.size = 1
            out.addfile(member, io.BytesIO(b"x"))
        with self.assertRaises(tarfile.FilterError):
            rc_archive.unpack(archive, self.root / "unpacked", reserve_bytes=0)
        self.assertFalse((self.root / "outside").exists())

    def test_rejects_wrong_architecture(self):
        package = self.root / "rc"
        for component in ("fe", "be"):
            (package / component / "bin").mkdir(parents=True)
            (package / component / "bin" / f"start_{component}.sh").touch()
        (package / "be/lib").mkdir()
        header = bytearray(20)
        header[:6] = b"\x7fELF\x02\x01"
        header[18:20] = (62).to_bytes(2, "little")
        (package / "be/lib/starrocks_be").write_bytes(header)
        self.assertEqual(rc_archive.find_package(self.root, "amd64"), package)
        with self.assertRaisesRegex(ValueError, "architecture"):
            rc_archive.find_package(self.root, "arm64")

    def test_heap_tuning_preserves_package_flags(self):
        conf = self.root / "fe.conf"
        conf.write_text('JAVA_OPTS="-Xmx8192m -Xms2g -Djava.security.policy=policy --add-opens=java.base/java.lang=ALL-UNNAMED"\n')
        options = sql_it.heap_options(conf, "2g")["JAVA_OPTS"]
        self.assertIn("-Djava.security.policy=policy", options)
        self.assertIn("--add-opens=java.base/java.lang=ALL-UNNAMED", options)
        self.assertIn("--add-opens=java.base/java.nio=ALL-UNNAMED", options)
        self.assertIn("-Xmx2g", options)
        self.assertNotIn("-Xmx8192m", options)

    def test_report_rejects_empty_and_incomplete_runs(self):
        (self.root / "concurrent-cases.json").write_text(json.dumps({"selected": ["a", "b"], "filtered": {}}))
        for tests, failures in ((0, 0), (1, 0), (2, 1)):
            (self.root / "concurrent.xml").write_text(f'<testsuite tests="{tests}" failures="{failures}" errors="0"/>')
            with self.assertRaises(RuntimeError):
                sql_it.report(self.root, "concurrent")

    def test_report_requires_distinct_cases_not_just_retries(self):
        (self.root / "concurrent-cases.json").write_text(json.dumps({"selected": ["a", "b"], "filtered": {}}))
        report = self.root / "concurrent.xml"
        report.write_text('<testsuite tests="2"><testcase name="a"/><testcase name="b"/></testsuite>')
        sql_it.report(self.root, "concurrent")
        report.write_text('<testsuite tests="3"><testcase name="a"/><testcase name="a"/>'
                          '<testcase name="a"/></testsuite>')
        with self.assertRaises(RuntimeError):
            sql_it.report(self.root, "concurrent")

    def test_sql_runtime_skip_fails(self):
        (self.root / "concurrent-cases.json").write_text(json.dumps({"selected": ["a"], "filtered": {}}))
        for xml in ('<testsuite tests="1" skipped="1"><testcase name="a"/></testsuite>',
                    '<testsuite tests="1"><testcase name="a"><skipped/></testcase></testsuite>'):
            (self.root / "concurrent.xml").write_text(xml)
            with self.assertRaises(RuntimeError):
                sql_it.report(self.root, "concurrent")

    def test_sql_failure_entries_cannot_be_hidden_by_summary_counts(self):
        (self.root / "concurrent-cases.json").write_text(json.dumps({"selected": ["a"], "filtered": {}}))
        for kind in ("failure", "error"):
            (self.root / "concurrent.xml").write_text(
                f'<testsuite tests="1" failures="0" errors="0"><testcase name="a"><{kind}/></testcase></testsuite>')
            with self.assertRaises(RuntimeError):
                sql_it.report(self.root, "concurrent")

    def test_shared_data_config_uses_cn_and_keeps_original_package(self):
        package = self.root / "rc"
        original = 'JAVA_OPTS="-Djava.security.policy=policy -Xmx8g"\n'
        for component in ("fe", "be"):
            (package / component / "conf").mkdir(parents=True)
            (package / component / "conf" / f"{component}.conf").write_text(original)
        (package / "be/conf/cn.conf").write_text(original)
        for mode, backend in (("shared_nothing", "be"), ("shared_data", "cn")):
            state = self.root / mode
            sql_it.prepare(package, state, mode)
            self.assertIn("run_mode = " + mode, (state / "fe/conf/fe.conf").read_text())
            for i in range(1, 4):
                text = (state / f"{backend}{i}/conf/{backend}.conf").read_text()
                self.assertIn("-Djava.security.policy=policy", text)
                self.assertIn("-Xmx512m", text)
            if mode == "shared_data":
                self.assertIn("aws_s3_endpoint = http://s3:9000", (state / "fe/conf/fe.conf").read_text())
                self.assertEqual((state / "cn1/conf/be.conf").read_text(), original)
        self.assertEqual((package / "fe/conf/fe.conf").read_text(), original)
        self.assertEqual((package / "be/conf/cn.conf").read_text(), original)


class UTReportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.report = self.root / "report.xml"

    def check(self):
        return check_ut_reports.check_reports(str(self.root / "*.xml"), "fixture", self.root / "summary.json")

    def test_missing_empty_and_malformed_reports_fail(self):
        with self.assertRaises(RuntimeError):
            self.check()
        for xml in ("", "<invalid/>", '<testsuite tests="0"/>',
                    '<testsuite tests="2"><testcase name="a"/></testsuite>',
                    '<testsuite tests="1" failures="-1"><testcase name="a"/></testsuite>'):
            with self.subTest(xml=xml):
                self.report.write_text(xml)
                with self.assertRaises(RuntimeError):
                    self.check()

    def test_failures_in_counts_or_cases_fail(self):
        for xml in ('<testsuite tests="1" failures="1"><testcase name="a"/></testsuite>',
                    '<testsuite tests="1"><testcase name="a"><error/></testcase></testsuite>'):
            self.report.write_text(xml)
            with self.assertRaises(RuntimeError):
                self.check()

    def test_all_skipped_is_not_a_successful_run(self):
        self.report.write_text('<testsuite tests="1"><testcase name="a"><skipped/></testcase></testsuite>')
        with self.assertRaises(RuntimeError):
            self.check()

    def test_gtest_disabled_and_framework_skips_are_recorded(self):
        self.report.write_text('<testsuites tests="3"><testsuite tests="3">'
                               '<testcase classname="Suite" name="passed" status="run"/>'
                               '<testcase classname="Suite" name="DISABLED_known" status="notrun" result="suppressed"/>'
                               '<testcase classname="Suite" name="conditional"><skipped message="known condition"/></testcase>'
                               '</testsuite></testsuites>')
        summary = self.check()
        self.assertEqual(summary["executed"], 1)
        self.assertEqual(summary["disabled"], ["Suite.DISABLED_known"])
        self.assertEqual(summary["skipped"], [dict(test="Suite.conditional", reason="known condition")])


class SelectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = self.root / "sr.conf"
        shutil.copyfile(sql_it.REPO / "test/conf/sr.conf", self.config)
        (self.root / "R").mkdir()
        (self.root / "R/cases").write_text(
            '-- name: core\nselect 1;\n'
            '-- name: serial @sequential\nselect 2;\n'
            '-- name: external\nselect "${external_mysql_ip}";\n'
            '-- name: data\nfunction: prepare_data("ssb", "db")\n')
        sys.path.insert(0, str(sql_it.REPO / "test"))
        self.addCleanup(sys.path.remove, str(sql_it.REPO / "test"))
        self.env = patch.dict(os.environ, {"config_path": str(self.config), "require_all": "True",
                              "exclude_components": "mysql", "exclude_datasets": "ssb", "attr": "!sequential",
                              "SQL_IT_CASE_MANIFEST": str(self.root / "cases.json")})
        self.env.start()
        self.addCleanup(self.env.stop)

    def choose(self):
        from lib.choose_cases import ChooseCase
        return ChooseCase(str(self.root), file_regex=".*", case_regex=".*")

    def test_only_explicit_dependencies_are_excluded(self):
        self.assertEqual([case.name for case in self.choose().case_list], ["core"])
        manifest = json.loads((self.root / "cases.json").read_text())
        self.assertFalse(manifest["missing_dependencies"])
        os.environ["exclude_components"] = ""
        with self.assertRaisesRegex(RuntimeError, "component mysql"):
            self.choose()

    def test_sequential_pass_and_unknown_exclusion(self):
        os.environ["attr"] = "sequential"
        self.assertEqual([case.name for case in self.choose().case_list], ["serial"])
        os.environ["exclude_components"] = "typo"
        with self.assertRaisesRegex(ValueError, "Unknown excluded"):
            self.choose()

    def test_native_and_cloud_cases_are_separated(self):
        (self.root / "R/modes").write_text('-- name: native_only @native\nselect 1;\n'
                                         '-- name: cloud_only @cloud\nselect 2;\n')
        for excluded, included in (("cloud", "native"), ("native", "cloud")):
            os.environ["attr"] = f"!{excluded},!sequential"
            self.assertEqual({case.name for case in self.choose().case_list}, {"core", included + "_only"})


class LauncherTests(unittest.TestCase):
    def test_duplicate_case_names_generate_distinct_methods(self):
        from parameterized import parameterized, param
        from types import SimpleNamespace
        module = ast.parse((sql_it.REPO / "test/test_sql_cases.py").read_text())
        function = next(node for node in module.body if isinstance(node, ast.FunctionDef) and node.name == "name_func")
        namespace = {"parameterized": parameterized}
        exec(compile(ast.Module(body=[function], type_ignores=[]), "name_func", "exec"), namespace)
        case = param(SimpleNamespace(name="test_same_name"))
        first = namespace["name_func"](None, 0, case)
        second = namespace["name_func"](None, 1, case)
        self.assertNotEqual(first, second)
        self.assertTrue(first.startswith("test_"))

    def test_real_nose_failure_propagates_to_exit_status(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copyfile(sql_it.REPO / "test/run.py", root / "run.py")
            (root / "lib").mkdir()
            (root / "lib/__init__.py").write_text('class ColorEnum:\n    GREEN = RED = ""\n')
            (root / "lib/sr_sql_lib.py").write_text('def self_print(*args):\n    pass\n')
            for success in (True, False):
                (root / "test_sql_cases.py").write_text(f'def test_result():\n    assert {success}\n')
                # Avoid stale .pyc files when changing same-sized test fixtures
                shutil.rmtree(root / "__pycache__", ignore_errors=True)
                result = subprocess.run([sys.executable, "run.py", "-c", "1", "--skip_reruns"], cwd=root,
                                        capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 0 if success else 1, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
