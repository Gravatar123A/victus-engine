#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Locked NeoForge 26.2 patched-game reconstruction, source merge, and distribution assembly."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
MODULE = ROOT / "hybrid-neoforge"
LOCK_PATH = MODULE / "locks" / "neoforge-26.2.0.57-lock.json"
BUILD = MODULE / "build" / "neoforge"
DOWNLOADS = BUILD / "downloads"
INSTALL = BUILD / "install"
SOURCES = BUILD / "sources"
MERGE = BUILD / "merge"
DIST = BUILD / "distribution"
BRIDGES = MODULE / "bridge-patches"
REPORT = MERGE / "conflicts.json"


def load_lock() -> dict:
    with LOCK_PATH.open(encoding="utf-8") as stream:
        return json.load(stream)


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def verify(path: pathlib.Path, record: dict) -> None:
    if not path.is_file():
        raise RuntimeError(f"missing locked artifact: {path}")
    actual_size = path.stat().st_size
    if actual_size != record["size"]:
        raise RuntimeError(f"size mismatch for {path.name}: {actual_size} != {record['size']}")
    actual_hash = sha256(path)
    if actual_hash != record["sha256"]:
        raise RuntimeError(f"SHA-256 mismatch for {path.name}: {actual_hash} != {record['sha256']}")


def download(record: dict, destination: pathlib.Path) -> pathlib.Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        try:
            verify(destination, record)
            return destination
        except RuntimeError:
            destination.unlink()
    temporary = destination.with_suffix(destination.suffix + ".part")
    with urllib.request.urlopen(record["url"], timeout=60) as response, temporary.open("wb") as output:
        shutil.copyfileobj(response, output)
    temporary.replace(destination)
    verify(destination, record)
    return destination


def installer_path(lock: dict) -> pathlib.Path:
    return download(lock["installer"], DOWNLOADS / "neoforge-installer.jar")


def parse_profile(lock: dict) -> tuple[dict, dict]:
    installer = installer_path(lock)
    with zipfile.ZipFile(installer) as archive:
        profile = json.loads(archive.read(lock["installer"]["profileEntry"]))
        version = json.loads(archive.read(lock["installer"]["versionEntry"]))
        patch = archive.getinfo(lock["patch"]["entry"])
        if patch.file_size != lock["patch"]["size"]:
            raise RuntimeError("installer binary patch size differs from lock")
        if hashlib.sha256(archive.read(lock["patch"]["entry"])).hexdigest() != lock["patch"]["sha256"]:
            raise RuntimeError("installer binary patch hash differs from lock")
    if profile.get("minecraft") != lock["minecraft"] or profile.get("version") != f"neoforge-{lock['neoForge']}":
        raise RuntimeError("installer profile version differs from lock")
    expected = {(item["coordinate"], item["sha1"], item["size"], item["url"])
                for item in lock["runtimeArtifacts"]}
    actual = {(item["name"], item["downloads"]["artifact"]["sha1"],
               item["downloads"]["artifact"]["size"], item["downloads"]["artifact"]["url"])
              for item in profile["libraries"]}
    if actual != expected:
        raise RuntimeError("installer library graph differs from tracked lock")
    if profile.get("processors") != lock["processors"]:
        raise RuntimeError("installer processor graph differs from tracked lock")
    if version.get("arguments", {}).get("jvm") != lock["jvmArguments"]:
        raise RuntimeError("installer JVM arguments differ from tracked lock")
    return profile, version


