#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run a prepared Victus/Fabric plan with a wall-clock bound and fixture assertions."""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("plan", type=pathlib.Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("target_args", nargs="*", default=["--nogui", "--initSettings"])
    args = parser.parse_args()
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    game = pathlib.Path(plan["gameDirectory"])
    game.mkdir(parents=True, exist_ok=True)
    log = args.plan.parent / "fabric-integration.log"
    command = [args.java, *plan["jvmProperties"], "-cp", plan["launchClasspath"], plan["mainClass"],
               "--hybrid-config=" + str(args.plan.parent / "fabric-launcher.properties"), "--",
               *(args.target_args or ["--nogui", "--initSettings"])]
    try:
        with log.open("w", encoding="utf-8") as output:
            result = subprocess.run(command, cwd=game, stdout=output, stderr=subprocess.STDOUT,
                                    timeout=args.timeout, check=False)
    except subprocess.TimeoutExpired:
        print(f"FABRIC_INTEGRATION_TIMEOUT: exceeded {args.timeout}s; log={log}", file=sys.stderr)
        return 124
    text = log.read_text(encoding="utf-8", errors="replace")
    if result.returncode:
        # Preserve every nested Java cause and the complete failing log. A fixed tail can truncate the
        # first useful Caused by line when Fabric reports all aggregate API modules and mixin warnings.
        causes = [line for line in text.splitlines()
                  if re.search(r"(^|\s)(Caused by:|Suppressed:)|FABRIC_[A-Z0-9_]+", line)]
        print("FABRIC_INTEGRATION_CAUSE_CHAIN:\n" + ("\n".join(causes) or "<no cause lines found>"),
              file=sys.stderr)
        print("FABRIC_INTEGRATION_FULL_LOG_BEGIN", file=sys.stderr)
        print(text, file=sys.stderr, end="" if text.endswith("\n") else "\n")
        print("FABRIC_INTEGRATION_FULL_LOG_END", file=sys.stderr)
    report_path = pathlib.Path(plan["startupReport"])
    report = report_path.read_text(encoding="utf-8", errors="replace") if report_path.is_file() else ""
    required = ["VICTUS_FIXTURE_FABRIC_PROOF", "discovered=true", "entrypoint=true",
                "fabricApi=true", "mixin=true"]
    missing = [marker for marker in required if marker not in text]
    success_report = "state=TARGET_DELEGATED" in report and "blocker=none" in report
    if result.returncode or missing or not success_report:
        print(f"FABRIC_INTEGRATION_FAILED: exit={result.returncode}, missing={missing}, "
              f"targetDelegated={success_report}, report={report_path}, log={log}", file=sys.stderr)
        return result.returncode or 2
    print(f"FABRIC_INTEGRATION_OK: log={log}, report={report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
