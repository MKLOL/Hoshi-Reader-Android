#!/usr/bin/env python3
"""Cut a Sui Manga Reader release.

Verifies the release gate, bumps the version, builds the debug-signed release APK, commits, tags, pushes,
and publishes a GitHub Release with the APK attached — every step that was
otherwise done by hand.

Usage:
    ./release.py            bump the patch number   (x.y.Z)
    ./release.py minor      bump the minor number   (x.Y.0)
    ./release.py major      bump the major number   (X.0.0)
    ./release.py --resume   finish a release whose push or publication failed after
                            its commit, reusing the same verified APK snapshot

Run it from the repo root with a clean working tree and Python 3.11+. Commit any code
changes first: this script only commits the version bump and the changelog.
"""

from __future__ import annotations

import datetime
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent
BUILD_GRADLE = REPO / "app" / "build.gradle.kts"
CHANGELOG = REPO / "docs" / "CHANGELOG.md"
RELEASE_APK = REPO / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"

# Stripped from the build environment so the release APK always falls back to
# debug signing (see the `release` buildType in app/build.gradle.kts). Every
# published release so far is debug-signed; the signature check below guards
# against accidentally changing the key, which would break in-place updates.
KEYSTORE_ENV = (
    "ANDROID_KEYSTORE_FILE",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
)


UNDO_VERSION_EDITS = (
    "The version files were changed but nothing was committed — undo the bump with\n"
    "    git checkout HEAD -- app/build.gradle.kts docs/CHANGELOG.md"
)
RESUME_HINT = (
    "The release commit exists; do not undo it. Fix the problem above, then run\n"
    "    ./release.py --resume\n"
    "  It pushes missing refs, replaces an unpublished draft left by this script,\n"
    "  and uploads the same verified APK. Published releases are never replaced."
)
SMOKE_RECEIPT = Path("build") / "reports" / "release-gate" / "release-smoke.json"


class VersionFilesEdited(SystemExit):
    """Someone else edited the version files mid-release; undoing them would discard that work."""


def branch_push_rejected_hint(branch: str) -> str:
    return (
        f"origin/{branch} may have commits this release does not contain. Merge them, keeping the\n"
        f"  release commit (do not rebase: that rewrites it and the verified tag would no longer match):\n"
        f"    git pull --no-rebase origin {branch}\n"
        f"    ./release.py --resume\n"
        f"  {RESUME_HINT}"
    )


def die(msg: str) -> None:
    sys.exit(f"\n  error: {msg}")


def describe(error: BaseException) -> str:
    if isinstance(error, subprocess.CalledProcessError):
        return f"command failed: {' '.join(str(c) for c in error.cmd)}"
    return str(error) or type(error).__name__


def require_python() -> None:
    # hashlib.file_digest (3.11) verifies the upload only after the tag is pushed; fail first.
    if sys.version_info < (3, 11):
        die(f"Python 3.11+ is required (this is {sys.version.split()[0]}); run it with python3.11 or newer")


def step(msg: str) -> None:
    print(f"\n==> {msg}")


def run(cmd: list[str], env: dict | None = None) -> None:
    print(f"    $ {' '.join(cmd)}")
    subprocess.run(cmd, cwd=REPO, env=env, check=True)


def out(cmd: list[str], *, strip: bool = True) -> str:
    output = subprocess.run(
        cmd, cwd=REPO, check=True, capture_output=True, text=True
    ).stdout
    return output.strip() if strip else output