def validate_lock(resolve: bool) -> None:
    lock = load_lock()
    parse_profile(lock)
    required = ("format", "minecraft", "neoForge", "installer", "neoForm", "sourceMachine",
                "patch", "upstreamServer", "expectedPatchedServer", "processors", "runtimeArtifacts")
    missing = [name for name in required if name not in lock]
    if missing:
        raise RuntimeError(f"lock missing keys: {missing}")
    if lock["format"] != 1 or lock["minecraft"] != "26.2" or lock["neoForge"] != "26.2.0.57":
        raise RuntimeError("unexpected NeoForge lock identity")
    if resolve:
        for artifact in lock["runtimeArtifacts"]:
            download(artifact, INSTALL / "libraries" / pathlib.PurePosixPath(artifact["path"]))
        for artifact in lock["fml"]["overrideArtifacts"]:
            download(artifact, INSTALL / "libraries" / pathlib.PurePosixPath(artifact["path"]))
        download(lock["neoForm"], DOWNLOADS / "neoform.zip")
        download(lock["sourceMachine"], DOWNLOADS / "neoforge-userdev.jar")
        download(lock["sourceMachine"]["neoFormRuntime"], DOWNLOADS / "neoform-runtime-all.jar")
        for index, tool in enumerate(lock["sourceMachine"]["requiredTools"]):
            download(tool, DOWNLOADS / "source-tools" / f"{index:02d}-{pathlib.PurePosixPath(tool['url']).name}")
    print(f"neoforge-lock: OK ({len(lock['runtimeArtifacts'])} installer artifacts, resolve={resolve})")


def reconstruct_binary() -> None:
    lock = load_lock()
    patched = INSTALL / "libraries" / pathlib.PurePosixPath(lock["expectedPatchedServer"]["path"])
    if not patched.is_file() or patched.stat().st_size != lock["expectedPatchedServer"]["size"] \
            or sha256(patched) != lock["expectedPatchedServer"]["sha256"]:
        installer = installer_path(lock)
        INSTALL.mkdir(parents=True, exist_ok=True)
        subprocess.run([java_executable(), "-jar", str(installer), "--install-server", str(INSTALL)],
                       cwd=ROOT, check=True)
    verify(patched, lock["expectedPatchedServer"])
    for artifact in lock["fml"]["overrideArtifacts"]:
        download(artifact, INSTALL / "libraries" / pathlib.PurePosixPath(artifact["path"]))
    write_provenance("patched-server", patched, {
        "producer": "NeoForge installer processors from install_profile.json",
        "installer": lock["installer"]["sha256"],
        "upstream": lock["upstreamServer"]["sha256"],
        "binaryPatch": lock["patch"]["sha256"],
    })
    print(f"neoforge-reconstruct: {patched}")


def reconstruct_sources() -> None:
    lock = load_lock()
    runtime = download(lock["sourceMachine"]["neoFormRuntime"], DOWNLOADS / "neoform-runtime-all.jar")
    userdev = download(lock["sourceMachine"], DOWNLOADS / "neoforge-userdev.jar")
    neoform = download(lock["neoForm"], DOWNLOADS / "neoform.zip")
    SOURCES.mkdir(parents=True, exist_ok=True)
    artifact_manifest = SOURCES / "artifacts.properties"
    manifest_lines = [
        f"net.neoforged\\:neoforge\\:26.2.0.57\\:userdev={userdev.as_posix()}",
        f"net.neoforged\\:neoform\\:26.2-2\\@zip={neoform.as_posix()}",
    ]
    for index, tool in enumerate(lock["sourceMachine"]["requiredTools"]):
        local = download(tool, DOWNLOADS / "source-tools" / f"{index:02d}-{pathlib.PurePosixPath(tool['url']).name}")
        escaped_coordinate = tool["coordinate"].replace(":", "\\:")
        manifest_lines.append(f"{escaped_coordinate}={local.as_posix()}")
    artifact_manifest.write_text("\n".join(manifest_lines) + "\n", encoding="utf-8")
    output = SOURCES / "neoforge-game-sources.jar"
    vanilla = SOURCES / "vanilla-sources.jar"
    common = [
        java_executable(), "-Xmx5g", "-jar", str(runtime),
        "--home-dir", str(BUILD / "neoform-home"),
        "--work-dir", str(BUILD / "neoform-work"),
        "--artifact-manifest", str(artifact_manifest), "run",
    ]
    subprocess.run(common + [
        "--neoforge", lock["sourceMachine"]["coordinate"], "--dist", "joined",
        f"--write-result=gameSourcesWithNeoForge:{output}",
    ], cwd=ROOT, check=True)
    # NeoForm's gameSources result is rebound after NeoForge graph transforms. A separate NeoForm-only
    # invocation is therefore required to produce a genuine three-way merge base.
    subprocess.run(common + [
        "--neoform", lock["neoForm"]["coordinate"], "--dist", "joined",
        f"--write-result=gameSources:{vanilla}",
    ], cwd=ROOT, check=True)
    write_provenance("neoforge-sources", output, {
        "producer": lock["sourceMachine"]["neoFormRuntime"]["coordinate"],
        "userdev": lock["sourceMachine"]["sha256"],
        "neoForm": lock["neoForm"]["sha256"],
        "sourceTools": {tool["coordinate"]: tool["sha256"] for tool in lock["sourceMachine"]["requiredTools"]},
        "minecraftVersionManifest": lock["minecraftVersionManifest"]["sha256"],
        "result": "gameSourcesWithNeoForge",
    })
    print(f"neoforge-sources: {output}")


