#!/usr/bin/env python3
"""Run the destructive event concurrency harness against Supabase local only."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
LOCAL_TEST = (
    REPOSITORY_ROOT
    / "supabase"
    / "local-tests"
    / "event_details_concurrency_test.sql"
)
WRAPPER = (
    REPOSITORY_ROOT
    / "supabase"
    / ".temp"
    / "event_details_concurrency_explicit_local.sql"
)
LOCAL_HOSTS = {"127.0.0.1", "localhost", "::1"}


def local_database_url() -> str:
    try:
        result = subprocess.run(
            ["supabase", "status", "-o", "env"],
            cwd=REPOSITORY_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
    except FileNotFoundError as error:
        raise RuntimeError("Supabase CLI is not available on PATH") from error

    if result.returncode != 0:
        raise RuntimeError("The Supabase local stack is not running")

    match = re.search(r'^DB_URL="([^"]+)"$', result.stdout, flags=re.MULTILINE)
    if match is None:
        raise RuntimeError("Supabase status did not expose a local database URL")
    return match.group(1)


def main() -> int:
    database_url = urlparse(local_database_url())
    if database_url.hostname not in LOCAL_HOSTS:
        raise RuntimeError("Refusing to run the concurrency harness outside localhost")
    if not LOCAL_TEST.is_file():
        raise RuntimeError(f"Missing local concurrency test: {LOCAL_TEST}")

    WRAPPER.parent.mkdir(parents=True, exist_ok=True)
    test_sql = LOCAL_TEST.read_text(encoding="utf-8")
    WRAPPER.write_text(
        "set kwabor.local_concurrency_harness = 'explicit-local-wrapper';\n"
        f"{test_sql}",
        encoding="utf-8",
    )

    try:
        result = subprocess.run(
            [
                "supabase",
                "test",
                "db",
                "--local",
                str(WRAPPER.relative_to(REPOSITORY_ROOT)),
            ],
            cwd=REPOSITORY_ROOT,
            check=False,
        )
        return result.returncode
    finally:
        WRAPPER.unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error
