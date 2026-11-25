# SPDX-License-Identifier: Apache-2.0
"""Protect the existing installation while packing a separate validation APK."""
import io
import json
from pathlib import Path
import runpy
import tarfile
import tempfile
import unittest


assemble = runpy.run_path(str(Path(__file__).resolve().parents[1] / "build-install-bundle.py"))["assemble"]
FP = "a" * 64


def cert(fingerprint=FP):
    return f"Signer #1 certificate SHA-256 digest: {fingerprint}\n"


def badging(package, name, run=43):
    return f"package: name='{package}' versionCode='{1000 + run}' versionName='{name}'\n"


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.primary = self.root / "app-release.apk"
        self.validation = self.root / "app-validation.apk"
        self.primary.write_bytes(b"release APK fixture")
        self.validation.write_bytes(b"validation APK fixture")
        self.plugin = self.root / "openrayneo-dsh-0.1.10.tgz"
        with tarfile.open(self.plugin, "w:gz") as archive:
            for name, data in (
                ("package/package.json", b'{"name":"openrayneo-dsh","version":"0.1.10"}'),
                ("package/lib/index.js", b"// entry"),
                ("package/lib/harness.js", b"// harness"),
            ):
                info = tarfile.TarInfo(name)
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))
        self.kw = dict(
            apk=self.primary, plugin=self.plugin,
            cert_report=cert(),
            badging=badging("com.openrayneo.android", "1.2.0-wearable.43"),
            commit="b" * 40, run=43, fixed=False, out=self.root / "bundle",
        )
        self.validation_kw = dict(
            validation_apk=self.validation, validation_cert_report=cert(),
            validation_badging=badging("com.openrayneo.android.validation", "1.2.0-wearable.43-validation"),
        )

    def test_standard_bundle_stays_compatible(self):
        result = assemble(**self.kw)
        self.assertNotIn("validationApk", result)
        self.assertEqual(sorted(p.name for p in self.kw["out"].iterdir()),
                         ["app-release.apk", "install-manifest.json", self.plugin.name])

    def test_validation_bundle_is_isolated_and_verified(self):
        result = assemble(**self.kw, **self.validation_kw)
        self.assertEqual(result["validationApk"]["package"], "com.openrayneo.android.validation")
        self.assertTrue(result["validationApk"]["isolatedInstall"])
        self.assertEqual(result["validationApk"]["signerSha256"], [FP])
        self.assertEqual(sorted(p.name for p in self.kw["out"].iterdir()),
                         ["app-release.apk", "install-manifest.json", self.plugin.name, "openrayneo-validation.apk"])
        saved = json.loads((self.kw["out"] / "install-manifest.json").read_text())
        self.assertEqual(saved["validationApk"]["sha256"], result["validationApk"]["sha256"])

    def test_refuses_primary_package_collision(self):
        invalid = dict(self.validation_kw, validation_badging=badging(
            "com.openrayneo.android", "1.2.0-wearable.43-validation"))
        with self.assertRaisesRegex(ValueError, "validation APK identity"):
            assemble(**self.kw, **invalid)
        self.assertFalse(self.kw["out"].exists())

    def test_refuses_different_validation_signer(self):
        invalid = dict(self.validation_kw, validation_cert_report=cert("c" * 64))
        with self.assertRaisesRegex(ValueError, "signer differs"):
            assemble(**self.kw, **invalid)
        self.assertFalse(self.kw["out"].exists())

    def test_refuses_missing_validation_inputs(self):
        with self.assertRaisesRegex(ValueError, "Incomplete validation APK"):
            assemble(**self.kw, validation_apk=self.validation)
        self.assertFalse(self.kw["out"].exists())


if __name__ == "__main__":
    unittest.main()