def java_executable() -> str:
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = pathlib.Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            return str(candidate)
    return "java"


def write_provenance(name: str, artifact: pathlib.Path, inputs: dict) -> None:
    provenance = {
        "format": 1,
        "artifact": artifact.relative_to(ROOT).as_posix(),
        "size": artifact.stat().st_size,
        "sha256": sha256(artifact),
        "inputs": inputs,
    }
    destination = artifact.with_name(f"{name}-provenance.json")
    destination.write_text(json.dumps(provenance, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def source_entries(path: pathlib.Path) -> tuple[dict[str, bytes], bool]:
    if path.is_dir():
        entries = {entry.relative_to(path).as_posix(): entry.read_bytes()
                   for entry in sorted(path.rglob("*.java")) if entry.is_file()}
        # Generated Paper/Victus source directories are server-only overlays, so an absent joined/client
        # source is not a deletion. Explicit deletion markers remain available for real removed classes.
        tombstones = path / ".hybrid-deletions"
        if tombstones.is_file():
            for line in tombstones.read_text(encoding="utf-8").splitlines():
                name = line.strip()
                if name and not name.startswith("#"):
                    entries[name] = None
        return entries, False
    if path.is_file() and zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            return ({name: archive.read(name) for name in sorted(archive.namelist()) if name.endswith(".java")}, True)
    raise RuntimeError(f"source input must be a directory or source jar: {path}")


def snapshot_hash(entries: dict[str, bytes | None]) -> str:
    digest = hashlib.sha256()
    for name, content in sorted(entries.items()):
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(b"<deleted>" if content is None else content)
        digest.update(b"\0")
    return digest.hexdigest()


def tree_hash(path: pathlib.Path) -> str:
    entries = {entry.relative_to(path).as_posix(): entry.read_bytes()
               for entry in path.rglob("*") if entry.is_file() and entry.name != ".gitkeep"}
    return snapshot_hash(entries)


def merge_sources(vanilla_path: pathlib.Path, neoforge_path: pathlib.Path, paper_path: pathlib.Path,
                  fail_unresolved: bool) -> None:
    base, base_complete = source_entries(vanilla_path)
    neo, neo_complete = source_entries(neoforge_path)
    paper, paper_complete = source_entries(paper_path)
    if MERGE.exists():
        shutil.rmtree(MERGE)
    overlay = MERGE / "overlay"
    overlay.mkdir(parents=True)
    bridge = source_entries(BRIDGES)[0] if BRIDGES.exists() else {}
    records = []
    overlay_deletions = []
    counts = {"unchanged": 0, "neoforgeOnly": 0, "paperOnly": 0, "identical": 0,
              "conflict": 0, "resolvedByBridge": 0}
    missing = object()
    for name in sorted(set(base) | set(neo) | set(paper)):
        original = base.get(name)
        neo_value = neo.get(name, missing)
        paper_value = paper.get(name, missing)
        neo_data = None if neo_value is missing and neo_complete else (original if neo_value is missing else neo_value)
        paper_data = None if paper_value is missing and paper_complete else (original if paper_value is missing else paper_value)
        neo_changed = neo_data != original
        paper_changed = paper_data != original
        state = "unchanged"
        selected = paper_data
        if neo_changed and not paper_changed:
            state, selected = "neoforgeOnly", neo_data
        elif paper_changed and not neo_changed:
            state, selected = "paperOnly", paper_data
        elif neo_changed and paper_changed and neo_data == paper_data:
            state, selected = "identical", neo_data
        elif neo_changed and paper_changed:
            if name in bridge:
                state, selected = "resolvedByBridge", bridge[name]
            else:
                state, selected = "conflict", None
        counts[state] += 1
        record = {
            "path": name,
            "status": state,
            "baseSha256": hashlib.sha256(original).hexdigest() if original is not None else None,
            "neoForgeSha256": hashlib.sha256(neo_data).hexdigest() if neo_data is not None else None,
            "paperSha256": hashlib.sha256(paper_data).hexdigest() if paper_data is not None else None,
            "bridge": (BRIDGES / name).relative_to(ROOT).as_posix() if state == "resolvedByBridge" else None,
        }
        records.append(record)
        if (neo_changed or paper_changed) and state != "conflict":
            if selected is None:
                overlay_deletions.append(name)
            else:
                destination = overlay / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(selected)
    if overlay_deletions:
        (overlay / ".hybrid-deletions").write_text("\n".join(overlay_deletions) + "\n", encoding="utf-8")
    report = {
        "format": 1,
        "minecraft": "26.2",
        "neoForge": "26.2.0.57",
        "paperCommit": "75c0b485bf038c175d6f3e6efc67519cd5cd524d",
        "inputs": {
            "vanilla": {"path": str(vanilla_path), "snapshotSha256": snapshot_hash(base), "complete": base_complete},
            "neoforge": {"path": str(neoforge_path), "snapshotSha256": snapshot_hash(neo), "complete": neo_complete},
            "paper": {"path": str(paper_path), "snapshotSha256": snapshot_hash(paper), "complete": paper_complete},
            "bridge": {"path": str(BRIDGES), "snapshotSha256": tree_hash(BRIDGES), "complete": False},
        },
        "counts": counts,
        "unresolved": [record["path"] for record in records if record["status"] == "conflict"],
        "classes": records,
    }
    REPORT.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"neoforge-merge: {counts}; report={REPORT}")
    if fail_unresolved and report["unresolved"]:
        raise RuntimeError(f"{len(report['unresolved'])} unresolved NeoForge/Paper source conflicts; add explicit files under {BRIDGES}")


def ensure_merge_clean() -> dict:
    if not REPORT.is_file():
        raise RuntimeError(f"merge report missing: run neoforgeMergeSources first")
    report = json.loads(REPORT.read_text(encoding="utf-8"))
    if report.get("unresolved"):
        raise RuntimeError(f"distribution refused: {len(report['unresolved'])} unresolved merge conflicts")
    for name in ("vanilla", "neoforge", "paper", "bridge"):
        record = report.get("inputs", {}).get(name)
        if not isinstance(record, dict) or "snapshotSha256" not in record:
            raise RuntimeError("distribution refused: merge report lacks input content identities; regenerate it")
        current_path = pathlib.Path(record["path"])
        current_hash = tree_hash(current_path) if name == "bridge" else snapshot_hash(source_entries(current_path)[0])
        if current_hash != record["snapshotSha256"]:
            raise RuntimeError(f"distribution refused: stale merge report; {name} input changed")
    return report


def bind_merged_server(server: pathlib.Path) -> None:
    report = ensure_merge_clean()
    if not server.is_file() or not zipfile.is_zipfile(server):
        raise RuntimeError("merged server must be an existing jar")
    with zipfile.ZipFile(server) as archive:
        if "net/minecraft/server/Main.class" not in archive.namelist():
            raise RuntimeError("merged server lacks net.minecraft.server.Main")
    report["mergedServer"] = str(server.resolve())
    report["mergedServerSha256"] = sha256(server)
    REPORT.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"neoforge-merge: bound merged server {server} ({report['mergedServerSha256']})")


