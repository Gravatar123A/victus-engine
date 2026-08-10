#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Bounded NeoForge fixture runner with a tracked expected-fail merge boundary."""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import time
import zipfile
import os

ROOT = pathlib.Path(__file__).resolve().parents[2]
MODULE = ROOT / "hybrid-neoforge"
INSTALL = MODULE / "build" / "neoforge" / "install"
MERGE_REPORT = MODULE / "build" / "neoforge" / "merge" / "conflicts.json"
EXPECTED = [
    "VICTUS_FIXTURE_NEOFORGE_CLASS_PROCESSOR_DISCOVERED",
    "VICTUS_FIXTURE_NEOFORGE_CLASS_PROCESSOR",
    "VICTUS_FIXTURE_NEOFORGE_MIXIN_SERVICE",
    "VICTUS_FIXTURE_NEOFORGE_CONSTRUCT",
    "VICTUS_FIXTURE_NEOFORGE_EVENT_BUS",
    "VICTUS_FIXTURE_NEOFORGE_DEFERRED_REGISTER",
    "VICTUS_FIXTURE_NEOFORGE_COMMON_SETUP",
]


def fixture_contract(path: pathlib.Path) -> None:
    required = {
        "META-INF/neoforge.mods.toml",
        "META-INF/services/net.neoforged.neoforgespi.transformation.ClassProcessorProvider",
        "victus-fixture-neoforge.mixins.json",
        "cloud/victus/hybrid/fixtures/neoforge/FixtureNeoForgeMod.class",
        "cloud/victus/hybrid/fixtures/neoforge/FixtureClassProcessorMarker.class",
        "cloud/victus/hybrid/fixtures/neoforge/FixtureMixinMarker.class",
    }
    with zipfile.ZipFile(path) as archive:
        missing = sorted(required - set(archive.namelist()))
    if missing:
        raise RuntimeError(f"fixture contract incomplete: {missing}")


