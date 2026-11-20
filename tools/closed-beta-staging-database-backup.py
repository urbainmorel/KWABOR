#!/usr/bin/env python3
"""Capture and qualify an encrypted logical backup of Kwabor staging.

The hosted database is only read. Plaintext exists exclusively below
``RUNNER_TEMP`` and the only uploadable data file is age encrypted. A backup is
qualified only after a real restore into a disposable Supabase PostgreSQL stack
on a GitHub-hosted runner and an exact logical fingerprint comparison.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.parse
from contextlib import AbstractContextManager
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence


EXPECTED_REPOSITORY = "urbainmorel/KWABOR"
EXPECTED_ENVIRONMENT = "staging"
EXPECTED_REF = "refs/heads/main"
EXPECTED_EVENT = "workflow_dispatch"
EXPECTED_CI_WORKFLOW = ".github/workflows/ci.yml"
EXPECTED_WORKFLOW = ".github/workflows/closed-beta-staging-database-backup.yml"
EXPECTED_SUPABASE_CLI_VERSION = "2.111.0"
EXPECTED_POSTGRES_MAJOR = 17
CAPTURE_CONFIRMATION = "CAPTURE-ENCRYPTED-STAGING-BACKUP"
TASK_ID = "B6.02"
CONTRIBUTES_TO = "G5"
GEL_FILENAME = "GEL-G5-STAGING-DATABASE-BACKUP.json"
GEL_HASH_FILENAME = f"{GEL_FILENAME}.sha256"
ARTIFACT_RETENTION_DAYS = 90
MAX_ESCROW_TEST_AGE_DAYS = 90
MAX_CIPHERTEXT_BYTES = 1_900_000_000
APPLICATION_SCHEMAS = ("app_private", "public", "supabase_migrations")
PAYLOAD_FILES = ("PAYLOAD-MANIFEST.json", "database.sql", "roles.sql")

SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
COMMIT_SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
PROJECT_REF_PATTERN = re.compile(r"[a-z0-9]{20}")
POSITIVE_INTEGER_PATTERN = re.compile(r"[1-9][0-9]*")
MIGRATION_VERSION_PATTERN = re.compile(r"[0-9]{14}")
POOLER_HOST_PATTERN = re.compile(r"[a-z0-9-]+\.pooler\.supabase\.com")
ENCODED_PASSWORD_PATTERN = re.compile(r"(?:[A-Za-z0-9._~-]|%[0-9A-Fa-f]{2})+")
AGE_RECIPIENT_PATTERN = re.compile(r"age1[0-9a-z]{40,100}")
SNAPSHOT_PATTERN = re.compile(r"[0-9A-Fa-f]+-[0-9A-Fa-f]+-[0-9]+")
DATABASE_URI_PATTERN = re.compile(r"(?i)postgres(?:ql)?://[^\s\"'<>]+")
JWT_PATTERN = re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b")

SENSITIVE_KEY_FRAGMENTS = {
    "authorization",
    "connectionstring",
    "databaseurl",
    "identity",
    "password",
    "privatekey",
    "secret",
    "servicerole",
    "token",
}
LIBPQ_OVERRIDE_KEYS = {
    "PGDATABASE",
    "PGCONNECT_TIMEOUT",
    "PGHOST",
    "PGHOSTADDR",
    "PGOPTIONS",
    "PGPASSFILE",
    "PGPASSWORD",
    "PGPORT",
    "PGSERVICE",
    "PGSERVICEFILE",
    "PGUSER",
}
BACKUP_SECRET_KEYS = {
    "KWABOR_STAGING_BACKUP_AGE_IDENTITY",
    "KWABOR_STAGING_DATABASE_URL",
}

EXPECTED_MANAGED_SCHEMA_TABLES = (
    ("auth", "audit_log_entries"),
    ("auth", "custom_oauth_providers"),
    ("auth", "flow_state"),
    ("auth", "identities"),
    ("auth", "instances"),
    ("auth", "mfa_amr_claims"),
    ("auth", "mfa_challenges"),
    ("auth", "mfa_factors"),
    ("auth", "oauth_authorizations"),
    ("auth", "oauth_client_states"),
    ("auth", "oauth_clients"),
    ("auth", "oauth_consents"),
    ("auth", "one_time_tokens"),
    ("auth", "refresh_tokens"),
    ("auth", "saml_providers"),
    ("auth", "saml_relay_states"),
    ("auth", "schema_migrations"),
    ("auth", "sessions"),
    ("auth", "sso_domains"),
    ("auth", "sso_providers"),
    ("auth", "users"),
    ("auth", "webauthn_challenges"),
    ("auth", "webauthn_credentials"),
    ("storage", "buckets"),
    ("storage", "buckets_analytics"),
    ("storage", "buckets_vectors"),
    ("storage", "migrations"),
    ("storage", "objects"),
    ("storage", "s3_multipart_uploads"),
    ("storage", "s3_multipart_uploads_parts"),
    ("storage", "vector_indexes"),
)

MANAGED_DATA_TABLES = (
    ("auth", "users", True),
    ("auth", "identities", False),
    ("auth", "sessions", False),
    ("auth", "refresh_tokens", False),
    ("auth", "mfa_factors", False),
    ("auth", "mfa_challenges", False),
    ("auth", "mfa_amr_claims", False),
    ("auth", "one_time_tokens", False),
    ("auth", "flow_state", False),
    ("auth", "audit_log_entries", False),
    ("auth", "saml_providers", False),
    ("auth", "saml_relay_states", False),
    ("auth", "sso_domains", False),
    ("auth", "sso_providers", False),
    ("auth", "oauth_clients", False),
    ("auth", "oauth_client_states", False),
    ("auth", "oauth_authorizations", False),
    ("auth", "oauth_consents", False),
    ("auth", "custom_oauth_providers", False),
    ("auth", "webauthn_credentials", False),
    ("auth", "webauthn_challenges", False),
    ("storage", "objects", True),
    ("storage", "buckets", True),
    ("storage", "buckets_analytics", False),
    ("storage", "buckets_vectors", False),
    ("storage", "s3_multipart_uploads", False),
    ("storage", "s3_multipart_uploads_parts", False),
    ("storage", "vector_indexes", False),
)


class BackupError(RuntimeError):
    """Stable and non-sensitive operational failure."""

    def __init__(self, code: str) -> None:
        if re.fullmatch(r"[A-Z][A-Z0-9_]{2,100}", code) is None:
            raise ValueError("Backup errors require stable non-sensitive codes")
        super().__init__(code)
        self.code = code


def require(condition: bool, code: str) -> None:
    if not condition:
        raise BackupError(code)


def elapsed_seconds_ceil(*, started: float, finished: float) -> int:
    elapsed = finished - started
    require(elapsed >= 0, "MONOTONIC_CLOCK_REGRESSION")
    return max(1, math.ceil(elapsed))


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def sha256_text(value: str) -> str:
    return sha256_bytes(value.encode("utf-8"))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json_bytes(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
    ).encode("utf-8")


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def format_timestamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def parse_timestamp(value: object, code: str) -> datetime:
    require(isinstance(value, str) and value.endswith("Z"), code)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise BackupError(code) from error
    require(parsed.tzinfo is not None, code)
    return parsed.astimezone(timezone.utc)


def positive_integer(value: object, code: str) -> int:
    if isinstance(value, bool):
        raise BackupError(code)
    if isinstance(value, int):
        parsed = value
    elif isinstance(value, str) and POSITIVE_INTEGER_PATTERN.fullmatch(value):
        parsed = int(value)
    else:
        raise BackupError(code)
    require(0 < parsed <= 9_223_372_036_854_775_807, code)
    return parsed


def bounded_integer(value: object, *, minimum: int, maximum: int, code: str) -> int:
    parsed = positive_integer(value, code)
    require(minimum <= parsed <= maximum, code)
    return parsed


def load_json(path: Path, code: str = "JSON_EVIDENCE_INVALID") -> dict[str, Any]:
    require(path.is_file() and not path.is_symlink(), code)
    require(path.stat().st_size <= 8 * 1024 * 1024, code)
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise BackupError(code) from error
    require(isinstance(value, dict), code)
    return value


def assert_safe_document(value: object) -> None:
    if isinstance(value, dict):
        for raw_key, child in value.items():
            require(isinstance(raw_key, str), "EVIDENCE_KEY_INVALID")
            normalized = re.sub(r"[^a-z]", "", raw_key.lower())
            require(
                not any(fragment in normalized for fragment in SENSITIVE_KEY_FRAGMENTS),
                "EVIDENCE_SENSITIVE_KEY",
            )
            assert_safe_document(child)
    elif isinstance(value, list):
        for child in value:
            assert_safe_document(child)
    elif isinstance(value, str):
        require(DATABASE_URI_PATTERN.search(value) is None, "EVIDENCE_DATABASE_URI")
        require(JWT_PATTERN.search(value) is None, "EVIDENCE_JWT")
        require("AGE-SECRET-KEY-" not in value, "EVIDENCE_AGE_PRIVATE_KEY")


def write_json(path: Path, value: Mapping[str, Any]) -> None:
    assert_safe_document(value)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(canonical_json_bytes(value))
    os.replace(temporary, path)


def write_sidecar(sidecar: Path, target: Path) -> None:
    sidecar.write_text(
        f"{sha256_file(target)}  {target.name}\n",
        encoding="ascii",
        newline="\n",
    )


def normalized_sql_sha256(path: Path) -> str:
    require(path.is_file() and not path.is_symlink(), "DUMP_FILE_MISSING")
    digest = hashlib.sha256()
    try:
        with path.open("r", encoding="utf-8", newline=None) as source:
            for raw_line in source:
                line = raw_line.rstrip("\r\n")
                if line.startswith("-- Dumped from database version"):
                    continue
                if line.startswith("-- Dumped by pg_dump version"):
                    continue
                if line.startswith("-- Started on ") or line.startswith("-- Completed on "):
                    continue
                if line.startswith("\\restrict ") or line.startswith("\\unrestrict "):
                    continue
                digest.update(line.encode("utf-8"))
                digest.update(b"\n")
    except (OSError, UnicodeDecodeError) as error:
        raise BackupError("DUMP_NOT_UTF8") from error
    return digest.hexdigest()


def sanitized_environment(*, read_only: bool) -> dict[str, str]:
    environment = {
        key: value
        for key, value in os.environ.items()
        if key not in LIBPQ_OVERRIDE_KEYS
        and key not in BACKUP_SECRET_KEYS
        and not key.startswith("SUPABASE_")
    }
    if read_only:
        environment["PGOPTIONS"] = (
            "-c default_transaction_read_only=on -c statement_timeout=900000 "
            "-c lock_timeout=30000"
        )
    environment["PGCONNECT_TIMEOUT"] = "15"
    return environment


def run_command(
    command: Sequence[str],
    *,
    cwd: Path,
    code: str,
    read_only: bool,
    timeout: int = 1800,
) -> str:
    require(bool(command), "COMMAND_EMPTY")
    try:
        result = subprocess.run(
            list(command),
            cwd=cwd,
            env=sanitized_environment(read_only=read_only),
            capture_output=True,
            check=False,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except (FileNotFoundError, OSError, subprocess.TimeoutExpired) as error:
        raise BackupError(code) from error
    require(result.returncode == 0, code)
    return result.stdout or ""


@dataclass(frozen=True)
class TargetAuthority:
    api_url: str
    project_ref: str
    project_ref_sha256: str
    production_project_ref: str
    endpoint_class: str
    database_host_sha256: str
    database_url: str = field(repr=False)

    def public_evidence(self) -> dict[str, Any]:
        return {
            "apiUrl": self.api_url,
            "databaseEndpointClass": self.endpoint_class,
            "databaseHostSha256": self.database_host_sha256,
            "environment": EXPECTED_ENVIRONMENT,
            "productionProjectRefSha256": sha256_text(self.production_project_ref),
            "projectRef": self.project_ref,
            "projectRefSha256": self.project_ref_sha256,
            "schemaVersion": 1,
            "tlsMode": "require",
        }


def validate_target_authority(
    *,
    environment: str,
    api_url: str,
    project_ref: str,
    production_project_ref: str,
    project_ref_sha256: str,
    database_url: str,
) -> TargetAuthority:
    require(environment == EXPECTED_ENVIRONMENT, "TARGET_ENVIRONMENT_INVALID")
    require(PROJECT_REF_PATTERN.fullmatch(project_ref) is not None, "STAGING_PROJECT_REF_INVALID")
    require(
        PROJECT_REF_PATTERN.fullmatch(production_project_ref) is not None,
        "PRODUCTION_PROJECT_REF_INVALID",
    )
    require(project_ref != production_project_ref, "PRODUCTION_TARGET_FORBIDDEN")
    require(api_url == f"https://{project_ref}.supabase.co", "TARGET_API_URL_INVALID")
    require(SHA256_PATTERN.fullmatch(project_ref_sha256) is not None, "PROJECT_REF_DIGEST_INVALID")
    require(sha256_text(project_ref) == project_ref_sha256, "PROJECT_REF_DIGEST_DRIFT")
    require(
        1 <= len(database_url) <= 4096
        and database_url == database_url.strip()
        and database_url.isascii()
        and database_url.count("@") == 1,
        "DATABASE_URL_INVALID",
    )
    try:
        parsed = urllib.parse.urlsplit(database_url)
        port = parsed.port
    except ValueError as error:
        raise BackupError("DATABASE_URL_INVALID") from error
    require(parsed.scheme == "postgresql", "DATABASE_URL_SCHEME_INVALID")
    require(parsed.path == "/postgres", "DATABASE_NAME_INVALID")
    require(parsed.query == "sslmode=require" and parsed.fragment == "", "DATABASE_TLS_REQUIRED")
    require(port == 5432, "DATABASE_PORT_INVALID")
    hostname = parsed.hostname
    require(isinstance(hostname, str) and hostname == hostname.lower(), "DATABASE_HOST_INVALID")
    require(parsed.netloc.count(":") >= 2, "DATABASE_USERINFO_INVALID")
    raw_userinfo = parsed.netloc.rsplit("@", maxsplit=1)[0]
    require(raw_userinfo.count(":") == 1, "DATABASE_USERINFO_INVALID")
    raw_username, raw_password = raw_userinfo.split(":", maxsplit=1)
    require(ENCODED_PASSWORD_PATTERN.fullmatch(raw_password) is not None, "DATABASE_PASSWORD_ENCODING_INVALID")
    try:
        username = urllib.parse.unquote(raw_username, errors="strict")
        password = urllib.parse.unquote(raw_password, errors="strict")
    except (UnicodeDecodeError, ValueError) as error:
        raise BackupError("DATABASE_USERINFO_INVALID") from error
    require(
        1 <= len(password) <= 1024
        and not any(character.isspace() or ord(character) < 32 for character in password),
        "DATABASE_PASSWORD_INVALID",
    )
    require(hostname != f"db.{project_ref}.supabase.co", "SESSION_POOLER_REQUIRED")
    require(POOLER_HOST_PATTERN.fullmatch(hostname) is not None, "DATABASE_HOST_INVALID")
    require(username == f"postgres.{project_ref}", "DATABASE_USERNAME_INVALID")
    endpoint_class = "session-pooler"
    return TargetAuthority(
        api_url=api_url,
        project_ref=project_ref,
        project_ref_sha256=project_ref_sha256,
        production_project_ref=production_project_ref,
        endpoint_class=endpoint_class,
        database_host_sha256=sha256_text(hostname),
        database_url=database_url,
    )


def validate_environment(document: Mapping[str, Any]) -> dict[str, Any]:
    require(document.get("name") == EXPECTED_ENVIRONMENT, "ENVIRONMENT_NAME_INVALID")
    require(document.get("can_admins_bypass") is False, "ENVIRONMENT_ADMIN_BYPASS_ENABLED")
    branch_policy = document.get("deployment_branch_policy")
    require(isinstance(branch_policy, dict), "ENVIRONMENT_BRANCH_POLICY_MISSING")
    require(
        branch_policy.get("protected_branches") is True
        and branch_policy.get("custom_branch_policies") is False,
        "ENVIRONMENT_BRANCH_POLICY_INVALID",
    )
    rules = document.get("protection_rules")
    require(isinstance(rules, list), "ENVIRONMENT_RULES_MISSING")
    reviewer_rules = [
        rule for rule in rules if isinstance(rule, dict) and rule.get("type") == "required_reviewers"
    ]
    branch_rules = [
        rule for rule in rules if isinstance(rule, dict) and rule.get("type") == "branch_policy"
    ]
    require(len(reviewer_rules) == 1 and len(branch_rules) == 1, "ENVIRONMENT_RULES_INVALID")
    reviewer_rule = reviewer_rules[0]
    reviewers = reviewer_rule.get("reviewers")
    require(
        reviewer_rule.get("prevent_self_review") is True
        and isinstance(reviewers, list)
        and bool(reviewers),
        "ENVIRONMENT_REVIEWER_INVALID",
    )
    for reviewer_link in reviewers:
        require(isinstance(reviewer_link, dict), "ENVIRONMENT_REVIEWER_INVALID")
        reviewer = reviewer_link.get("reviewer")
        require(
            reviewer_link.get("type") in {"User", "Team"}
            and isinstance(reviewer, dict)
            and positive_integer(reviewer.get("id"), "ENVIRONMENT_REVIEWER_INVALID") > 0,
            "ENVIRONMENT_REVIEWER_INVALID",
        )
    environment_id = positive_integer(document.get("id"), "ENVIRONMENT_RESOURCE_INVALID")
    updated_at = document.get("updated_at")
    parse_timestamp(updated_at, "ENVIRONMENT_TIMESTAMP_INVALID")
    return {
        "canAdminsBypass": False,
        "environmentId": environment_id,
        "name": EXPECTED_ENVIRONMENT,
        "preventSelfReview": True,
        "protectedBranchesOnly": True,
        "reviewerCount": len(reviewers),
        "schemaVersion": 1,
        "updatedAt": updated_at,
    }


def validate_ci_run(
    document: Mapping[str, Any],
    *,
    expected_run_id: int,
    expected_sha: str,
) -> dict[str, Any]:
    require(document.get("id") == expected_run_id, "CI_RUN_ID_DRIFT")
    require(document.get("head_sha") == expected_sha, "CI_SHA_DRIFT")
    require(document.get("head_branch") == "main", "CI_BRANCH_INVALID")
    require(document.get("event") == "push", "CI_EVENT_INVALID")
    require(document.get("path") == EXPECTED_CI_WORKFLOW, "CI_WORKFLOW_INVALID")
    require(document.get("status") == "completed", "CI_NOT_COMPLETED")
    require(document.get("conclusion") == "success", "CI_NOT_SUCCESSFUL")
    repository = document.get("repository")
    require(
        isinstance(repository, dict) and repository.get("full_name") == EXPECTED_REPOSITORY,
        "CI_REPOSITORY_DRIFT",
    )
    attempt = positive_integer(document.get("run_attempt"), "CI_RUN_ATTEMPT_INVALID")
    expected_url = f"https://github.com/{EXPECTED_REPOSITORY}/actions/runs/{expected_run_id}"
    require(document.get("html_url") == expected_url, "CI_RUN_URL_INVALID")
    return {
        "conclusion": "success",
        "event": "push",
        "headBranch": "main",
        "headSha": expected_sha,
        "runAttempt": attempt,
        "runId": expected_run_id,
        "runUrl": expected_url,
        "status": "completed",
        "workflowPath": EXPECTED_CI_WORKFLOW,
    }


def validate_request(args: argparse.Namespace) -> dict[str, Any]:
    require(args.operation in {"readiness", "backup"}, "OPERATION_INVALID")
    require(COMMIT_SHA_PATTERN.fullmatch(args.expected_sha) is not None, "EXPECTED_SHA_INVALID")
    require(os.environ.get("GITHUB_REPOSITORY") == EXPECTED_REPOSITORY, "REPOSITORY_NOT_CANONICAL")
    require(os.environ.get("GITHUB_EVENT_NAME") == EXPECTED_EVENT, "EVENT_NOT_MANUAL")
    require(os.environ.get("GITHUB_REF") == EXPECTED_REF, "REF_NOT_MAIN")
    require(os.environ.get("GITHUB_SHA") == args.expected_sha, "DISPATCH_SHA_DRIFT")
    require(os.environ.get("GITHUB_SERVER_URL") == "https://github.com", "GITHUB_SERVER_INVALID")
    require(
        os.environ.get("GITHUB_WORKFLOW_REF")
        == f"{EXPECTED_REPOSITORY}/{EXPECTED_WORKFLOW}@{EXPECTED_REF}",
        "WORKFLOW_IDENTITY_DRIFT",
    )
    run_id = positive_integer(os.environ.get("GITHUB_RUN_ID", ""), "RUN_ID_INVALID")
    run_attempt = positive_integer(os.environ.get("GITHUB_RUN_ATTEMPT", ""), "RUN_ATTEMPT_INVALID")
    ci_run_id = positive_integer(args.validated_ci_run_id, "CI_RUN_ID_INVALID")
    require(run_id != ci_run_id, "CI_RUN_ID_INVALID")
    if args.operation == "backup":
        require(args.capture_confirmation == CAPTURE_CONFIRMATION, "CAPTURE_CONFIRMATION_INVALID")
        require(os.environ.get("GITHUB_ACTIONS") == "true", "GITHUB_ACTIONS_REQUIRED")
        require(os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted", "GITHUB_HOSTED_RUNNER_REQUIRED")
    else:
        require(args.capture_confirmation == "", "CAPTURE_CONFIRMATION_UNEXPECTED")
    return {
        "expectedSha": args.expected_sha,
        "ref": EXPECTED_REF,
        "repository": EXPECTED_REPOSITORY,
        "runAttempt": run_attempt,
        "runId": run_id,
        "runUrl": f"https://github.com/{EXPECTED_REPOSITORY}/actions/runs/{run_id}",
        "validatedCiRunId": ci_run_id,
        "workflowPath": EXPECTED_WORKFLOW,
    }


def validate_age_and_escrow(now: datetime) -> dict[str, Any]:
    recipient = os.environ.get("KWABOR_STAGING_BACKUP_AGE_RECIPIENT", "").strip()
    require(AGE_RECIPIENT_PATTERN.fullmatch(recipient) is not None, "AGE_RECIPIENT_INVALID")
    identity = os.environ.get("KWABOR_STAGING_BACKUP_AGE_IDENTITY", "").strip()
    require("AGE-SECRET-KEY-" in identity and len(identity) <= 8192, "AGE_IDENTITY_INVALID")
    require(
        os.environ.get("KWABOR_STAGING_BACKUP_ESCROW_MODE", "") == "offline-two-person",
        "AGE_ESCROW_MODE_INVALID",
    )
    tested_at = parse_timestamp(
        os.environ.get("KWABOR_STAGING_BACKUP_ESCROW_TESTED_AT", ""),
        "AGE_ESCROW_TEST_INVALID",
    )
    valid_until = parse_timestamp(
        os.environ.get("KWABOR_STAGING_BACKUP_ESCROW_VALID_UNTIL", ""),
        "AGE_ESCROW_VALIDITY_INVALID",
    )
    require(tested_at <= now, "AGE_ESCROW_TEST_INVALID")
    require(now - tested_at <= timedelta(days=MAX_ESCROW_TEST_AGE_DAYS), "AGE_ESCROW_TEST_STALE")
    require(valid_until >= now + timedelta(days=ARTIFACT_RETENTION_DAYS), "AGE_ESCROW_VALIDITY_INVALID")
    recipient_sha256 = sha256_text(recipient)
    require(
        os.environ.get("KWABOR_STAGING_BACKUP_ESCROW_RECIPIENT_SHA256", "")
        == recipient_sha256,
        "AGE_ESCROW_RECIPIENT_DRIFT",
    )
    return {
        "identity": identity,
        "public": {
            "custodyMode": "offline-two-person",
            "maxRecoveryTestAgeDays": MAX_ESCROW_TEST_AGE_DAYS,
            "recipientSha256": recipient_sha256,
            "recoveryTestedAt": format_timestamp(tested_at),
            "status": "provisioned",
            "validUntil": format_timestamp(valid_until),
        },
        "recipient": recipient,
    }


def validate_age_pair(identity: str, recipient: str, *, root: Path) -> Path:
    identity_path = root / "age-identity.txt"
    identity_path.write_text(identity + "\n", encoding="ascii", newline="\n")
    os.chmod(identity_path, 0o600)
    derived = run_command(
        ["age-keygen", "-y", str(identity_path)],
        cwd=root,
        code="AGE_IDENTITY_DERIVATION_FAILED",
        read_only=False,
        timeout=30,
    ).strip()
    require(derived == recipient, "AGE_IDENTITY_RECIPIENT_MISMATCH")
    return identity_path


def constraint_inventory_sql() -> str:
    return (
        "(select coalesce(jsonb_agg(jsonb_build_object("
        "'constraintName',constraint_name,'constraintType',constraint_type,"
        "'deferred',initially_deferred,'deferrable',is_deferrable,"
        "'definition',definition,'namespace',namespace_name,"
        "'relationName',relation_name,'relationSchema',relation_schema,"
        "'validated',is_validated) order by namespace_name,relation_schema,"
        "relation_name,constraint_name,constraint_type,definition), '[]'::jsonb) "
        "from (select c.conname::text as constraint_name,c.contype::text as constraint_type,"
        "c.condeferred as initially_deferred,c.condeferrable as is_deferrable,"
        "pg_get_constraintdef(c.oid,true) as definition,n.nspname::text as namespace_name,"
        "coalesce(r.relname::text,'') as relation_name,"
        "coalesce(rn.nspname::text,'') as relation_schema,"
        "c.convalidated as is_validated from pg_catalog.pg_constraint c "
        "join pg_catalog.pg_namespace n on n.oid=c.connamespace "
        "left join pg_catalog.pg_class r on r.oid=c.conrelid "
        "left join pg_catalog.pg_namespace rn on rn.oid=r.relnamespace "
        "where n.nspname in ('public','app_private')) constraint_rows)"
    )


def validate_constraint_inventory(value: object, *, code: str) -> dict[str, Any]:
    require(isinstance(value, list) and 0 < len(value) <= 10_000, code)
    normalized: list[dict[str, Any]] = []
    expected_fields = {
        "constraintName",
        "constraintType",
        "deferred",
        "deferrable",
        "definition",
        "namespace",
        "relationName",
        "relationSchema",
        "validated",
    }
    for entry in value:
        require(isinstance(entry, dict) and set(entry) == expected_fields, code)
        require(
            isinstance(entry["constraintName"], str)
            and 1 <= len(entry["constraintName"]) <= 256
            and entry["constraintType"] in {"c", "f", "n", "p", "t", "u", "x"}
            and isinstance(entry["definition"], str)
            and 1 <= len(entry["definition"]) <= 1_048_576
            and entry["namespace"] in APPLICATION_SCHEMAS
            and isinstance(entry["relationName"], str)
            and len(entry["relationName"]) <= 256
            and isinstance(entry["relationSchema"], str)
            and entry["relationSchema"] in {"", *APPLICATION_SCHEMAS}
            and type(entry["deferred"]) is bool
            and type(entry["deferrable"]) is bool
            and type(entry["validated"]) is bool,
            code,
        )
        normalized.append(dict(entry))
    sort_key = lambda item: (
        item["namespace"],
        item["relationSchema"],
        item["relationName"],
        item["constraintName"],
        item["constraintType"],
        item["definition"],
    )
    require(normalized == sorted(normalized, key=sort_key), code)
    require(len({sort_key(item) for item in normalized}) == len(normalized), code)
    foreign_key_count = sum(item["constraintType"] == "f" for item in normalized)
    unvalidated_count = sum(not item["validated"] for item in normalized)
    require(foreign_key_count > 0 and unvalidated_count == 0, code)
    return {
        "constraintCount": len(normalized),
        "constraintInventorySha256": sha256_bytes(canonical_json_bytes(normalized)),
        "foreignKeyCount": foreign_key_count,
        "unvalidatedConstraintCount": unvalidated_count,
    }


def managed_data_query() -> str:
    values = ",".join(
        f"('{schema}','{table}',{'true' if required else 'false'})"
        for schema, table, required in MANAGED_DATA_TABLES
    )
    return (
        "with configured(schema_name,table_name,is_required) as (values "
        + values
        + "), counts as (select schema_name,table_name,is_required,"
        "to_regclass(format('%I.%I',schema_name,table_name)) is not null as relation_exists,"
        "case when to_regclass(format('%I.%I',schema_name,table_name)) is null then null::bigint "
        "else coalesce((xpath('/table/row/value/text()',query_to_xml(format("
        "'select count(*)::bigint as value from %I.%I',schema_name,table_name),"
        "true,false,'')))[1]::text::bigint,0::bigint) end as row_count from configured), "
        "migration_versions as (select version::text as version from "
        "supabase_migrations.schema_migrations order by version) select jsonb_build_object("
        "'managedSchemaTables',(select coalesce(jsonb_agg(jsonb_build_object("
        "'schema',n.nspname,'table',c.relname) order by n.nspname,c.relname),'[]'::jsonb) "
        "from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace "
        "where n.nspname in ('auth','storage') and c.relkind in ('r','p')),'managedTables',"
        "(select coalesce(jsonb_agg(jsonb_build_object("
        "'exists',relation_exists,'required',is_required,'rowCount',row_count,"
        "'schema',schema_name,'table',table_name) order by schema_name,table_name),'[]'::jsonb) "
        "from counts),'migrationVersions',(select coalesce(jsonb_agg(version order by version),"
        "'[]'::jsonb) from migration_versions),'constraintInventory',"
        + constraint_inventory_sql()
        + ",'postgresMajor',"
        "current_setting('server_version_num')::integer / 10000)::text;"
    )


def parse_snapshot_proof(raw: str) -> dict[str, Any]:
    json_lines = [line.strip() for line in raw.splitlines() if line.strip().startswith("{")]
    require(len(json_lines) == 1, "SNAPSHOT_PROOF_OUTPUT_INVALID")
    try:
        document = json.loads(json_lines[0])
    except json.JSONDecodeError as error:
        raise BackupError("SNAPSHOT_PROOF_OUTPUT_INVALID") from error
    require(isinstance(document, dict), "SNAPSHOT_PROOF_OUTPUT_INVALID")
    require(document.get("postgresMajor") == EXPECTED_POSTGRES_MAJOR, "POSTGRES_MAJOR_DRIFT")
    versions = document.get("migrationVersions")
    require(
        isinstance(versions, list)
        and all(isinstance(item, str) and MIGRATION_VERSION_PATTERN.fullmatch(item) for item in versions)
        and versions == sorted(set(versions)),
        "MIGRATION_HISTORY_INVALID",
    )
    managed_schema_tables = document.pop("managedSchemaTables", None)
    require(
        isinstance(managed_schema_tables, list)
        and all(
            isinstance(entry, dict)
            and set(entry) == {"schema", "table"}
            and isinstance(entry["schema"], str)
            and isinstance(entry["table"], str)
            for entry in managed_schema_tables
        ),
        "MANAGED_SCHEMA_CATALOG_INVALID",
    )
    observed_schema_tables = tuple(
        (entry["schema"], entry["table"]) for entry in managed_schema_tables
    )
    require(
        observed_schema_tables == EXPECTED_MANAGED_SCHEMA_TABLES,
        "MANAGED_SCHEMA_CATALOG_DRIFT",
    )
    document["managedSchemaTableCount"] = len(observed_schema_tables)
    document["managedSchemaTableSha256"] = sha256_bytes(
        canonical_json_bytes(managed_schema_tables)
    )
    managed = document.get("managedTables")
    require(isinstance(managed, list) and len(managed) == len(MANAGED_DATA_TABLES), "MANAGED_DATA_PROOF_INVALID")
    expected = {(schema, table): required for schema, table, required in MANAGED_DATA_TABLES}
    observed: set[tuple[str, str]] = set()
    for entry in managed:
        require(isinstance(entry, dict), "MANAGED_DATA_PROOF_INVALID")
        key = (entry.get("schema"), entry.get("table"))
        require(key in expected and key not in observed, "MANAGED_DATA_PROOF_INVALID")
        observed.add(key)
        exists = entry.get("exists")
        required = entry.get("required")
        row_count = entry.get("rowCount")
        require(required is expected[key] and isinstance(exists, bool), "MANAGED_DATA_PROOF_INVALID")
        require(not expected[key] or exists, "MANAGED_REQUIRED_TABLE_MISSING")
        require(exists, "MANAGED_SCHEMA_CATALOG_DRIFT")
        require(type(row_count) is int, "MANAGED_DATA_PROOF_INVALID")
        require(row_count == 0, "MANAGED_AUTH_STORAGE_NOT_EMPTY")
    require(observed == set(expected), "MANAGED_DATA_PROOF_INVALID")
    constraint_evidence = validate_constraint_inventory(
        document.pop("constraintInventory", None),
        code="SOURCE_CONSTRAINT_INVENTORY_INVALID",
    )
    document.update(constraint_evidence)
    document["managedDataEmpty"] = True
    document["schemaVersion"] = 2
    return document


class SnapshotLease(AbstractContextManager["SnapshotLease"]):
    def __init__(self, database_url: str, *, cwd: Path) -> None:
        self.database_url = database_url
        self.cwd = cwd
        self.process: subprocess.Popen[str] | None = None
        self.snapshot_id: str | None = None

    def __enter__(self) -> "SnapshotLease":
        try:
            self.process = subprocess.Popen(
                ["psql", self.database_url, "--no-psqlrc", "--quiet", "--no-align", "--tuples-only", "--set", "ON_ERROR_STOP=1"],
                cwd=self.cwd,
                env=sanitized_environment(read_only=True),
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
            )
        except (FileNotFoundError, OSError) as error:
            raise BackupError("SNAPSHOT_EXPORT_FAILED") from error
        require(self.process.stdin is not None and self.process.stdout is not None, "SNAPSHOT_EXPORT_FAILED")
        self.process.stdin.write(
            "begin transaction isolation level repeatable read read only;\n"
            "select pg_export_snapshot();\n"
            "\\echo KWABOR_SNAPSHOT_READY\n"
        )
        self.process.stdin.flush()
        candidates: list[str] = []
        for _ in range(20):
            line = self.process.stdout.readline()
            if line == "":
                break
            value = line.strip()
            if value == "KWABOR_SNAPSHOT_READY":
                break
            if SNAPSHOT_PATTERN.fullmatch(value):
                candidates.append(value)
        if len(candidates) != 1:
            self.process.kill()
            self.process.wait(timeout=10)
            raise BackupError("SNAPSHOT_EXPORT_FAILED")
        self.snapshot_id = candidates[0]
        return self

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> bool:
        process = self.process
        if process is None:
            return False
        try:
            if process.stdin is not None:
                process.stdin.write("rollback;\n\\quit\n")
                process.stdin.flush()
                process.stdin.close()
            process.wait(timeout=30)
        except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
            process.kill()
            process.wait(timeout=10)
        if exc_type is None:
            require(process.returncode == 0, "SNAPSHOT_EXPORT_SESSION_FAILED")
        return False


def run_snapshot_query(database_url: str, snapshot_id: str, *, cwd: Path) -> dict[str, Any]:
    require(SNAPSHOT_PATTERN.fullmatch(snapshot_id) is not None, "SNAPSHOT_ID_INVALID")
    sql = (
        "begin transaction isolation level repeatable read read only;"
        f"set transaction snapshot '{snapshot_id}';"
        + managed_data_query()
        + "commit;"
    )
    output = run_command(
        ["psql", database_url, "--no-psqlrc", "--quiet", "--no-align", "--tuples-only", "--set", "ON_ERROR_STOP=1", "--command", sql],
        cwd=cwd,
        code="SNAPSHOT_PROOF_QUERY_FAILED",
        read_only=True,
    )
    return parse_snapshot_proof(output)


def dump_database(database_url: str, snapshot_id: str | None, destination: Path, *, cwd: Path) -> None:
    command = [
        "pg_dump",
        "--dbname",
        database_url,
        "--format=plain",
        "--encoding=UTF8",
        "--no-owner",
        "--no-sync",
        "--quote-all-identifiers",
    ]
    if snapshot_id is not None:
        require(SNAPSHOT_PATTERN.fullmatch(snapshot_id) is not None, "SNAPSHOT_ID_INVALID")
        command.extend(["--snapshot", snapshot_id])
    for schema in APPLICATION_SCHEMAS:
        command.extend(["--schema", schema])
    command.extend(["--file", str(destination)])
    run_command(command, cwd=cwd, code="DATABASE_DUMP_FAILED", read_only=True)
    require(destination.is_file() and 0 < destination.stat().st_size, "DATABASE_DUMP_EMPTY")


def dump_roles(database_url: str, destination: Path, *, cwd: Path) -> None:
    run_command(
        ["supabase", "db", "dump", "--db-url", database_url, "--file", str(destination), "--role-only"],
        cwd=cwd,
        code="ROLES_DUMP_FAILED",
        read_only=True,
    )
    require(destination.is_file() and 0 < destination.stat().st_size, "ROLES_DUMP_EMPTY")


def create_archive(source: Path, destination: Path) -> None:
    require({path.name for path in source.iterdir()} == set(PAYLOAD_FILES), "PAYLOAD_SET_INVALID")
    with tarfile.open(destination, "w:gz", format=tarfile.PAX_FORMAT) as archive:
        for name in sorted(PAYLOAD_FILES):
            archive.add(source / name, arcname=name, recursive=False)


def extract_archive(source: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=False)
    with tarfile.open(source, "r:gz") as archive:
        members = archive.getmembers()
        require({member.name for member in members} == set(PAYLOAD_FILES), "ARCHIVE_CONTENT_INVALID")
        require(sum(member.size for member in members) <= 8 * 1024 * 1024 * 1024, "ARCHIVE_TOO_LARGE")
        for member in members:
            require(
                member.isfile()
                and not member.issym()
                and not member.islnk()
                and Path(member.name).name == member.name,
                "ARCHIVE_MEMBER_INVALID",
            )
            extracted = archive.extractfile(member)
            require(extracted is not None, "ARCHIVE_MEMBER_INVALID")
            with (destination / member.name).open("xb") as target:
                shutil.copyfileobj(extracted, target)


def configure_restore_stack(root: Path, project_id: str) -> None:
    run_command(["supabase", "init"], cwd=root, code="RESTORE_STACK_INIT_FAILED", read_only=False)
    config = root / "supabase" / "config.toml"
    source = config.read_text(encoding="utf-8")
    source, project_count = re.subn(
        r'(?m)^project_id\s*=\s*"[^"]+"$',
        f'project_id = "{project_id}"',
        source,
        count=1,
    )
    source, major_count = re.subn(
        r"(?m)^major_version\s*=\s*[0-9]+$",
        f"major_version = {EXPECTED_POSTGRES_MAJOR}",
        source,
        count=1,
    )
    require(project_count == 1 and major_count == 1, "RESTORE_STACK_CONFIG_INVALID")
    config.write_text(source, encoding="utf-8", newline="\n")


def local_database_url(root: Path) -> str:
    output = run_command(
        ["supabase", "status", "-o", "env"],
        cwd=root,
        code="RESTORE_STACK_STATUS_FAILED",
        read_only=False,
    )
    match = re.search(r'^DB_URL="([^"]+)"$', output, flags=re.MULTILINE)
    require(match is not None, "RESTORE_DATABASE_URL_MISSING")
    value = match.group(1)
    parsed = urllib.parse.urlsplit(value)
    require(parsed.hostname in {"127.0.0.1", "localhost", "::1"}, "RESTORE_TARGET_NOT_LOCAL")
    return value


def restore_payload(database_url: str, payload: Path, *, cwd: Path) -> dict[str, Any]:
    run_command(
        ["psql", database_url, "--no-psqlrc", "--set", "ON_ERROR_STOP=1", "--single-transaction", "--file", str(payload / "roles.sql")],
        cwd=cwd,
        code="ROLES_RESTORE_FAILED",
        read_only=False,
    )
    run_command(
        [
            "psql",
            database_url,
            "--no-psqlrc",
            "--set",
            "ON_ERROR_STOP=1",
            "--single-transaction",
            "--command",
            "drop schema if exists app_private cascade; drop schema if exists public cascade; drop schema if exists supabase_migrations cascade;",
            "--file",
            str(payload / "database.sql"),
        ],
        cwd=cwd,
        code="DATABASE_RESTORE_FAILED",
        read_only=False,
    )
    integrity_sql = (
        "select jsonb_build_object('constraintInventory',"
        + constraint_inventory_sql()
        + ",'sessionReplicationRole',current_setting('session_replication_role'))::text;"
    )
    output = run_command(
        ["psql", database_url, "--no-psqlrc", "--quiet", "--no-align", "--tuples-only", "--set", "ON_ERROR_STOP=1", "--command", integrity_sql],
        cwd=cwd,
        code="RESTORE_INTEGRITY_QUERY_FAILED",
        read_only=True,
    )
    lines = [line for line in output.splitlines() if line.strip().startswith("{")]
    require(len(lines) == 1, "RESTORE_INTEGRITY_OUTPUT_INVALID")
    try:
        evidence = json.loads(lines[0])
    except json.JSONDecodeError as error:
        raise BackupError("RESTORE_INTEGRITY_OUTPUT_INVALID") from error
    require(
        isinstance(evidence, dict) and evidence.get("sessionReplicationRole") == "origin",
        "RESTORE_INTEGRITY_FAILED",
    )
    constraint_evidence = validate_constraint_inventory(
        evidence.pop("constraintInventory", None),
        code="RESTORE_CONSTRAINT_INVENTORY_INVALID",
    )
    evidence.update(constraint_evidence)
    evidence["allConstraintsValidated"] = True
    evidence["sessionReplicationRoleUsed"] = False
    return evidence


def database_fingerprint(*, logical_sql_sha256: str, migration_sha256: str) -> str:
    require(SHA256_PATTERN.fullmatch(logical_sql_sha256) is not None, "LOGICAL_DIGEST_INVALID")
    require(SHA256_PATTERN.fullmatch(migration_sha256) is not None, "MIGRATION_DIGEST_INVALID")
    return sha256_bytes(
        canonical_json_bytes(
            {
                "logicalSqlNormalizedSha256": logical_sql_sha256,
                "migrationPrefixSha256": migration_sha256,
                "postgresMajor": EXPECTED_POSTGRES_MAJOR,
                "schemas": list(APPLICATION_SCHEMAS),
            }
        )
    )


def build_base_evidence(args: argparse.Namespace, now: datetime) -> dict[str, Any]:
    request = validate_request(args)
    ci = validate_ci_run(
        load_json(Path(args.ci_run_json), "CI_EVIDENCE_INVALID"),
        expected_run_id=request["validatedCiRunId"],
        expected_sha=args.expected_sha,
    )
    environment = validate_environment(
        load_json(Path(args.environment_json), "ENVIRONMENT_EVIDENCE_INVALID")
    )
    target = validate_target_authority(
        environment=os.environ.get("KWABOR_ENVIRONMENT", ""),
        api_url=os.environ.get("KWABOR_SUPABASE_URL", ""),
        project_ref=os.environ.get("KWABOR_SUPABASE_PROJECT_REF", ""),
        production_project_ref=os.environ.get("KWABOR_PRODUCTION_SUPABASE_PROJECT_REF", ""),
        project_ref_sha256=os.environ.get("KWABOR_STAGING_PROJECT_REF_SHA256", ""),
        database_url=os.environ.get("KWABOR_STAGING_DATABASE_URL", ""),
    )
    age = validate_age_and_escrow(now)
    max_rpo = bounded_integer(
        os.environ.get("KWABOR_STAGING_BACKUP_MAX_RPO_SECONDS", ""),
        minimum=60,
        maximum=3600,
        code="RPO_TARGET_INVALID",
    )
    max_rto = bounded_integer(
        os.environ.get("KWABOR_STAGING_BACKUP_MAX_RTO_SECONDS", ""),
        minimum=60,
        maximum=7200,
        code="RTO_TARGET_INVALID",
    )
    require(
        os.environ.get("KWABOR_STAGING_BACKUP_RETENTION_DAYS", "")
        == str(ARTIFACT_RETENTION_DAYS),
        "ARTIFACT_RETENTION_INVALID",
    )
    target_public = target.public_evidence()
    return {
        "age": age,
        "ci": ci,
        "environmentEvidence": environment,
        "maxRpoSeconds": max_rpo,
        "maxRtoSeconds": max_rto,
        "request": request,
        "target": target,
        "targetDigestSha256": sha256_bytes(canonical_json_bytes(target_public)),
        "targetPublic": target_public,
    }


def readiness_receipt(base: Mapping[str, Any], qualified_at: datetime) -> dict[str, Any]:
    request = base["request"]
    return {
        "artifactPolicy": {
            "expirationAuthority": "github-actions-artifact-api",
            "retentionDays": ARTIFACT_RETENTION_DAYS,
        },
        "ci": base["ci"],
        "contributesTo": CONTRIBUTES_TO,
        "environmentEvidence": base["environmentEvidence"],
        "errorCode": None,
        "expectedSha": request["expectedSha"],
        "operation": "readiness",
        "qualifiedAt": format_timestamp(qualified_at),
        "ref": EXPECTED_REF,
        "repository": EXPECTED_REPOSITORY,
        "restorable": False,
        "runAttempt": request["runAttempt"],
        "runId": request["runId"],
        "runUrl": request["runUrl"],
        "schemaVersion": 2,
        "status": "prepared_not_executable",
        "target": base["targetPublic"],
        "targetDigestSha256": base["targetDigestSha256"],
        "taskId": TASK_ID,
        "validatedCiRunId": request["validatedCiRunId"],
        "workflowPath": EXPECTED_WORKFLOW,
    }


def capture_and_restore(args: argparse.Namespace, base: Mapping[str, Any], evidence: Path) -> dict[str, Any]:
    request = base["request"]
    target: TargetAuthority = base["target"]
    age = base["age"]
    require(os.environ.get("KWABOR_STAGING_BACKUP_LIVE_ENABLED") == "true", "LIVE_BACKUP_DISABLED")
    capture_started = now_utc()
    capture_clock = time.monotonic()
    runner_temp = os.environ.get("RUNNER_TEMP", "")
    require(bool(runner_temp), "RUNNER_TEMP_MISSING")
    temporary_root = Path(tempfile.mkdtemp(prefix="kwabor-b602-", dir=runner_temp))
    os.chmod(temporary_root, 0o700)
    plaintext = temporary_root / "plaintext"
    plaintext.mkdir(mode=0o700)
    archive = temporary_root / "database-backup.tar.gz"
    decrypted = temporary_root / "database-restore.tar.gz"
    restored_payload = temporary_root / "restored-payload"
    restore_root = temporary_root / "restore-stack"
    restore_root.mkdir()
    project_id = f"kwabor-b602-{request['runId']}-{request['runAttempt']}"
    stack_started = False
    try:
        identity_path = validate_age_pair(
            age["identity"],
            age["recipient"],
            root=temporary_root,
        )
        pg_dump_version = run_command(
            ["pg_dump", "--version"],
            cwd=temporary_root,
            code="PG_DUMP_VERSION_FAILED",
            read_only=False,
            timeout=30,
        ).strip()
        require(re.search(r"\b17(?:\.|\b)", pg_dump_version) is not None, "PG_DUMP_MAJOR_DRIFT")
        cli_version = run_command(
            ["supabase", "--version"],
            cwd=temporary_root,
            code="SUPABASE_CLI_VERSION_FAILED",
            read_only=False,
            timeout=30,
        ).strip()
        require(cli_version == EXPECTED_SUPABASE_CLI_VERSION, "SUPABASE_CLI_VERSION_DRIFT")
        database_dump = plaintext / "database.sql"
        roles_dump = plaintext / "roles.sql"
        dump_roles(target.database_url, roles_dump, cwd=Path(args.workspace))
        snapshot_started = now_utc()
        with SnapshotLease(target.database_url, cwd=Path(args.workspace)) as lease:
            require(lease.snapshot_id is not None, "SNAPSHOT_EXPORT_FAILED")
            snapshot_proof = run_snapshot_query(
                target.database_url,
                lease.snapshot_id,
                cwd=Path(args.workspace),
            )
            dump_database(
                target.database_url,
                lease.snapshot_id,
                database_dump,
                cwd=Path(args.workspace),
            )
            snapshot_digest = sha256_text(lease.snapshot_id)
        versions = snapshot_proof["migrationVersions"]
        migration_payload = "\n".join(versions) + ("\n" if versions else "")
        migration_sha256 = sha256_text(migration_payload)
        source_sql_sha256 = normalized_sql_sha256(database_dump)
        source_fingerprint = database_fingerprint(
            logical_sql_sha256=source_sql_sha256,
            migration_sha256=migration_sha256,
        )
        payload_manifest = {
            "databaseDumpBytes": database_dump.stat().st_size,
            "databaseDumpSha256": sha256_file(database_dump),
            "databaseFingerprintSha256": source_fingerprint,
            "logicalSqlNormalizedSha256": source_sql_sha256,
            "managedDataProof": snapshot_proof,
            "managedDataProofSha256": sha256_bytes(canonical_json_bytes(snapshot_proof)),
            "migrationPrefixCount": len(versions),
            "migrationPrefixSha256": migration_sha256,
            "postgresMajor": EXPECTED_POSTGRES_MAJOR,
            "rolesDumpBytes": roles_dump.stat().st_size,
            "rolesDumpSha256": sha256_file(roles_dump),
            "schemaVersion": 2,
            "schemas": list(APPLICATION_SCHEMAS),
            "snapshotIdentifierSha256": snapshot_digest,
        }
        write_json(plaintext / "PAYLOAD-MANIFEST.json", payload_manifest)
        create_archive(plaintext, archive)
        ciphertext_name = (
            f"kwabor-staging-{target.project_ref_sha256[:16]}-"
            f"{source_fingerprint}.tar.gz.age"
        )
        ciphertext = evidence / ciphertext_name
        run_command(
            ["age", "--encrypt", "--recipient", age["recipient"], "--output", str(ciphertext), str(archive)],
            cwd=temporary_root,
            code="AGE_ENCRYPTION_FAILED",
            read_only=False,
        )
        require(
            ciphertext.is_file() and 0 < ciphertext.stat().st_size <= MAX_CIPHERTEXT_BYTES,
            "CIPHERTEXT_SIZE_INVALID",
        )
        shutil.rmtree(plaintext)
        archive.unlink()
        restore_started = time.monotonic()
        run_command(
            ["age", "--decrypt", "--identity", str(identity_path), "--output", str(decrypted), str(ciphertext)],
            cwd=temporary_root,
            code="AGE_DECRYPTION_FAILED",
            read_only=False,
        )
        extract_archive(decrypted, restored_payload)
        restored_manifest = load_json(restored_payload / "PAYLOAD-MANIFEST.json", "PAYLOAD_MANIFEST_INVALID")
        require(restored_manifest == payload_manifest, "PAYLOAD_MANIFEST_DRIFT")
        require(
            sha256_file(restored_payload / "database.sql") == payload_manifest["databaseDumpSha256"]
            and sha256_file(restored_payload / "roles.sql") == payload_manifest["rolesDumpSha256"],
            "DECRYPTED_PAYLOAD_DRIFT",
        )
        configure_restore_stack(restore_root, project_id)
        run_command(
            ["supabase", "db", "start"],
            cwd=restore_root,
            code="RESTORE_STACK_START_FAILED",
            read_only=False,
        )
        stack_started = True
        local_url = local_database_url(restore_root)
        integrity = restore_payload(local_url, restored_payload, cwd=restore_root)
        require(
            integrity["constraintCount"] == snapshot_proof["constraintCount"]
            and integrity["foreignKeyCount"] == snapshot_proof["foreignKeyCount"]
            and integrity["constraintInventorySha256"]
            == snapshot_proof["constraintInventorySha256"],
            "RESTORED_CONSTRAINT_INVENTORY_DRIFT",
        )
        restored_dump = temporary_root / "restored-database.sql"
        dump_database(local_url, None, restored_dump, cwd=restore_root)
        restored_sql_sha256 = normalized_sql_sha256(restored_dump)
        restored_fingerprint = database_fingerprint(
            logical_sql_sha256=restored_sql_sha256,
            migration_sha256=migration_sha256,
        )
        require(restored_sql_sha256 == source_sql_sha256, "RESTORED_LOGICAL_SQL_DRIFT")
        require(restored_fingerprint == source_fingerprint, "RESTORED_FINGERPRINT_DRIFT")
        rto_seconds = elapsed_seconds_ceil(
            started=restore_started,
            finished=time.monotonic(),
        )
        require(rto_seconds <= base["maxRtoSeconds"], "RTO_TARGET_EXCEEDED")
        qualified_at = now_utc()
        capture_seconds = elapsed_seconds_ceil(
            started=capture_clock,
            finished=time.monotonic(),
        )
        require(capture_seconds <= base["maxRpoSeconds"], "RPO_TARGET_EXCEEDED")
        # The usable window is anchored to the database snapshot, not to the
        # later restore qualification. Otherwise capture/restore time would be
        # granted a second RPO window and an apply could consume data nearly
        # twice as old as the approved objective.
        apply_valid_until = snapshot_started + timedelta(seconds=base["maxRpoSeconds"])
        require(qualified_at < apply_valid_until, "RPO_APPLY_WINDOW_EXHAUSTED")
        artifact_estimated_expires_at = qualified_at + timedelta(days=ARTIFACT_RETENTION_DAYS)
        expected_artifact_name = (
            f"kwabor-gel-g5-staging-database-backup-{args.expected_sha}-"
            f"{request['runAttempt']}"
        )
        receipt = {
            "artifactPolicy": {
                "actualDigestValidatedByConsumer": True,
                "estimatedExpiresAt": format_timestamp(artifact_estimated_expires_at),
                "expectedName": expected_artifact_name,
                "expirationAuthority": "github-actions-artifact-api",
                "retentionDays": ARTIFACT_RETENTION_DAYS,
            },
            "ci": base["ci"],
            "contributesTo": CONTRIBUTES_TO,
            "databaseScope": {
                "dumpModes": ["roles", "single-consistent-application-dump"],
                "managedAuthStorageDataIncluded": False,
                "managedAuthStorageEmpty": True,
                "schemas": list(APPLICATION_SCHEMAS),
                "type": "targeted-logical",
            },
            "encryption": {
                "algorithm": "age-x25519",
                "ciphertextBytes": ciphertext.stat().st_size,
                "ciphertextFileName": ciphertext_name,
                "ciphertextSha256": sha256_file(ciphertext),
                "encryptedBeforeArtifactBoundary": True,
                "plaintextArtifactCount": 0,
                "recipientSha256": age["public"]["recipientSha256"],
            },
            "environmentEvidence": base["environmentEvidence"],
            "errorCode": None,
            "expectedSha": request["expectedSha"],
            "operation": "backup",
            "qualifiedAt": format_timestamp(qualified_at),
            "ref": EXPECTED_REF,
            "repository": EXPECTED_REPOSITORY,
            "restorable": True,
            "restore": {
                "allConstraintsValidated": integrity["allConstraintsValidated"],
                "constraintCount": integrity["constraintCount"],
                "constraintInventorySha256": integrity["constraintInventorySha256"],
                "databaseFingerprintSha256": restored_fingerprint,
                "executionBoundary": "github-actions-disposable-supabase",
                "fingerprintMatch": True,
                "foreignKeyCount": integrity["foreignKeyCount"],
                "logicalSqlNormalizedSha256": restored_sql_sha256,
                "sessionReplicationRoleUsed": False,
                "unvalidatedConstraintCount": 0,
                "verified": True,
            },
            "rpo": {
                "applyValidUntil": format_timestamp(apply_valid_until),
                "captureSeconds": capture_seconds,
                "maxSeconds": base["maxRpoSeconds"],
                "met": True,
            },
            "rto": {
                "maxSeconds": base["maxRtoSeconds"],
                "met": True,
                "observedSeconds": rto_seconds,
            },
            "runAttempt": request["runAttempt"],
            "runId": request["runId"],
            "runUrl": request["runUrl"],
            "schemaVersion": 2,
            "snapshot": {
                "applicationDumpAndManagedProofShareSnapshot": True,
                "exportedByDedicatedSession": True,
                "identifierSha256": snapshot_digest,
                "isolation": "repeatable-read-read-only",
                "mechanism": "pg-export-snapshot",
                "snapshotEstablishedAt": format_timestamp(snapshot_started),
            },
            "source": {
                "databaseFingerprintSha256": source_fingerprint,
                "logicalSqlNormalizedSha256": source_sql_sha256,
                "managedDataProof": snapshot_proof,
                "managedDataProofSha256": payload_manifest["managedDataProofSha256"],
                "migrationPrefixCount": len(versions),
                "migrationPrefixSha256": migration_sha256,
                "postgresMajor": EXPECTED_POSTGRES_MAJOR,
            },
            "status": "succeeded",
            "target": base["targetPublic"],
            "targetDigestSha256": base["targetDigestSha256"],
            "taskId": TASK_ID,
            "validatedCiRunId": request["validatedCiRunId"],
            "workflowPath": EXPECTED_WORKFLOW,
            "ageEscrow": age["public"],
        }
        assert_safe_document(receipt)
        return receipt
    finally:
        if stack_started:
            try:
                run_command(
                    ["supabase", "stop", "--project-id", project_id, "--no-backup"],
                    cwd=restore_root,
                    code="RESTORE_STACK_STOP_FAILED",
                    read_only=False,
                    timeout=300,
                )
            except BackupError:
                print("WARNING B6.02 cleanup: RESTORE_STACK_STOP_FAILED", file=sys.stderr)
        shutil.rmtree(temporary_root, ignore_errors=True)


def failure_receipt(args: argparse.Namespace, error_code: str) -> dict[str, Any]:
    expected_sha = args.expected_sha if COMMIT_SHA_PATTERN.fullmatch(args.expected_sha) else None
    run_id_raw = os.environ.get("GITHUB_RUN_ID", "")
    run_attempt_raw = os.environ.get("GITHUB_RUN_ATTEMPT", "")
    run_id = int(run_id_raw) if POSITIVE_INTEGER_PATTERN.fullmatch(run_id_raw) else None
    run_attempt = int(run_attempt_raw) if POSITIVE_INTEGER_PATTERN.fullmatch(run_attempt_raw) else None
    operation = args.operation if args.operation in {"readiness", "backup"} else "unknown"
    return {
        "contributesTo": CONTRIBUTES_TO,
        "errorCode": error_code,
        "expectedSha": expected_sha,
        "operation": operation,
        "qualifiedAt": format_timestamp(now_utc()),
        "ref": EXPECTED_REF,
        "repository": EXPECTED_REPOSITORY,
        "restorable": False,
        "runAttempt": run_attempt,
        "runId": run_id,
        "runUrl": (
            f"https://github.com/{EXPECTED_REPOSITORY}/actions/runs/{run_id}"
            if run_id is not None
            else None
        ),
        "schemaVersion": 2,
        "status": "failed",
        "taskId": TASK_ID,
        "workflowPath": EXPECTED_WORKFLOW,
    }


def execute(args: argparse.Namespace) -> None:
    repository_root = Path(__file__).resolve().parents[1]
    workspace = Path(args.workspace).resolve()
    evidence = Path(args.evidence_directory).resolve()
    require(workspace == repository_root, "WORKSPACE_DRIFT")
    evidence.mkdir(parents=True, exist_ok=True)
    require(evidence.is_dir() and not evidence.is_symlink(), "EVIDENCE_DIRECTORY_INVALID")
    require(not any(evidence.iterdir()), "EVIDENCE_DIRECTORY_NOT_EMPTY")
    try:
        qualified_at = now_utc()
        base = build_base_evidence(args, qualified_at)
        temporary_root = Path(tempfile.mkdtemp(prefix="kwabor-b602-readiness-", dir=os.environ.get("RUNNER_TEMP")))
        try:
            validate_age_pair(
                base["age"]["identity"],
                base["age"]["recipient"],
                root=temporary_root,
            )
        finally:
            shutil.rmtree(temporary_root, ignore_errors=True)
        if args.operation == "readiness":
            receipt = readiness_receipt(base, qualified_at)
        else:
            receipt = capture_and_restore(args, base, evidence)
        write_json(evidence / GEL_FILENAME, receipt)
        write_sidecar(evidence / GEL_HASH_FILENAME, evidence / GEL_FILENAME)
    except BackupError as error:
        if not (evidence / GEL_FILENAME).exists():
            write_json(evidence / GEL_FILENAME, failure_receipt(args, error.code))
            write_sidecar(evidence / GEL_HASH_FILENAME, evidence / GEL_FILENAME)
        raise
    print(f"OK B6.02 operation={args.operation} receipt={GEL_FILENAME}")


def emit_failure(args: argparse.Namespace) -> None:
    require(
        re.fullmatch(r"[A-Z][A-Z0-9_]{2,100}", args.failure_code or "") is not None,
        "FAILURE_CODE_INVALID",
    )
    evidence = Path(args.evidence_directory).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    require(evidence.is_dir() and not evidence.is_symlink(), "EVIDENCE_DIRECTORY_INVALID")
    receipt_path = evidence / GEL_FILENAME
    if not receipt_path.exists():
        write_json(receipt_path, failure_receipt(args, args.failure_code))
        write_sidecar(evidence / GEL_HASH_FILENAME, receipt_path)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Fail-closed staging backup producer B6.02")
    parser.add_argument("--operation", choices=("readiness", "backup"), required=True)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--validated-ci-run-id", required=True)
    parser.add_argument("--capture-confirmation", default="")
    parser.add_argument("--ci-run-json", default="")
    parser.add_argument("--environment-json", default="")
    parser.add_argument("--workspace", required=True)
    parser.add_argument("--evidence-directory", required=True)
    parser.add_argument("--failure-code", default="")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.failure_code:
            emit_failure(args)
        else:
            execute(args)
    except BackupError as error:
        print(f"ERROR closed-beta staging backup: {error.code}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
