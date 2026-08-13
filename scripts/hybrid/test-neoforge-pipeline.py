#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Fixture regression tests for the conservative NeoForge/Paper three-way merger."""
from __future__ import annotations

import importlib.util
import pathlib
import sys

SCRIPT = pathlib.Path(__file__).with_name("neoforge_pipeline.py")
SPEC = importlib.util.spec_from_file_location("neoforge_pipeline", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
PIPELINE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = PIPELINE
SPEC.loader.exec_module(PIPELINE)


def merged(base: bytes, neo: bytes, paper: bytes) -> bytes:
    selected, conflicts = PIPELINE.semantic_three_way_merge(base, neo, paper)
    assert not conflicts, conflicts
    assert selected is not None
    return selected


def main() -> int:
    base = b"class Test {\r\n    int left = 1;\r\n    int right = 2;\r\n}\r\n"
    neo = b"class Test {\r\n    int left = 10;\r\n    int right = 2;\r\n}\r\n"
    paper = b"class Test {\r\n    int left = 1;\r\n    int right = 20;\r\n}\r\n"
    assert merged(base, neo, paper) == \
        b"class Test {\r\n    int left = 10;\r\n    int right = 20;\r\n}\r\n"

    identical = b"class Test {\n    int value = 2;\n}\n"
    assert merged(b"class Test {\n    int value = 1;\n}\n", identical, identical) == identical

    same_insert = merged(
        b"package fixture;\nimport common.C;\n\nclass Test {}\n",
        b"package fixture;\nimport common.C;\nimport zeta.Z;\n\nclass Test {}\n",
        b"package fixture;\nimport alpha.A;\nimport common.C;\n\nclass Test {}\n",
    )
    assert same_insert == \
        b"package fixture;\nimport alpha.A;\nimport common.C;\nimport zeta.Z;\n\nclass Test {}\n", same_insert

    # Overlapping inline comments retain one executable line rather than duplicating it.
    comment_overlap = merged(
        b"class Test {\n    int value = 1; // base\n}\n",
        b"class Test {\n    int value = 1; // NeoForge\n}\n",
        b"class Test {\n    int value = 1; // Paper\n}\n",
    )
    assert comment_overlap == b"class Test {\n    int value = 1; // NeoForge\n}\n", comment_overlap

    selected, conflicts = PIPELINE.semantic_three_way_merge(
        b"class Test {\n    int value() { return 1; }\n}\n",
        b"class Test {\n    int value() { return 2; }\n}\n",
        b"class Test {\n    int value() { return 3; }\n}\n",
    )
    assert selected is None
    assert len(conflicts) == 1
    assert conflicts[0]["category"] == "method body", conflicts
    assert conflicts[0]["baseRange"] == {"start": 2, "end": 2}, conflicts
    assert len(conflicts[0]["baseSha256"]) == 64
    assert len(conflicts[0]["neoForgeSha256"]) == 64
    assert len(conflicts[0]["paperSha256"]) == 64

    selected = merged(
        b"// caf\xe9\nclass Test {\n    int value = 1;\n}\n",
        b"// caf\xe9\nclass Test {\n    int value = 2;\n}\n",
        b"// caf\xe9\r\nclass Test {\r\n    int value = 1;\r\n}\r\n",
    )
    assert selected == b"// caf\xe9\r\nclass Test {\r\n    int value = 2;\n}\r\n", selected

    print("neoforge-pipeline self-test: 6 semantic merge fixtures passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
