#!/usr/bin/env python3
"""Publish, verify, or logically roll back the demo catalog on protected staging."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import parse_qs, urlparse


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
CATALOG_ROOT = REPOSITORY_ROOT / "demo" / "catalog" / "v1"
MANIFEST_PATH = CATALOG_ROOT / "manifest.json"
SEED_PATH = CATALOG_ROOT / "generated" / "seed.sql"
ROLLBACK_PATH = CATALOG_ROOT / "generated" / "rollback.sql"
PROJECT_REF_PATTERN = re.compile(r"^[a-z0-9]{20}$")
ROLLBACK_CONFIRMATION = "HIDE-EXACT-DEMO-CATALOG"


class DatabaseOperationError(RuntimeError):
    """Raised when a protected staging database operation cannot be proven safe."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise DatabaseOperationError(message)


@dataclass(frozen=True)
class CatalogState:
    target_listings: int
    tagged_listings: int
    published_listings: int
    archived_listings: int
    media: int

    def counts(self, prefix: str) -> dict[str, int]:
        return {
            f"{prefix}ArchivedListings": self.archived_listings,
            f"{prefix}Media": self.media,
            f"{prefix}PublishedListings": self.published_listings,
            f"{prefix}TaggedListings": self.tagged_listings,
            f"{prefix}TargetListings": self.target_listings,
        }


ABSENT_STATE = CatalogState(0, 0, 0, 0, 0)
PUBLISHED_STATE = CatalogState(60, 60, 60, 0, 180)
ARCHIVED_STATE = CatalogState(60, 60, 0, 60, 180)


def _project_ref_from_database_url(database_url: str) -> str:
    parsed = urlparse(database_url)
    require(parsed.scheme in {"postgres", "postgresql"}, "Staging database URL must use PostgreSQL")
    require(parsed.hostname is not None, "Staging database URL is missing a hostname")
    require(parsed.username is not None, "Staging database URL is missing a username")
    require(parsed.path == "/postgres", "Staging database URL must target the postgres database")
    require(not parsed.fragment, "Staging database URL must not contain a fragment")
    try:
        query_parameters = parse_qs(parsed.query, keep_blank_values=True, strict_parsing=True)
    except ValueError as error:
        raise DatabaseOperationError("Staging database URL query is malformed") from error
    require(
        query_parameters == {"sslmode": ["require"]},
        "Staging database URL must contain only sslmode=require",
    )

    direct_match = re.fullmatch(r"db\.([a-z0-9]{20})\.supabase\.co", parsed.hostname)
    if direct_match is not None:
        return direct_match.group(1)

    require(
        parsed.hostname.endswith(".pooler.supabase.com"),
        "Staging database URL must use an official Supabase direct or pooler hostname",
    )
    pooler_match = re.fullmatch(r"postgres\.([a-z0-9]{20})", parsed.username)
    require(pooler_match is not None, "Supabase pooler username must identify the project ref")
    return pooler_match.group(1)


def _validated_environment() -> tuple[str, str]:
    require(os.environ.get("KWABOR_ENVIRONMENT") == "staging", "KWABOR_ENVIRONMENT must equal staging")
    expected_ref = os.environ.get("KWABOR_SUPABASE_PROJECT_REF", "").strip()
    production_ref = os.environ.get("KWABOR_PRODUCTION_SUPABASE_PROJECT_REF", "").strip()
    database_url = os.environ.get("KWABOR_STAGING_DATABASE_URL", "").strip()
    require(PROJECT_REF_PATTERN.fullmatch(expected_ref) is not None, "Staging project ref is invalid")
    require(PROJECT_REF_PATTERN.fullmatch(production_ref) is not None, "Production project ref is invalid")
    require(expected_ref != production_ref, "Staging and production project refs must be distinct")
    require(database_url != "", "KWABOR_STAGING_DATABASE_URL is required")
    require(
        _project_ref_from_database_url(database_url) == expected_ref,
        "Database URL does not identify the expected staging project",
    )
    return database_url, expected_ref


def _manifest_ids() -> list[str]:
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    ids = [listing["id"] for listing in manifest["listings"]]
    require(manifest.get("environment") == "staging-only", "Manifest is not staging-only")
    require(len(ids) == 60 and len(set(ids)) == 60, "Manifest must contain 60 unique listing IDs")
    return ids


def _run_psql(database_url: str, *arguments: str) -> subprocess.CompletedProcess[str]:
    environment = os.environ.copy()
    environment["PGDATABASE"] = database_url
    environment["PGCONNECT_TIMEOUT"] = "10"
    try:
        return subprocess.run(
            ["psql", "--no-psqlrc", "--set", "ON_ERROR_STOP=1", *arguments],
            cwd=REPOSITORY_ROOT,
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )
    except FileNotFoundError as error:
        raise DatabaseOperationError("psql is not available on PATH") from error


