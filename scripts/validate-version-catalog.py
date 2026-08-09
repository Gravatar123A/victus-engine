#!/usr/bin/env python3
"""Dependency-free semantic validator for versions/versions.json."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "versions" / "versions.json"
SCHEMA = ROOT / "versions" / "versions.schema.json"
SNAPSHOT = ROOT / "versions" / "upstream-snapshot.json"
HEX40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
VERSION = re.compile(r"^(?:1\.\d+(?:\.\d+)?|26\.\d+(?:\.\d+)?)$")
JAVA_TIERS = {8, 16, 17, 21, 25}
CAPABILITIES = {
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
}


class Problems:
    def __init__(self) -> None:
        self.items: list[str] = []

    def require(self, condition: bool, message: str) -> None:
        if not condition:
            self.items.append(message)


def load(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"cannot read {path}: {exc}") from exc


def immutable_download(download: dict[str, Any]) -> bool:
    url = download.get("url", "")
    sha256 = download.get("sha256", "")
    parsed = urlparse(url)
    return (
        parsed.scheme == "https"
        and parsed.netloc == "fill-data.papermc.io"
        and f"/objects/{sha256}/" in parsed.path
        and not parsed.query
        and not parsed.fragment
    )


def validate_shape(catalog: dict[str, Any], problems: Problems) -> None:
    problems.require(catalog.get("$schema") == "./versions.schema.json", "catalog must reference ./versions.schema.json")
    problems.require(catalog.get("formatVersion") == 1, "formatVersion must be 1")
    entries = catalog.get("versions")
    problems.require(isinstance(entries, list), "versions must be an array")
    scope = catalog.get("catalogScope", {})
    expected = {
        "firstRelease": "1.8",
        "lastRelease": "26.2",
        "officialReleaseCount": 74,
        "paperBaselineCount": 53,
        "paperGapCount": 21,
        "implementedSourceLineCount": 1,
    }
    problems.require(scope == expected, f"catalogScope must equal {expected}")
    problems.require(set(catalog.get("capabilityKeys", [])) == CAPABILITIES, "capabilityKeys do not match validator contract")


def validate_entry(entry: dict[str, Any], index: int, problems: Problems) -> None:
    prefix = f"versions[{index}]"
    version = entry.get("minecraftVersion")
    problems.require(isinstance(version, str) and VERSION.fullmatch(version) is not None, f"{prefix}: invalid Minecraft version")
    parts = version.split(".") if isinstance(version, str) else []
    problems.require(entry.get("family") == ".".join(parts[:2]), f"{prefix}: wrong family")
    availability = entry.get("availability")
    baseline = entry.get("paperBaseline")
    implemented = entry.get("sourceImplemented")
    problems.require(availability in {"implemented", "planned", "unavailable"}, f"{prefix}: invalid availability")
    problems.require(isinstance(baseline, bool), f"{prefix}: paperBaseline must be boolean")
    problems.require(isinstance(implemented, bool), f"{prefix}: sourceImplemented must be boolean")
    if baseline:
        problems.require(entry.get("unavailableReason") is None, f"{prefix}: available baseline cannot have unavailableReason")
        problems.require(availability in {"implemented", "planned"}, f"{prefix}: baseline must be implemented or planned")
    else:
        problems.require(availability == "unavailable", f"{prefix}: Paper gap must be unavailable")
        problems.require(bool(entry.get("unavailableReason")), f"{prefix}: Paper gap needs a reason")
        problems.require(not implemented, f"{prefix}: Paper gap cannot be implemented")
    if implemented:
        problems.require(version == "26.2", f"{prefix}: only 26.2 is currently implemented")
        problems.require(availability == "implemented", f"{prefix}: implemented source must use implemented availability")
        problems.require(bool(entry.get("sourceBranch")), f"{prefix}: implemented source needs a branch")
    else:
        problems.require(entry.get("sourceBranch") is None, f"{prefix}: unimplemented sourceBranch must be null")
    java = entry.get("java", {})
    problems.require(java.get("build") in JAVA_TIERS, f"{prefix}: invalid build Java tier")
    problems.require(java.get("runtime") in JAVA_TIERS, f"{prefix}: invalid runtime Java tier")
    build = entry.get("buildSystem", {})
    problems.require(build.get("generation") in {"modern-paperweight", "legacy-paperweight", "legacy-paper-archive"}, f"{prefix}: invalid build generation")
    if implemented:
        for key in ("gradle", "paperweight", "task", "artifactPath"):
            problems.require(bool(build.get(key)), f"{prefix}: implemented line needs buildSystem.{key}")
    else:
        for key in ("gradle", "paperweight", "task", "artifactPath"):
            problems.require(build.get(key) is None, f"{prefix}: unimplemented line must not pretend buildSystem.{key} exists")
    capabilities = entry.get("capabilities", {})
    problems.require(set(capabilities) == CAPABILITIES, f"{prefix}: capability object has missing or extra keys")
    problems.require(all(isinstance(value, bool) for value in capabilities.values()), f"{prefix}: capabilities must be booleans")
    if not implemented:
        problems.require(not any(capabilities.values()), f"{prefix}: unimplemented line cannot claim Victus capabilities")
    problems.require(not capabilities.get("parallelTicking", True), f"{prefix}: parallel ticking is experimental, not supported")
    problems.require(not capabilities.get("regionizedThreading", True), f"{prefix}: regionized threading is planned, not supported")
    problems.require(not capabilities.get("fabricBridge", True), f"{prefix}: Fabric bridge is not supported")
    problems.require(not capabilities.get("neoForgeBridge", True), f"{prefix}: NeoForge bridge is not supported")
    tests = entry.get("tests", {})
    problems.require(set(tests) == {"catalog", "patchApply", "unit", "boot", "bannerOrdering"}, f"{prefix}: test flags do not match contract")
    problems.require(all(isinstance(value, bool) for value in tests.values()), f"{prefix}: test flags must be booleans")
    problems.require(entry.get("publicationEligible") is False, f"{prefix}: no current line is publication eligible")
    mojang = entry.get("mojang", {})
    problems.require(HEX40.fullmatch(mojang.get("manifestSha1", "")) is not None, f"{prefix}: invalid Mojang manifest SHA-1")
    problems.require(urlparse(mojang.get("manifestUrl", "")).scheme == "https", f"{prefix}: Mojang manifest URL must use HTTPS")
    upstream = entry.get("upstream", {})
    problems.require(upstream.get("provider") == "Paper" and upstream.get("project") == "paper", f"{prefix}: invalid upstream provider")
    problems.require(upstream.get("version") == version, f"{prefix}: upstream version mismatch")
    if baseline:
        problems.require(isinstance(upstream.get("build"), int) and upstream["build"] > 0, f"{prefix}: missing Paper build")
        problems.require(upstream.get("channel") in {"alpha", "beta", "stable"}, f"{prefix}: invalid Paper channel")
        commit = upstream.get("commit")
        problems.require(commit is None or HEX40.fullmatch(commit) is not None, f"{prefix}: invalid Paper commit")
        download = upstream.get("download", {})
        problems.require(HEX64.fullmatch(download.get("sha256", "")) is not None, f"{prefix}: invalid Paper SHA-256")
        problems.require(isinstance(download.get("size"), int) and download["size"] > 1_000_000, f"{prefix}: invalid Paper download size")
        problems.require(immutable_download(download), f"{prefix}: Paper URL is not immutable/content-addressed")
        if implemented:
            problems.require(HEX40.fullmatch(upstream.get("sourceRef", "")) is not None, f"{prefix}: implemented source needs exact upstream ref")
            problems.require(isinstance(upstream.get("sourceBuild"), int), f"{prefix}: implemented source needs source build")
    else:
        problems.require(set(upstream) == {"provider", "project", "version"}, f"{prefix}: gap must not contain fabricated upstream data")


def validate_catalog(catalog: dict[str, Any]) -> list[str]:
    problems = Problems()
    validate_shape(catalog, problems)
    entries = catalog.get("versions", [])
    if not isinstance(entries, list):
        return problems.items
    snapshot = load(SNAPSHOT)
    problems.require(snapshot.get("formatVersion") == 1, "snapshot formatVersion must be 1")
    problems.require(len(snapshot.get("releases", [])) == 74, "snapshot must contain exactly 74 releases")
    expected_versions = [release.get("version") for release in snapshot.get("releases", [])]
    actual_versions: list[Any] = []
    seen: set[str] = set()
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            problems.items.append(f"versions[{index}] must be an object")
            continue
        version = entry.get("minecraftVersion")
        actual_versions.append(version)
        problems.require(version not in seen, f"duplicate version: {version}")
        seen.add(version)
        validate_entry(entry, index, problems)
    problems.require(len(entries) == 74, f"expected 74 releases, found {len(entries)}")
    problems.require(actual_versions == expected_versions, "catalog versions must match the frozen snapshot order and scope")
    baselines = [entry for entry in entries if isinstance(entry, dict) and entry.get("paperBaseline")]
    gaps = [entry for entry in entries if isinstance(entry, dict) and not entry.get("paperBaseline")]
    implemented = [entry for entry in entries if isinstance(entry, dict) and entry.get("sourceImplemented")]
    problems.require(len(baselines) == 53, f"expected 53 Paper baselines, found {len(baselines)}")
    problems.require(len(gaps) == 21, f"expected 21 Paper gaps, found {len(gaps)}")
    problems.require([entry.get("minecraftVersion") for entry in implemented] == ["26.2"], "26.2 must be the only implemented source line")
    return problems.items


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()
    for required in (SCHEMA, SNAPSHOT):
        if not required.exists():
            print(f"missing required catalog input: {required}", file=sys.stderr)
            return 1
    catalog = load(CATALOG)
    problems = validate_catalog(catalog)
    if problems:
        for problem in problems:
            print(f"ERROR: {problem}", file=sys.stderr)
        print(f"version catalog validation failed with {len(problems)} error(s)", file=sys.stderr)
        return 1
    if not args.quiet:
        digest = hashlib.sha256(CATALOG.read_bytes()).hexdigest()
        print(f"version catalog valid: 74 Mojang releases, 53 Paper baselines, 21 exact gaps, sha256={digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
