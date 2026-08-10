#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run the foundation integration gate with a hard wall-clock bound."""
from __future__ import annotations

import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
GRADLE = ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")

try:
    completed = subprocess.run(
        [str(GRADLE), "--no-daemon", ":hybrid-launcher:boundedIntegrationTest"],
        cwd=ROOT,
        timeout=120,
        check=False,
    )
except subprocess.TimeoutExpired:
    print("hybrid integration exceeded 120 second process bound", file=sys.stderr)
    raise SystemExit(124)
raise SystemExit(completed.returncode)
