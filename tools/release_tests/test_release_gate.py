from contextlib import ExitStack, contextmanager
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import release
from tools import verify_release


def record_smoke(root: Path, apk: Path, sha256: str | None = None) -> None:
    """What `verify_release.py --smoke-apk` leaves behind after launching `apk`."""
    from tools.release_artifacts import digest
    receipt = root / release.SMOKE_RECEIPT
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps({"apk_sha256": sha256 or digest(apk)}))


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
                elif "--smoke-apk" in args:
                    record_smoke(root, Path(args[-1]))
                elif args[:2] == ["git", "push"]:
                    apk.write_bytes(b"unrelated concurrent build")
                elif "tools/release_artifacts.py" in args:
                    published.append(Path(args[args.index("--apk") + 1]).read_bytes())

            def verify(candidate, repo, tag, **kwargs):
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


class FakeReleaseRepository:
    """A git/GitHub stand-in: release.py sees real files, fake refs, and scripted command failures."""

    def __init__(self, root: Path):
        self.root = root
        self.gradle = root / "app/build.gradle.kts"
        self.gradle.parent.mkdir(parents=True)
        self.gradle.write_text('versionCode = 1200\nversionName = "0.12.0"\n')
        self.changelog = root / "docs/CHANGELOG.md"
        self.changelog.parent.mkdir(parents=True)
        self.changelog.write_text("## [Unreleased]\n\n### Fixed\n- A reviewed fix.\n")
        self.apk = root / "app-release.apk"
        self.head = "source-head"
        self.branch = "main"
        self.reachable = {"source-head"}
        self.tags: dict[str, str] = {}
        self.commands: list[list[str]] = []
        self.published: list[tuple[bytes, bool]] = []
        self.expected: list[str] = []
        self.failures: list = []
        self.smoke_sha: str | None = None
        self.edit_changelog_during_build = False

    def out(self, args, **kwargs):
        if args[:2] == ["git", "rev-parse"]:
            if args[2] == "--abbrev-ref":
                return self.branch
            if args[2] == "HEAD":
                return self.head
            return self.tags[args[2].removesuffix("^{commit}")]
        if args[:3] == ["git", "tag", "-l"]:
            return args[3] if args[3] in self.tags else ""
        if args[:3] == ["git", "merge-base", "--is-ancestor"]:
            if args[3] not in self.reachable:
                raise subprocess.CalledProcessError(1, args)
            return ""
        return ""

    def merge_remote_branch(self):
        """`git pull --no-rebase` after a rejected push: a new HEAD that keeps the release commit."""
        self.head = "merge-commit"
        self.reachable.add("merge-commit")

    def run(self, args, env=None):
        self.commands.append(list(args))
        for matches, error in self.failures:
            if matches(args):
                raise error
        if args[0] == "./gradlew":
            self.apk.write_bytes(b"reviewed release build")
            if self.edit_changelog_during_build:
                self.changelog.write_text(self.changelog.read_text() + "- Someone else's edit.\n")
        elif "--smoke-apk" in args:
            record_smoke(self.root, Path(args[-1]), self.smoke_sha)
        elif args[:2] == ["git", "commit"]:
            self.head = "release-commit"
            self.reachable.add("release-commit")
        elif args[:2] == ["git", "tag"]:
            self.tags[args[3]] = self.head if args[4] == "-m" else args[4]
        elif args[:2] == ["git", "push"]:
            self.apk.write_bytes(b"unrelated concurrent build")
        elif "tools/release_artifacts.py" in args:
            self.published.append((Path(args[args.index("--apk") + 1]).read_bytes(), "--resume" in args))
            self.expected.append(args[args.index("--expected-sha256") + 1])

    def fail(self, matches, error=None):
        self.failures.append((matches, error or subprocess.CalledProcessError(1, ["failing"])))

    def ran(self, *prefix):
        return [command for command in self.commands if command[:len(prefix)] == list(prefix)]

    def main(self, *arguments, verify=None):
        from tools import release_artifacts
        with ExitStack() as stack:
            for field, value in (("REPO", self.root), ("BUILD_GRADLE", self.gradle),
                                 ("CHANGELOG", self.changelog), ("RELEASE_APK", self.apk)):
                stack.enter_context(patch.object(release, field, value))
            stack.enter_context(patch.object(sys, "argv", ["release.py", *arguments]))
            stack.enter_context(patch.object(release.os, "chdir"))
            stack.enter_context(patch.object(release.subprocess, "run"))
            stack.enter_context(patch.object(release, "out", side_effect=self.out))
            stack.enter_context(patch.object(release, "resolve_ndk", return_value="/ndk"))
            stack.enter_context(patch.object(release, "origin_repo", return_value="test/repo"))
            stack.enter_context(patch.object(release, "run", side_effect=self.run))
            stack.enter_context(patch.object(release_artifacts, "verify_candidate",
                                             side_effect=verify or (lambda *a, **k: None)))
            release.main()


class ReleaseRecoveryTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.repo = FakeReleaseRepository(Path(temp.name))
        self.candidate = Path(temp.name) / "build/release-candidates/v0.12.1"

    def test_python_older_than_311_stops_before_git_version_or_tag_changes(self):
        with patch.object(release.sys, "version_info", (3, 9, 6)), \
                self.assertRaisesRegex(SystemExit, "Python 3.11"):
            self.repo.main()
        self.assertEqual([], self.repo.commands)
        self.assertIn('versionName = "0.12.0"', self.repo.gradle.read_text())

    def test_exact_release_apk_is_launched_before_anything_is_committed(self):
        self.repo.main()
        smoke = self.repo.ran(sys.executable, "tools/verify_release.py", "--smoke-apk")
        self.assertEqual(1, len(smoke))
        commit_index = self.repo.commands.index(self.repo.ran("git", "commit")[0])
        self.assertLess(self.repo.commands.index(smoke[0]), commit_index)
        self.assertEqual([(b"reviewed release build", False)], self.repo.published)
        self.assertFalse(self.candidate.exists())  # Removed only after publication succeeded.

    def test_failures_after_the_version_edit_explain_how_to_undo_it_and_commit_nothing(self):
        cases = {
            "smoke": lambda repo: repo.fail(lambda args: "--smoke-apk" in args),
            "build": lambda repo: repo.fail(lambda args: args[0] == "./gradlew"),
            "commit hook": lambda repo: repo.fail(lambda args: args[:2] == ["git", "commit"]),
        }
        for label, arrange in cases.items():
            with self.subTest(label):
                self.setUp()
                arrange(self.repo)
                with self.assertRaises(SystemExit) as stopped:
                    self.repo.main()
                self.assertIn("git checkout HEAD -- app/build.gradle.kts docs/CHANGELOG.md", str(stopped.exception.code))
                self.assertEqual([], self.repo.ran("git", "tag"))
                self.assertEqual([], self.repo.ran("git", "push"))
                self.assertEqual([], self.repo.published)
                self.assertFalse(self.candidate.exists())

    def test_upgrade_incompatibility_explains_how_to_undo_the_version_edit(self):
        def incompatible(*args, **kwargs):
            raise ValueError("package or signing certificate changed")
        with self.assertRaises(SystemExit) as stopped:
            self.repo.main(verify=incompatible)
        message = str(stopped.exception.code)
        self.assertIn("signing certificate changed", message)
        self.assertIn("git checkout HEAD --", message)
        self.assertEqual([], self.repo.ran("git", "commit"))

    def test_failed_publication_keeps_the_verified_snapshot_and_resume_publishes_the_same_bytes(self):
        self.repo.fail(lambda args: "tools/release_artifacts.py" in args)
        with self.assertRaises(SystemExit) as stopped:
            self.repo.main()
        self.assertIn("./release.py --resume", str(stopped.exception.code))
        self.assertNotIn("git checkout", str(stopped.exception.code))
        receipt = json.loads((self.candidate / "receipt.json").read_text())
        self.assertEqual(("v0.12.1", "release-commit", True), (receipt["tag"], receipt["commit"], receipt["smoke_passed"]))

        self.repo.failures.clear()
        self.repo.commands.clear()
        self.repo.main("--resume")
        self.assertEqual([(b"reviewed release build", True)], self.repo.published)
        self.assertEqual([], self.repo.ran("./gradlew"))  # Nothing is rebuilt.
        self.assertEqual([], self.repo.ran("git", "tag"))  # The pushed tag is reused.
        self.assertEqual([["git", "push", "origin", "main"], ["git", "push", "origin", "v0.12.1"]],
                         self.repo.ran("git", "push"))
        self.assertFalse(self.candidate.exists())

    def test_resume_recreates_a_missing_tag_on_the_recorded_release_commit(self):
        self.repo.fail(lambda args: args[:2] == ["git", "tag"])
        with self.assertRaisesRegex(SystemExit, "--resume"):
            self.repo.main()
        self.repo.failures.clear()
        self.repo.main("--resume")
        self.assertEqual({"v0.12.1": "release-commit"}, self.repo.tags)
        self.assertEqual(1, len(self.repo.published))

    def publish_fails_once(self, matches=lambda args: "tools/release_artifacts.py" in args):
        self.repo.fail(matches)
        with self.assertRaises(SystemExit) as stopped:
            self.repo.main()
        self.repo.failures.clear()
        self.repo.commands.clear()
        self.repo.published.clear()
        return str(stopped.exception.code)

    def test_publication_is_pinned_to_the_smoke_tested_bytes(self):
        self.repo.main()
        self.assertEqual([hashlib.sha256(b"reviewed release build").hexdigest()], self.repo.expected)
        self.assertEqual(self.repo.expected[0], hashlib.sha256(self.repo.published[0][0]).hexdigest())

    def test_a_smoke_test_of_other_bytes_blocks_the_commit(self):
        self.repo.smoke_sha = "0" * 64
        with self.assertRaises(SystemExit) as stopped:
            self.repo.main()
        message = str(stopped.exception.code)
        self.assertIn("not the release candidate", message)
        self.assertIn("git checkout HEAD --", message)
        self.assertEqual([], self.repo.ran("git", "commit"))

    def test_someone_elses_version_file_edits_are_never_offered_for_undo(self):
        self.repo.edit_changelog_during_build = True
        with self.assertRaises(SystemExit) as stopped:
            self.repo.main()
        message = str(stopped.exception.code)
        self.assertIn("those edits are yours", message)
        self.assertNotIn("git checkout HEAD --", message)
        self.assertIn("Someone else's edit.", self.repo.changelog.read_text())
        self.assertEqual([], self.repo.ran("git", "commit"))
        self.assertFalse(self.candidate.exists())

    def test_a_rejected_branch_push_resumes_after_merging_the_remote_branch(self):
        message = self.publish_fails_once(lambda args: args == ["git", "push", "origin", "main"])
        self.assertIn("git pull --no-rebase origin main", message)
        self.assertIn("do not rebase", message)
        self.repo.merge_remote_branch()

        self.repo.main("--resume")

        self.assertEqual({"v0.12.1": "release-commit"}, self.repo.tags)  # The verified commit keeps the tag.
        self.assertEqual([["git", "push", "origin", "main"], ["git", "push", "origin", "v0.12.1"]],
                         self.repo.ran("git", "push"))
        self.assertEqual([(b"reviewed release build", True)], self.repo.published)

    def test_a_missing_tag_is_recreated_on_the_release_commit_not_a_later_head(self):
        self.publish_fails_once(lambda args: args[:2] == ["git", "tag"])
        self.repo.merge_remote_branch()
        self.repo.main("--resume")
        self.assertEqual({"v0.12.1": "release-commit"}, self.repo.tags)
        self.assertIn(["git", "tag", "-a", "v0.12.1", "release-commit", "-m", "Release 0.12.1"],
                      self.repo.ran("git", "tag"))

    def test_resume_refuses_a_head_that_lost_the_release_commit(self):
        self.publish_fails_once(lambda args: args == ["git", "push", "origin", "main"])
        self.repo.head = "rebased-copy"  # `git pull --rebase` rewrote the release commit.
        self.repo.reachable = {"source-head", "rebased-copy"}
        with self.assertRaisesRegex(SystemExit, "does not contain the v0.12.1 release commit"):
            self.repo.main("--resume")
        self.assertEqual([], self.repo.ran("git", "push"))
        self.assertEqual([], self.repo.published)

    def test_resume_on_a_detached_head_asks_for_the_release_branch(self):
        self.publish_fails_once()
        self.repo.branch = "HEAD"
        with self.assertRaisesRegex(SystemExit, "detached.*main"):
            self.repo.main("--resume")
        self.repo.branch = "other-branch"
        with self.assertRaisesRegex(SystemExit, "released from main"):
            self.repo.main("--resume")
        self.assertEqual([], self.repo.ran("git", "push"))
        self.assertEqual([], self.repo.published)

    def test_a_new_release_is_refused_while_a_committed_one_is_unpublished(self):
        self.publish_fails_once()
        self.repo.head = "later-work"
        with self.assertRaises(SystemExit) as stopped:
            self.repo.main()
        message = str(stopped.exception.code)
        self.assertIn("v0.12.1 was committed", message)
        self.assertIn("./release.py --resume", message)
        self.assertEqual([], self.repo.commands)  # Not even the gate ran.

    def test_resume_without_a_snapshot_explains_a_tag_that_cannot_be_finished(self):
        self.publish_fails_once(lambda args: args == ["git", "push", "origin", "main"])
        import shutil
        shutil.rmtree(self.candidate)
        with self.assertRaises(SystemExit) as stopped:
            self.repo.main("--resume")
        message = str(stopped.exception.code)
        self.assertIn("v0.12.1 is tagged", message)
        self.assertIn("snapshot", message)
        self.assertEqual([], self.repo.published)

    def test_resume_refuses_anything_but_the_recorded_commit_and_bytes(self):
        def moved_head(repo):
            repo.head = "unrelated-commit"  # Does not contain the release commit.
            repo.reachable = {"source-head", "unrelated-commit"}

        def changed_apk(repo):
            apk = self.candidate / "app-release.apk"
            apk.chmod(0o644)
            apk.write_bytes(b"different bytes")

        def moved_tag(repo):
            repo.tags["v0.12.1"] = "other-commit"

        def unsmoked(repo):
            receipt = json.loads((self.candidate / "receipt.json").read_text())
            (self.candidate / "receipt.json").write_text(json.dumps(dict(receipt, smoke_passed=False)))

        def missing(repo):
            import shutil
            shutil.rmtree(self.candidate)

        for label, tamper in (("head", moved_head), ("apk", changed_apk), ("tag", moved_tag),
                              ("smoke", unsmoked), ("missing", missing)):
            with self.subTest(label):
                self.setUp()
                self.repo.fail(lambda args: "tools/release_artifacts.py" in args)
                with self.assertRaises(SystemExit):
                    self.repo.main()
                self.repo.failures.clear()
                self.repo.commands.clear()
                self.repo.published.clear()
                tamper(self.repo)
                with self.assertRaises(SystemExit):
                    self.repo.main("--resume")
                self.assertEqual([], self.repo.ran("git", "push"))
                self.assertEqual([], self.repo.published)


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


