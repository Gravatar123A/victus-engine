#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run two full Victus/Fabric boots, stop cleanly, and assert bridge persistence proof."""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import threading
import time


def run_boot(command: list[str], game: pathlib.Path, log: pathlib.Path, timeout: int, boot: int) -> tuple[int, str]:
    lines: list[str] = []
    ready = threading.Event()
    failed = threading.Event()
    with log.open("w", encoding="utf-8") as output:
        process = subprocess.Popen(command, cwd=game, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                                   bufsize=1)

        def collect() -> None:
            assert process.stdout is not None
            for line in process.stdout:
                lines.append(line)
                output.write(line)
                output.flush()
                if "Done (" in line:
                    ready.set()
                if re.search(r"HYBRID_BUKKIT_BRIDGE_.*_FAILED|Victus hybrid startup refused|Exception in thread", line):
                    failed.set()

        reader = threading.Thread(target=collect, name=f"victus-fabric-log-{boot}", daemon=True)
        reader.start()
        deadline = time.monotonic() + timeout
        timed_out = False
        while process.poll() is None and time.monotonic() < deadline and not failed.is_set():
            text = "".join(lines)
            if "Done (" in text:
                ready.set()
                break
            time.sleep(0.1)
        if ready.is_set() and process.poll() is None:
            assert process.stdin is not None
            process.stdin.write("stop\n")
            process.stdin.flush()
        elif process.poll() is None:
            timed_out = time.monotonic() >= deadline
            process.kill()
            process.wait(timeout=15)
            reader.join(timeout=5)
            text = "".join(lines)
            if timed_out:
                print(f"FABRIC_INTEGRATION_BOOT_TIMEOUT boot={boot} timeout={timeout}s log={log}", file=sys.stderr)
                return 124, text
            return 2, text
        try:
            status = process.wait(timeout=max(15, int(deadline - time.monotonic())))
        except subprocess.TimeoutExpired:
            process.kill()
            status = process.wait(timeout=15)
        reader.join(timeout=5)
    return status, "".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("plan", type=pathlib.Path)
    parser.add_argument("--java", default=os.environ.get("JAVA", "java"))
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--port", type=int, default=0, help="0 asks the OS for an isolated random server port")
    parser.add_argument("target_args", nargs="*", default=[])
    args = parser.parse_args()
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    game = pathlib.Path(plan["gameDirectory"])
    game.mkdir(parents=True, exist_ok=True)
    (game / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (game / "server.properties").write_text(
        f"server-port={args.port}\nonline-mode=false\nlevel-name=world\nenable-query=false\nenable-rcon=false\n",
        encoding="utf-8")
    base = [args.java, *plan["jvmProperties"], "-cp", plan["launchClasspath"], plan["mainClass"],
            "--hybrid-config=" + str(args.plan.parent / "fabric-launcher.properties"), "--",
            "--nogui", *(args.target_args or [])]
    required = [
        "Loading 15 mods:", "VICTUS_FIXTURE_FABRIC_PROOF", "discovered=true", "entrypoint=true",
        "fabricApi=true", "mixin=true", "VICTUS_FABRIC_BRIDGE_INITIALIZED",
        "VICTUS_BUKKIT_BRIDGE_BOUND", "VICTUS_FIXTURE_BUKKIT_PROOF", "bridgeApi=1",
        "vanilla=minecraft:stone", "owned=victus_hybrid_fixture_fabric:bridge_probe",
        "commandCollision=NAMESPACE_REQUIRED", "event=EXACT", "Done (",
        "VICTUS_BUKKIT_BRIDGE_READY", "Stopping server", "Saving worlds",
    ]
    combined: list[str] = []
    for boot in (1, 2):
        log = args.plan.parent / f"fabric-integration-boot-{boot}.log"
        status, text = run_boot(base, game, log, args.timeout, boot)
        combined.append(text)
        missing = [marker for marker in required if marker not in text]
        expected_manifest = "manifest=CREATED" if boot == 1 else "manifest=VERIFIED removalGuard=REFUSED"
        if expected_manifest not in text:
            missing.append(expected_manifest)
        if status or missing:
            causes = [line for line in text.splitlines() if re.search(
                r"Caused by:|Suppressed:|FABRIC_[A-Z0-9_]+|HYBRID_BUKKIT_BRIDGE|Exception in thread", line)]
            print("FABRIC_INTEGRATION_CAUSE_CHAIN:\n" + ("\n".join(causes) or "<no cause lines found>"),
                  file=sys.stderr)
            print(f"FABRIC_INTEGRATION_FAILED boot={boot} exit={status} missing={missing} log={log}",
                  file=sys.stderr)
            return status or 2
    report_path = pathlib.Path(plan["startupReport"])
    report = report_path.read_text(encoding="utf-8", errors="replace") if report_path.is_file() else ""
    manifest = game / "victus-hybrid-world.manifest"
    proof = game / "victus-hybrid-proof.txt"
    if "state=TARGET_DELEGATED" not in report or "blocker=none" not in report or not manifest.is_file() or not proof.is_file():
        print(f"FABRIC_INTEGRATION_FAILED report={report_path} manifest={manifest} proof={proof}", file=sys.stderr)
        return 2
    (args.plan.parent / "fabric-integration.log").write_text("".join(combined), encoding="utf-8")
    print(f"FABRIC_INTEGRATION_OK boots=2 port={args.port} report={report_path} manifest={manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