def require_release_source(expected_head: str, expected_files: dict[str, bytes] | None = None) -> None:
    """A long verification/build must not silently release untested workspace edits."""
    expected_files = expected_files or {}
    if out(["git", "rev-parse", "HEAD"]) != expected_head:
        die("HEAD changed during release verification; restart from the intended commit")
    status = out(["git", "status", "--porcelain=v1", "-z", "--untracked-files=all"], strip=False)
    changed = {entry[3:] for entry in status.split("\0") if entry}
    if changed - expected_files.keys():
        die("source changed during release verification/build; commit it and rerun verification")
    if any((REPO / path).read_bytes() != content for path, content in expected_files.items()):
        raise VersionFilesEdited(
            "\n  error: app/build.gradle.kts or docs/CHANGELOG.md changed during the build, after this\n"
            "  script bumped them. Nothing was committed, and those edits are yours: review them with\n"
            "  `git diff`, restore the pre-release version by hand, and rerun ./release.py.")


def sdk_dir() -> Path:
    props = REPO / "local.properties"
    if props.exists():
        for line in props.read_text().splitlines():
            if line.strip().startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].strip())
    for env in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(env):
            return Path(os.environ[env])
    die("could not locate the Android SDK (set sdk.dir in local.properties)")
    raise AssertionError  # unreachable


def resolve_ndk() -> str:
    env = os.environ.get("ANDROID_NDK_HOME")
    if env and Path(env).is_dir():
        return env
    ndk_root = sdk_dir() / "ndk"
    found = sorted(p for p in ndk_root.iterdir() if p.is_dir()) if ndk_root.is_dir() else []
    if not found:
        die(f"no NDK under {ndk_root} — install one or set ANDROID_NDK_HOME")
    return str(found[-1])


def build_tool(name: str) -> str:
    root = sdk_dir() / "build-tools"
    found = sorted(p for p in root.iterdir() if p.is_dir()) if root.is_dir() else []
    for version in reversed(found):
        if (version / name).exists():
            return str(version / name)
    die(f"{name} not found under {root}")
    raise AssertionError  # unreachable


def apk_cert(apk: Path) -> str | None:
    text = out([build_tool("apksigner"), "verify", "--print-certs", str(apk)])
    match = re.search(r"SHA-256 digest:\s*([0-9a-f]+)", text)
    return match.group(1) if match else None


def origin_repo() -> str:
    url = out(["git", "remote", "get-url", "origin"])
    match = re.search(r"github\.com[:/]+([^/]+/[^/]+?)(?:\.git)?/?$", url)
    if not match:
        die(f"could not parse a GitHub repo from the origin URL: {url}")
    return match.group(1)  # type: ignore[union-attr]


def read_version() -> tuple[int, str]:
    text = BUILD_GRADLE.read_text()
    code = re.search(r"(?m)^\s*versionCode\s*=\s*(\d+)", text)
    name = re.search(r'(?m)^\s*versionName\s*=\s*"([^"]+)"', text)
    if not code or not name:
        die("could not find versionCode/versionName in app/build.gradle.kts")
    return int(code.group(1)), name.group(1)  # type: ignore[union-attr]


def bump(name: str, part: str) -> str:
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", name)
    if not match:
        die(f"versionName '{name}' is not in x.y.z form")
    major, minor, patch = (int(x) for x in match.groups())  # type: ignore[union-attr]
    if part == "major":
        return f"{major + 1}.0.0"
    if part == "minor":
        return f"{major}.{minor + 1}.0"
    return f"{major}.{minor}.{patch + 1}"


def version_code(name: str) -> int:
    major, minor, patch = (int(x) for x in name.split("."))
    return major * 10000 + minor * 100 + patch


def write_version(new_name: str, new_code: int) -> None:
    text = BUILD_GRADLE.read_text()
    text, n_code = re.subn(
        r"(?m)^(\s*versionCode\s*=\s*)\d+", rf"\g<1>{new_code}", text, count=1
    )
    text, n_name = re.subn(
        r'(?m)^(\s*versionName\s*=\s*")[^"]+(")', rf"\g<1>{new_name}\g<2>", text, count=1
    )
    if n_code != 1 or n_name != 1:
        die("failed to rewrite versionCode/versionName in app/build.gradle.kts")
    BUILD_GRADLE.write_text(text)


