from __future__ import annotations

from pathlib import Path
import tempfile
import unittest
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from tools.prepare_android_release_bundle import (
    BundlePreparationError,
    strip_jar_signatures,
    verify_single_jar_signature,
)


class AndroidReleaseBundlePreparationTest(unittest.TestCase):
    def test_strip_removes_only_root_signature_metadata_case_insensitively(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source = root / "source.aab"
            output = root / "unsigned.aab"
            entries = {
                "base/manifest/AndroidManifest.xml": b"manifest",
                "META-INF/manifest.mf": b"old manifest",
                "Meta-Inf/OLD.Sf": b"old sf",
                "META-INF/OLD.rSa": b"old block",
                "META-INF/SIG-CUSTOM": b"custom block",
                "META-INF/services/example": b"keep service",
                "META-INF/nested/KEEP.SF": b"keep nested",
            }
            with ZipFile(source, "w", ZIP_DEFLATED) as bundle:
                bundle.comment = b"kwabor"
                for name, value in entries.items():
                    bundle.writestr(name, value)

            removed = strip_jar_signatures(source, output)

            self.assertEqual(
                set(removed),
                {
                    "META-INF/manifest.mf",
                    "Meta-Inf/OLD.Sf",
                    "META-INF/OLD.rSa",
                    "META-INF/SIG-CUSTOM",
                },
            )
            with ZipFile(output, "r") as bundle:
                self.assertEqual(bundle.comment, b"kwabor")
                self.assertEqual(
                    set(bundle.namelist()),
                    {
                        "base/manifest/AndroidManifest.xml",
                        "META-INF/services/example",
                        "META-INF/nested/KEEP.SF",
                    },
                )

    def test_strip_rejects_same_path_and_duplicate_entries(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source = root / "source.aab"
            with ZipFile(source, "w") as bundle:
                bundle.writestr("base/a", b"a")
                duplicate = ZipInfo("BASE/A")
                bundle.writestr(duplicate, b"b")

            with self.assertRaisesRegex(BundlePreparationError, "paths must be different"):
                strip_jar_signatures(source, source)
            with self.assertRaisesRegex(BundlePreparationError, "Duplicate"):
                strip_jar_signatures(source, root / "output.aab")

    def test_verify_requires_exactly_one_signer_layout(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            signed_manifest = (
                b"Manifest-Version: 1.0\r\n"
                b"\r\n"
                b"Name: base/a\r\n"
                b"SHA-256-Digest: placeholder\r\n"
                b"\r\n"
            )
            valid = root / "valid.aab"
            with ZipFile(valid, "w") as bundle:
                bundle.writestr("base/a", b"a")
                bundle.writestr("META-INF/MANIFEST.MF", signed_manifest)
                bundle.writestr("META-INF/KWABOR.SF", b"sf")
                bundle.writestr("META-INF/KWABOR.RSA", b"rsa")
            verify_single_jar_signature(valid)

            multiple = root / "multiple.aab"
            with ZipFile(multiple, "w") as bundle:
                bundle.writestr("base/a", b"a")
                bundle.writestr("META-INF/MANIFEST.MF", signed_manifest)
                bundle.writestr("META-INF/KWABOR.SF", b"sf")
                bundle.writestr("META-INF/KWABOR.RSA", b"rsa")
                bundle.writestr("META-INF/SECOND.SF", b"sf2")
                bundle.writestr("META-INF/SECOND.EC", b"ec2")
            with self.assertRaisesRegex(BundlePreparationError, "exactly one"):
                verify_single_jar_signature(multiple)

            unsigned_extra = root / "unsigned-extra.aab"
            with ZipFile(unsigned_extra, "w") as bundle:
                bundle.writestr("base/a", b"a")
                bundle.writestr("base/not-in-manifest", b"unsigned")
                bundle.writestr("META-INF/MANIFEST.MF", signed_manifest)
                bundle.writestr("META-INF/KWABOR.SF", b"sf")
                bundle.writestr("META-INF/KWABOR.RSA", b"rsa")
            with self.assertRaisesRegex(BundlePreparationError, "cover every"):
                verify_single_jar_signature(unsigned_extra)


if __name__ == "__main__":
    unittest.main()
