#!/usr/bin/env python3
"""Cut a Sui Manga Reader release.

Bumps the version, builds the debug-signed release APK, commits, tags, pushes,
and publishes a GitHub Release with the APK attached — every step that was
otherwise done by hand.

Usage:
    ./release.py            bump the patch number   (x.y.Z)
    ./release.py minor      bump the minor number   (x.Y.0)
    ./release.py major      bump the major number   (X.0.0)

Run it from the repo root with a clean working tree. Commit any code changes
first: this script only commits the version bump and the changelog.
"""

from __future__ import annotations

import datetime
import os
import re
import subprocess
import sys
import tempfile
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


def die(msg: str) -> None:
    sys.exit(f"\n  error: {msg}")


def step(msg: str) -> None:
    print(f"\n==> {msg}")


def run(cmd: list[str], env: dict | None = None) -> None:
    print(f"    $ {' '.join(cmd)}")
    subprocess.run(cmd, cwd=REPO, env=env, check=True)


def out(cmd: list[str]) -> str:
    return subprocess.run(
        cmd, cwd=REPO, check=True, capture_output=True, text=True
    ).stdout.strip()


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


def main() -> None:
    os.chdir(REPO)
    part = sys.argv[1] if len(sys.argv) > 1 else "patch"
    if part in ("-h", "--help"):
        print(__doc__)
        return
    if part not in ("patch", "minor", "major"):
        die(f"unknown argument '{part}' — use patch, minor, major, or --help")

    step("Checking preconditions")
    if not BUILD_GRADLE.exists():
        die("run this from the repo root")
    if out(["git", "status", "--porcelain"]):
        die("working tree is not clean — commit or stash your changes first")
    branch = out(["git", "rev-parse", "--abbrev-ref", "HEAD"])
    if branch == "HEAD":
        die("detached HEAD — check out a branch first")
    try:
        subprocess.run(["gh", "auth", "status"], check=True, capture_output=True)
    except (subprocess.CalledProcessError, FileNotFoundError):
        die("the GitHub CLI is missing or not logged in (run `gh auth login`)")
    ndk = resolve_ndk()
    repo = origin_repo()

    cur_code, cur_name = read_version()
    new_name = bump(cur_name, part)
    new_code = max(version_code(new_name), cur_code + 1)
    tag = f"v{new_name}"
    if out(["git", "tag", "-l", tag]):
        die(f"tag {tag} already exists")
    print(f"    {cur_name} ({cur_code})  ->  {new_name} ({new_code})   branch: {branch}")

    step(f"Bumping version and changelog to {new_name}")
    write_version(new_name, new_code)
    notes = update_changelog(new_name)
    if not notes:
        print("    note: the [Unreleased] changelog section was empty")

    step("Building the debug-signed release APK")
    env = {k: v for k, v in os.environ.items() if k not in KEYSTORE_ENV}
    env["ANDROID_NDK_HOME"] = ndk
    try:
        run(["./gradlew", ":app:assembleRelease", "--console=plain"], env=env)
    except subprocess.CalledProcessError:
        die(
            "the build failed. The version files were changed but nothing was "
            "committed — undo the bump with\n"
            "    git checkout app/build.gradle.kts docs/CHANGELOG.md"
        )
    if not RELEASE_APK.exists():
        die(f"the build finished but {RELEASE_APK} is missing")

    step("Checking the APK is signed like the previous release")
    new_cert = apk_cert(RELEASE_APK)
    with tempfile.TemporaryDirectory() as tmp:
        try:
            subprocess.run(
                ["gh", "release", "download", "--repo", repo,
                 "--pattern", "*.apk", "--dir", tmp],
                check=True, capture_output=True, text=True,
            )
        except subprocess.CalledProcessError:
            print("    note: no previous release APK to compare against — skipping")
        else:
            prev = next(Path(tmp).glob("*.apk"), None)
            prev_cert = apk_cert(prev) if prev else None
            if prev_cert and new_cert and prev_cert != new_cert:
                die(
                    "the release APK is signed with a different key than the last "
                    "release:\n"
                    f"    previous: {prev_cert}\n"
                    f"    this run: {new_cert}\n"
                    "  Installing it would not update existing installs in place. "
                    "Undo the bump with\n"
                    "    git checkout app/build.gradle.kts docs/CHANGELOG.md"
                )
            print(f"    ok — signing cert {new_cert}")

    step(f"Committing and tagging {tag}")
    run(["git", "add", "app/build.gradle.kts", "docs/CHANGELOG.md"])
    run(["git", "commit", "-m", f"chore: release {new_name}"])
    run(["git", "tag", "-a", tag, "-m", f"Release {new_name}"])

    step("Pushing to origin")
    run(["git", "push", "origin", branch])
    run(["git", "push", "origin", tag])

    step("Publishing the GitHub Release")
    notes_file = Path(tempfile.gettempdir()) / f"hoshi-notes-{tag}.md"
    notes_file.write_text((notes or f"Release {new_name}") + "\n")
    # APK asset name must match what the in-app updater expects in
    # `GitHubReleaseUpdateRepository.availableUpdateOrNull` so installed users get
    # automatic in-place upgrades. Keep this string in lockstep with the Kotlin
    # `expectedManga` constant.
    assets = [f"{RELEASE_APK}#Hoshi-Manga-{tag}.apk"]
    if (REPO / "LICENSE").exists():
        assets.append("LICENSE")
    try:
        run(["gh", "release", "create", tag, "--repo", repo, "--title", tag,
             "--notes-file", str(notes_file), *assets])
    finally:
        notes_file.unlink(missing_ok=True)

    print(f"\n✓ Released {tag}  ->  https://github.com/{repo}/releases/tag/{tag}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        die(f"command failed: {' '.join(str(c) for c in error.cmd)}")