def update_changelog(new_name: str) -> str:
    """Insert a dated heading under '## [Unreleased]' and return that version's notes."""
    lines = CHANGELOG.read_text().splitlines()
    today = datetime.date.today().isoformat()
    for i, line in enumerate(lines):
        if line.strip() == "## [Unreleased]":
            at = i + 1
            if at < len(lines) and lines[at].strip() == "":
                at += 1
            lines[at:at] = [f"## [v{new_name}] - {today}", ""]
            CHANGELOG.write_text("\n".join(lines) + "\n")
            notes: list[str] = []
            for note_line in lines[at + 2:]:
                if note_line.startswith("## ["):
                    break
                notes.append(note_line)
            return "\n".join(notes).strip()
    die("could not find '## [Unreleased]' in docs/CHANGELOG.md")
    raise AssertionError  # unreachable


def candidate_dir(tag: str) -> Path:
    """The verified APK, notes and receipt survive a failed publication so --resume reuses them."""
    return REPO / "build" / "release-candidates" / tag


def write_receipt(directory: Path, receipt: dict) -> None:
    (directory / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")


def require_github_cli() -> None:
    try:
        subprocess.run(["gh", "auth", "status"], check=True, capture_output=True)
    except (subprocess.CalledProcessError, FileNotFoundError):
        die("the GitHub CLI is missing or not logged in (run `gh auth login`)")


def publish_candidate(directory: Path, receipt: dict, repo: str, branch: str, resume: bool) -> None:
    tag = receipt["tag"]
    step("Pushing to origin")
    try:
        run(["git", "push", "origin", branch])
    except (subprocess.SubprocessError, OSError) as error:
        die(f"{describe(error)}\n  {branch_push_rejected_hint(branch)}")
    try:
        run(["git", "push", "origin", tag])

        step("Publishing the GitHub Release")
        command = [sys.executable, "tools/release_artifacts.py", "--apk", str(directory / "app-release.apk"),
                   "--repo", repo, "--tag", tag, "--notes", str(directory / "notes.md"),
                   "--expected-sha256", receipt["sha256"]]
        if resume:
            command.append("--resume")
        run(command)
    except (subprocess.SubprocessError, OSError, ValueError) as error:
        die(f"{describe(error)}\n  {RESUME_HINT}")
    shutil.rmtree(directory, ignore_errors=True)


def pending_releases() -> list[dict]:
    """Receipts of releases that were committed but never finished publishing."""
    root = REPO / "build" / "release-candidates"
    pending = []
    for receipt_file in sorted(root.glob("*/receipt.json")) if root.is_dir() else []:
        try:
            receipt = json.loads(receipt_file.read_text())
        except (OSError, ValueError):
            continue
        if receipt.get("commit"):
            pending.append(receipt)
    return pending


def contains_commit(commit: str) -> bool:
    try:
        out(["git", "merge-base", "--is-ancestor", commit, "HEAD"])
        return True
    except subprocess.CalledProcessError as error:
        if error.returncode == 1:
            return False
        raise


def resume() -> None:
    step("Checking the interrupted release")
    if not BUILD_GRADLE.exists():
        die("run this from the repo root")
    if out(["git", "status", "--porcelain"]):
        die("working tree is not clean — commit or stash your changes first")
    require_github_cli()
    repo = origin_repo()
    code, name = read_version()
    tag = f"v{name}"
    directory = candidate_dir(tag)
    receipt_file = directory / "receipt.json"
    if not receipt_file.is_file():
        if out(["git", "tag", "-l", tag]):
            die(f"{tag} is tagged, but its verified APK snapshot ({directory}) is gone — for example "
                "build/ was deleted. Resume only publishes the exact bytes that passed the gate, so it "
                f"cannot finish {tag}. Check whether GitHub already has the {tag} release; if not, "
                f"publish {tag} from its tag with the manual release workflow, or leave {tag} unreleased "
                "and cut the next patch release")
        die(f"nothing to resume: {tag} has no verified release candidate in {directory} and no tag. "
            "Start a new release with ./release.py")
    receipt = json.loads(receipt_file.read_text())
    if (receipt.get("tag"), receipt.get("version_name"), receipt.get("version_code")) != (tag, name, code):
        die(f"the saved candidate is for {receipt.get('tag')}, but app/build.gradle.kts is at {tag} ({code})")
    if receipt.get("smoke_passed") is not True:
        die("the saved candidate never passed the release APK smoke test; start a new release")
    release_commit = receipt.get("commit")
    if not release_commit:
        die(f"the saved {tag} candidate was never committed; undo the version edits and rerun ./release.py")
    branch = out(["git", "rev-parse", "--abbrev-ref", "HEAD"])
    if branch == "HEAD":
        die(f"HEAD is detached. Check out the branch that contains the {tag} release commit "
            f"{release_commit} (usually {receipt.get('branch') or 'the release branch'}) and rerun --resume, "
            "so the branch is pushed with the release")
    if receipt.get("branch") and branch != receipt["branch"]:
        die(f"{tag} was released from {receipt['branch']}, but {branch} is checked out; "
            f"check out {receipt['branch']} and rerun --resume")
    # A merge of the remote branch after a rejected push keeps the release commit reachable.
    if not contains_commit(release_commit):
        die(f"HEAD does not contain the {tag} release commit {release_commit} (a rebase or reset rewrote "
            f"it?). Check out a branch that contains it — merge rather than rebase — and rerun --resume")
    from tools.release_artifacts import digest
    candidate = directory / "app-release.apk"
    if not candidate.is_file() or digest(candidate) != receipt.get("sha256"):
        die(f"the verified APK snapshot in {directory} is missing or changed; it cannot be published")
    if out(["git", "tag", "-l", tag]):
        if out(["git", "rev-parse", f"{tag}^{{commit}}"]) != release_commit:
            die(f"tag {tag} does not point at the release commit {release_commit}")
    else:
        step(f"Tagging {tag}")
        run(["git", "tag", "-a", tag, release_commit, "-m", f"Release {name}"])
    publish_candidate(directory, receipt, repo, branch, resume=True)
    print(f"\n✓ Released {tag}  ->  https://github.com/{repo}/releases/tag/{tag}")


def main() -> None:
    os.chdir(REPO)
    part = sys.argv[1] if len(sys.argv) > 1 else "patch"
    if part in ("-h", "--help"):
        print(__doc__)
        return
    require_python()
    if part == "--resume":
        resume()
        return
    if part not in ("patch", "minor", "major"):
        die(f"unknown argument '{part}' — use patch, minor, major, --resume, or --help")

    step("Checking preconditions")
    if not BUILD_GRADLE.exists():
        die("run this from the repo root")
    if out(["git", "status", "--porcelain"]):
        die("working tree is not clean — commit or stash your changes first")
    branch = out(["git", "rev-parse", "--abbrev-ref", "HEAD"])
    source_head = out(["git", "rev-parse", "HEAD"])
    if branch == "HEAD":
        die("detached HEAD — check out a branch first")
    require_github_cli()
    ndk = resolve_ndk()
    repo = origin_repo()

    for pending in pending_releases():
        die(f"{pending.get('tag')} was committed ({pending.get('commit')}) but never finished publishing. "
            "Run ./release.py --resume to finish it. If you are abandoning it on purpose, delete "
            f"{candidate_dir(str(pending.get('tag')))} first (and its tag and draft, if any)")
    cur_code, cur_name = read_version()
    new_name = bump(cur_name, part)
    new_code = max(version_code(new_name), cur_code + 1)
    tag = f"v{new_name}"
    if out(["git", "tag", "-l", tag]):
        die(f"tag {tag} already exists")
    print(f"    {cur_name} ({cur_code})  ->  {new_name} ({new_code})   branch: {branch}")

    step("Running mandatory release verification before changing version files")
    env = {k: v for k, v in os.environ.items() if k not in KEYSTORE_ENV}
    env["ANDROID_NDK_HOME"] = ndk
    run([sys.executable, "tools/verify_release.py"], env=env)
    require_release_source(source_head)

    step(f"Bumping version and changelog to {new_name}")
    write_version(new_name, new_code)
    notes = update_changelog(new_name)
    version_files = {str(path.relative_to(REPO)): path.read_bytes() for path in (BUILD_GRADLE, CHANGELOG)}
    if not notes:
        print("    note: the [Unreleased] changelog section was empty")

    # Nothing is committed until the exact APK is built, launched, and upgrade-checked.
    # Any failure in this block leaves only the version edits, so say how to undo them.
    directory = candidate_dir(tag)
    try:
        step("Building the debug-signed release APK")
        run(["./gradlew", ":app:assembleRelease", "--console=plain"], env=env)
        if not RELEASE_APK.exists():
            die(f"the build finished but {RELEASE_APK} is missing")

        # Keep the verified build private across commit/tag/push/upload, and after a failed
        # publication so --resume uploads the same bytes. Another local build may replace
        # Gradle's output while those commands or the upload are running.
        shutil.rmtree(directory, ignore_errors=True)
        directory.mkdir(parents=True)
        candidate = directory / "app-release.apk"
        shutil.copyfile(RELEASE_APK, candidate)
        candidate.chmod(0o444)
        (directory / "notes.md").write_text((notes or f"Release {new_name}") + "\n")

        step("Launching the exact release APK on a disposable emulator")
        from tools.release_artifacts import digest, verify_candidate
        sha256 = digest(candidate)
        (REPO / SMOKE_RECEIPT).unlink(missing_ok=True)
        run([sys.executable, "tools/verify_release.py", "--smoke-apk", str(candidate)], env=env)
        smoked = json.loads((REPO / SMOKE_RECEIPT).read_text()).get("apk_sha256")
        if smoked != sha256 or digest(candidate) != sha256:
            die(f"the smoke-tested APK ({smoked}) is not the release candidate ({sha256})")

        step("Verifying upgrade compatibility before committing or pushing")
        verify_candidate(candidate, repo, tag, before_commit=True)
        require_release_source(source_head, version_files)
        receipt = {"tag": tag, "version_name": new_name, "version_code": new_code, "sha256": sha256,
                   "smoke_passed": True, "source_head": source_head, "branch": branch, "commit": None}
        write_receipt(directory, receipt)

        step(f"Committing {tag}")
        run(["git", "add", "app/build.gradle.kts", "docs/CHANGELOG.md"])
        run(["git", "commit", "-m", f"chore: release {new_name}"])
    except VersionFilesEdited:
        shutil.rmtree(directory, ignore_errors=True)
        raise
    except SystemExit as error:
        shutil.rmtree(directory, ignore_errors=True)
        sys.exit(f"{error.code}\n  {UNDO_VERSION_EDITS}")
    except (subprocess.SubprocessError, OSError, ValueError) as error:
        shutil.rmtree(directory, ignore_errors=True)
        die(f"{describe(error)}\n  {UNDO_VERSION_EDITS}")

    receipt["commit"] = out(["git", "rev-parse", "HEAD"])
    write_receipt(directory, receipt)
    step(f"Tagging {tag}")
    try:
        run(["git", "tag", "-a", tag, "-m", f"Release {new_name}"])
    except subprocess.CalledProcessError as error:
        die(f"{describe(error)}\n  {RESUME_HINT}")
    publish_candidate(directory, receipt, repo, branch, resume=False)

    print(f"\n✓ Released {tag}  ->  https://github.com/{repo}/releases/tag/{tag}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        die(f"command failed: {' '.join(str(c) for c in error.cmd)}")