def _execute(database_url: str, source_path: Path) -> None:
    media_base_url = os.environ.get("KWABOR_DEMO_MEDIA_BASE_URL", "").strip()
    require(re.fullmatch(r"https://[^\s?#]+/", media_base_url) is not None, "Demo media base URL is invalid")
    staging_url = os.environ.get("KWABOR_SUPABASE_URL", "").rstrip("/")
    expected_media_base_url = f"{staging_url}/storage/v1/object/public/kwabor-catalog-demo/"
    require(
        media_base_url == expected_media_base_url,
        "Demo media base URL does not identify the protected staging bucket",
    )
    source = source_path.read_text(encoding="utf-8")
    escaped_media_base_url = media_base_url.replace("'", "''")
    prefix = (
        "\\set ON_ERROR_STOP on\n"
        "set app.kwabor_environment = 'staging';\n"
        "set app.kwabor_demo_catalog_enabled = 'true';\n"
        f"set app.kwabor_demo_media_base_url = '{escaped_media_base_url}';\n"
    )
    with tempfile.NamedTemporaryFile(
        mode="w", encoding="utf-8", newline="\n", suffix=".sql", delete=False, dir=REPOSITORY_ROOT
    ) as temporary:
        temporary.write(prefix)
        temporary.write(source)
        temporary_path = Path(temporary.name)
    try:
        result = _run_psql(database_url, "--file", str(temporary_path))
    finally:
        temporary_path.unlink(missing_ok=True)
    if result.returncode != 0:
        safe_error = "\n".join(line for line in result.stderr.splitlines() if "postgres" not in line.lower())
        raise DatabaseOperationError(f"Demo catalog SQL failed: {safe_error[-2000:]}")


def _catalog_state(database_url: str) -> CatalogState:
    ids = _manifest_ids()
    values = ",".join("'" + listing_id + "'::uuid" for listing_id in ids)
    query = f"""
      select
        count(*)::text || '|' ||
        count(*) filter (where 'demo-kwabor' = any(listing.tags))::text || '|' ||
        count(*) filter (
          where 'demo-kwabor' = any(listing.tags)
            and listing.status = 'publie'
        )::text || '|' ||
        count(*) filter (
          where 'demo-kwabor' = any(listing.tags)
            and listing.status = 'archive'
        )::text || '|' ||
        (select count(*) from public.listing_media media where media.listing_id = any(array[{values}]::uuid[]))::text
      from public.listings listing
      where listing.id = any(array[{values}]::uuid[])
    """
    result = _run_psql(database_url, "--tuples-only", "--no-align", "--command", query)
    require(result.returncode == 0, "Unable to verify demo catalog state")
    raw_state = result.stdout.strip()
    parts = raw_state.split("|")
    require(
        len(parts) == 5 and all(re.fullmatch(r"0|[1-9][0-9]*", part) for part in parts),
        f"Invalid demo catalog state: {raw_state}",
    )
    return CatalogState(*(int(part) for part in parts))


def _verify(
    database_url: str,
    expected_published: int,
    state: CatalogState | None = None,
) -> CatalogState:
    state = state or _catalog_state(database_url)
    expected = CatalogState(
        60,
        60,
        expected_published,
        60 if expected_published == 0 else 0,
        180,
    )
    require(state == expected, f"Unexpected demo catalog state: {state}")
    if expected_published == 60:
        global_result = _run_psql(
            database_url,
            "--tuples-only",
            "--no-align",
            "--command",
            "select count(*) from public.listings where status = 'publie'",
        )
        require(global_result.returncode == 0, "Unable to verify the global published catalog count")
        require(
            global_result.stdout.strip() == "60",
            f"Staging contains non-demo published listings: {global_result.stdout.strip()} total",
        )
    return state


def _operation_result(
    operation: str,
    mode: str,
    before: CatalogState,
    after: CatalogState,
) -> dict[str, object]:
    return {
        "counts": {**before.counts("before"), **after.counts("after")},
        "kind": "demo-catalog-database-operation",
        "mode": mode,
        "operation": operation,
        "outcome": "succeeded",
        "schemaVersion": 1,
    }


def _write_result(path: Path, result: dict[str, object]) -> None:
    require(not path.is_symlink(), "Database result output must not be a symbolic link")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("publish", "verify", "rollback"))
    parser.add_argument("--confirm-rollback")
    parser.add_argument("--allow-absent-for-storage-rollback", action="store_true")
    parser.add_argument("--result-json", type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    require(
        not args.allow_absent_for_storage_rollback or args.command == "rollback",
        "Absent-state allowance is valid only for rollback",
    )
    if args.command == "rollback":
        require(args.confirm_rollback == ROLLBACK_CONFIRMATION, "Rollback confirmation is missing")
    database_url, project_ref = _validated_environment()
    _manifest_ids()
    before = _catalog_state(database_url)
    if args.command == "publish":
        require(
            before in {ABSENT_STATE, ARCHIVED_STATE, PUBLISHED_STATE},
            f"Refusing publish from drifted demo catalog state: {before}",
        )
        _execute(database_url, SEED_PATH)
        after = _verify(database_url, expected_published=60)
        mode = "published-and-verified"
    elif args.command == "rollback":
        if before == ABSENT_STATE:
            require(
                args.allow_absent_for_storage_rollback,
                "Standalone database rollback refuses an absent catalog state",
            )
            after = _catalog_state(database_url)
            require(after == ABSENT_STATE, "Absent rollback state changed unexpectedly")
            mode = "already-absent"
        elif before == ARCHIVED_STATE:
            _execute(database_url, ROLLBACK_PATH)
            after = _verify(database_url, expected_published=0)
            mode = "already-archived"
        else:
            require(before == PUBLISHED_STATE, f"Refusing rollback from drifted demo catalog state: {before}")
            _execute(database_url, ROLLBACK_PATH)
            after = _verify(database_url, expected_published=0)
            mode = "archived-exact-catalog"
    else:
        after = _verify(database_url, expected_published=60, state=before)
        before = after
        mode = "verified"
    result = _operation_result(args.command, mode, before, after)
    if args.result_json is not None:
        _write_result(args.result_json, result)
    print(f"OK {args.command} demo catalog on protected staging project {project_ref}")


if __name__ == "__main__":
    try:
        main()
    except DatabaseOperationError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error
