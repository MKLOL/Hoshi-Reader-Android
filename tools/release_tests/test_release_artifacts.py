import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from tools import release_artifacts as artifacts


class ReleaseArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "app.apk"
        self.apk.write_bytes(b"verified APK")
        self.notes = self.root / "notes.md"
        self.notes.write_text("release notes")
        self.releases = [{"tagName": "v0.11.22", "isDraft": False, "isPrerelease": False,
                          "publishedAt": "2026-09-30T00:00:00Z"}]
        self.calls = []
        self.corrupt_upload = False
        self.uploaded = b""

    def gh(self, args):
        self.calls.append(args)
        operation = args[2]
        if operation == "list":
            return json.dumps(self.releases)
        if operation == "create":
            self.uploaded = Path(args[4]).read_bytes()
        if operation == "download":
            name = args[args.index("--pattern") + 1]
            target = Path(args[args.index("--dir") + 1]) / name
            target.write_bytes(b"wrong bytes" if self.corrupt_upload else self.uploaded or self.apk.read_bytes())
        return ""

    def identity(self, apk):
        current = apk.name == "Hoshi-Manga-v0.11.23.apk"
        return "moe.antimony.hoshi.debug", (1123 if current else 1122), ("0.11.23" if current else "0.11.22"), "same-cert"

    def publish(self):
        artifacts.publish(self.apk, "test/repo", "v0.11.23", self.notes)

    def test_verified_draft_is_downloaded_before_becoming_latest(self):
        with patch.object(artifacts, "command", side_effect=self.gh), \
                patch.object(artifacts, "apk_identity", side_effect=self.identity):
            self.publish()
        operations = [args[2] for args in self.calls]
        self.assertEqual(["list", "download", "create", "download", "edit"], operations)
        self.assertIn("--draft", self.calls[2])
        self.assertIn("--verify-tag", self.calls[2])
        self.assertIn("--draft=false", self.calls[-1])
        self.assertIn("--latest", self.calls[-1])
        self.assertFalse(any("--clobber" in args for args in self.calls))

    def test_upload_mismatch_keeps_release_draft(self):
        self.corrupt_upload = True
        with patch.object(artifacts, "command", side_effect=self.gh), \
                patch.object(artifacts, "apk_identity", side_effect=self.identity), \
                self.assertRaisesRegex(ValueError, "checksum"):
            self.publish()
        self.assertNotIn("edit", [args[2] for args in self.calls])

    def test_existing_draft_or_published_release_is_never_overwritten(self):
        for draft in (True, False):
            self.releases = [{"tagName": "v0.11.23", "isDraft": draft, "isPrerelease": False}]
            self.calls.clear()
            with patch.object(artifacts, "command", side_effect=self.gh), self.assertRaisesRegex(ValueError, "overwrite"):
                self.publish()
            self.assertEqual(["list"], [args[2] for args in self.calls])

    def test_discovery_or_previous_download_failure_prevents_publication(self):
        for failure in ("list", "download"):
            def failing_gh(args):
                if args[2] == failure:
                    raise subprocess.CalledProcessError(1, args)
                return self.gh(args)
            self.calls.clear()
            with patch.object(artifacts, "command", side_effect=failing_gh), \
                    patch.object(artifacts, "apk_identity", side_effect=self.identity), \
                    self.assertRaises(subprocess.CalledProcessError):
                self.publish()
            self.assertNotIn("create", [args[2] for args in self.calls])

    def test_changed_package_certificate_or_nonincreasing_version_is_blocking(self):
        for current in (("other.package", 1123, "0.11.23", "same-cert"),
                        ("moe.antimony.hoshi.debug", 1123, "0.11.23", "wrong-cert"),
                        ("moe.antimony.hoshi.debug", 1122, "0.11.23", "same-cert"),
                        ("moe.antimony.hoshi.debug", 1123, "0.11.22", "same-cert")):
            def identity(apk):
                return current if apk.name == "Hoshi-Manga-v0.11.23.apk" else ("moe.antimony.hoshi.debug", 1122, "0.11.22", "same-cert")
            self.calls.clear()
            with patch.object(artifacts, "command", side_effect=self.gh), \
                    patch.object(artifacts, "apk_identity", side_effect=identity), self.assertRaises(ValueError):
                self.publish()
            self.assertNotIn("create", [args[2] for args in self.calls])

    def test_first_release_still_requires_a_verified_apk(self):
        self.releases = []
        with patch.object(artifacts, "command", side_effect=self.gh), \
                patch.object(artifacts, "apk_identity", side_effect=ValueError("unsigned")), \
                self.assertRaisesRegex(ValueError, "unsigned"):
            self.publish()
        self.assertEqual(["list"], [args[2] for args in self.calls])

    def test_concurrent_build_cannot_replace_the_verified_upload(self):
        original = self.apk.read_bytes()
        verified = []
        def verify(staged, repo, tag):
            verified.append(staged.read_bytes())
            self.apk.write_bytes(b"different concurrent build")
        with patch.object(artifacts, "verify_candidate", side_effect=verify), \
                patch.object(artifacts, "command", side_effect=self.gh):
            self.publish()
        self.assertEqual([original], verified)
        self.assertEqual(original, self.uploaded)


if __name__ == "__main__":
    unittest.main()
