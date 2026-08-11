#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Regression checks for Fabric aggregate filtering and source-replacement ownership."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import pathlib
import tempfile
import zipfile

SCRIPT = pathlib.Path(__file__).with_name("prepare-fabric-runtime.py")
SPEC = importlib.util.spec_from_file_location("prepare_fabric_runtime", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


def nested_module(module_id: str, config: str | None = None, depends: dict[str, str] | None = None) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("fabric.mod.json", json.dumps({
            "schemaVersion": 1, "id": module_id, "version": "1", "depends": depends or {}
        }))
        if config is not None:
            archive.writestr("fabric-registry-sync-v0.mixins.json", json.dumps({
                "package": "fixture", "mixins": ["BootstrapMixin", "KeptMixin"]
            }))
    return buffer.getvalue()


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="victus-fabric-prepare-test-") as temporary:
        aggregate = pathlib.Path(temporary) / "fabric-api-test.jar"
        entries = [
            "META-INF/jars/fabric-registry-sync-v0-1.jar",
            "META-INF/jars/fabric-recipe-api-v1-1.jar",
            "META-INF/jars/fabric-dependent-api-v1-1.jar",
            "META-INF/jars/fabric-api-base-1.jar",
            "META-INF/jars/fabric-command-api-v2-1.jar",
        ]
        with zipfile.ZipFile(aggregate, "w") as archive:
            archive.writestr("fabric.mod.json", json.dumps({
                "schemaVersion": 1, "id": "fabric-api", "version": "1",
                "jars": [{"file": entry} for entry in entries],
            }))
            archive.writestr(entries[0], nested_module("fabric-registry-sync-v0", "mixins"))
            archive.writestr(entries[1], nested_module("fabric-recipe-api-v1"))
            archive.writestr(entries[2], nested_module("fabric-dependent-api-v1", depends={
                "fabric-recipe-api-v1": "*"
            }))
            archive.writestr(entries[3], nested_module("fabric-api-base"))
            command = io.BytesIO()
            with zipfile.ZipFile(command, "w") as module:
                module.writestr("fabric.mod.json", json.dumps({
                    "schemaVersion": 1, "id": "fabric-command-api-v2", "version": "1"
                }))
                module.writestr("fabric-command-api-v2.mixins.json", json.dumps({
                    "package": "fixture", "mixins": ["CommandsMixin", "KeptCommandMixin"]
                }))
            archive.writestr(entries[4], command.getvalue())

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            PREPARE.filter_fabric_api(aggregate, {
                "fabric-recipe-api-v1": "fixture dependency closure",
            }, {
                "fabric-registry-sync-v0": (
                    "fabric-registry-sync-v0.mixins.json", "BootstrapMixin",
                    "owner=Victus FabricRegistryBridge reason=fixture",
                ),
                "fabric-command-api-v2": (
                    "fabric-command-api-v2.mixins.json", "CommandsMixin",
                    "owner=Victus FabricCommandBridge reason=fixture",
                ),
            })

        with zipfile.ZipFile(aggregate) as archive:
            metadata = json.loads(archive.read("fabric.mod.json"))
            remaining = [pathlib.PurePosixPath(entry["file"]).name for entry in metadata["jars"]]
            assert remaining == ["fabric-registry-sync-v0-1.jar", "fabric-api-base-1.jar",
                                 "fabric-command-api-v2-1.jar"], remaining
            assert metadata["custom"]["victus:refused-modules"] == [
                {"id": "fabric-dependent-api-v1", "reason": "depends on refused fabric-recipe-api-v1"},
                {"id": "fabric-recipe-api-v1", "reason": "fixture dependency closure"},
            ]
            with zipfile.ZipFile(io.BytesIO(archive.read(entries[0]))) as registry:
                mixins = json.loads(registry.read("fabric-registry-sync-v0.mixins.json"))["mixins"]
                assert mixins == ["KeptMixin"], mixins
            with zipfile.ZipFile(io.BytesIO(archive.read(entries[4]))) as command:
                mixins = json.loads(command.read("fabric-command-api-v2.mixins.json"))["mixins"]
                assert mixins == ["KeptCommandMixin"], mixins

        marker = output.getvalue()
        assert "FABRIC_API_MIXIN_REPLACED module=fabric-registry-sync-v0 mixin=BootstrapMixin " \
               "owner=Victus FabricRegistryBridge" in marker, marker
        assert "FABRIC_API_MIXIN_REPLACED module=fabric-command-api-v2 mixin=CommandsMixin " \
               "owner=Victus FabricCommandBridge" in marker, marker
        script = SCRIPT.read_text(encoding="utf-8")
        assert '(output / "plugins").mkdir()' in script
        assert 'shutil.copytree(output / "plugins", output / "run" / "plugins")' in script
        assert '"hybrid-bukkit-"' in script
        assert '"victus-fixture-bukkit-"' in script
        print("prepare-fabric-runtime self-test: replacement ownership, bridge runtime, and plugin passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
