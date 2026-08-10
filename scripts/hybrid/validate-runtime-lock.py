#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Validate or resolve the tracked hybrid runtime lock without trusting Gradle metadata."""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
LOCK = ROOT / "hybrid" / "locks" / "runtime-lock.json"
REQUIRED_PROFILES = {"fabric", "neoforge"}
REQUIRED_FIELDS = {"coordinate", "repository", "path", "license", "size", "sha256", "role"}


def load_lock() -> dict:
    with LOCK.open("r", encoding="utf-8") as stream:
        return json.load(stream)


def structural_errors(lock: dict) -> list[str]:
    errors: list[str] = []
    if lock.get("format") != 1:
        errors.append("format must be 1")
    if lock.get("minecraftVersion") != "26.2":
        errors.append("minecraftVersion must be 26.2")
    profiles = lock.get("profiles", {})
    if set(profiles) != REQUIRED_PROFILES:
        errors.append(f"profiles must be exactly {sorted(REQUIRED_PROFILES)}")
    coordinates: set[str] = set()
    for profile_name, profile in profiles.items():
        artifacts = profile.get("artifacts", [])
        if not artifacts:
            errors.append(f"{profile_name}: no artifacts")
        for index, artifact in enumerate(artifacts):
            prefix = f"{profile_name}.artifacts[{index}]"
            missing = REQUIRED_FIELDS - set(artifact)
            if missing:
                errors.append(f"{prefix}: missing {sorted(missing)}")
                continue
            coordinate = artifact["coordinate"]
            if coordinate in coordinates and coordinate != "net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7":
                errors.append(f"{prefix}: duplicate coordinate {coordinate}")
            coordinates.add(coordinate)
            if not artifact["repository"].startswith("https://"):
                errors.append(f"{prefix}: repository must use https")
            if ".." in pathlib.PurePosixPath(artifact["path"]).parts:
                errors.append(f"{prefix}: unsafe artifact path")
            if not artifact["license"] or artifact["license"] == "unknown":
                errors.append(f"{prefix}: license required")
            digest = artifact["sha256"]
            if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
                errors.append(f"{prefix}: sha256 must be 64 lowercase hex characters")
            if not isinstance(artifact["size"], int) or artifact["size"] <= 0:
                errors.append(f"{prefix}: positive size required")
    fabric = profiles.get("fabric", {})
    if fabric.get("loader") != "0.19.3" or fabric.get("fixtureApi") != "0.156.0+26.2":
        errors.append("Fabric Loader/API pins changed")
    if fabric.get("mixin") != "0.17.3+mixin.0.8.7":
        errors.append("Fabric Loader-selected Sponge Mixin pin changed")
    neo = profiles.get("neoforge", {})
    if neo.get("neoForge") != "26.2.0.57" or neo.get("fml") != "11.0.17":
        errors.append("NeoForge/FML pins changed")
    return errors


def resolve(lock: dict, destination: pathlib.Path | None = None, profile_filter: str | None = None) -> list[str]:
    errors: list[str] = []
    for profile_name, profile in lock["profiles"].items():
        if profile_filter is not None and profile_name != profile_filter:
            continue
        for artifact in profile["artifacts"]:
            url = artifact["repository"].rstrip("/") + "/" + artifact["path"]
            hasher = hashlib.sha256()
            data = bytearray()
            try:
                with urllib.request.urlopen(url, timeout=30) as response:
                    while chunk := response.read(1024 * 1024):
                        hasher.update(chunk)
                        data.extend(chunk)
            except Exception as failure:  # network validation must report all failures
                errors.append(f"{profile_name}: cannot resolve {url}: {failure}")
                continue
            if len(data) != artifact["size"]:
                errors.append(f"{artifact['coordinate']}: size {len(data)} != {artifact['size']}")
            actual = hasher.hexdigest()
            if actual != artifact["sha256"]:
                errors.append(f"{artifact['coordinate']}: sha256 {actual} != {artifact['sha256']}")
            elif destination is not None:
                output = destination / profile_name / artifact["path"]
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(data)
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--resolve", action="store_true", help="download every artifact and verify size/SHA-256")
    parser.add_argument("--output", type=pathlib.Path,
                        help="write verified artifacts below this directory (requires --resolve)")
    parser.add_argument("--profile", choices=sorted(REQUIRED_PROFILES),
                        help="resolve only this profile (requires --resolve)")
    args = parser.parse_args()
    if (args.output is not None or args.profile is not None) and not args.resolve:
        parser.error("--output/--profile require --resolve")
    lock = load_lock()
    errors = structural_errors(lock)
    if args.resolve and not errors:
        destination = args.output.resolve() if args.output is not None else None
        errors.extend(resolve(lock, destination, args.profile))
    if errors:
        for error in errors:
            print(f"runtime-lock: {error}", file=sys.stderr)
        return 1
    count = sum(len(profile["artifacts"]) for profile in lock["profiles"].values())
    mode = "resolved" if args.resolve else "structural"
    scope = f", profile={args.profile}" if args.profile else ""
    output = f", output={args.output.resolve()}" if args.output else ""
    print(f"runtime-lock: OK ({mode}{scope}{output}, {count} artifacts locked)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