def assemble_distribution(server: pathlib.Path, fixture: pathlib.Path) -> None:
    report = ensure_merge_clean()
    if not server.is_file() or not fixture.is_file():
        raise RuntimeError("distribution inputs must be existing merged-server and fixture jars")
    server_hash = sha256(server)
    expected_server_hash = report.get("mergedServerSha256")
    if expected_server_hash is None or expected_server_hash != server_hash:
        raise RuntimeError("distribution refused: merge report is not bound to the supplied merged server; run bind-server")
    lock = load_lock()
    if not INSTALL.is_dir():
        raise RuntimeError("NeoForge install missing; run neoforgeReconstructPatchedGame first")
    DIST.mkdir(parents=True, exist_ok=True)
    shutil.copy2(server, DIST / "victus-neoforge-hybrid.jar")
    mods = DIST / "mods"
    mods.mkdir(exist_ok=True)
    shutil.copy2(fixture, mods / "victus-neoforge-fixture.jar")
    libraries = DIST / "libraries"
    if libraries.exists():
        shutil.rmtree(libraries)
    shutil.copytree(INSTALL / "libraries", libraries)
    # FML locates game content at the installer's patched-game coordinate. Replace that slot with
    # the conflict-clean merged Victus/NeoForge game; never leave stock or unpatched Paper there.
    game_slot = libraries / pathlib.PurePosixPath(lock["expectedPatchedServer"]["path"])
    shutil.copy2(server, game_slot)
    args_file = libraries / "net" / "neoforged" / "neoforge" / lock["neoForge"] / ("win_args.txt" if os.name == "nt" else "unix_args.txt")
    launch = {
        "format": 1,
        "entrypoint": lock["entrypoint"],
        "server": "victus-neoforge-hybrid.jar",
        "argsFile": args_file.relative_to(DIST).as_posix(),
        "jvmArguments": lock["jvmArguments"],
        "gameArguments": lock["gameArguments"],
        "services": ["FML class processors", "FML Mixin service", "access transformers"],
        "mergeReport": REPORT.relative_to(ROOT).as_posix(),
        "capability": False,
    }
    (DIST / "launch-plan.json").write_text(json.dumps(launch, indent=2) + "\n", encoding="utf-8")
    write_provenance("hybrid-distribution", DIST / "victus-neoforge-hybrid.jar", {
        "paperVictus": sha256(server), "neoForgeInstaller": lock["installer"]["sha256"], "mergeReport": sha256(REPORT)
    })
    print(f"neoforge-distribution: {DIST}")


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    validate = sub.add_parser("validate-lock")
    validate.add_argument("--resolve", action="store_true")
    sub.add_parser("reconstruct-binary")
    sub.add_parser("reconstruct-sources")
    merge = sub.add_parser("merge")
    merge.add_argument("--vanilla", type=pathlib.Path, required=True)
    merge.add_argument("--neoforge", type=pathlib.Path, required=True)
    merge.add_argument("--paper", type=pathlib.Path, required=True)
    merge.add_argument("--allow-conflicts", action="store_true")
    sub.add_parser("check-merge")
    bind = sub.add_parser("bind-server")
    bind.add_argument("--server", type=pathlib.Path, required=True)
    dist = sub.add_parser("assemble")
    dist.add_argument("--server", type=pathlib.Path, required=True)
    dist.add_argument("--fixture", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.command == "validate-lock":
        validate_lock(args.resolve)
    elif args.command == "reconstruct-binary":
        reconstruct_binary()
    elif args.command == "reconstruct-sources":
        reconstruct_sources()
    elif args.command == "merge":
        merge_sources(args.vanilla, args.neoforge, args.paper, not args.allow_conflicts)
    elif args.command == "check-merge":
        ensure_merge_clean()
        print("neoforge-merge: no unresolved conflicts")
    elif args.command == "bind-server":
        bind_merged_server(args.server)
    elif args.command == "assemble":
        assemble_distribution(args.server, args.fixture)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as failure:
        print(f"neoforge-pipeline: {failure}", file=sys.stderr)
        raise SystemExit(1)
