#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Create a deterministic Fabric hybrid distribution and launcher plan from verified inputs."""
from __future__ import annotations

import argparse
import io
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


def patch_module_mixin(source: bytes, config_name: str, removed_mixin: str) -> bytes:
    """Remove one source-owned incompatible mixin while retaining the rest of its API module."""
    input_buffer = io.BytesIO(source)
    output_buffer = io.BytesIO()
    with zipfile.ZipFile(input_buffer) as module, zipfile.ZipFile(output_buffer, "w") as output:
        for info in module.infolist():
            data = module.read(info.filename)
            if info.filename == config_name:
                config = json.loads(data)
                before = list(config.get("mixins", []))
                config["mixins"] = [name for name in before if name != removed_mixin]
                if len(config["mixins"]) == len(before):
                    raise SystemExit(f"FABRIC_API_MIXIN_PATCH_MISSING: {config_name} does not declare {removed_mixin}")
                data = json.dumps(config, separators=(",", ":")).encode()
            output.writestr(info, data)
    return output_buffer.getvalue()


def filter_fabric_api(aggregate: pathlib.Path, refused_modules: dict[str, str],
                      replacement_mixins: dict[str, tuple[str, str, str]]) -> None:
    """Remove explicitly refused modules and patch only source-owned mixins in retained modules."""
    temporary = aggregate.with_suffix(".filtered.jar")
    with zipfile.ZipFile(aggregate) as source:
        metadata = json.loads(source.read("fabric.mod.json"))
        nested_metadata: dict[str, dict] = {}
        for entry in metadata.get("jars", []):
            name = pathlib.PurePosixPath(entry["file"]).name
            with zipfile.ZipFile(io.BytesIO(source.read(entry["file"]))) as nested:
                module_metadata = json.loads(nested.read("fabric.mod.json"))
                nested_metadata[module_metadata["id"]] = module_metadata
        changed = True
        while changed:
            changed = False
            for module, module_metadata in nested_metadata.items():
                if module in refused_modules:
                    continue
                refused_dependency = next((dependency for dependency in module_metadata.get("depends", {})
                                           if dependency in refused_modules), None)
                if refused_dependency is not None:
                    refused_modules[module] = f"depends on refused {refused_dependency}"
                    changed = True
        removed: list[str] = []
        kept_jars = []
        for entry in metadata.get("jars", []):
            name = pathlib.PurePosixPath(entry["file"]).name
            module_id = next((module for module in refused_modules if name.startswith(module + "-")), None)
            if module_id is None:
                kept_jars.append(entry)
            else:
                removed.append(module_id)
        metadata["jars"] = kept_jars
        custom = metadata.setdefault("custom", {})
        custom["victus:refused-modules"] = [
            {"id": module, "reason": refused_modules[module]} for module in sorted(removed)
        ]
        with zipfile.ZipFile(temporary, "w") as output:
            for info in source.infolist():
                upper_name = info.filename.upper()
                if (upper_name.startswith("META-INF/") and upper_name.endswith((".SF", ".RSA", ".DSA"))):
                    # Nested module edits invalidate the upstream aggregate signature; the locked input was
                    # verified before this deterministic local transformation.
                    continue
                if info.filename == "fabric.mod.json" or any(
                        pathlib.PurePosixPath(info.filename).name.startswith(module + "-")
                        and info.filename.startswith("META-INF/jars/") for module in removed):
                    continue
                data = source.read(info.filename)
                nested_name = pathlib.PurePosixPath(info.filename).name
                replacement = next((value for module, value in replacement_mixins.items()
                                    if nested_name.startswith(module + "-")), None)
                if replacement is not None:
                    data = patch_module_mixin(data, replacement[0], replacement[1])
                output.writestr(info, data)
            output.writestr("fabric.mod.json", json.dumps(metadata, separators=(",", ":")))
    temporary.replace(aggregate)
    for module in sorted(removed):
        print(f"FABRIC_API_MODULE_REFUSED id={module} reason={refused_modules[module]}")
    for module, (_, mixin, ownership) in replacement_mixins.items():
        if module not in removed:
            print(f"FABRIC_API_MIXIN_REPLACED module={module} mixin={mixin} {ownership}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", required=True, type=pathlib.Path,
                        help="class-bearing victus-server jar (never Paperclip/bundler)")
    parser.add_argument("--target-classpath", type=pathlib.Path,
                        help="text file with one absolute target runtime library path per line")
    parser.add_argument("--resolved", required=True, type=pathlib.Path,
                        help="output previously produced by validate-runtime-lock.py --resolve --profile fabric --output")
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "build" / "hybrid-fabric-dist")
    parser.add_argument("--java", default=os.environ.get("JAVA", "java"))
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
    (output / "plugins").mkdir()

    launcher = one(ROOT / "hybrid-launcher" / "build" / "libs", "hybrid-launcher-")
    common = one(ROOT / "hybrid-common" / "build" / "libs", "hybrid-common-")
    bukkit = one(ROOT / "hybrid-bukkit" / "build" / "libs", "hybrid-bukkit-")
    adapter = one(ROOT / "hybrid-fabric" / "build" / "libs", "hybrid-fabric-")
    relocator = one(ROOT / "hybrid-relocator" / "build" / "libs", "hybrid-relocator-")
    fixture = one(ROOT / "hybrid-fixtures" / "build" / "libs", "victus-fixture-fabric-")
    bukkit_fixture = one(ROOT / "hybrid-fixtures" / "build" / "libs", "victus-fixture-bukkit-")
    for source in (launcher, common, bukkit, adapter):
        shutil.copy2(source, output / "runtime" / source.name)
    shutil.copy2(fixture, output / "mods" / fixture.name)
    shutil.copy2(bukkit_fixture, output / "plugins" / bukkit_fixture.name)
    # Full Fabric API is a nested mod and belongs in mods, not on Knot's platform classpath.
    for source, artifact in zip(artifacts, lock["artifacts"]):
        # The aggregate Fabric API mod nests its modules. Individually locked fixture modules are compile/test
        # inputs and must not be duplicated on Knot's platform classpath in a real distribution.
        if artifact["role"] == "fixture-api-module":
            continue
        destination = output / ("mods" if artifact["role"] == "fixture-api" else "runtime") / source.name
        shutil.copy2(source, destination)
    aggregate = one(output / "mods", "fabric-api-")
    filter_fabric_api(aggregate, {
        "fabric-block-api-v1": "Paper removes vanilla LevelChunkSection$1BlockCounter; its required air-state mixin cannot apply",
        "fabric-game-rule-api-v1": "Paper adds ServerLevel to MinecraftServer game-rule updates; the Fabric injection descriptor is incompatible",
        "fabric-resource-loader-v1": "Paper adds OptionSet/DataLoadContext to MinecraftServer construction; the Fabric injection descriptor is incompatible",
        "fabric-content-registries-v0": "depends on refused fabric-resource-loader-v1",
        "fabric-creative-tab-api-v1": "depends on refused fabric-resource-loader-v1",
        "fabric-item-api-v1": "depends on refused fabric-resource-loader-v1",
        "fabric-loot-api-v3": "depends on refused fabric-resource-loader-v1",
        "fabric-lifecycle-events-v1": "Paper rewrites chunk futures, unload scheduling, tag reload and server lifecycle targets; required event injections have no targets",
        "fabric-data-generation-api-v1": "depends on refused fabric-lifecycle-events-v1",
        "fabric-networking-api-v1": "depends on refused fabric-lifecycle-events-v1",
        "fabric-particles-v1": "depends on refused fabric-networking-api-v1",
        "fabric-permission-api-v1": "depends on refused fabric-lifecycle-events-v1",
        "fabric-recipe-api-v1": "depends on refused fabric-lifecycle-events-v1 and fabric-networking-api-v1",
        "fabric-registry-sync-v0": "depends on refused fabric-networking-api-v1; its BootstrapMixin is source-replaceable but the module dependency closure is not",
        "fabric-resource-conditions-api-v1": "depends on refused fabric-lifecycle-events-v1",
        "fabric-resource-loader-v0": "depends on refused fabric-resource-loader-v1",
        "fabric-tag-api-v1": "depends on refused fabric-resource-loader-v1",
        "fabric-entity-events-v1": "Paper rewrites ServerPlayer respawn safety checks; the required monster-nearby redirect has no target",
        "fabric-data-attachment-api-v1": "depends on refused fabric-entity-events-v1",
        "fabric-dimensions-v1": "entrypoint links refused fabric-lifecycle-events-v1 ServerLifecycleEvents without declaring the dependency",
        "fabric-menu-api-v1": "Paper rewrites ServerPlayer container opening; the required closeContainer redirect has no target",
    }, {
        "fabric-command-api-v2": ("fabric-command-api-v2.mixins.json", "CommandsMixin",
                                   "owner=Victus FabricCommandBridge reason=Paper adds a modern Commands constructor overload"),
        # These replacements remain declared even while dependency closure refuses their current modules. If a
        # future Fabric release makes the enclosing module retainable, only the incompatible mixin is removed.
        "fabric-lifecycle-events-v1": ("fabric-lifecycle-events-v1.mixins.json", "MinecraftServerMixin",
                                       "owner=Victus FabricLifecycleBridge reason=Paper has no createLevels lifecycle target"),
        "fabric-registry-sync-v0": ("fabric-registry-sync-v0.mixins.json", "BootstrapMixin",
                                    "owner=Victus FabricRegistryBridge reason=Paper owns BuiltInRegistries creation/freeze ordering"),
    })
    with zipfile.ZipFile(aggregate) as archive:
        metadata = json.loads(archive.read("fabric.mod.json"))
        nested_modules = [entry["file"] for entry in metadata.get("jars", [])]
    print(f"FABRIC_API_AGGREGATE modules={len(nested_modules)} validation=descriptor-audit")

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
    print(f"FABRIC_TARGET_CLASSPATH libraries={len(target_libraries)}")
    runtime_cp_file = output / "target-runtime-classpath.txt"
    runtime_cp_file.write_text("\n".join(target_libraries) + "\n", encoding="utf-8")
    shaded_target = output / "victus-server-fabric.jar"
    relocation_report = output / "fabric-asm-relocation.json"
    import subprocess
    relocate = subprocess.run([
        args.java, "-jar", str(relocator), str(target), str(runtime_cp_file),
        str(shaded_target), str(relocation_report)
    ], text=True)
    if relocate.returncode:
        raise SystemExit("FABRIC_ASM_RELOCATION_FAILED: see " + str(relocation_report))
    original_target = target
    target = shaded_target
    # Relocated Paper ASM is embedded in the Fabric target; do not expose the original
    # unrelocated ASM jars to Knot.
    def contains_asm_jar(path: str) -> bool:
        try:
            with zipfile.ZipFile(path) as archive:
                return "org/objectweb/asm/ClassVisitor.class" in archive.namelist()
        except zipfile.BadZipFile:
            return False
    target_libraries = [path for path in target_libraries if not contains_asm_jar(path)]
    runtime_cp_file.write_text("\n".join(target_libraries) + "\n", encoding="utf-8")
    print(f"FABRIC_SHADED_TARGET source={original_target} target={target} libraries={len(target_libraries)}")
    report = output / "fabric-api-compatibility.json"
    audit_command = [
        os.environ.get("JAVA", "java"), "-cp", os.pathsep.join((str(output / "runtime" / adapter.name),
                                                                  str(output / "runtime" / common.name),
                                                                  *[str(path) for path in artifacts if path.name.startswith("fabric-loader-")],
                                                                  *[str(path) for path in artifacts if path.name.startswith("asm-")])),
        "cloud.victus.hybrid.fabric.FabricApiCompatibilityValidator",
        "--aggregate", str(aggregate), "--target", str(target), "--report", str(report),
    ]
    for library in target_libraries:
        audit_command.extend(("--target-library", library))
    result = subprocess.run(audit_command, text=True)
    if result.returncode:
        raise SystemExit("FABRIC_API_COMPATIBILITY_AUDIT_FAILED: see " + str(report))

    runtime = output / "runtime"
    adapter_cp = os.pathsep.join((str(runtime / adapter.name), str(runtime / bukkit.name)))
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
    shutil.copytree(output / "plugins", output / "run" / "plugins")
    (output / "fabric-launch-plan.json").write_text(json.dumps(plan, indent=2) + "\n", encoding="utf-8")
    def property_value(value: object) -> str:
        # java.util.Properties treats backslash as an escape introducer; preserve Windows paths.
        return str(value).replace("\\", "\\\\")

    properties = "\n".join(f"{key}={property_value(value)}" for key, value in plan.items()
                           if key in {"profile", "targetMain", "targetArtifact", "targetClasspath",
                                      "adapterClasspath", "loaderClasspath", "gameDirectory",
                                      "modsDirectory", "startupReport"})
    (output / "fabric-launcher.properties").write_text(properties + "\n", encoding="utf-8")
    print(output / "fabric-launch-plan.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
