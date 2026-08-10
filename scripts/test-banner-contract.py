#!/usr/bin/env python3
"""Source/snapshot assertions for the native post-ready startup banner."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BANNER_SOURCE = ROOT / "victus-core" / "src" / "main" / "java" / "cloud" / "victus" / "core" / "branding" / "StartupBanner.java"
ENGINE_PATCH = ROOT / "victus-server" / "paper-patches" / "files" / "src" / "main" / "java" / "cloud" / "victus" / "engine" / "VictusEngine.java.patch"
SERVER_PATCH = ROOT / "victus-server" / "minecraft-patches" / "sources" / "net" / "minecraft" / "server" / "MinecraftServer.java.patch"
STARTUP_BANNER_PATCH = ROOT / "victus-server" / "paper-patches" / "files" / "src" / "main" / "java" / "org" / "bukkit" / "craftbukkit" / "CraftServer.java.patch"
EXPECTED = (
    "__     _____ ____ _____ _   _ ____     ____ _     ___  _   _ ____ ",
    "\\ \\   / /_ _/ ___|_   _| | | / ___|   / ___| |   / _ \\| | | |  _ \\",
    " \\ \\ / / | | |     | | | | | \\___ \\  | |   | |  | | | | | | | | |",
    "  \\ V /  | | |___  | | | |_| |___) | | |___| |__| |_| | |_| | |_| |",
    "   \\_/  |___|\\____| |_|  \\___/|____/   \\____|_____|\\___/ \\___/|____/",
    "Powered by Victus Cloud — https://victuscloud.com",
)


def decoded_java_strings(source: str) -> tuple[str, ...]:
    block = re.search(r"LINES = List\.of\((?P<body>.*?)\n    \);", source, re.DOTALL)
    if not block:
        raise AssertionError("StartupBanner has no LINES snapshot")
    values = re.findall(r'^            "(.*)"[,]?$', block.group("body"), re.MULTILINE)
    return tuple(value.replace("\\\\", "\\") for value in values)


def main() -> int:
    source = BANNER_SOURCE.read_text(encoding="utf-8")
    engine = ENGINE_PATCH.read_text(encoding="utf-8")
    server = SERVER_PATCH.read_text(encoding="utf-8")
    startup = STARTUP_BANNER_PATCH.read_text(encoding="utf-8")
    errors: list[str] = []
    try:
        actual = decoded_java_strings(source)
        if actual != EXPECTED:
            errors.append(f"banner snapshot mismatch:\nexpected={EXPECTED!r}\nactual={actual!r}")
    except AssertionError as exc:
        errors.append(str(exc))
    if "private static final java.util.concurrent.atomic.AtomicBoolean BANNER_PRINTED" not in engine:
        errors.append("banner needs a process-wide AtomicBoolean once guard")
    if "if (!BANNER_PRINTED.compareAndSet(false, true))" not in engine:
        errors.append("banner once guard is not enforced")
    if "public static void logStartupBanner()" not in engine:
        errors.append("native banner logging contract method is missing")
    if "cloud.victus.core.branding.StartupBanner.LINES" not in engine:
        errors.append("native adapter does not use the shared banner contract")
    patch_lines = engine.splitlines()
    header = next((line for line in patch_lines if line.startswith("@@ -1,0 +_,")), "")
    added = sum(1 for line in patch_lines[3:] if line.startswith("+"))
    match = re.fullmatch(r"@@ -1,0 \+_,(\d+) @@", header)
    if match is None or int(match.group(1)) != added:
        errors.append(f"VictusEngine new-file patch header count does not match {added} added lines")
    # The shared brand and once-guard are compiled into the engine now. The exact post-ready
    # lifecycle hook is intentionally withheld until the Paper patch is regenerated through the
    # supported fixup task; hand-authoring an insertion adjacent to an upstream-added line caused
    # diffpatch failures on clean clones. Boot validation must remain false until that regeneration.
    if "cloud.victus.engine.VictusEngine.logStartupBanner()" in startup:
        errors.append("CraftServer must not contain an unregenerated startup banner hunk")
    if "cloud.victus.engine.VictusEngine.logStartupBanner()" in server:
        errors.append("MinecraftServer must not contain an incompatible startup banner hunk")
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("banner contract source assertions passed: shared brand and once guard exist; post-ready hook awaits regenerated patch")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
