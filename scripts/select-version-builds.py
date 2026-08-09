#!/usr/bin/env python3
"""Emit a GitHub Actions matrix for source lines that actually exist."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "versions" / "versions.json"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--versions", default="26.2", help="comma-separated versions or 'implemented'")
    args = parser.parse_args()
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    entries = {entry["minecraftVersion"]: entry for entry in catalog["versions"]}
    requested = (
        [version for version, entry in entries.items() if entry["sourceImplemented"]]
        if args.versions == "implemented"
        else [item.strip() for item in args.versions.split(",") if item.strip()]
    )
    unknown = [version for version in requested if version not in entries]
    if unknown:
        raise SystemExit(f"unknown catalog version(s): {', '.join(unknown)}")
    unavailable = [version for version in requested if not entries[version]["sourceImplemented"]]
    if unavailable:
        raise SystemExit(
            "requested version line(s) have no implemented sourceBranch and cannot be built: "
            + ", ".join(unavailable)
        )
    include = []
    for version in requested:
        entry = entries[version]
        include.append(
            {
                "minecraft": version,
                "java": entry["java"]["build"],
                "sourceBranch": entry["sourceBranch"],
                "buildTask": entry["buildSystem"]["task"],
                "artifactPath": entry["buildSystem"]["artifactPath"],
                "publicationEligible": entry["publicationEligible"],
            }
        )
    print(json.dumps({"include": include}, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