def write_report(path: pathlib.Path, report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def source_snapshot(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    entries: list[tuple[str, bytes]] = []
    if path.is_dir():
        entries = [(entry.relative_to(path).as_posix(), entry.read_bytes())
                   for entry in path.rglob("*.java") if entry.is_file()]
    elif path.is_file() and zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            entries = [(name, archive.read(name)) for name in archive.namelist() if name.endswith(".java")]
    else:
        raise RuntimeError(f"merge input missing: {path}")
    for name, content in sorted(entries):
        digest.update(name.encode("utf-8") + b"\0" + content + b"\0")
    return digest.hexdigest()


def tree_snapshot(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    entries = [(entry.relative_to(path).as_posix(), entry.read_bytes())
               for entry in path.rglob("*") if entry.is_file() and entry.name != ".gitkeep"]
    for name, content in sorted(entries):
        digest.update(name.encode("utf-8") + b"\0" + content + b"\0")
    return digest.hexdigest()


def validate_merge(merge: dict, server: pathlib.Path | None) -> str | None:
    if merge.get("unresolved"):
        return "NEOFORGE_MERGE_CONFLICTS"
    for name, record in merge.get("inputs", {}).items():
        current = tree_snapshot(pathlib.Path(record["path"])) if name == "bridge" else source_snapshot(pathlib.Path(record["path"]))
        if not isinstance(record, dict) or current != record.get("snapshotSha256"):
            return "NEOFORGE_MERGE_REPORT_STALE"
    if server is not None and merge.get("mergedServerSha256") != hashlib.sha256(server.read_bytes()).hexdigest():
        return "NEOFORGE_MERGED_SERVER_UNBOUND"
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture", type=pathlib.Path, required=True)
    parser.add_argument("--server", type=pathlib.Path)
    parser.add_argument("--report", type=pathlib.Path, required=True)
    parser.add_argument("--timeout", type=int, default=90)
    args = parser.parse_args()
    fixture_contract(args.fixture)
    base = {
        "format": 1,
        "fixture": str(args.fixture),
        "fixtureSha256": hashlib.sha256(args.fixture.read_bytes()).hexdigest(),
        "expectedMarkers": EXPECTED,
        "capability": False,
    }
    merge = json.loads(MERGE_REPORT.read_text(encoding="utf-8")) if MERGE_REPORT.is_file() else None
    if not args.server or not args.server.is_file():
        blocker = validate_merge(merge, None) if merge else "NEOFORGE_MERGED_GAME_MISSING"
        detail = {"unresolvedCount": len(merge["unresolved"]), "unresolved": merge["unresolved"]} \
            if blocker == "NEOFORGE_MERGE_CONFLICTS" else {}
        write_report(args.report, {**base, "status": "expected-fail", "blocker": blocker, **detail})
        print(f"NeoForge integration expected-fail: {blocker}")
        return 0
    if merge is None:
        write_report(args.report, {**base, "status": "expected-fail", "blocker": "NEOFORGE_MERGE_REPORT_MISSING"})
        print("NeoForge integration expected-fail: NEOFORGE_MERGE_REPORT_MISSING")
        return 0
    blocker = validate_merge(merge, args.server)
    if blocker:
        write_report(args.report, {**base, "status": "expected-fail", "blocker": blocker,
                                   "unresolvedCount": len(merge.get("unresolved", []))})
        print(f"NeoForge integration expected-fail: {blocker}")
        return 0
    args_file = INSTALL / "libraries" / "net" / "neoforged" / "neoforge" / "26.2.0.57" / (
        "win_args.txt" if sys.platform == "win32" else "unix_args.txt")
    if not args_file.is_file():
        write_report(args.report, {**base, "status": "expected-fail", "blocker": "NEOFORGE_RUNTIME_NOT_HYDRATED"})
        print("NeoForge integration expected-fail: NEOFORGE_RUNTIME_NOT_HYDRATED")
        return 0

    game = pathlib.Path(tempfile.mkdtemp(prefix="victus-neoforge-integration-"))
    (game / "mods").mkdir()
    (game / "mods" / args.fixture.name).write_bytes(args.fixture.read_bytes())
    # FML Server forwards its program arguments directly to Minecraft Main; --gameDir is a client option.
    # Use cwd for the server game directory and keep only server-recognized arguments after the args file.
    game_slot = INSTALL / "libraries" / "net" / "neoforged" / "minecraft-server-patched" / "26.2.0.57" / \
        "minecraft-server-patched-26.2.0.57.jar"
    original = game_slot.read_bytes()
    game_slot.write_bytes(args.server.read_bytes())
    java = str(pathlib.Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) \
        if os.environ.get("JAVA_HOME") else "java"
    command = [java, f"@{args_file}", "--nogui"]
    started = time.monotonic()
    try:
        try:
            process = subprocess.run(command, cwd=game, text=True, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, timeout=args.timeout, check=False)
        except subprocess.TimeoutExpired as failure:
            output = failure.stdout or ""
            if isinstance(output, bytes):
                output = output.decode(errors="replace")
            found = [marker for marker in EXPECTED if marker in output]
            status = "passed" if set(found) == set(EXPECTED) else "failed"
            write_report(args.report, {**base, "status": status, "exit": "timeout-after-marker-window",
                                       "seconds": time.monotonic() - started, "foundMarkers": found})
            return 0 if status == "passed" else 1
        found = [marker for marker in EXPECTED if marker in process.stdout]
        status = "passed" if set(found) == set(EXPECTED) else "failed"
        write_report(args.report, {**base, "status": status, "exit": process.returncode,
                                   "seconds": time.monotonic() - started, "foundMarkers": found,
                                   "logTail": process.stdout[-12000:]})
        return 0 if status == "passed" else 1
    finally:
        game_slot.write_bytes(original)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError, zipfile.BadZipFile) as failure:
        print(f"neoforge-integration: {failure}", file=sys.stderr)
        raise SystemExit(1)
