import hashlib
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
        self.created_notes = ""
        self.published_asset: bytes | None = None
        self.draft_view = None
        self.api_view = None

    def operation(self, args):
        if args[1] == "api":
            return "api-delete" if "-X" in args else "api-get"
        return args[2]

    def gh(self, args):
        self.calls.append(args)
        operation = self.operation(args)
        if operation == "list":
            return json.dumps(self.releases)
        if operation == "view":
            return json.dumps(self.draft_view)
        if operation == "api-get":
            return json.dumps(self.api_view)
        if operation == "api-delete":
            self.releases = [item for item in self.releases if item["tagName"] != "v0.11.23"]
        if operation == "create":
            self.uploaded = Path(args[4]).read_bytes()
            self.created_notes = Path(args[args.index("--notes-file") + 1]).read_text()
        if operation == "download":
            name = args[args.index("--pattern") + 1]
            target = Path(args[args.index("--dir") + 1]) / name
            if name == "Hoshi-Manga-v0.11.23.apk" and not self.uploaded:
                if self.published_asset is None:
                    raise subprocess.CalledProcessError(1, args)  # No such asset.
                target.write_bytes(self.published_asset)
            else:
                target.write_bytes(b"wrong bytes" if self.corrupt_upload else self.uploaded or b"previous APK")
        return ""

    def identity(self, apk):
        current = apk.name == "Hoshi-Manga-v0.11.23.apk"
        return "moe.antimony.hoshi.debug", (1123 if current else 1122), ("0.11.23" if current else "0.11.22"), "same-cert"

    def publish(self, resume=False, expected=None):
        artifacts.publish(self.apk, "test/repo", "v0.11.23", self.notes, resume=resume, expected_sha256=expected)

    def interrupted_draft(self, api=None, **changes):
        self.releases.append({"tagName": "v0.11.23", "isDraft": True, "isPrerelease": False,
                              "publishedAt": None, "name": "Sui Manga Reader v0.11.23"})
        self.draft_view = {"databaseId": 7, "tagName": "v0.11.23", "isDraft": True,
                           "name": "Sui Manga Reader v0.11.23",
                           "body": f"release notes\n\n{artifacts.DRAFT_MARKER}\n",
                           "assets": [{"name": "Hoshi-Manga-v0.11.23.apk"}, {"name": "LICENSE"}], **changes}
        view = self.draft_view
        self.api_view = {"id": 7, "tag_name": view["tagName"], "draft": view["isDraft"], "name": view["name"],
                         "body": view["body"], "assets": view["assets"], **(api or {})}

    def published(self, asset):
        self.releases.append({"tagName": "v0.11.23", "isDraft": False, "isPrerelease": False,
                              "publishedAt": "2026-10-01T00:00:00Z", "name": "Sui Manga Reader v0.11.23"})
        self.published_asset = asset

    def test_verified_draft_is_marked_downloaded_and_then_becomes_latest(self):
        with patch.object(artifacts, "command", side_effect=self.gh), \
                patch.object(artifacts, "apk_identity", side_effect=self.identity):
            self.publish()
        operations = [self.operation(args) for args in self.calls]
        self.assertEqual(["list", "download", "create", "download", "edit"], operations)
        self.assertIn("--draft", self.calls[2])
        self.assertIn("--verify-tag", self.calls[2])
        self.assertIn("--draft=false", self.calls[-1])
        self.assertIn("--latest", self.calls[-1])
        self.assertFalse(any("--clobber" in args for args in self.calls))
        # Only a marked draft can later be recognised as this tool's and replaced by --resume.
        self.assertTrue(self.created_notes.startswith("release notes"))
        self.assertIn(artifacts.DRAFT_MARKER, self.created_notes)

    def test_upload_mismatch_keeps_release_draft(self):
        self.corrupt_upload = True
        with patch.object(artifacts, "command", side_effect=self.gh), \
                patch.object(artifacts, "apk_identity", side_effect=self.identity), \
                self.assertRaisesRegex(ValueError, "checksum.*--resume"):
            self.publish()
        self.assertNotIn("edit", [self.operation(args) for args in self.calls])

    def test_an_apk_that_is_not_the_verified_candidate_is_refused_before_any_request(self):
        with patch.object(artifacts, "command", side_effect=self.gh), \
                self.assertRaisesRegex(ValueError, "not the verified release candidate"):
            self.publish(expected="0" * 64)
        self.assertEqual([], self.calls)
        with patch.object(artifacts, "command", side_effect=self.gh), \
                patch.object(artifacts, "apk_identity", side_effect=self.identity):
            self.publish(expected=hashlib.sha256(b"verified APK").hexdigest())
        self.assertEqual(b"verified APK", self.uploaded)

    def test_resume_deletes_the_marked_draft_by_id_and_reverifies_the_upload(self):
        self.interrupted_draft(assets=[{"name": "Hoshi-Manga-v0.11.23.apk"}])  # Upload stopped halfway.
        with patch.object(artifacts, "command", side_effect=self.gh), \
                patch.object(artifacts, "apk_identity", side_effect=self.identity):
            self.publish(resume=True)
        operations = [self.operation(args) for args in self.calls]
        self.assertEqual(["list", "view", "api-get", "api-delete", "list", "download", "create", "download",
                          "edit"], operations)
        self.assertEqual(["gh", "api", "-X", "DELETE", "repos/test/repo/releases/7"], self.calls[3])
        self.assertFalse(any(args[:3] == ["gh", "release", "delete"] for args in self.calls))
        self.assertEqual(self.apk.read_bytes(), self.uploaded)
        self.assertIn("--draft=false", self.calls[-1])
        self.assertFalse(any("--clobber" in args for args in self.calls))

    def test_resume_without_a_draft_publishes_normally(self):
        with patch.object(artifacts, "command", side_effect=self.gh), \
                patch.object(artifacts, "apk_identity", side_effect=self.identity):
            self.publish(resume=True)
        self.assertEqual(["list", "list", "download", "create", "download", "edit"],
                         [self.operation(args) for args in self.calls])

    def test_resume_after_a_lost_publish_response_succeeds_when_the_public_apk_matches(self):
        self.published(b"verified APK")
        with patch.object(artifacts, "command", side_effect=self.gh):
            self.publish(resume=True, expected=hashlib.sha256(b"verified APK").hexdigest())
        operations = [self.operation(args) for args in self.calls]
        self.assertEqual(["list", "download"], operations)

    def test_resume_never_changes_a_release_published_with_other_or_missing_bytes(self):
        for label, asset in (("different", b"another build"), ("missing", None)):
            with self.subTest(label):
                self.releases = self.releases[:1]
                self.calls.clear()
                self.published(asset)
                with patch.object(artifacts, "command", side_effect=self.gh), \
                        self.assertRaisesRegex(ValueError, "already published with a different or missing APK"):
                    self.publish(resume=True)
                operations = [self.operation(args) for args in self.calls]
                self.assertEqual(["list", "download"], operations)

    def test_resume_refuses_foreign_ambiguous_or_just_published_drafts(self):
        cases = {
            "unmarked": lambda: self.interrupted_draft(body="Hand-written notes without the marker"),
            "renamed": lambda: self.interrupted_draft(name="Hand-written notes"),
            "foreign asset": lambda: self.interrupted_draft(assets=[{"name": "LICENSE"}, {"name": "extra.zip"}]),
            "published meanwhile": lambda: self.interrupted_draft(isDraft=False),
            "published before delete": lambda: self.interrupted_draft(api={"draft": False}),
            "marker removed before delete": lambda: self.interrupted_draft(api={"body": "edited"}),
            "other tag": lambda: self.interrupted_draft(tagName="v0.11.24"),
            "no id": lambda: self.interrupted_draft(databaseId=None),
            "two drafts": lambda: (self.interrupted_draft(), self.interrupted_draft()),
        }
        for label, arrange in cases.items():
            with self.subTest(label):
                self.releases = self.releases[:1]
                self.calls.clear()
                arrange()
                with patch.object(artifacts, "command", side_effect=self.gh), self.assertRaises(ValueError):
                    self.publish(resume=True)
                operations = [self.operation(args) for args in self.calls]
                self.assertNotIn("api-delete", operations)
                self.assertNotIn("create", operations)

    def test_without_resume_an_interrupted_draft_blocks_and_explains_recovery(self):
        self.interrupted_draft()
        with patch.object(artifacts, "command", side_effect=self.gh), \
                self.assertRaisesRegex(ValueError, "release.py --resume"):
            self.publish()
        self.assertEqual(["list"], [self.operation(args) for args in self.calls])

    def test_a_draft_found_before_the_release_commit_is_not_sent_to_resume(self):
        self.interrupted_draft()
        with patch.object(artifacts, "command", side_effect=self.gh), \
                self.assertRaises(ValueError) as refused:
            artifacts.verify_candidate(self.apk, "test/repo", "v0.11.23", before_commit=True)
        message = str(refused.exception)
        self.assertIn("gh release delete v0.11.23 --repo test/repo --yes", message)
        self.assertNotIn("--resume", message)

    def test_existing_draft_or_published_release_is_never_overwritten(self):
        for draft in (True, False):
            self.releases = [{"tagName": "v0.11.23", "isDraft": draft, "isPrerelease": False}]
            self.calls.clear()
            with patch.object(artifacts, "command", side_effect=self.gh), self.assertRaisesRegex(ValueError, "overwrite"):
                self.publish()
            self.assertEqual(["list"], [self.operation(args) for args in self.calls])

    def test_discovery_or_previous_download_failure_prevents_publication(self):
        for failure in ("list", "download"):
            def failing_gh(args):
                if self.operation(args) == failure:
                    raise subprocess.CalledProcessError(1, args)
                return self.gh(args)
            self.calls.clear()
            with patch.object(artifacts, "command", side_effect=failing_gh), \
                    patch.object(artifacts, "apk_identity", side_effect=self.identity), \
                    self.assertRaises(subprocess.CalledProcessError):
                self.publish()
            self.assertNotIn("create", [self.operation(args) for args in self.calls])

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
            self.assertNotIn("create", [self.operation(args) for args in self.calls])

    def test_first_release_still_requires_a_verified_apk(self):
        self.releases = []
        with patch.object(artifacts, "command", side_effect=self.gh), \
                patch.object(artifacts, "apk_identity", side_effect=ValueError("unsigned")), \
                self.assertRaisesRegex(ValueError, "unsigned"):
            self.publish()
        self.assertEqual(["list"], [self.operation(args) for args in self.calls])

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
