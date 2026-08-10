#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Create a deterministic Fabric hybrid distribution and launcher plan from verified inputs."""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import shutil
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
LOCK = ROOT / "hybrid" / "locks" / "runtime-lock.json"


def one(directory: pathlib.Path, prefix: str) -> pathlib.Path:
    matches = sorted(directory.glob(prefix + "*.jar"))
    if len(matches) != 1:
        raise SystemExit(f"expected one {prefix}*.jar below {directory}, found {len(matches)}")
    return matches[0].resolve()


def contains(jar: pathlib.Path, class_name: str) -> bool:
    try:
        with zipfile.ZipFile(jar) as archive:
            return class_name.replace(".", "/") + ".class" in archive.namelist()
    except zipfile.BadZipFile:
        return False


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", required=True, type=pathlib.Path,
                        help="class-bearing victus-server jar (never Paperclip/bundler)")
    parser.add_argument("--target-classpath", type=pathlib.Path,
                        help="text file with one absolute target runtime library path per line")
    parser.add_argument("--resolved", required=True, type=pathlib.Path,
                        help="output previously produced by validate-runtime-lock.py --resolve --profile fabric --output")
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "build" / "hybrid-fabric-dist")
    args = parser.parse_args()

    target = args.target.resolve()
    if not target.is_file() or not contains(target, "org.bukkit.craftbukkit.Main"):
        raise SystemExit("FABRIC_TARGET_SHAPE_INVALID: target must be the class-bearing victus-server jar "
                         "containing org.bukkit.craftbukkit.Main; Paperclip and bundler are refused")
    lock = json.loads(LOCK.read_text(encoding="utf-8"))["profiles"]["fabric"]
    resolved = args.resolved.resolve() / "fabric"
    artifacts: list[pathlib.Path] = []
    for artifact in lock["artifacts"]:
        path = resolved / pathlib.PurePosixPath(artifact["path"])
        if not path.is_file():
            raise SystemExit(f"FABRIC_LOCKED_ARTIFACT_MISSING: {path}")
        artifacts.append(path)

    output = args.output.resolve()
    if output.exists():
        shutil.rmtree(output)
    (output / "runtime").mkdir(parents=True)
    (output / "mods").mkdir()

    launcher = one(ROOT / "hybrid-launcher" / "build" / "libs", "hybrid-launcher-")
    common = one(ROOT / "hybrid-common" / "build" / "libs", "hybrid-common-")
    adapter = one(ROOT / "hybrid-fabric" / "build" / "libs", "hybrid-fabric-")
    fixture = one(ROOT / "hybrid-fixtures" / "build" / "libs", "victus-fixture-fabric-")
    for source in (launcher, common, adapter):
        shutil.copy2(source, output / "runtime" / source.name)
    shutil.copy2(fixture, output / "mods" / fixture.name)
    # Full Fabric API is a nested mod and belongs in mods, not on Knot's platform classpath.
    for source, artifact in zip(artifacts, lock["artifacts"]):
        # The aggregate Fabric API mod nests its modules. Individually locked fixture modules are compile/test
        # inputs and must not be duplicated on Knot's platform classpath in a real distribution.
        if artifact["role"] == "fixture-api-module":
            continue
        destination = output / ("mods" if artifact["role"] == "fixture-api" else "runtime") / source.name
        shutil.copy2(source, destination)

    target_libraries: list[str] = []
    if args.target_classpath:
        lines = args.target_classpath.read_text(encoding="utf-8").splitlines()
        target_libraries = [str(pathlib.Path(entry.strip()).resolve())
                            for line in lines
                            for entry in line.split(os.pathsep)
                            if entry.strip()]
        missing = [entry for entry in target_libraries if not pathlib.Path(entry).is_file()]
        if missing:
            raise SystemExit("FABRIC_TARGET_LIBRARY_MISSING: " + ",".join(missing))

    runtime = output / "runtime"
    adapter_cp = str(runtime / adapter.name)
    loader_cp = os.pathsep.join(str(path) for path in sorted(runtime.glob("*.jar"))
                                if path.name not in {launcher.name, common.name, adapter.name})
    launch_cp = os.pathsep.join((str(runtime / launcher.name), str(runtime / common.name)))
    plan = {
        "profile": "fabric",
        "targetMain": "org.bukkit.craftbukkit.Main",
        "targetArtifact": str(target),
        "targetClasspath": os.pathsep.join(target_libraries),
        "adapterClasspath": adapter_cp,
        "loaderClasspath": loader_cp,
        "gameDirectory": str(output / "run"),
        "modsDirectory": str(output / "mods"),
        "startupReport": str(output / "run" / "logs" / "victus-hybrid-startup.txt"),
        "fixtureProofRequired": True,
        "launchClasspath": launch_cp,
        "mainClass": "cloud.victus.hybrid.launcher.VictusHybridLauncher",
        "jvmProperties": ["-Dvictus.fabric.requireFixtureProof=true"],
    }
    (output / "run").mkdir()
    (output / "fabric-launch-plan.json").write_text(json.dumps(plan, indent=2) + "\n", encoding="utf-8")
    properties = "\n".join(f"{key}={value}" for key, value in plan.items()
                           if key in {"profile", "targetMain", "targetArtifact", "targetClasspath",
                                      "adapterClasspath", "loaderClasspath", "gameDirectory",
                                      "modsDirectory", "startupReport"})
    (output / "fabric-launcher.properties").write_text(properties + "\n", encoding="utf-8")
    print(output / "fabric-launch-plan.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
