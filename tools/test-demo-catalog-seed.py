#!/usr/bin/env python3
"""Run the destructive closed-beta seed proof against Supabase local only."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
HARNESS = REPOSITORY_ROOT / "supabase" / "local-tests" / "closed_beta_demo_seed_test.sql"
WRAPPER = REPOSITORY_ROOT / "supabase" / ".temp" / "closed_beta_demo_seed_explicit_local.sql"
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
        raise RuntimeError("Refusing to run the demo catalog seed proof outside localhost")
    if not HARNESS.is_file():
        raise RuntimeError(f"Missing demo catalog seed proof: {HARNESS}")

    seed = (REPOSITORY_ROOT / "demo" / "catalog" / "v1" / "generated" / "seed.sql").read_text(
        encoding="utf-8"
    )
    rollback = (
        REPOSITORY_ROOT / "demo" / "catalog" / "v1" / "generated" / "rollback.sql"
    ).read_text(encoding="utf-8")
    harness = HARNESS.read_text(encoding="utf-8")
    WRAPPER.parent.mkdir(parents=True, exist_ok=True)
    WRAPPER.write_text(
        "\\set ON_ERROR_STOP on\n"
        "set kwabor.local_demo_catalog_harness = 'explicit-local-wrapper';\n"
        "set app.kwabor_environment = 'local';\n"
        "set app.kwabor_demo_catalog_enabled = 'true';\n"
        "set app.kwabor_demo_media_base_url = 'https://staging.example.test/storage/';\n"
        f"{harness}\n"
        "-- First import.\n"
        f"{seed}\n"
        "select pg_temp.seed_closed_beta_user_relations();\n"
        "select pg_temp.capture_closed_beta_outside_state('outside-before');\n"
        "select pg_temp.capture_closed_beta_demo_state('first');\n"
        "select pg_temp.assert_closed_beta_rpc_surface();\n"
        "-- A byte-for-byte equivalent import must not change durable state or freshness.\n"
        f"{seed}\n"
        "select pg_temp.assert_closed_beta_demo_state_unchanged('first');\n"
        "-- Rollback is logical: it must hide the demo corpus without deleting parents or children.\n"
        f"{rollback}\n"
        "select pg_temp.assert_closed_beta_demo_logically_rolled_back();\n"
        "select pg_temp.assert_closed_beta_outside_state_unchanged('outside-before');\n"
        "select plan(1);\n"
        "select pass('closed-beta demo seed import, replay, RPC and logical rollback completed');\n"
        "select * from finish();\n",
        encoding="utf-8",
        newline="\n",
    )
    try:
        return subprocess.run(
            ["supabase", "test", "db", "--local", str(WRAPPER.relative_to(REPOSITORY_ROOT))],
            cwd=REPOSITORY_ROOT,
            check=False,
        ).returncode
    finally:
        WRAPPER.unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error
