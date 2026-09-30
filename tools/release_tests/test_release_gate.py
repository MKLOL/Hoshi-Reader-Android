from contextlib import ExitStack
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import release
from tools import verify_release


class ReleaseEntryPointTest(unittest.TestCase):
    def test_build_output_replaced_during_push_cannot_change_verified_release(self):
        from tools import release_artifacts
        with tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
            root = Path(directory)
            gradle = root / "app/build.gradle.kts"
            gradle.parent.mkdir()
            gradle.write_text('versionCode = 1122\nversionName = "0.11.22"\n')
            changelog = root / "docs/CHANGELOG.md"
            changelog.parent.mkdir()
            changelog.write_text("## [Unreleased]\n\n### Added\n- A reviewed feature.\n")
            apk = root / "app-release.apk"
            verified, published = [], []

            def command(args, env=None):
                if args[0] == "./gradlew":
                    apk.write_bytes(b"reviewed release build")
                elif args[:2] == ["git", "push"]:
                    apk.write_bytes(b"unrelated concurrent build")
                elif "tools/release_artifacts.py" in args:
                    published.append(Path(args[args.index("--apk") + 1]).read_bytes())

            def verify(candidate, repo, tag):
                self.assertEqual("v0.12.0", tag)
                verified.append(candidate.read_bytes())

            for field, value in (("REPO", root), ("BUILD_GRADLE", gradle),
                                 ("CHANGELOG", changelog), ("RELEASE_APK", apk)):
                stack.enter_context(patch.object(release, field, value))
            stack.enter_context(patch.object(sys, "argv", ["release.py", "minor"]))
            stack.enter_context(patch.object(release.os, "chdir"))
            stack.enter_context(patch.object(release.subprocess, "run"))
            stack.enter_context(patch.object(release, "out", side_effect=lambda args, **kwargs: "source-head" if "rev-parse" in args else ""))
            stack.enter_context(patch.object(release, "resolve_ndk", return_value="/ndk"))
            stack.enter_context(patch.object(release, "origin_repo", return_value="test/repo"))
            stack.enter_context(patch.object(release, "run", side_effect=command))
            stack.enter_context(patch.object(release_artifacts, "verify_candidate", side_effect=verify))
            release.main()
            self.assertEqual([b"reviewed release build"], verified)
            self.assertEqual(verified, published)

    def test_source_or_head_changes_during_verification_are_blocking(self):
        for head, status in (("changed-head", ""), ("expected-head", " M app/src/main/Changed.kt\0"),
                             ("expected-head", "?? untracked-source.kt\0")):
            with patch.object(release, "out", side_effect=[head, status]), self.assertRaises(SystemExit):
                release.require_release_source("expected-head")

    def test_only_exact_version_edits_are_allowed_after_build(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "version").write_bytes(b"expected")
            with patch.object(release, "REPO", root), \
                    patch.object(release, "out", side_effect=["head", " M version\0"]):
                release.require_release_source("head", {"version": b"expected"})
            (root / "version").write_bytes(b"other edit")
            with patch.object(release, "REPO", root), \
                    patch.object(release, "out", side_effect=["head", " M version\0"]), \
                    self.assertRaises(SystemExit):
                release.require_release_source("head", {"version": b"expected"})

    def test_failed_verification_stops_before_version_commit_tag_build_or_publish(self):
        with ExitStack() as stack:
            stack.enter_context(patch.object(sys, "argv", ["release.py"]))
            stack.enter_context(patch.object(release.os, "chdir"))
            stack.enter_context(patch.object(release.subprocess, "run"))
            stack.enter_context(patch.object(release, "out", side_effect=lambda args: "main" if "rev-parse" in args else ""))
            stack.enter_context(patch.object(release, "read_version", return_value=(1122, "0.11.22")))
            stack.enter_context(patch.object(release, "resolve_ndk", return_value="/test-ndk"))
            stack.enter_context(patch.object(release, "origin_repo", return_value="test/repo"))
            run = stack.enter_context(patch.object(release, "run", side_effect=subprocess.CalledProcessError(1, ["gate"])))
            write = stack.enter_context(patch.object(release, "write_version"))
            changelog = stack.enter_context(patch.object(release, "update_changelog"))
            cert = stack.enter_context(patch.object(release, "apk_cert"))
            with self.assertRaises(subprocess.CalledProcessError):
                release.main()
            run.assert_called_once()
            self.assertEqual([sys.executable, "tools/verify_release.py"], run.call_args.args[0])
            write.assert_not_called()
            changelog.assert_not_called()
            cert.assert_not_called()


