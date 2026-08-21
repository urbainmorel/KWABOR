#!/usr/bin/env python3
"""Create and qualify an encrypted, restorable staging database backup.

Remote database access is read-only. The only persistent write performed by the
``backup`` operation is publication of encrypted assets to an independently
configured GitHub immutable-release vault. Docker is used only by the future
GitHub Actions runner; unit tests exercise pure validation helpers.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.parse
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence


EXPECTED_REPOSITORY = "urbainmorel/KWABOR"
EXPECTED_ENVIRONMENT = "staging"
EXPECTED_REF = "refs/heads/main"
EXPECTED_EVENT = "workflow_dispatch"
EXPECTED_CI_WORKFLOW = ".github/workflows/ci.yml"
EXPECTED_WORKFLOW = ".github/workflows/closed-beta-staging-database-backup.yml"
EXPECTED_CLI_VERSION = "2.111.0"
EXPECTED_POSTGRES_MAJOR = 17
CAPTURE_CONFIRMATION = "CAPTURE-ENCRYPTED-STAGING-BACKUP"
TASK_ID = "B6.02"
CONTRIBUTES_TO = "G5"
GEL_FILENAME = "GEL-G5-STAGING-DATABASE-BACKUP.json"
GEL_HASH_FILENAME = f"{GEL_FILENAME}.sha256"
PREFLIGHT_FILENAME = "BACKUP-PREFLIGHT.json"
CAPTURE_FILENAME = "BACKUP-CAPTURE.json"
VAULT_MANIFEST_FILENAME = "VAULT-G5-STAGING-DATABASE-BACKUP.json"
VAULT_MANIFEST_HASH_FILENAME = f"{VAULT_MANIFEST_FILENAME}.sha256"
ARTIFACT_RETENTION_DAYS = 90
MINIMUM_OFFSITE_RETENTION_DAYS = 180
MAXIMUM_OFFSITE_RETENTION_DAYS = 3650
MINIMUM_RPO_SECONDS = 60
MAXIMUM_RPO_SECONDS = 3600
MINIMUM_RTO_SECONDS = 60
MAXIMUM_RTO_SECONDS = 7200
MAX_ESCROW_TEST_AGE_DAYS = 90
MAX_ENCRYPTED_BUNDLE_BYTES = 1_900_000_000
APPLICATION_SCHEMAS = ("public", "app_private")
DUMP_MODES = ("roles", "schema", "data", "migration_schema", "migration_data")

SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
COMMIT_SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
PROJECT_REF_PATTERN = re.compile(r"[a-z0-9]{20}")
POSITIVE_INTEGER_PATTERN = re.compile(r"[1-9][0-9]*")
MIGRATION_VERSION_PATTERN = re.compile(r"[0-9]{14}")
POOLER_HOST_PATTERN = re.compile(r"[a-z0-9-]+\.pooler\.supabase\.com")
ENCODED_PASSWORD_PATTERN = re.compile(r"(?:[A-Za-z0-9._~-]|%[0-9A-Fa-f]{2})+")
AGE_RECIPIENT_PATTERN = re.compile(r"age1[0-9a-z]{40,100}")
AGE_IDENTITY_PATTERN = re.compile(r"AGE-SECRET-KEY-1[0-9A-Z]{40,100}")
REPOSITORY_PATTERN = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
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
SUPABASE_OVERRIDE_KEYS = {
    "SUPABASE_ACCESS_TOKEN",
    "SUPABASE_DB_PASSWORD",
    "SUPABASE_PROJECT_ID",
}
BACKUP_SECRET_KEYS = {
    "GITHUB_TOKEN",
    "GH_TOKEN",
    "KWABOR_BACKUP_VAULT_TOKEN",
    "KWABOR_STAGING_BACKUP_AGE_IDENTITY",
    "KWABOR_STAGING_DATABASE_URL",
}
SECRET_ENVIRONMENT_FRAGMENTS = ("CREDENTIAL", "PASSWORD", "SECRET", "TOKEN")


class BackupError(RuntimeError):
    """A stable, non-sensitive operational failure."""

    def __init__(self, code: str) -> None:
        if re.fullmatch(r"[A-Z][A-Z0-9_]{2,100}", code) is None:
            raise ValueError("Backup errors must use stable non-sensitive codes")
        super().__init__(code)
        self.code = code


def require(condition: bool, code: str) -> None:
    if not condition:
        raise BackupError(code)


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


def canonical_json_bytes(document: Mapping[str, Any]) -> bytes:
    return (
        json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
    ).encode("utf-8")


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def utc_now() -> str:
    return format_timestamp(now_utc())


def parse_timestamp(value: object, code: str) -> datetime:
    require(isinstance(value, str) and value.endswith("Z"), code)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise BackupError(code) from error
    require(parsed.tzinfo is not None, code)
    return parsed.astimezone(timezone.utc)


def format_timestamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def positive_integer(value: object, code: str) -> int:
    if isinstance(value, bool):
        raise BackupError(code)
    if isinstance(value, int):
        parsed = value
    elif isinstance(value, str) and POSITIVE_INTEGER_PATTERN.fullmatch(value):
        parsed = int(value)
    else:
        raise BackupError(code)
    require(parsed > 0, code)
    return parsed


def load_json(path: Path, code: str = "JSON_INVALID") -> dict[str, Any]:
    require(path.is_file() and not path.is_symlink(), code)
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise BackupError(code) from error
    require(isinstance(value, dict), code)
    return value


def assert_safe_document(value: object, *, path: str = "root") -> None:
    if isinstance(value, dict):
        for raw_key, item in value.items():
            require(isinstance(raw_key, str), "EVIDENCE_KEY_INVALID")
            normalized = re.sub(r"[^a-z]", "", raw_key.lower())
            require(
                not any(fragment in normalized for fragment in SENSITIVE_KEY_FRAGMENTS),
                "EVIDENCE_SENSITIVE_KEY",
            )
            assert_safe_document(item, path=f"{path}.{raw_key}")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            assert_safe_document(item, path=f"{path}[{index}]")
        return
    if isinstance(value, str):
        require(DATABASE_URI_PATTERN.search(value) is None, "EVIDENCE_DATABASE_URI")
        require(JWT_PATTERN.search(value) is None, "EVIDENCE_JWT")
        require("AGE-SECRET-KEY-" not in value, "EVIDENCE_AGE_IDENTITY")
        require("%40" not in value.lower() or "postgres" not in value.lower(), "EVIDENCE_CREDENTIAL")


def write_json(path: Path, document: Mapping[str, Any]) -> None:
    assert_safe_document(document)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(canonical_json_bytes(document))
    os.replace(temporary, path)


def write_sidecar(path: Path, target: Path) -> None:
    path.write_text(f"{sha256_file(target)}  {target.name}\n", encoding="ascii", newline="\n")


def validate_sidecar(path: Path, target: Path, code: str) -> None:
    require(path.is_file() and not path.is_symlink(), code)
    try:
        value = path.read_text(encoding="ascii")
    except (OSError, UnicodeDecodeError) as error:
        raise BackupError(code) from error
    require(value == f"{sha256_file(target)}  {target.name}\n", code)


def normalized_sql_bytes(payload: bytes) -> bytes:
    """Remove pg_dump invocation noise while preserving every SQL statement."""
    try:
        text = payload.decode("utf-8")
    except UnicodeDecodeError as error:
        raise BackupError("DUMP_NOT_UTF8") from error
    normalized: list[str] = []
    for raw_line in text.splitlines():
        if raw_line.startswith("-- Dumped from database version"):
            continue
        if raw_line.startswith("-- Dumped by pg_dump version"):
            continue
        if raw_line.startswith("-- Started on ") or raw_line.startswith("-- Completed on "):
            continue
        if raw_line.startswith("\\restrict ") or raw_line.startswith("\\unrestrict "):
            continue
        normalized.append(raw_line)
    return ("\n".join(normalized) + "\n").encode("utf-8")


def dump_evidence(path: Path) -> dict[str, Any]:
    require(path.is_file() and not path.is_symlink(), "DUMP_FILE_MISSING")
    require(0 < path.stat().st_size <= 8 * 1024 * 1024 * 1024, "DUMP_FILE_SIZE_INVALID")
    payload = path.read_bytes()
    return {
        "bytes": len(payload),
        "sha256": sha256_bytes(payload),
        "normalizedSha256": sha256_bytes(normalized_sql_bytes(payload)),
    }


def validate_ci_run(
    document: Mapping[str, Any],
    *,
    expected_run_id: int,
    expected_sha: str,
    repository_id: int,
) -> dict[str, Any]:
    require(document.get("id") == expected_run_id, "CI_RUN_ID_DRIFT")
    require(document.get("head_sha") == expected_sha, "CI_SHA_DRIFT")
    require(document.get("head_branch") == "main", "CI_BRANCH_INVALID")
    require(document.get("event") == "push", "CI_EVENT_INVALID")
    require(document.get("path") == EXPECTED_CI_WORKFLOW, "CI_WORKFLOW_INVALID")
    require(document.get("status") == "completed", "CI_NOT_COMPLETED")
    require(document.get("conclusion") == "success", "CI_NOT_SUCCESSFUL")
    repository = document.get("repository")
    require(isinstance(repository, dict), "CI_REPOSITORY_MISSING")
    require(repository.get("id") == repository_id, "CI_REPOSITORY_ID_DRIFT")
    require(repository.get("full_name") == EXPECTED_REPOSITORY, "CI_REPOSITORY_DRIFT")
    attempt = positive_integer(document.get("run_attempt"), "CI_RUN_ATTEMPT_INVALID")
    created_at = parse_timestamp(document.get("created_at"), "CI_CREATED_AT_INVALID")
    updated_at = parse_timestamp(document.get("updated_at"), "CI_UPDATED_AT_INVALID")
    require(updated_at >= created_at, "CI_TIMELINE_INVALID")
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


def validate_environment(document: Mapping[str, Any]) -> dict[str, Any]:
    require(document.get("name") == EXPECTED_ENVIRONMENT, "ENVIRONMENT_NAME_INVALID")
    require(document.get("can_admins_bypass") is False, "ENVIRONMENT_ADMIN_BYPASS_ENABLED")
    branch_policy = document.get("deployment_branch_policy")
    require(isinstance(branch_policy, dict), "ENVIRONMENT_BRANCH_POLICY_MISSING")
    require(branch_policy.get("protected_branches") is True, "ENVIRONMENT_BRANCH_POLICY_INVALID")
    require(branch_policy.get("custom_branch_policies") is False, "ENVIRONMENT_BRANCH_POLICY_INVALID")
    rules = document.get("protection_rules")
    require(isinstance(rules, list), "ENVIRONMENT_RULES_MISSING")
    reviewer_rules = [
        rule
        for rule in rules
        if isinstance(rule, dict) and rule.get("type") == "required_reviewers"
    ]
    branch_rules = [
        rule for rule in rules if isinstance(rule, dict) and rule.get("type") == "branch_policy"
    ]
    require(len(reviewer_rules) == 1, "ENVIRONMENT_REVIEWER_RULE_INVALID")
    require(len(branch_rules) == 1, "ENVIRONMENT_BRANCH_RULE_INVALID")
    reviewer_rule = reviewer_rules[0]
    reviewers = reviewer_rule.get("reviewers")
    require(isinstance(reviewers, list) and bool(reviewers), "ENVIRONMENT_REVIEWER_MISSING")
    require(reviewer_rule.get("prevent_self_review") is True, "ENVIRONMENT_SELF_REVIEW_ENABLED")
    reviewer_types: set[str] = set()
    for reviewer_link in reviewers:
        require(isinstance(reviewer_link, dict), "ENVIRONMENT_REVIEWER_INVALID")
        reviewer_type = reviewer_link.get("type")
        reviewer = reviewer_link.get("reviewer")
        require(reviewer_type in {"User", "Team"}, "ENVIRONMENT_REVIEWER_INVALID")
        require(
            isinstance(reviewer, dict)
            and isinstance(reviewer.get("id"), int)
            and reviewer["id"] > 0,
            "ENVIRONMENT_REVIEWER_INVALID",
        )
        reviewer_types.add(str(reviewer_type))
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
        "reviewerTypes": sorted(reviewer_types),
        "schemaVersion": 1,
        "updatedAt": updated_at,
    }


@dataclass(frozen=True)
class TargetAuthority:
    api_url: str
    project_ref: str
    project_ref_sha256: str
    production_project_ref: str
    database_endpoint_class: str
    database_host_sha256: str
    database_url: str = field(repr=False)

    def public_evidence(self) -> dict[str, Any]:
        return {
            "apiUrl": self.api_url,
            "databaseEndpointClass": self.database_endpoint_class,
            "databaseHostSha256": self.database_host_sha256,
            "environment": EXPECTED_ENVIRONMENT,
            "productionProjectRefSha256": sha256_text(self.production_project_ref),
            "projectRef": self.project_ref,
            "projectRefSha256": self.project_ref_sha256,
            "schemaVersion": 1,
        }


def _parse_database_url(database_url: str, project_ref: str) -> tuple[str, str]:
    require(1 <= len(database_url) <= 4096, "DATABASE_URL_INVALID")
    require(database_url == database_url.strip() and database_url.isascii(), "DATABASE_URL_INVALID")
    require(not any(character.isspace() or ord(character) < 32 for character in database_url), "DATABASE_URL_INVALID")
    require(database_url.count("@") == 1, "DATABASE_URL_INVALID")
    require("?" not in database_url and "#" not in database_url, "DATABASE_URL_OVERRIDE_FORBIDDEN")
    try:
        parsed = urllib.parse.urlsplit(database_url)
        port = parsed.port
    except ValueError as error:
        raise BackupError("DATABASE_URL_INVALID") from error
    require(parsed.scheme == "postgresql", "DATABASE_URL_SCHEME_INVALID")
    require(parsed.path == "/postgres", "DATABASE_NAME_INVALID")
    require(port == 5432, "DATABASE_PORT_INVALID")
    hostname = parsed.hostname
    require(isinstance(hostname, str) and hostname == hostname.lower(), "DATABASE_HOST_INVALID")
    authority = database_url.split("://", maxsplit=1)[1].split("/", maxsplit=1)[0]
    raw_userinfo, raw_hostport = authority.rsplit("@", maxsplit=1)
    require(raw_userinfo.count(":") == 1, "DATABASE_USERINFO_INVALID")
    raw_username, raw_password = raw_userinfo.split(":", maxsplit=1)
    require(ENCODED_PASSWORD_PATTERN.fullmatch(raw_password) is not None, "DATABASE_PASSWORD_ENCODING_INVALID")
    require(raw_hostport == f"{hostname}:5432", "DATABASE_HOST_INVALID")
    try:
        username = urllib.parse.unquote(raw_username, errors="strict")
        decoded_password = urllib.parse.unquote(raw_password, errors="strict")
    except (UnicodeDecodeError, ValueError) as error:
        raise BackupError("DATABASE_USERINFO_INVALID") from error
    require(1 <= len(decoded_password) <= 1024, "DATABASE_PASSWORD_INVALID")
    require(not any(character.isspace() or ord(character) < 32 for character in decoded_password), "DATABASE_PASSWORD_INVALID")
    direct_host = f"db.{project_ref}.supabase.co"
    if hostname == direct_host:
        require(username == "postgres", "DATABASE_USERNAME_INVALID")
        return "direct", hostname
    require(POOLER_HOST_PATTERN.fullmatch(hostname) is not None, "DATABASE_HOST_INVALID")
    require(username == f"postgres.{project_ref}", "DATABASE_USERNAME_INVALID")
    return "session-pooler", hostname


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
    require(PROJECT_REF_PATTERN.fullmatch(production_project_ref) is not None, "PRODUCTION_PROJECT_REF_INVALID")
    require(project_ref != production_project_ref, "PRODUCTION_TARGET_FORBIDDEN")
    require(api_url == f"https://{project_ref}.supabase.co", "TARGET_API_URL_INVALID")
    require(SHA256_PATTERN.fullmatch(project_ref_sha256) is not None, "PROJECT_REF_DIGEST_INVALID")
    require(sha256_text(project_ref) == project_ref_sha256, "PROJECT_REF_DIGEST_DRIFT")
    endpoint_class, hostname = _parse_database_url(database_url, project_ref)
    return TargetAuthority(
        api_url=api_url,
        project_ref=project_ref,
        project_ref_sha256=project_ref_sha256,
        production_project_ref=production_project_ref,
        database_endpoint_class=endpoint_class,
        database_host_sha256=sha256_text(hostname),
        database_url=database_url,
    )


def validate_vault(
    repository_document: Mapping[str, Any],
    immutable_document: Mapping[str, Any],
    *,
    configured_repository: str,
    source_repository_id: int,
) -> dict[str, Any]:
    require(REPOSITORY_PATTERN.fullmatch(configured_repository) is not None, "VAULT_REPOSITORY_INVALID")
    require(configured_repository != EXPECTED_REPOSITORY, "VAULT_REPOSITORY_NOT_INDEPENDENT")
    require(repository_document.get("full_name") == configured_repository, "VAULT_REPOSITORY_DRIFT")
    repository_id = positive_integer(repository_document.get("id"), "VAULT_REPOSITORY_ID_INVALID")
    require(repository_id != source_repository_id, "VAULT_REPOSITORY_NOT_INDEPENDENT")
    require(repository_document.get("private") is True, "VAULT_REPOSITORY_NOT_PRIVATE")
    require(repository_document.get("archived") is False, "VAULT_REPOSITORY_ARCHIVED")
    require(repository_document.get("disabled") is False, "VAULT_REPOSITORY_DISABLED")
    default_branch = repository_document.get("default_branch")
    require(isinstance(default_branch, str) and bool(default_branch), "VAULT_DEFAULT_BRANCH_INVALID")
    require(immutable_document.get("enabled") is True, "VAULT_IMMUTABLE_RELEASES_DISABLED")
    return {
        "defaultBranch": default_branch,
        "immutableReleasesEnabled": True,
        "repository": configured_repository,
        "repositoryDigestSha256": sha256_text(configured_repository),
        "repositoryId": repository_id,
    }


def validate_key_escrow(
    *,
    release_document: Mapping[str, Any],
    asset_document: Mapping[str, Any],
    receipt_document: Mapping[str, Any],
    receipt_sha256: str,
    expected_release_id: int,
    expected_release_tag: str,
    expected_asset_id: int,
    expected_asset_sha256: str,
    age_recipient_sha256: str,
    minimum_retention_days: int,
    now: datetime,
) -> dict[str, Any]:
    require(release_document.get("id") == expected_release_id, "ESCROW_RELEASE_ID_DRIFT")
    require(release_document.get("tag_name") == expected_release_tag, "ESCROW_RELEASE_TAG_DRIFT")
    require(release_document.get("draft") is False, "ESCROW_RELEASE_NOT_PUBLISHED")
    require(release_document.get("immutable") is True, "ESCROW_RELEASE_NOT_IMMUTABLE")
    require(asset_document.get("id") == expected_asset_id, "ESCROW_ASSET_ID_DRIFT")
    require(asset_document.get("state") == "uploaded", "ESCROW_ASSET_NOT_UPLOADED")
    require(asset_document.get("name") == "kwabor-age-key-escrow.json", "ESCROW_ASSET_NAME_INVALID")
    require(asset_document.get("digest") == f"sha256:{expected_asset_sha256}", "ESCROW_ASSET_DIGEST_DRIFT")
    release_assets = release_document.get("assets")
    require(isinstance(release_assets, list), "ESCROW_RELEASE_ASSETS_MISSING")
    matching_release_assets = [
        asset
        for asset in release_assets
        if isinstance(asset, dict) and asset.get("id") == expected_asset_id
    ]
    require(len(matching_release_assets) == 1, "ESCROW_ASSET_NOT_IN_RELEASE")
    require(
        matching_release_assets[0].get("name") == "kwabor-age-key-escrow.json"
        and matching_release_assets[0].get("digest") == f"sha256:{expected_asset_sha256}",
        "ESCROW_RELEASE_ASSET_DRIFT",
    )
    require(receipt_sha256 == expected_asset_sha256, "ESCROW_RECEIPT_DIGEST_DRIFT")
    require(receipt_document.get("schemaVersion") == 1, "ESCROW_RECEIPT_SCHEMA_INVALID")
    require(receipt_document.get("type") == "kwabor-age-key-escrow", "ESCROW_RECEIPT_TYPE_INVALID")
    require(receipt_document.get("status") == "active", "ESCROW_RECEIPT_NOT_ACTIVE")
    require(receipt_document.get("ageRecipientSha256") == age_recipient_sha256, "ESCROW_RECIPIENT_DRIFT")
    require(receipt_document.get("recoveryIdentityStoredOffsite") is True, "ESCROW_OFFSITE_COPY_MISSING")
    require(receipt_document.get("custodyMode") in {"kms", "offline-two-person"}, "ESCROW_CUSTODY_INVALID")
    custodians = receipt_document.get("minimumCustodians")
    require(isinstance(custodians, int) and not isinstance(custodians, bool) and custodians >= 2, "ESCROW_CUSTODIANS_INVALID")
    tested_at = parse_timestamp(receipt_document.get("recoveryTestedAt"), "ESCROW_TEST_TIMESTAMP_INVALID")
    valid_until = parse_timestamp(receipt_document.get("validUntil"), "ESCROW_VALID_UNTIL_INVALID")
    require(timedelta(0) <= now - tested_at <= timedelta(days=MAX_ESCROW_TEST_AGE_DAYS), "ESCROW_RECOVERY_TEST_STALE")
    require(valid_until >= now + timedelta(days=minimum_retention_days), "ESCROW_VALIDITY_TOO_SHORT")
    return {
        "assetId": expected_asset_id,
        "assetSha256": expected_asset_sha256,
        "custodyMode": receipt_document["custodyMode"],
        "minimumCustodians": custodians,
        "recoveryKeyStoredOffsite": True,
        "recoveryTestedAt": format_timestamp(tested_at),
        "releaseId": expected_release_id,
        "releaseTag": expected_release_tag,
        "validUntil": format_timestamp(valid_until),
    }


def _repository_evidence(document: Mapping[str, Any]) -> tuple[int, dict[str, Any]]:
    require(document.get("full_name") == EXPECTED_REPOSITORY, "SOURCE_REPOSITORY_DRIFT")
    require(document.get("default_branch") == "main", "SOURCE_DEFAULT_BRANCH_INVALID")
    require(document.get("archived") is False, "SOURCE_REPOSITORY_ARCHIVED")
    require(document.get("disabled") is False, "SOURCE_REPOSITORY_DISABLED")
    repository_id = positive_integer(document.get("id"), "SOURCE_REPOSITORY_ID_INVALID")
    return repository_id, {
        "defaultBranch": "main",
        "repository": EXPECTED_REPOSITORY,
        "repositoryId": repository_id,
    }


def _workflow_context(args: argparse.Namespace) -> dict[str, Any]:
    require(COMMIT_SHA_PATTERN.fullmatch(args.expected_sha) is not None, "EXPECTED_SHA_INVALID")
    require(args.head_sha == args.expected_sha, "CHECKOUT_SHA_DRIFT")
    require(args.github_repository == EXPECTED_REPOSITORY, "SOURCE_REPOSITORY_DRIFT")
    require(args.github_event_name == EXPECTED_EVENT, "WORKFLOW_EVENT_INVALID")
    require(args.github_ref == EXPECTED_REF, "WORKFLOW_REF_INVALID")
    require(args.github_sha == args.expected_sha, "DISPATCH_SHA_DRIFT")
    run_id = positive_integer(args.github_run_id, "RUN_ID_INVALID")
    run_attempt = positive_integer(args.github_run_attempt, "RUN_ATTEMPT_INVALID")
    require(
        args.github_workflow_ref == f"{EXPECTED_REPOSITORY}/{EXPECTED_WORKFLOW}@{EXPECTED_REF}",
        "WORKFLOW_IDENTITY_INVALID",
    )
    return {
        "event": EXPECTED_EVENT,
        "ref": EXPECTED_REF,
        "runAttempt": run_attempt,
        "runId": run_id,
        "runUrl": f"https://github.com/{EXPECTED_REPOSITORY}/actions/runs/{run_id}",
        "workflowPath": EXPECTED_WORKFLOW,
    }


def _bounded_setting(value: str, *, minimum: int, maximum: int, code: str) -> int:
    parsed = positive_integer(value, code)
    require(minimum <= parsed <= maximum, code)
    return parsed


def preflight(args: argparse.Namespace) -> None:
    evidence_directory = Path(args.evidence_directory)
    evidence_directory.mkdir(parents=True, exist_ok=True)
    context = _workflow_context(args)
    repository_id, source = _repository_evidence(load_json(Path(args.source_repository_json)))
    validated_ci_run_id = positive_integer(args.validated_ci_run_id, "CI_RUN_ID_INVALID")
    ci = validate_ci_run(
        load_json(Path(args.ci_run_json)),
        expected_run_id=validated_ci_run_id,
        expected_sha=args.expected_sha,
        repository_id=repository_id,
    )
    environment = validate_environment(load_json(Path(args.environment_json)))

    database_url = os.environ.get("KWABOR_STAGING_DATABASE_URL", "").strip()
    target = validate_target_authority(
        environment=os.environ.get("KWABOR_ENVIRONMENT", ""),
        api_url=os.environ.get("KWABOR_SUPABASE_URL", ""),
        project_ref=os.environ.get("KWABOR_SUPABASE_PROJECT_REF", ""),
        production_project_ref=os.environ.get("KWABOR_PRODUCTION_SUPABASE_PROJECT_REF", ""),
        project_ref_sha256=os.environ.get("KWABOR_STAGING_PROJECT_REF_SHA256", ""),
        database_url=database_url,
    )
    recipient = os.environ.get("KWABOR_STAGING_BACKUP_AGE_RECIPIENT", "").strip()
    require(AGE_RECIPIENT_PATTERN.fullmatch(recipient) is not None, "AGE_RECIPIENT_INVALID")
    recipient_sha256 = sha256_text(recipient)
    restore_identity = os.environ.get("KWABOR_STAGING_BACKUP_AGE_IDENTITY", "").strip()
    require(bool(restore_identity), "AGE_RESTORE_IDENTITY_MISSING")
    require(AGE_IDENTITY_PATTERN.fullmatch(restore_identity) is not None, "AGE_RESTORE_IDENTITY_INVALID")
    retention_days = _bounded_setting(
        os.environ.get("KWABOR_STAGING_BACKUP_OFFSITE_RETENTION_DAYS", ""),
        minimum=MINIMUM_OFFSITE_RETENTION_DAYS,
        maximum=MAXIMUM_OFFSITE_RETENTION_DAYS,
        code="OFFSITE_RETENTION_INVALID",
    )
    max_rpo_seconds = _bounded_setting(
        os.environ.get("KWABOR_STAGING_BACKUP_MAX_RPO_SECONDS", ""),
        minimum=MINIMUM_RPO_SECONDS,
        maximum=MAXIMUM_RPO_SECONDS,
        code="RPO_TARGET_INVALID",
    )
    max_rto_seconds = _bounded_setting(
        os.environ.get("KWABOR_STAGING_BACKUP_MAX_RTO_SECONDS", ""),
        minimum=MINIMUM_RTO_SECONDS,
        maximum=MAXIMUM_RTO_SECONDS,
        code="RTO_TARGET_INVALID",
    )
    vault_repository = os.environ.get("KWABOR_STAGING_BACKUP_VAULT_REPOSITORY", "").strip()
    vault = validate_vault(
        load_json(Path(args.vault_repository_json)),
        load_json(Path(args.vault_immutable_json)),
        configured_repository=vault_repository,
        source_repository_id=repository_id,
    )
    require(bool(os.environ.get("KWABOR_BACKUP_VAULT_TOKEN", "").strip()), "VAULT_TOKEN_MISSING")

    escrow_release_id = positive_integer(
        os.environ.get("KWABOR_STAGING_BACKUP_KEY_ESCROW_RELEASE_ID", ""),
        "ESCROW_RELEASE_ID_INVALID",
    )
    escrow_asset_id = positive_integer(
        os.environ.get("KWABOR_STAGING_BACKUP_KEY_ESCROW_ASSET_ID", ""),
        "ESCROW_ASSET_ID_INVALID",
    )
    escrow_tag = os.environ.get("KWABOR_STAGING_BACKUP_KEY_ESCROW_RELEASE_TAG", "").strip()
    require(bool(escrow_tag) and len(escrow_tag) <= 200, "ESCROW_RELEASE_TAG_INVALID")
    escrow_digest = os.environ.get("KWABOR_STAGING_BACKUP_KEY_ESCROW_ASSET_SHA256", "").strip()
    require(SHA256_PATTERN.fullmatch(escrow_digest) is not None, "ESCROW_ASSET_DIGEST_INVALID")
    escrow_receipt_path = Path(args.escrow_receipt_json)
    escrow = validate_key_escrow(
        release_document=load_json(Path(args.escrow_release_json)),
        asset_document=load_json(Path(args.escrow_asset_json)),
        receipt_document=load_json(escrow_receipt_path),
        receipt_sha256=sha256_file(escrow_receipt_path),
        expected_release_id=escrow_release_id,
        expected_release_tag=escrow_tag,
        expected_asset_id=escrow_asset_id,
        expected_asset_sha256=escrow_digest,
        age_recipient_sha256=recipient_sha256,
        minimum_retention_days=retention_days,
        now=now_utc(),
    )

    require(args.operation in {"readiness", "backup"}, "OPERATION_INVALID")
    live_enabled = os.environ.get("KWABOR_STAGING_BACKUP_LIVE_ENABLED", "") == "true"
    if args.operation == "backup":
        require(live_enabled, "LIVE_BACKUP_DISABLED")
        require(args.capture_confirmation == CAPTURE_CONFIRMATION, "CAPTURE_CONFIRMATION_INVALID")

    document = {
        "ageRecipientSha256": recipient_sha256,
        "authorizedAt": utc_now(),
        "ci": ci,
        "environmentEvidence": environment,
        "expectedSha": args.expected_sha,
        "keyEscrow": escrow,
        "liveEnabled": live_enabled,
        "maxRpoSeconds": max_rpo_seconds,
        "maxRtoSeconds": max_rto_seconds,
        "offsiteRetentionDays": retention_days,
        "operation": args.operation,
        "repository": EXPECTED_REPOSITORY,
        "schemaVersion": 1,
        "source": source,
        "status": "authorized" if args.operation == "backup" else "prepared_not_executable",
        "target": target.public_evidence(),
        "targetDigestSha256": sha256_bytes(canonical_json_bytes(target.public_evidence())),
        "validatedCiRunId": validated_ci_run_id,
        "vault": vault,
        **context,
    }
    write_json(evidence_directory / PREFLIGHT_FILENAME, document)
    if args.operation == "readiness":
        write_failure_receipt(
            evidence_directory,
            code="LIVE_BACKUP_NOT_REQUESTED",
            status="prepared_not_executable",
            expected_sha=args.expected_sha,
            context=document,
        )


def sanitized_environment(*, read_only: bool) -> dict[str, str]:
    environment = {
        key: value
        for key, value in os.environ.items()
        if key not in LIBPQ_OVERRIDE_KEYS
        and key not in SUPABASE_OVERRIDE_KEYS
        and key not in BACKUP_SECRET_KEYS
        and not any(fragment in key.upper() for fragment in SECRET_ENVIRONMENT_FRAGMENTS)
    }
    if read_only:
        environment["PGOPTIONS"] = "-c default_transaction_read_only=on"
    return environment


def run_command(
    command: Sequence[str],
    *,
    cwd: Path,
    code: str,
    environment: Mapping[str, str] | None = None,
    timeout: int = 900,
) -> str:
    require(bool(command), "COMMAND_EMPTY")
    try:
        result = subprocess.run(
            list(command),
            cwd=cwd,
            env=dict(environment) if environment is not None else None,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError) as error:
        raise BackupError(code) from error
    require(result.returncode == 0, code)
    return result.stdout


def _psql_scalar(database_url: str, query: str, *, cwd: Path, code: str) -> str:
    output = run_command(
        [
            "psql",
            database_url,
            "--no-psqlrc",
            "--no-align",
            "--tuples-only",
            "--set",
            "ON_ERROR_STOP=1",
            "--command",
            query,
        ],
        cwd=cwd,
        code=code,
        environment=sanitized_environment(read_only=True),
    )
    return output.strip()


def _migration_versions(database_url: str, *, cwd: Path) -> list[str]:
    relation = _psql_scalar(
        database_url,
        "select coalesce(to_regclass('supabase_migrations.schema_migrations')::text, '');",
        cwd=cwd,
        code="MIGRATION_HISTORY_INSPECTION_FAILED",
    )
    if not relation:
        return []
    output = _psql_scalar(
        database_url,
        "select version::text from supabase_migrations.schema_migrations order by version;",
        cwd=cwd,
        code="MIGRATION_HISTORY_INSPECTION_FAILED",
    )
    versions = [line.strip() for line in output.splitlines() if line.strip()]
    require(all(MIGRATION_VERSION_PATTERN.fullmatch(item) is not None for item in versions), "MIGRATION_HISTORY_INVALID")
    require(versions == sorted(set(versions)), "MIGRATION_HISTORY_INVALID")
    return versions


def _database_major(database_url: str, *, cwd: Path) -> int:
    value = _psql_scalar(
        database_url,
        "select current_setting('server_version_num');",
        cwd=cwd,
        code="POSTGRES_VERSION_INSPECTION_FAILED",
    )
    require(value.isdigit(), "POSTGRES_VERSION_INVALID")
    major = int(value) // 10000
    require(major == EXPECTED_POSTGRES_MAJOR, "POSTGRES_MAJOR_DRIFT")
    return major


def _run_dump(database_url: str, destination: Path, mode: str, *, cwd: Path) -> None:
    command = ["supabase", "db", "dump", "--db-url", database_url, "--file", str(destination)]
    if mode == "roles":
        command.append("--role-only")
    elif mode == "schema":
        command.extend(["--schema", ",".join(APPLICATION_SCHEMAS)])
    elif mode == "data":
        command.extend(["--schema", ",".join(APPLICATION_SCHEMAS), "--data-only", "--use-copy"])
    elif mode == "migration_schema":
        command.extend(["--schema", "supabase_migrations"])
    elif mode == "migration_data":
        command.extend(["--schema", "supabase_migrations", "--data-only", "--use-copy"])
    else:
        raise BackupError("DUMP_MODE_INVALID")
    run_command(
        command,
        cwd=cwd,
        code=f"{mode.upper()}_DUMP_FAILED",
        environment=sanitized_environment(read_only=True),
        timeout=1800,
    )


def _create_archive(source_directory: Path, archive_path: Path) -> None:
    allowed = {*(f"{mode}.sql" for mode in DUMP_MODES), "PAYLOAD-MANIFEST.json"}
    require({path.name for path in source_directory.iterdir()} == allowed, "ARCHIVE_INPUT_INVALID")
    with tarfile.open(archive_path, "w:gz", format=tarfile.PAX_FORMAT) as archive:
        for name in sorted(allowed):
            archive.add(source_directory / name, arcname=name, recursive=False)


def _extract_archive(archive_path: Path, destination: Path) -> None:
    allowed = {*(f"{mode}.sql" for mode in DUMP_MODES), "PAYLOAD-MANIFEST.json"}
    destination.mkdir(parents=True, exist_ok=False)
    with tarfile.open(archive_path, "r:gz") as archive:
        members = archive.getmembers()
        require({member.name for member in members} == allowed, "ARCHIVE_CONTENT_INVALID")
        for member in members:
            require(member.isfile() and not member.issym() and not member.islnk(), "ARCHIVE_MEMBER_INVALID")
            require(Path(member.name).name == member.name, "ARCHIVE_MEMBER_INVALID")
            extracted = archive.extractfile(member)
            require(extracted is not None, "ARCHIVE_MEMBER_INVALID")
            target = destination / member.name
            with target.open("xb") as output:
                shutil.copyfileobj(extracted, output)


def _configure_restore_stack(root: Path, postgres_major: int, project_id: str) -> None:
    run_command(
        ["supabase", "init"],
        cwd=root,
        code="RESTORE_STACK_INIT_FAILED",
        environment=sanitized_environment(read_only=False),
    )
    config_path = root / "supabase" / "config.toml"
    source = config_path.read_text(encoding="utf-8")
    source, project_count = re.subn(
        r'(?m)^project_id\s*=\s*"[^"]+"$',
        f'project_id = "{project_id}"',
        source,
        count=1,
    )
    source, major_count = re.subn(
        r"(?m)^major_version\s*=\s*[0-9]+$",
        f"major_version = {postgres_major}",
        source,
        count=1,
    )
    require(project_count == 1 and major_count == 1, "RESTORE_STACK_CONFIG_INVALID")
    config_path.write_text(source, encoding="utf-8", newline="\n")


def _local_database_url(root: Path) -> str:
    output = run_command(
        ["supabase", "status", "-o", "env"],
        cwd=root,
        code="RESTORE_STACK_STATUS_FAILED",
        environment=sanitized_environment(read_only=False),
    )
    match = re.search(r'^DB_URL="([^"]+)"$', output, flags=re.MULTILINE)
    require(match is not None, "RESTORE_DATABASE_URL_MISSING")
    value = match.group(1)
    parsed = urllib.parse.urlsplit(value)
    require(parsed.hostname in {"127.0.0.1", "localhost", "::1"}, "RESTORE_TARGET_NOT_LOCAL")
    return value


def _restore_payload(database_url: str, payload_directory: Path, *, cwd: Path) -> None:
    run_command(
        [
            "psql",
            database_url,
            "--no-psqlrc",
            "--set",
            "ON_ERROR_STOP=1",
            "--single-transaction",
            "--file",
            str(payload_directory / "roles.sql"),
            "--file",
            str(payload_directory / "schema.sql"),
            "--command",
            "set session_replication_role = replica;",
            "--file",
            str(payload_directory / "data.sql"),
            "--file",
            str(payload_directory / "migration_schema.sql"),
            "--file",
            str(payload_directory / "migration_data.sql"),
        ],
        cwd=cwd,
        code="RESTORE_SQL_FAILED",
        environment=sanitized_environment(read_only=False),
        timeout=1800,
    )


def _payload_fingerprint(
    dumps: Mapping[str, Mapping[str, Any]],
    *,
    migration_prefix_sha256: str,
    postgres_major: int,
) -> str:
    document = {
        "dumps": {key: value["normalizedSha256"] for key, value in sorted(dumps.items())},
        "migrationPrefixSha256": migration_prefix_sha256,
        "postgresMajor": postgres_major,
        "schemas": list(APPLICATION_SCHEMAS),
    }
    return sha256_bytes(canonical_json_bytes(document))


def _require_github_hosted_runner(preflight_document: Mapping[str, Any]) -> None:
    require(os.environ.get("GITHUB_ACTIONS") == "true", "GITHUB_ACTIONS_REQUIRED")
    require(os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted", "GITHUB_HOSTED_RUNNER_REQUIRED")
    require(os.environ.get("GITHUB_REPOSITORY") == EXPECTED_REPOSITORY, "SOURCE_REPOSITORY_DRIFT")
    require(os.environ.get("GITHUB_REF") == EXPECTED_REF, "WORKFLOW_REF_INVALID")
    require(os.environ.get("GITHUB_SHA") == preflight_document.get("expectedSha"), "DISPATCH_SHA_DRIFT")
    require(
        os.environ.get("GITHUB_RUN_ID") == str(preflight_document.get("runId")),
        "RUN_ID_DRIFT",
    )
    require(
        os.environ.get("GITHUB_RUN_ATTEMPT") == str(preflight_document.get("runAttempt")),
        "RUN_ATTEMPT_DRIFT",
    )


def _vault_manifest(
    preflight_document: Mapping[str, Any],
    capture_document: Mapping[str, Any],
) -> dict[str, Any]:
    return {
        "ageRecipientSha256": preflight_document["ageRecipientSha256"],
        "captureCompletedAt": capture_document["captureCompletedAt"],
        "captureStartedAt": capture_document["captureStartedAt"],
        "ci": preflight_document["ci"],
        "databaseFingerprintSha256": capture_document["databaseFingerprintSha256"],
        "databaseScope": {
            "dumpModes": list(DUMP_MODES),
            "managedAuthStorageDataIncluded": False,
            "schemas": list(APPLICATION_SCHEMAS),
            "type": "targeted-logical",
        },
        "encryptedBundle": capture_document["encryptedBundle"],
        "environmentEvidenceSha256": sha256_bytes(
            canonical_json_bytes(preflight_document["environmentEvidence"])
        ),
        "expectedSha": preflight_document["expectedSha"],
        "keyEscrowAssetSha256": preflight_document["keyEscrow"]["assetSha256"],
        "migrationPrefixCount": capture_document["migrationPrefixCount"],
        "migrationPrefixSha256": capture_document["migrationPrefixSha256"],
        "offsiteRetentionDays": preflight_document["offsiteRetentionDays"],
        "operation": "backup",
        "postgresMajor": capture_document["postgresMajor"],
        "qualificationStatus": "restore-verified-awaiting-offsite-immutability",
        "ref": preflight_document["ref"],
        "repository": EXPECTED_REPOSITORY,
        "restore": capture_document["restore"],
        "rpoTargetSeconds": preflight_document["maxRpoSeconds"],
        "rtoTargetSeconds": preflight_document["maxRtoSeconds"],
        "runAttempt": preflight_document["runAttempt"],
        "runId": preflight_document["runId"],
        "schemaVersion": 1,
        "targetDigestSha256": preflight_document["targetDigestSha256"],
        "taskId": TASK_ID,
        "validatedCiRunId": preflight_document["validatedCiRunId"],
        "vaultRepositoryDigestSha256": preflight_document["vault"]["repositoryDigestSha256"],
        "vaultRepositoryId": preflight_document["vault"]["repositoryId"],
        "workflowPath": EXPECTED_WORKFLOW,
    }


def capture(args: argparse.Namespace) -> None:
    evidence_directory = Path(args.evidence_directory)
    preflight_document = load_json(evidence_directory / PREFLIGHT_FILENAME, "PREFLIGHT_EVIDENCE_INVALID")
    require(preflight_document.get("status") == "authorized", "PREFLIGHT_NOT_AUTHORIZED")
    require(preflight_document.get("operation") == "backup", "PREFLIGHT_OPERATION_INVALID")
    require(preflight_document.get("expectedSha") == args.expected_sha, "PREFLIGHT_SHA_DRIFT")
    _require_github_hosted_runner(preflight_document)
    database_url = os.environ.get("KWABOR_STAGING_DATABASE_URL", "").strip()
    recipient = os.environ.get("KWABOR_STAGING_BACKUP_AGE_RECIPIENT", "").strip()
    identity = os.environ.get("KWABOR_STAGING_BACKUP_AGE_IDENTITY", "").strip()
    require(AGE_RECIPIENT_PATTERN.fullmatch(recipient) is not None, "AGE_RECIPIENT_INVALID")
    require(AGE_IDENTITY_PATTERN.fullmatch(identity) is not None, "AGE_RESTORE_IDENTITY_INVALID")
    require(sha256_text(recipient) == preflight_document.get("ageRecipientSha256"), "AGE_RECIPIENT_DRIFT")
    _parse_database_url(database_url, preflight_document["target"]["projectRef"])

    capture_started = now_utc()
    monotonic_started = time.monotonic()
    run_id = positive_integer(preflight_document.get("runId"), "RUN_ID_INVALID")
    run_attempt = positive_integer(preflight_document.get("runAttempt"), "RUN_ATTEMPT_INVALID")
    temporary_root = Path(tempfile.mkdtemp(prefix="kwabor-b6-02-", dir=os.environ.get("RUNNER_TEMP")))
    os.chmod(temporary_root, 0o700)
    plaintext_root = temporary_root / "plaintext"
    plaintext_root.mkdir(mode=0o700)
    archive_path = temporary_root / "kwabor-staging-backup.tar.gz"
    decrypted_archive = temporary_root / "restore.tar.gz"
    extracted_root = temporary_root / "restore-payload"
    restore_stack_root = temporary_root / "restore-stack"
    restore_stack_root.mkdir()
    identity_path = temporary_root / "age-identity.txt"
    identity_path.write_text(identity + "\n", encoding="ascii", newline="\n")
    os.chmod(identity_path, 0o600)
    project_id = f"kwabor-b6-02-{run_id}-{run_attempt}"
    stack_started = False

    try:
        derived_recipient = run_command(
            ["age-keygen", "-y", str(identity_path)],
            cwd=temporary_root,
            code="AGE_IDENTITY_DERIVATION_FAILED",
        ).strip()
        require(derived_recipient == recipient, "AGE_IDENTITY_RECIPIENT_MISMATCH")
        postgres_major = _database_major(database_url, cwd=Path(args.workspace))
        migration_versions = _migration_versions(database_url, cwd=Path(args.workspace))
        migration_prefix_payload = ("\n".join(migration_versions) + ("\n" if migration_versions else "")).encode("ascii")
        migration_prefix_sha256 = sha256_bytes(migration_prefix_payload)

        dump_paths = {mode: plaintext_root / f"{mode}.sql" for mode in DUMP_MODES}
        for mode, destination in dump_paths.items():
            _run_dump(database_url, destination, mode, cwd=Path(args.workspace))
        source_dumps = {mode: dump_evidence(path) for mode, path in dump_paths.items()}
        source_fingerprint = _payload_fingerprint(
            source_dumps,
            migration_prefix_sha256=migration_prefix_sha256,
            postgres_major=postgres_major,
        )
        payload_manifest = {
            "databaseFingerprintSha256": source_fingerprint,
            "dumps": source_dumps,
            "migrationPrefixCount": len(migration_versions),
            "migrationPrefixSha256": migration_prefix_sha256,
            "postgresMajor": postgres_major,
            "schemaVersion": 1,
            "schemas": list(APPLICATION_SCHEMAS),
        }
        write_json(plaintext_root / "PAYLOAD-MANIFEST.json", payload_manifest)
        _create_archive(plaintext_root, archive_path)
        bundle_name = (
            f"kwabor-staging-{preflight_document['target']['projectRefSha256'][:16]}-"
            f"{source_fingerprint}.tar.gz.age"
        )
        encrypted_bundle = evidence_directory / bundle_name
        run_command(
            ["age", "--encrypt", "--recipient", recipient, "--output", str(encrypted_bundle), str(archive_path)],
            cwd=temporary_root,
            code="AGE_ENCRYPTION_FAILED",
            timeout=1800,
        )
        encrypted_bundle_sha256 = sha256_file(encrypted_bundle)
        encrypted_bundle_bytes = encrypted_bundle.stat().st_size
        require(
            0 < encrypted_bundle_bytes <= MAX_ENCRYPTED_BUNDLE_BYTES,
            "ENCRYPTED_BUNDLE_SIZE_INVALID",
        )

        shutil.rmtree(plaintext_root)
        archive_path.unlink()
        restore_started = time.monotonic()
        run_command(
            ["age", "--decrypt", "--identity", str(identity_path), "--output", str(decrypted_archive), str(encrypted_bundle)],
            cwd=temporary_root,
            code="AGE_DECRYPTION_FAILED",
            timeout=1800,
        )
        _extract_archive(decrypted_archive, extracted_root)
        restored_manifest = load_json(extracted_root / "PAYLOAD-MANIFEST.json", "PAYLOAD_MANIFEST_INVALID")
        require(restored_manifest == payload_manifest, "PAYLOAD_MANIFEST_DRIFT")
        for mode in DUMP_MODES:
            require(dump_evidence(extracted_root / f"{mode}.sql") == source_dumps[mode], "DECRYPTED_DUMP_DRIFT")

        _configure_restore_stack(restore_stack_root, postgres_major, project_id)
        run_command(
            ["supabase", "db", "start"],
            cwd=restore_stack_root,
            code="RESTORE_STACK_START_FAILED",
            environment=sanitized_environment(read_only=False),
            timeout=1800,
        )
        stack_started = True
        local_database_url = _local_database_url(restore_stack_root)
        _restore_payload(local_database_url, extracted_root, cwd=restore_stack_root)

        verification_root = temporary_root / "verification"
        verification_root.mkdir()
        restored_dumps: dict[str, dict[str, Any]] = {}
        for mode in DUMP_MODES:
            destination = verification_root / f"{mode}.sql"
            _run_dump(local_database_url, destination, mode, cwd=restore_stack_root)
            restored_dumps[mode] = dump_evidence(destination)
        for mode in DUMP_MODES:
            require(
                restored_dumps[mode]["normalizedSha256"] == source_dumps[mode]["normalizedSha256"],
                "RESTORED_FINGERPRINT_DRIFT",
            )
        restored_migration_versions = _migration_versions(local_database_url, cwd=restore_stack_root)
        restored_migration_payload = (
            "\n".join(restored_migration_versions) + ("\n" if restored_migration_versions else "")
        ).encode("ascii")
        restored_migration_prefix_sha256 = sha256_bytes(restored_migration_payload)
        require(
            restored_migration_prefix_sha256 == migration_prefix_sha256
            and restored_migration_versions == migration_versions,
            "RESTORED_MIGRATION_PREFIX_DRIFT",
        )
        restored_fingerprint = _payload_fingerprint(
            restored_dumps,
            migration_prefix_sha256=restored_migration_prefix_sha256,
            postgres_major=postgres_major,
        )
        require(restored_fingerprint == source_fingerprint, "RESTORED_FINGERPRINT_DRIFT")
        rto_seconds = max(1, int(time.monotonic() - restore_started))
        require(rto_seconds <= preflight_document["maxRtoSeconds"], "RTO_TARGET_EXCEEDED")
        capture_completed = now_utc()

        age_version = run_command(["age", "--version"], cwd=temporary_root, code="AGE_VERSION_FAILED").strip()
        cli_version = run_command(["supabase", "--version"], cwd=temporary_root, code="CLI_VERSION_FAILED").strip()
        require(cli_version == EXPECTED_CLI_VERSION, "CLI_VERSION_DRIFT")
        psql_version = run_command(["psql", "--version"], cwd=temporary_root, code="PSQL_VERSION_FAILED").strip()
        capture_document = {
            "ageVersion": age_version,
            "captureCompletedAt": format_timestamp(capture_completed),
            "captureDurationSeconds": max(1, int(time.monotonic() - monotonic_started)),
            "captureStartedAt": format_timestamp(capture_started),
            "databaseFingerprintSha256": source_fingerprint,
            "encryptedBundle": {
                "bytes": encrypted_bundle_bytes,
                "fileName": bundle_name,
                "sha256": encrypted_bundle_sha256,
            },
            "expectedSha": args.expected_sha,
            "migrationPrefixCount": len(migration_versions),
            "migrationPrefixSha256": migration_prefix_sha256,
            "postgresMajor": postgres_major,
            "psqlVersion": psql_version,
            "qualificationStatus": "restore-verified-awaiting-offsite-immutability",
            "restore": {
                "databaseFingerprintSha256": restored_fingerprint,
                "executionBoundary": "github-actions-disposable-supabase",
                "fingerprintMatch": True,
                "rtoSeconds": rto_seconds,
                "rtoTargetSeconds": preflight_document["maxRtoSeconds"],
                "verified": True,
            },
            "runAttempt": run_attempt,
            "runId": run_id,
            "schemaVersion": 1,
            "sourceDumps": source_dumps,
            "supabaseCliVersion": cli_version,
        }
        write_json(evidence_directory / CAPTURE_FILENAME, capture_document)
        vault_manifest = _vault_manifest(preflight_document, capture_document)
        vault_manifest_path = evidence_directory / VAULT_MANIFEST_FILENAME
        write_json(vault_manifest_path, vault_manifest)
        write_sidecar(evidence_directory / VAULT_MANIFEST_HASH_FILENAME, vault_manifest_path)
    finally:
        if stack_started:
            try:
                run_command(
                    ["supabase", "stop", "--project-id", project_id, "--no-backup"],
                    cwd=restore_stack_root,
                    code="RESTORE_STACK_STOP_FAILED",
                    environment=sanitized_environment(read_only=False),
                    timeout=300,
                )
            except BackupError:
                print("WARNING staging backup cleanup: RESTORE_STACK_STOP_FAILED", file=sys.stderr)
        shutil.rmtree(temporary_root, ignore_errors=True)


def _validate_vault_release(
    document: Mapping[str, Any],
    *,
    expected_tag: str,
    expected_vault: Mapping[str, Any],
) -> dict[str, Any]:
    release_id = positive_integer(document.get("id"), "VAULT_RELEASE_ID_INVALID")
    require(document.get("tag_name") == expected_tag, "VAULT_RELEASE_TAG_DRIFT")
    require(document.get("draft") is False, "VAULT_RELEASE_NOT_PUBLISHED")
    require(document.get("prerelease") is False, "VAULT_RELEASE_PRERELEASE_INVALID")
    require(document.get("immutable") is True, "VAULT_RELEASE_NOT_IMMUTABLE")
    require(document.get("target_commitish") == expected_vault["defaultBranch"], "VAULT_RELEASE_TARGET_DRIFT")
    published_at = parse_timestamp(document.get("published_at"), "VAULT_RELEASE_TIMESTAMP_INVALID")
    html_url = document.get("html_url")
    expected_url = f"https://github.com/{expected_vault['repository']}/releases/tag/{expected_tag}"
    require(html_url == expected_url, "VAULT_RELEASE_URL_INVALID")
    assets = document.get("assets")
    require(isinstance(assets, list) and len(assets) == 3, "VAULT_RELEASE_ASSET_SET_INVALID")
    asset_ids = [
        asset.get("id")
        for asset in assets
        if isinstance(asset, dict) and isinstance(asset.get("id"), int)
    ]
    require(len(asset_ids) == 3 and len(set(asset_ids)) == 3, "VAULT_RELEASE_ASSET_SET_INVALID")
    return {
        "assetIds": sorted(asset_ids),
        "immutable": True,
        "publishedAt": format_timestamp(published_at),
        "releaseId": release_id,
        "releaseTag": expected_tag,
        "releaseUrl": html_url,
    }


def _validate_vault_asset(
    document: Mapping[str, Any],
    *,
    expected_name: str,
    expected_sha256: str,
    expected_bytes: int,
) -> dict[str, Any]:
    asset_id = positive_integer(document.get("id"), "VAULT_ASSET_ID_INVALID")
    require(document.get("name") == expected_name, "VAULT_ASSET_NAME_DRIFT")
    require(document.get("state") == "uploaded", "VAULT_ASSET_NOT_UPLOADED")
    require(document.get("size") == expected_bytes, "VAULT_ASSET_SIZE_DRIFT")
    require(document.get("digest") == f"sha256:{expected_sha256}", "VAULT_ASSET_DIGEST_DRIFT")
    return {
        "assetId": asset_id,
        "assetName": expected_name,
        "bytes": expected_bytes,
        "sha256": expected_sha256,
    }


def finalize(args: argparse.Namespace) -> None:
    evidence_directory = Path(args.evidence_directory)
    preflight_document = load_json(evidence_directory / PREFLIGHT_FILENAME, "PREFLIGHT_EVIDENCE_INVALID")
    capture_document = load_json(evidence_directory / CAPTURE_FILENAME, "CAPTURE_EVIDENCE_INVALID")
    require(preflight_document.get("expectedSha") == args.expected_sha, "PREFLIGHT_SHA_DRIFT")
    require(preflight_document.get("repository") == EXPECTED_REPOSITORY, "PREFLIGHT_REPOSITORY_DRIFT")
    require(preflight_document.get("ref") == EXPECTED_REF, "PREFLIGHT_REF_DRIFT")
    target = preflight_document.get("target")
    require(isinstance(target, dict), "PREFLIGHT_TARGET_INVALID")
    require(
        preflight_document.get("targetDigestSha256")
        == sha256_bytes(canonical_json_bytes(target)),
        "PREFLIGHT_TARGET_DIGEST_DRIFT",
    )
    require(
        capture_document.get("qualificationStatus")
        == "restore-verified-awaiting-offsite-immutability",
        "CAPTURE_NOT_RESTORE_VERIFIED",
    )
    restore = capture_document.get("restore")
    require(isinstance(restore, dict), "CAPTURE_RESTORE_EVIDENCE_INVALID")
    require(restore.get("verified") is True, "CAPTURE_NOT_RESTORE_VERIFIED")
    require(restore.get("fingerprintMatch") is True, "CAPTURE_FINGERPRINT_MISMATCH")
    require(
        restore.get("databaseFingerprintSha256") == capture_document.get("databaseFingerprintSha256"),
        "CAPTURE_FINGERPRINT_MISMATCH",
    )
    require(capture_document.get("expectedSha") == args.expected_sha, "CAPTURE_SHA_DRIFT")
    run_id = positive_integer(preflight_document.get("runId"), "RUN_ID_INVALID")
    run_attempt = positive_integer(preflight_document.get("runAttempt"), "RUN_ATTEMPT_INVALID")
    require(capture_document.get("runId") == run_id, "CAPTURE_RUN_ID_DRIFT")
    require(capture_document.get("runAttempt") == run_attempt, "CAPTURE_RUN_ATTEMPT_DRIFT")
    require(
        SHA256_PATTERN.fullmatch(str(capture_document.get("databaseFingerprintSha256", "")))
        is not None,
        "CAPTURE_FINGERPRINT_INVALID",
    )
    require(
        SHA256_PATTERN.fullmatch(str(capture_document.get("migrationPrefixSha256", "")))
        is not None,
        "CAPTURE_MIGRATION_PREFIX_INVALID",
    )
    require(capture_document.get("postgresMajor") == EXPECTED_POSTGRES_MAJOR, "POSTGRES_MAJOR_DRIFT")
    expected_tag = f"kwabor-staging-backup-{args.expected_sha}-{run_id}-{run_attempt}"
    release = _validate_vault_release(
        load_json(Path(args.vault_release_json)),
        expected_tag=expected_tag,
        expected_vault=preflight_document["vault"],
    )
    bundle = capture_document["encryptedBundle"]
    bundle_asset = _validate_vault_asset(
        load_json(Path(args.vault_bundle_asset_json)),
        expected_name=bundle["fileName"],
        expected_sha256=bundle["sha256"],
        expected_bytes=bundle["bytes"],
    )
    manifest_path = evidence_directory / VAULT_MANIFEST_FILENAME
    manifest_hash_path = evidence_directory / VAULT_MANIFEST_HASH_FILENAME
    manifest_document = load_json(manifest_path, "VAULT_MANIFEST_INVALID")
    require(
        manifest_document == _vault_manifest(preflight_document, capture_document),
        "VAULT_MANIFEST_DRIFT",
    )
    validate_sidecar(manifest_hash_path, manifest_path, "VAULT_MANIFEST_SIDECAR_DRIFT")
    manifest_asset = _validate_vault_asset(
        load_json(Path(args.vault_manifest_asset_json)),
        expected_name=VAULT_MANIFEST_FILENAME,
        expected_sha256=sha256_file(manifest_path),
        expected_bytes=manifest_path.stat().st_size,
    )
    manifest_hash_asset = _validate_vault_asset(
        load_json(Path(args.vault_manifest_hash_asset_json)),
        expected_name=VAULT_MANIFEST_HASH_FILENAME,
        expected_sha256=sha256_file(manifest_hash_path),
        expected_bytes=manifest_hash_path.stat().st_size,
    )
    require(
        sorted(
            [
                bundle_asset["assetId"],
                manifest_asset["assetId"],
                manifest_hash_asset["assetId"],
            ]
        )
        == release["assetIds"],
        "VAULT_RELEASE_ASSET_SET_INVALID",
    )
    require(sha256_file(Path(args.vault_redownload_path)) == bundle["sha256"], "VAULT_REDOWNLOAD_DIGEST_DRIFT")

    capture_started = parse_timestamp(capture_document.get("captureStartedAt"), "CAPTURE_TIMESTAMP_INVALID")
    capture_completed = parse_timestamp(capture_document.get("captureCompletedAt"), "CAPTURE_TIMESTAMP_INVALID")
    published_at = parse_timestamp(release["publishedAt"], "VAULT_RELEASE_TIMESTAMP_INVALID")
    require(capture_started <= capture_completed <= published_at, "BACKUP_TIMELINE_INVALID")
    observed_rpo_seconds = int((published_at - capture_started).total_seconds())
    require(observed_rpo_seconds <= preflight_document["maxRpoSeconds"], "RPO_TARGET_EXCEEDED")
    retention_days = positive_integer(
        preflight_document.get("offsiteRetentionDays"),
        "OFFSITE_RETENTION_INVALID",
    )
    require(
        MINIMUM_OFFSITE_RETENTION_DAYS <= retention_days <= MAXIMUM_OFFSITE_RETENTION_DAYS,
        "OFFSITE_RETENTION_INVALID",
    )
    retention_until = published_at + timedelta(days=retention_days)
    artifact_estimated_expires_at = published_at + timedelta(days=ARTIFACT_RETENTION_DAYS)
    apply_valid_until = capture_completed + timedelta(seconds=preflight_document["maxRpoSeconds"])
    require(published_at <= apply_valid_until, "RPO_TARGET_EXCEEDED")
    qualified_at = now_utc()
    require(published_at <= qualified_at, "VAULT_RELEASE_IN_FUTURE")
    require(qualified_at <= apply_valid_until, "RPO_TARGET_EXCEEDED")
    escrow_valid_until = parse_timestamp(
        preflight_document["keyEscrow"].get("validUntil"),
        "ESCROW_VALID_UNTIL_INVALID",
    )
    require(escrow_valid_until >= retention_until, "ESCROW_VALIDITY_TOO_SHORT")
    rto_seconds = positive_integer(
        capture_document["restore"].get("rtoSeconds"),
        "RTO_EVIDENCE_INVALID",
    )
    require(rto_seconds <= preflight_document["maxRtoSeconds"], "RTO_TARGET_EXCEEDED")

    receipt = {
        "ageEncryption": {
            "ciphertextOnlyUploaded": True,
            "recipientSha256": preflight_document["ageRecipientSha256"],
            "scheme": "age-x25519",
        },
        "capture": {
            "completedAt": capture_document["captureCompletedAt"],
            "durationSeconds": capture_document["captureDurationSeconds"],
            "startedAt": capture_document["captureStartedAt"],
        },
        "ci": preflight_document["ci"],
        "contributesTo": CONTRIBUTES_TO,
        "databaseFingerprint": {
            "dumpModes": list(DUMP_MODES),
            "managedAuthStorageDataIncluded": False,
            "migrationPrefixCount": capture_document["migrationPrefixCount"],
            "migrationPrefixSha256": capture_document["migrationPrefixSha256"],
            "postgresMajor": capture_document["postgresMajor"],
            "restoredSha256": capture_document["restore"]["databaseFingerprintSha256"],
            "schemas": list(APPLICATION_SCHEMAS),
            "sourceSha256": capture_document["databaseFingerprintSha256"],
            "type": "targeted-logical",
        },
        "environmentEvidence": preflight_document["environmentEvidence"],
        "event": preflight_document["event"],
        "executionDisposition": "EXECUTED_READ_ONLY",
        "expectedSha": args.expected_sha,
        "gateClosed": False,
        "keyEscrow": preflight_document["keyEscrow"],
        "offsiteRetention": {
            "bundleAsset": bundle_asset,
            "immutable": True,
            "manifestAsset": manifest_asset,
            "manifestHashAsset": manifest_hash_asset,
            "provider": "github-immutable-release-vault",
            "providerRetentionControl": "immutable-release-plus-vault-governance",
            "redownloadVerified": True,
            "release": release,
            "repository": preflight_document["vault"]["repository"],
            "repositoryDigestSha256": preflight_document["vault"]["repositoryDigestSha256"],
            "repositoryId": preflight_document["vault"]["repositoryId"],
            "retentionDays": retention_days,
            "retentionUntil": format_timestamp(retention_until),
        },
        "operation": "backup",
        "qualifiedAt": format_timestamp(qualified_at),
        "repository": EXPECTED_REPOSITORY,
        "repositoryEvidence": preflight_document["source"],
        "ref": preflight_document["ref"],
        "restorable": True,
        "restoreVerification": capture_document["restore"],
        "retention": {
            "githubArtifactEstimatedExpiresAt": format_timestamp(artifact_estimated_expires_at),
            "githubArtifactExpirationAuthority": "github-actions-artifact-api",
            "githubArtifactRetentionDays": ARTIFACT_RETENTION_DAYS,
            "githubArtifactRole": "short-lived-evidence-not-durable-backup",
        },
        "rpo": {
            "applyValidUntil": format_timestamp(apply_valid_until),
            "observedSeconds": observed_rpo_seconds,
            "targetSeconds": preflight_document["maxRpoSeconds"],
        },
        "runAttempt": run_attempt,
        "runId": run_id,
        "runUrl": preflight_document["runUrl"],
        "schemaVersion": 1,
        "status": "succeeded",
        "target": preflight_document["target"],
        "targetDigestSha256": preflight_document["targetDigestSha256"],
        "taskId": TASK_ID,
        "validatedCiRunId": preflight_document["validatedCiRunId"],
        "workflowPath": EXPECTED_WORKFLOW,
    }
    receipt_path = evidence_directory / GEL_FILENAME
    write_json(receipt_path, receipt)
    write_sidecar(evidence_directory / GEL_HASH_FILENAME, receipt_path)


def write_failure_receipt(
    evidence_directory: Path,
    *,
    code: str,
    status: str = "failed",
    expected_sha: str | None = None,
    context: Mapping[str, Any] | None = None,
) -> None:
    require(re.fullmatch(r"[A-Z][A-Z0-9_]{2,100}", code) is not None, "FAILURE_CODE_INVALID")
    safe_sha = expected_sha if isinstance(expected_sha, str) and COMMIT_SHA_PATTERN.fullmatch(expected_sha) else None
    receipt: dict[str, Any] = {
        "contributesTo": CONTRIBUTES_TO,
        "errorCode": code,
        "expectedSha": safe_sha,
        "gateClosed": False,
        "operation": "backup",
        "repository": EXPECTED_REPOSITORY,
        "restorable": False,
        "schemaVersion": 1,
        "status": status,
        "taskId": TASK_ID,
        "workflowPath": EXPECTED_WORKFLOW,
    }
    if context:
        for key in ("runId", "runAttempt", "runUrl", "target", "targetDigestSha256", "validatedCiRunId"):
            if key in context:
                receipt[key] = context[key]
    receipt_path = evidence_directory / GEL_FILENAME
    write_json(receipt_path, receipt)
    write_sidecar(evidence_directory / GEL_HASH_FILENAME, receipt_path)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    preflight_parser = subparsers.add_parser("preflight")
    preflight_parser.add_argument("--operation", choices=("readiness", "backup"), required=True)
    preflight_parser.add_argument("--expected-sha", required=True)
    preflight_parser.add_argument("--validated-ci-run-id", required=True)
    preflight_parser.add_argument("--capture-confirmation", default="")
    preflight_parser.add_argument("--head-sha", required=True)
    preflight_parser.add_argument("--github-repository", required=True)
    preflight_parser.add_argument("--github-event-name", required=True)
    preflight_parser.add_argument("--github-ref", required=True)
    preflight_parser.add_argument("--github-sha", required=True)
    preflight_parser.add_argument("--github-run-id", required=True)
    preflight_parser.add_argument("--github-run-attempt", required=True)
    preflight_parser.add_argument("--github-workflow-ref", required=True)
    preflight_parser.add_argument("--source-repository-json", required=True)
    preflight_parser.add_argument("--ci-run-json", required=True)
    preflight_parser.add_argument("--environment-json", required=True)
    preflight_parser.add_argument("--vault-repository-json", required=True)
    preflight_parser.add_argument("--vault-immutable-json", required=True)
    preflight_parser.add_argument("--escrow-release-json", required=True)
    preflight_parser.add_argument("--escrow-asset-json", required=True)
    preflight_parser.add_argument("--escrow-receipt-json", required=True)
    preflight_parser.add_argument("--evidence-directory", required=True)

    capture_parser = subparsers.add_parser("capture")
    capture_parser.add_argument("--expected-sha", required=True)
    capture_parser.add_argument("--workspace", required=True)
    capture_parser.add_argument("--evidence-directory", required=True)

    finalize_parser = subparsers.add_parser("finalize")
    finalize_parser.add_argument("--expected-sha", required=True)
    finalize_parser.add_argument("--vault-release-json", required=True)
    finalize_parser.add_argument("--vault-bundle-asset-json", required=True)
    finalize_parser.add_argument("--vault-manifest-asset-json", required=True)
    finalize_parser.add_argument("--vault-manifest-hash-asset-json", required=True)
    finalize_parser.add_argument("--vault-redownload-path", required=True)
    finalize_parser.add_argument("--evidence-directory", required=True)

    failure_parser = subparsers.add_parser("failure")
    failure_parser.add_argument("--expected-sha", required=True)
    failure_parser.add_argument("--error-code", required=True)
    failure_parser.add_argument("--evidence-directory", required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    evidence_directory = Path(args.evidence_directory)
    try:
        if args.command == "preflight":
            preflight(args)
        elif args.command == "capture":
            capture(args)
        elif args.command == "finalize":
            finalize(args)
        elif args.command == "failure":
            context: Mapping[str, Any] | None = None
            preflight_path = evidence_directory / PREFLIGHT_FILENAME
            if preflight_path.is_file():
                context = load_json(preflight_path, "PREFLIGHT_EVIDENCE_INVALID")
            write_failure_receipt(
                evidence_directory,
                code=args.error_code,
                expected_sha=args.expected_sha,
                context=context,
            )
        else:
            raise BackupError("COMMAND_INVALID")
    except BackupError as error:
        evidence_directory.mkdir(parents=True, exist_ok=True)
        context: Mapping[str, Any] | None = None
        preflight_path = evidence_directory / PREFLIGHT_FILENAME
        if preflight_path.is_file():
            try:
                context = load_json(preflight_path)
            except BackupError:
                context = None
        write_failure_receipt(
            evidence_directory,
            code=error.code,
            expected_sha=getattr(args, "expected_sha", None),
            context=context,
        )
        print(f"ERROR staging backup: {error.code}", file=sys.stderr)
        return 1
    print(f"OK staging backup: {args.command}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
