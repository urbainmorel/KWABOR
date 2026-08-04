#!/usr/bin/env python3
"""Decide whether a pull request must execute the native iOS validation matrix."""

from __future__ import annotations

import argparse
import subprocess
from collections.abc import Iterable


IOS_RELEVANT_PREFIXES = (
    ".github/actions/",
    "build-logic/",
    "buildSrc/",
    "gradle/",
    "iosApp/",
    "shared/",
)

IOS_RELEVANT_FILES = frozenset(
    {
        ".github/workflows/ci.yml",
        "build.gradle.kts",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
        "settings.gradle.kts",
        "tools/detect_ios_validation_scope.py",
        "tools/test_detect_ios_validation_scope.py",
    },
)

IOS_SAFE_SKIP_PREFIXES = (
    "androidApp/",
    "docs/",
    "supabase/",
)

IOS_SAFE_SKIP_FILES = frozenset(
    {
        "AGENTS.md",
        "BACKLOG.md",
        "CONTRIBUTING.md",
        "DESIGN.md",
        "PRD.md",
        "PROJECT_STATE.md",
        "README.md",
        "TOOLING_SETUP_qualite_kmp.md",
    },
)


def normalize_repository_path(path: str) -> str:
    normalized = path.strip().replace("\\", "/")
    while normalized.startswith("./"):
        normalized = normalized[2:]
    return normalized


def requires_ios_validation(path: str) -> bool:
    normalized = normalize_repository_path(path)
    if normalized in IOS_RELEVANT_FILES or normalized.startswith(IOS_RELEVANT_PREFIXES):
        return True
    if normalized in IOS_SAFE_SKIP_FILES or normalized.startswith(IOS_SAFE_SKIP_PREFIXES):
        return False
    return True


def changes_require_ios_validation(paths: Iterable[str]) -> bool:
    return any(requires_ios_validation(path) for path in paths)


def changed_files(base_sha: str, head_sha: str) -> tuple[str, ...]:
    result = subprocess.run(
        [
            "git",
            "diff",
            "--name-only",
            "--no-renames",
            "--diff-filter=ACDMRT",
            "-z",
            base_sha,
            head_sha,
        ],
        check=True,
        capture_output=True,
    )
    return tuple(path.decode("utf-8") for path in result.stdout.split(b"\0") if path)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    required = changes_require_ios_validation(changed_files(args.base, args.head))
    print(str(required).lower())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