class GateExecutionTest(unittest.TestCase):
    def test_host_failure_cannot_start_device_tests_or_leave_success_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory)
            summary = reports / "summary.json"
            summary.write_text("stale success")
            with patch.object(verify_release, "REPORTS", reports), \
                    patch.object(verify_release, "resolve_ndk", return_value="/ndk"), \
                    patch.object(verify_release, "run", side_effect=subprocess.CalledProcessError(1, ["tests"])), \
                    patch.object(verify_release, "run_android") as device:
                with self.assertRaises(subprocess.CalledProcessError):
                    verify_release.verify()
                device.assert_not_called()
                self.assertFalse(summary.exists())

    def test_only_full_success_writes_receipt_and_external_credentials_are_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            results = root / "app/build/test-results/testDebugUnitTest"
            results.mkdir(parents=True)
            stale = results / "TEST-stale.xml"
            stale.write_text("stale")
            reports = root / "reports"
            with patch.object(verify_release, "REPO", root), \
                    patch.object(verify_release, "REPORTS", reports), \
                    patch.object(verify_release, "resolve_ndk", return_value="/ndk"), \
                    patch.dict(os.environ, {"HOSHI_KV_TOKEN": "do-not-send", "HOSHI_KV_BASE_URL": "https://real.invalid", "ANDROID_KEYSTORE_PASSWORD": "secret"}), \
                    patch.object(verify_release, "run") as run, \
                    patch.object(verify_release, "check_junit", return_value={"passed": 100, "optional_live_skipped": 6}), \
                    patch.object(verify_release, "run_android", return_value=70):
                verify_release.verify()
                self.assertFalse(stale.exists())
                self.assertTrue((reports / "summary.json").exists())
                for call in run.call_args_list:
                    self.assertNotIn("HOSHI_KV_TOKEN", call.kwargs["env"])
                    self.assertNotIn("HOSHI_KV_BASE_URL", call.kwargs["env"])
                    self.assertNotIn("ANDROID_KEYSTORE_PASSWORD", call.kwargs["env"])
                    self.assertEqual("0", call.kwargs["env"]["HOSHI_ENABLE_LIVE_SYNC_TESTS"])

    def test_device_identity_mismatch_never_installs_or_kills_an_existing_device(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "system-images/android-36/google_apis/x86_64").mkdir(parents=True)
            process = Mock()
            process.poll.return_value = None
            def adb_reply(args, **kwargs):
                output = "1\n" if "getprop" in args else "some-users-emulator\nOK\n"
                return subprocess.CompletedProcess(args, 0, output, "")
            with patch.object(verify_release, "REPORTS", root), \
                    patch.object(verify_release, "sdk_dir", return_value=root), \
                    patch.object(verify_release.platform, "machine", return_value="x86_64"), \
                    patch.object(verify_release, "free_emulator_port", return_value=5570), \
                    patch.object(verify_release, "run") as run, \
                    patch.object(verify_release.subprocess, "Popen", return_value=process), \
                    patch.object(verify_release.subprocess, "run", side_effect=adb_reply):
                with self.assertRaisesRegex(RuntimeError, "Refusing to install"):
                    verify_release.run_android({})
                self.assertEqual(1, run.call_count)  # Only create our own temporary AVD.
                self.assertIn("create", run.call_args.args[0])
                process.terminate.assert_called_once()


if __name__ == "__main__":
    unittest.main()
