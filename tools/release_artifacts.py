#!/usr/bin/env python3
"""Verify upgrade compatibility and publish a verified draft without replacing published assets."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))
from release import apk_cert, build_tool


def command(args: list[str]) -> str:
    return subprocess.run(args, cwd=REPO, check=True, capture_output=True, text=True, timeout=300).stdout.strip()


def apk_identity(apk: Path) -> tuple[str, int, str, str]:
    certificate = apk_cert(apk)
    if not certificate:
        raise ValueError(f"APK has no verified signing certificate: {apk}")
    badging = command([build_tool("aapt"), "dump", "badging", str(apk)])
    metadata = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
    if not metadata:
        raise ValueError(f"Cannot read APK package and versionCode: {apk}")
    return metadata[1], int(metadata[2]), metadata[3], certificate


def digest(path: Path) -> str:
    with path.open("rb") as data:
        return hashlib.file_digest(data, "sha256").hexdigest()


def verify_upgrade(apk: Path, repo: str, tag: str, releases: list[dict]) -> None:
    current_package, current_code, current_name, current_cert = apk_identity(apk)
    if tag != f"v{current_name}":
        raise ValueError(f"APK versionName {current_name} does not match release tag {tag}")
    # A failed API request must fail verification; only a successful empty listing proves
    # there is no previous stable release. Never mistake auth/network failures for bootstrap.
    stable = [item for item in releases if not item["isDraft"] and not item["isPrerelease"] and item["tagName"] != tag]
    if not stable:
        return
    previous_tag = max(stable, key=lambda item: item["publishedAt"])["tagName"]
    asset = f"Hoshi-Manga-{previous_tag}.apk"
    with tempfile.TemporaryDirectory(prefix="hoshi-previous-apk-") as directory:
        command(["gh", "release", "download", previous_tag, "--repo", repo,
                 "--pattern", asset, "--dir", directory])
        previous = Path(directory) / asset
        package, code, _, certificate = apk_identity(previous)
        if package != current_package or certificate != current_cert:
            raise ValueError("Release APK cannot update the previous release: package or signing certificate changed")
        if current_code <= code:
            raise ValueError(f"Release versionCode {current_code} must exceed previous versionCode {code}")


def release_title(tag: str) -> str:
    return f"Sui Manga Reader {tag}"


# Hidden in the rendered notes. Only drafts carrying it were created by this tool and may be
# deleted by --resume; a hand-made or CI draft never is.
DRAFT_MARKER = "<!-- sui-release-tool -->"


def apk_asset(tag: str) -> str:
    return f"Hoshi-Manga-{tag}.apk"


def list_releases(repo: str) -> list[dict]:
    return json.loads(command(["gh", "release", "list", "--repo", repo, "--limit", "100",
                               "--json", "tagName,isDraft,isPrerelease,publishedAt,name"]))


def verify_candidate(apk: Path, repo: str, tag: str, before_commit: bool = False) -> None:
    releases = list_releases(repo)
    existing = [item for item in releases if item["tagName"] == tag]
    if any(not item["isDraft"] for item in existing):
        raise ValueError(f"Release {tag} is already published; refusing to overwrite its assets")
    if existing and before_commit:
        # No release commit exists yet, so there is nothing for --resume to finish.
        raise ValueError(f"A draft release for {tag} already exists on GitHub, but this run has not "
                         f"committed {tag}. Review it, delete it with `gh release delete {tag} --repo {repo} "
                         "--yes`, and rerun")
    if existing:
        raise ValueError(f"Release {tag} has an unpublished draft from an interrupted publish; refusing to "
                         "overwrite it. Rerun `./release.py --resume` (or this script with --resume).")
    verify_upgrade(apk, repo, tag, releases)


def published_matches(repo: str, tag: str, expected_sha256: str) -> bool:
    """True when `tag` is already public with exactly the verified APK (a lost `gh` response)."""
    with tempfile.TemporaryDirectory(prefix="hoshi-published-apk-") as directory:
        try:
            command(["gh", "release", "download", tag, "--repo", repo, "--pattern", apk_asset(tag),
                     "--dir", directory])
        except subprocess.CalledProcessError:
            return False
        published = Path(directory) / apk_asset(tag)
        return published.is_file() and digest(published) == expected_sha256


def discard_interrupted_draft(repo: str, tag: str, existing: list[dict]) -> bool:
    """Deletes only the single draft this tool left for `tag`; published releases are never touched."""
    if not existing:
        return False
    if any(not item["isDraft"] for item in existing):
        raise ValueError(f"Release {tag} is already published; refusing to overwrite its assets")
    if len(existing) != 1:
        raise ValueError(f"{len(existing)} drafts exist for {tag}; delete the extra drafts on GitHub, then resume")
    draft = json.loads(command(["gh", "release", "view", tag, "--repo", repo,
                                "--json", "databaseId,tagName,isDraft,name,assets,body"]))
    foreign = (f"The {tag} draft was not created by this tool; review and delete it on GitHub "
               f"(`gh release delete {tag} --repo {repo} --yes`), then resume")
    owned_by_tool(draft, tag, foreign)
    release_id = draft.get("databaseId")
    if not isinstance(release_id, int):
        raise ValueError(f"Cannot identify the {tag} draft; refusing to delete it")
    # Delete by id, after re-reading that id, so a release published in the meantime (or a
    # new draft reusing the tag) is never the one removed.
    current = json.loads(command(["gh", "api", f"repos/{repo}/releases/{release_id}"]))
    owned_by_tool({"tagName": current.get("tag_name"), "isDraft": current.get("draft"),
                   "name": current.get("name"), "body": current.get("body"),
                   "assets": current.get("assets", [])}, tag, foreign)
    # Deleting a release never deletes its tag; the tag stays on the verified release commit.
    command(["gh", "api", "-X", "DELETE", f"repos/{repo}/releases/{release_id}"])
    return True


def owned_by_tool(draft: dict, tag: str, foreign: str) -> None:
    assets = {asset["name"] for asset in draft.get("assets") or []}
    if draft.get("tagName") != tag or draft.get("isDraft") is not True:
        raise ValueError(f"Release {tag} is not an unpublished draft; refusing to delete it")
    if (DRAFT_MARKER not in (draft.get("body") or "") or draft.get("name") != release_title(tag)
            or not assets <= {apk_asset(tag), "LICENSE"}):
        raise ValueError(foreign)


def publish(apk: Path, repo: str, tag: str, notes: Path, prerelease: bool = False, resume: bool = False,
            expected_sha256: str | None = None) -> None:
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise ValueError("Release tag must be vMAJOR.MINOR.PATCH")
    with tempfile.TemporaryDirectory(prefix="hoshi-release-assets-") as directory:
        named_apk = Path(directory) / apk_asset(tag)
        shutil.copyfile(apk, named_apk)
        verified_sha256 = digest(named_apk)
        if expected_sha256 is not None and verified_sha256 != expected_sha256:
            raise ValueError(f"APK SHA-256 {verified_sha256} is not the verified release candidate "
                             f"{expected_sha256}; refusing to publish it")
        if resume:
            releases = [item for item in list_releases(repo) if item["tagName"] == tag]
            if any(not item["isDraft"] for item in releases):
                # A lost `gh` response after publication must not make resume fail forever.
                if published_matches(repo, tag, verified_sha256):
                    print(f"{tag} is already published with this exact APK; nothing left to do", flush=True)
                    return
                raise ValueError(f"Release {tag} is already published with a different or missing APK; "
                                 "refusing to change it. Review it on GitHub")
            if discard_interrupted_draft(repo, tag, releases):
                print(f"Removed the unpublished {tag} draft left by an interrupted publish", flush=True)
        # A concurrent build may replace app-release.apk. Verify and upload this same
        # private snapshot, never the mutable build output after checking it. This also
        # re-lists releases, so a draft that was not actually removed still blocks.
        verify_candidate(named_apk, repo, tag)
        marked_notes = Path(directory) / "notes.md"
        marked_notes.write_text(notes.read_text().rstrip("\n") + f"\n\n{DRAFT_MARKER}\n")
        create = ["gh", "release", "create", tag, str(named_apk), str(REPO / "LICENSE"),
                  "--repo", repo, "--verify-tag", "--draft", "--title", release_title(tag),
                  "--notes-file", str(marked_notes)]
        if prerelease:
            create.append("--prerelease")
        command(create)
        downloaded = Path(directory) / "downloaded"
        downloaded.mkdir()
        command(["gh", "release", "download", tag, "--repo", repo, "--pattern", named_apk.name,
                 "--dir", str(downloaded)])
        if verified_sha256 != digest(downloaded / named_apk.name):
            raise ValueError("Uploaded APK checksum differs; the release remains an unpublished draft. "
                             "Rerun `./release.py --resume` to replace it.")
        command(["gh", "release", "edit", tag, "--repo", repo, "--draft=false",
                 "--latest=false" if prerelease else "--latest"])


def main() -> None:
    if sys.version_info < (3, 11):
        sys.exit("Python 3.11+ is required (hashlib.file_digest).")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--notes", required=True, type=Path)
    parser.add_argument("--prerelease", action="store_true")
    parser.add_argument("--resume", action="store_true",
                        help="replace this tool's unpublished draft for the tag left by an interrupted publish, "
                             "or confirm a publication whose final response was lost")
    parser.add_argument("--expected-sha256",
                        help="refuse unless the APK is exactly the verified release candidate")
    args = parser.parse_args()
    publish(args.apk, args.repo, args.tag, args.notes, args.prerelease, args.resume, args.expected_sha256)


if __name__ == "__main__":
    main()
