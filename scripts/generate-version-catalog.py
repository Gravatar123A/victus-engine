#!/usr/bin/env python3
"""Generate the frozen Victus version catalog.

The default path is deterministic and offline: it transforms the checked-in
versions/upstream-snapshot.json.  --refresh downloads Mojang and Paper metadata,
verifies the supported range, and replaces that snapshot before generation.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT = ROOT / "versions" / "upstream-snapshot.json"
CATALOG = ROOT / "versions" / "versions.json"
MOJANG_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
PAPER_URL = "https://fill.papermc.io/v3/projects/paper"
PAPER_BUILDS_URL = "https://fill.papermc.io/v3/projects/paper/versions/{version}/builds"
VERSION_RE = re.compile(r"^(?:1\.\d+(?:\.\d+)?|26\.\d+(?:\.\d+)?)$")
CAPABILITIES = (
    "pluginApi",
    "victusCore",
    "nativeConfig",
    "dab",
    "asyncChunkSend",
    "lagDoctor",
    "metrics",
    "parallelTicking",
    "regionizedThreading",
    "fabricBridge",
    "neoForgeBridge",
)


def fetch_json(url: str) -> Any:
    request = urllib.request.Request(url, headers={"User-Agent": "victus-version-catalog/1"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def scoped_releases(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    releases = [entry for entry in manifest["versions"] if entry["type"] == "release"]
    ids = [entry["id"] for entry in releases]
    try:
        newest = ids.index("26.2")
        oldest = ids.index("1.8")
    except ValueError as exc:
        raise RuntimeError("Mojang manifest no longer contains the 1.8..26.2 bounds") from exc
    result = releases[newest : oldest + 1]
    if len(result) != 74:
        raise RuntimeError(f"expected 74 Mojang releases in scope, found {len(result)}")
    return result


def java_major(release: dict[str, Any]) -> int:
    metadata = fetch_json(release["url"])
    value = metadata.get("javaVersion", {}).get("majorVersion")
    if value not in {8, 16, 17, 21, 25}:
        raise RuntimeError(f"unexpected Java requirement for {release['id']}: {value!r}")
    return value


def latest_build(version: str) -> dict[str, Any]:
    builds = fetch_json(PAPER_BUILDS_URL.format(version=version))
    if not builds:
        raise RuntimeError(f"Paper lists {version}, but returned no builds")
    build = max(builds, key=lambda item: item["id"])
    download = build.get("downloads", {}).get("server:default")
    if not download:
        raise RuntimeError(f"Paper build {version}-{build['id']} has no server download")
    commits = build.get("commits") or []
    return {
        "build": build["id"],
        "channel": build["channel"].lower(),
        "time": build["time"],
        "commit": commits[-1]["sha"] if commits else None,
        "download": {
            "url": download["url"],
            "sha256": download["checksums"]["sha256"],
            "size": download["size"],
        },
    }


def refresh_snapshot() -> dict[str, Any]:
    mojang = fetch_json(MOJANG_URL)
    paper_project = fetch_json(PAPER_URL)
    releases = scoped_releases(mojang)
    paper_versions = {
        version
        for family in paper_project["versions"].values()
        for version in family
        if VERSION_RE.fullmatch(version)
    }
    entries = []
    for release in releases:
        version = release["id"]
        entry = {
            "version": version,
            "releaseTime": release["releaseTime"],
            "manifestSha1": release["sha1"],
            "manifestUrl": release["url"],
            "java": java_major(release),
            "paper": latest_build(version) if version in paper_versions else None,
        }
        entries.append(entry)
        print(f"refreshed {version}", file=sys.stderr)
    snapshot = {
        "formatVersion": 1,
        "refreshedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "sources": {"mojang": MOJANG_URL, "paper": PAPER_URL},
        "releases": entries,
    }
    SNAPSHOT.write_text(json.dumps(snapshot, indent=2) + "\n", encoding="utf-8")
    return snapshot


def version_key(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def family(version: str) -> str:
    parts = version.split(".")
    return ".".join(parts[:2])


def build_generation(version: str) -> str:
    key = version_key(version)
    if key >= (1, 17):
        return "modern-paperweight"
    if key >= (1, 13):
        return "legacy-paperweight"
    return "legacy-paper-archive"


def support_tier(version: str, available: bool) -> str:
    if not available:
        return "unavailable"
    if version == "26.2":
        return "beta"
    if version_key(version) >= (1, 20, 5):
        return "planned"
    return "legacy-planned"


def capabilities(version: str) -> dict[str, bool]:
    implemented = version == "26.2"
    return {
        "pluginApi": implemented,
        "victusCore": implemented,
        "nativeConfig": implemented,
        "dab": implemented,
        "asyncChunkSend": implemented,
        "lagDoctor": implemented,
        "metrics": implemented,
        "parallelTicking": False,
        "regionizedThreading": False,
        "fabricBridge": False,
        "neoForgeBridge": False,
    }


def generate(snapshot: dict[str, Any]) -> dict[str, Any]:
    releases = snapshot["releases"]
    if len(releases) != 74:
        raise RuntimeError(f"snapshot must contain 74 releases, found {len(releases)}")
    entries = []
    for release in releases:
        version = release["version"]
        paper = release["paper"]
        available = paper is not None
        implemented = version == "26.2"
        upstream: dict[str, Any]
        if available:
            upstream = {
                "provider": "Paper",
                "project": "paper",
                "version": version,
                "build": paper["build"],
                "channel": paper["channel"],
                "buildTime": paper["time"],
                "commit": paper["commit"],
                "download": paper["download"],
            }
            if implemented:
                upstream["sourceRef"] = "75c0b485bf038c175d6f3e6efc67519cd5cd524d"
                upstream["sourceBuild"] = 62
        else:
            upstream = {"provider": "Paper", "project": "paper", "version": version}
        entry = {
            "minecraftVersion": version,
            "family": family(version),
            "mojang": {
                "releaseTime": release["releaseTime"],
                "manifestSha1": release["manifestSha1"],
                "manifestUrl": release["manifestUrl"],
            },
            "paperBaseline": available,
            "availability": "implemented" if implemented else ("planned" if available else "unavailable"),
            "unavailableReason": None if available else "Paper never shipped a build for this exact Mojang release.",
            "java": {"build": release["java"], "runtime": release["java"]},
            "buildSystem": {
                "generation": build_generation(version),
                "gradle": "9.4.1" if implemented else None,
                "paperweight": "2.0.0-beta.21" if implemented else None,
                "task": "./gradlew applyAllPatches build" if implemented else None,
                "artifactPath": "victus-server/build/libs/*.jar" if implemented else None,
            },
            "sourceBranch": "main" if implemented else None,
            "sourceImplemented": implemented,
            "supportTier": support_tier(version, available),
            "capabilities": capabilities(version),
            "tests": {
                "catalog": True,
                "patchApply": implemented,
                "unit": implemented,
                "boot": False,
                "bannerOrdering": False,
            },
            "publicationEligible": False,
            "upstream": upstream,
        }
        entries.append(entry)
    return {
        "$schema": "./versions.schema.json",
        "formatVersion": 1,
        "catalogScope": {
            "firstRelease": "1.8",
            "lastRelease": "26.2",
            "officialReleaseCount": 74,
            "paperBaselineCount": 53,
            "paperGapCount": 21,
            "implementedSourceLineCount": 1,
        },
        "frozenUpstream": {
            "refreshedAt": snapshot["refreshedAt"],
            "sources": snapshot["sources"],
            "note": "Regenerate offline from upstream-snapshot.json; use --refresh only for an explicit metadata update.",
        },
        "capabilityKeys": list(CAPABILITIES),
        "versions": entries,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--refresh", action="store_true", help="refresh the frozen upstream snapshot over HTTPS")
    parser.add_argument("--check", action="store_true", help="fail if versions.json is not generated from the snapshot")
    args = parser.parse_args()
    snapshot = refresh_snapshot() if args.refresh else json.loads(SNAPSHOT.read_text(encoding="utf-8"))
    rendered = json.dumps(generate(snapshot), indent=2) + "\n"
    if args.check:
        current = CATALOG.read_text(encoding="utf-8") if CATALOG.exists() else ""
        if current != rendered:
            print("versions/versions.json is stale; run scripts/generate-version-catalog.py", file=sys.stderr)
            return 1
        print("version catalog generation check passed")
        return 0
    CATALOG.write_text(rendered, encoding="utf-8")
    print(f"wrote {CATALOG}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