class ReleaseApkSmokeTest(unittest.TestCase):
    PACKAGE = "moe.antimony.hoshi.debug"

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.reports = Path(temp.name)
        self.apk = self.reports / "Hoshi-Manga-v0.12.1.apk"
        self.apk.write_bytes(b"release apk")
        self.abilist = "arm64-v8a\n"
        self.pids = ["4321\n", "4321\n"]
        self.logcat = "I ActivityManager: Start proc 4321:moe.antimony.hoshi.debug/u0a190\n"
        self.logged: list[list[str]] = []
        self.emulators = 0
        # One `dumpsys window` answer per poll; the last one repeats.
        self.focus = ["Window{1 u0 moe.antimony.hoshi.debug/moe.antimony.hoshi.MainActivity}"]
        self.launch_status = "ok"

    @contextmanager
    def emulator(self, env, label=""):
        self.emulators += 1
        self.assertEqual("release-smoke", label)
        yield "adb", "emulator-5570"

    def adb_probe(self, args, **kwargs):
        if "getprop" in args:
            return subprocess.CompletedProcess(args, 0, self.abilist, "")
        if "pidof" in args:
            pid = self.pids.pop(0)
            return subprocess.CompletedProcess(args, 0 if pid.strip() else 1, pid, "")
        if args[-2:] == ["dumpsys", "window"]:
            focus = self.focus.pop(0) if len(self.focus) > 1 else self.focus[0]
            return subprocess.CompletedProcess(args, 0, f"  mTopFocusedDisplayId=0\n  mCurrentFocus={focus}\n", "")
        raise AssertionError(f"unexpected probe {args}")

    def logged_command(self, args, **kwargs):
        self.logged.append(list(args))
        if "resolve-activity" in args:
            return "priority=0 preferredOrder=0 match=0x108000\nmoe.antimony.hoshi.debug/moe.antimony.hoshi.MainActivity\n"
        if args[-3:] == ["-W", "-n", "moe.antimony.hoshi.debug/moe.antimony.hoshi.MainActivity"]:
            return f"Status: {self.launch_status}\nLaunchState: COLD\nComplete\n"
        if args[-2:] == ["logcat", "-d"]:
            return self.logcat
        return ""

    def smoke(self):
        with patch.object(verify_release, "REPORTS", self.reports), \
                patch.object(verify_release, "disposable_emulator", side_effect=self.emulator), \
                patch.object(verify_release, "apk_identity", return_value=(self.PACKAGE, 1201, "0.12.1", "cert")), \
                patch.object(verify_release, "apk_native_abis", return_value={"arm64-v8a"}), \
                patch.object(verify_release, "digest", return_value="sha"), \
                patch.object(verify_release.time, "sleep"), \
                patch.object(verify_release.subprocess, "run", side_effect=self.adb_probe), \
                patch.object(verify_release, "run", side_effect=self.logged_command):
            return verify_release.smoke_release_apk(self.apk, {})

    def test_exact_apk_is_installed_launched_and_recorded(self):
        result = self.smoke()
        self.assertEqual(1, self.emulators)
        install = [args for args in self.logged if "install" in args]
        self.assertEqual([["adb", "-s", "emulator-5570", "install", "-r", str(self.apk)]], install)
        self.assertEqual("sha", result["apk_sha256"])
        self.assertEqual("sha", json.loads((self.reports / "release-smoke.json").read_text())["apk_sha256"])

    def test_input_is_sent_while_the_app_holds_focus(self):
        self.smoke()
        keys = [args[-1] for args in self.logged if args[-2:-1] == ["keyevent"]]
        self.assertEqual(list(verify_release.SMOKE_KEYS), keys)

    def test_an_app_that_never_takes_focus_or_loses_it_after_input_fails(self):
        dialog = "Window{9 u0 Application Not Responding: moe.antimony.hoshi.debug}"
        app = "Window{1 u0 moe.antimony.hoshi.debug/moe.antimony.hoshi.MainActivity}"
        for label, focus in (("never focused", ["null"]), ("ANR after input", [app, dialog])):
            with self.subTest(label):
                self.pids = ["4321\n", "4321\n"]
                self.logged.clear()
                self.focus = focus
                with self.assertRaisesRegex(ValueError, "focus"):
                    self.smoke()
                self.assertFalse((self.reports / "release-smoke.json").exists())

    def test_a_slow_first_frame_passes_only_once_the_activity_is_focused(self):
        self.launch_status = "timeout"
        self.focus = ["null"] * (verify_release.FOCUS_SECONDS + 5) + [
            "Window{1 u0 moe.antimony.hoshi.debug/moe.antimony.hoshi.MainActivity}"]
        self.smoke()  # Within the longer bound after a launch timeout.
        self.launch_status = "timeout"
        self.pids = ["4321\n", "4321\n"]
        self.focus = ["null"]
        with self.assertRaisesRegex(ValueError, "never held focus"):
            self.smoke()

    def test_unsupported_emulator_abi_fails_before_installing(self):
        self.abilist = "x86_64,x86\n"
        with self.assertRaisesRegex(ValueError, "cannot run"):
            self.smoke()
        self.assertFalse(any("install" in args for args in self.logged))
        self.assertFalse((self.reports / "release-smoke.json").exists())

    def test_crash_or_restart_after_launch_fails_and_leaves_no_receipt(self):
        (self.reports / "release-smoke.json").write_text("stale success")
        for label, arrange in (
            ("crash", lambda: setattr(self, "logcat", "E AndroidRuntime: Process: moe.antimony.hoshi.debug, PID: 4321\n")),
            ("restart", lambda: setattr(self, "pids", ["4321\n", "5555\n"])),
            ("died", lambda: setattr(self, "pids", ["4321\n", ""])),
        ):
            with self.subTest(label):
                self.pids = ["4321\n", "4321\n"]
                self.logcat = ""
                arrange()
                with self.assertRaises(ValueError):
                    self.smoke()
                self.assertFalse((self.reports / "release-smoke.json").exists())

    def test_smoke_mode_accepts_only_an_apk_path(self):
        script = str(Path(verify_release.__file__))
        for arguments in (["--smoke-apk"], ["--smoke-apk", "a.apk", "--skip"], ["--skip-android"]):
            with self.subTest(arguments=arguments):
                completed = subprocess.run([sys.executable, script, *arguments], capture_output=True, text=True, timeout=60)
                self.assertNotEqual(0, completed.returncode)
                self.assertIn("No skip or bypass options", completed.stderr)


if __name__ == "__main__":
    unittest.main()
