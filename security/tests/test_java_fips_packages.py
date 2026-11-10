# Copyright 2021-present StarRocks, Inc. All rights reserved.
# Licensed under the Apache License, Version 2.0

import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile


spec = importlib.util.spec_from_file_location("checker", Path(__file__).parents[1] / "check-java-fips.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class PackageChecksTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def jar(self, name, entries):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as archive:
            for entry, data in entries.items():
                archive.writestr(entry, data)
        return path

    def test_reader_without_bundled_provider(self):
        self.jar("reader.jar", {"com/starrocks/Reader.class": b"reader"})
        self.assertEqual([], checker.check_packages([self.root]))

    def test_package_cli_keeps_startup_validation_separate(self):
        self.jar("reader.jar", {"com/starrocks/Reader.class": b"reader"})
        command = ["python3", checker.__file__, str(self.root)]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Java FIPS artifact checks passed", result.stdout)
        result = subprocess.run(command + ["--home", str(self.root), "--component", "fe"],
                                capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("cannot be combined", result.stderr)

    def test_ordinary_provider_in_fat_jar(self):
        self.jar("reader.jar", {"org/bouncycastle/jce/provider/BouncyCastleProvider.class": b"provider"})
        self.assertIn("Bundled or relocated BC class", "\n".join(checker.check_packages([self.root])))

    def test_vendor_classes_without_bundled_crypto_are_not_banned(self):
        self.jar("connector.jar", {"com/qcloud/cos/COSClient.class": b"connector"})
        self.assertEqual([], checker.check_packages([self.root]))

    def test_relocated_provider_in_nested_jar(self):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as archive:
            archive.writestr("vendor/shaded/org/bouncycastle/crypto/Digest.class", b"digest")
        self.jar("reader.JAR", {"lib/nested.jar": nested.getvalue()})
        errors = "\n".join(checker.check_packages([self.root]))
        self.assertIn("reader.JAR!lib/nested.jar!vendor/shaded/org/bouncycastle", errors)

    def test_provider_with_entire_package_renamed(self):
        self.jar("vendor.jar", {"vendor/crypto/BouncyCastleProvider.class": b"provider"})
        self.assertIn("Bundled or relocated BC class", "\n".join(checker.check_packages([self.root])))

    def test_stale_native_tls_binding_in_nested_jar(self):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as archive:
            archive.writestr("org/wildfly/openssl/OpenSSLProvider.class", b"native TLS provider")
        self.jar("connector.jar", {"lib/transport.jar": nested.getvalue()})
        errors = "\n".join(checker.check_packages([self.root]))
        self.assertIn("Unapproved native TLS binding", errors)
        self.assertIn("connector.jar!lib/transport.jar!org/wildfly/openssl/OpenSSLProvider.class", errors)

    def test_missing_provider(self):
        self.jar("reader.jar", {"com/starrocks/Reader.class": b"reader"})
        errors = "\n".join(checker.check_packages([self.root], self.root))
        for name in checker.PINS["jars"]:
            self.assertIn(name, errors)

    def test_altered_provider_and_duplicate_copy(self):
        name = "bc-fips-2.1.1.jar"
        self.jar(name, {"org/bouncycastle/crypto/fips/FipsStatus.class": b"altered"})
        self.jar("reader-lib/" + name, {"org/bouncycastle/crypto/fips/FipsStatus.class": b"altered"})
        errors = "\n".join(checker.check_packages([self.root], self.root))
        self.assertIn("SHA-256 mismatch", errors)
        self.assertIn("Provider outside the service provider directory", errors)
        self.assertIn("found 2 copies", errors)

    def test_corrupt_archive(self):
        (self.root / "broken.jar").write_bytes(b"not a ZIP archive")
        self.assertIn("Cannot inspect", "\n".join(checker.check_packages([self.root])))

    def test_missing_package_path(self):
        self.assertIn("Missing package path", "\n".join(checker.check_packages([self.root / "missing"])))


if __name__ == "__main__":
    unittest.main()
