from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.parse
import zipfile
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence


EXPECTED_REPOSITORY = "urbainmorel/KWABOR"
EXPECTED_ENVIRONMENT = "staging"
EXPECTED_GITHUB_EVENT = "workflow_dispatch"
EXPECTED_GITHUB_REF = "refs/heads/main"
EXPECTED_CI_WORKFLOW = ".github/workflows/ci.yml"
EXPECTED_DATABASE_WORKFLOW = ".github/workflows/closed-beta-staging-database.yml"
EXPECTED_BACKUP_WORKFLOW = ".github/workflows/closed-beta-staging-database-backup.yml"
EXPECTED_SUPABASE_CLI_VERSION = "2.111.0"
APPLY_CONFIRMATION = "APPLY-EXACT-STAGING-MIGRATIONS"
BACKUP_PRODUCER_AVAILABLE = False
TASK_ID = "B6.01.database-migrations"
CONTRIBUTES_TO = "G5"
GEL_FILENAME = "GEL-G5-STAGING-DATABASE.json"
GEL_HASH_FILENAME = f"{GEL_FILENAME}.sha256"
BACKUP_GEL_FILENAME = "GEL-G5-STAGING-DATABASE-BACKUP.json"
BACKUP_GEL_HASH_FILENAME = f"{BACKUP_GEL_FILENAME}.sha256"
REMOTE_MIGRATION_QUERY = (
    "select unnest(xpath('/table/row/version/text()', query_to_xml("
    "case when to_regclass('supabase_migrations.schema_migrations') is null "
    "then 'select null::text as version where false' "
    "else 'select version::text as version from "
    "supabase_migrations.schema_migrations order by version' end,"
    "true,false,'')))::text as version;"
)

SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
COMMIT_SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
PROJECT_REF_PATTERN = re.compile(r"[a-z0-9]{20}")
POSITIVE_INTEGER_PATTERN = re.compile(r"[1-9][0-9]*")
ARTIFACT_DIGEST_PATTERN = re.compile(r"(?P<sha256>[0-9a-f]{64})")
MIGRATION_FILENAME_PATTERN = re.compile(r"(?P<version>[0-9]{14})_[a-z0-9_]+\.sql")
POOLER_HOST_PATTERN = re.compile(r"[a-z0-9-]+\.pooler\.supabase\.com")
ENCODED_PASSWORD_PATTERN = re.compile(r"(?:[A-Za-z0-9._~-]|%[0-9A-Fa-f]{2})+")
GITHUB_ACTOR_PATTERN = re.compile(r"[A-Za-z0-9-]{1,100}")
DATABASE_URI_PATTERN = re.compile(r"(?i)postgres(?:ql)?://[^\s\"'<>]+")
JWT_PATTERN = re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b")
MAX_EVIDENCE_BYTES = 8 * 1024 * 1024

FORBIDDEN_COMMAND_TOKENS = {
    "--include-all",
    "--include-roles",
    "--include-seed",
    "--linked",
    "--local",
    "down",
    "fetch",
    "link",
    "pull",
    "repair",
    "reset",
    "seed",
    "unlink",
}
SENSITIVE_KEY_FRAGMENTS = {
    "apikey",
    "authorization",
    "connectionstring",
    "databaseurl",
    "dburl",
    "password",
    "privatekey",
    "publishablekey",
    "secret",
    "servicerole",
    "token",
}


class StagingDatabaseError(RuntimeError):
    """A fail-closed, non-sensitive operational error."""

    def __init__(self, code: str) -> None:
        if re.fullmatch(r"[A-Z][A-Z0-9_]{2,80}", code) is None:
            raise ValueError("Operational error codes must be stable and non-sensitive")
        super().__init__(code)
        self.code = code


def require(condition: bool, code: str) -> None:
    if not condition:
        raise StagingDatabaseError(code)


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def sha256_text(value: str) -> str:
    return sha256_bytes(value.encode("utf-8"))


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def parse_github_timestamp(value: object, code: str) -> datetime:
    require(isinstance(value, str) and value.endswith("Z"), code)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise StagingDatabaseError(code) from error
    require(parsed.tzinfo is not None, code)
    return parsed.astimezone(timezone.utc)


def canonical_json_bytes(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
    ).encode("utf-8")


def load_json_object(path: Path) -> dict[str, Any]:
    require(path.is_file() and not path.is_symlink(), "JSON_EVIDENCE_MISSING")
    require(path.stat().st_size <= MAX_EVIDENCE_BYTES, "JSON_EVIDENCE_TOO_LARGE")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise StagingDatabaseError("JSON_EVIDENCE_INVALID") from error
    require(isinstance(value, dict), "JSON_EVIDENCE_NOT_OBJECT")
    return value


def _contains_sensitive_key(value: object) -> bool:
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = re.sub(r"[^a-z0-9]", "", str(key).lower())
            if any(fragment in normalized for fragment in SENSITIVE_KEY_FRAGMENTS):
                return True
            if _contains_sensitive_key(child):
                return True
    elif isinstance(value, list):
        return any(_contains_sensitive_key(child) for child in value)
    return False


def assert_safe_text(value: str, secret_values: Iterable[str] = ()) -> None:
    require(len(value.encode("utf-8")) <= MAX_EVIDENCE_BYTES, "EVIDENCE_TOO_LARGE")
    require(DATABASE_URI_PATTERN.search(value) is None, "DATABASE_URI_IN_EVIDENCE")
    require(JWT_PATTERN.search(value) is None, "JWT_IN_EVIDENCE")
    for secret in secret_values:
        if secret:
            require(secret not in value, "SECRET_IN_EVIDENCE")


def assert_safe_document(value: object, secret_values: Iterable[str] = ()) -> None:
    require(not _contains_sensitive_key(value), "SENSITIVE_FIELD_IN_EVIDENCE")
    serialized = canonical_json_bytes(value).decode("utf-8")
    assert_safe_text(serialized, secret_values)


def write_text_exclusive(
    path: Path,
    value: str,
    *,
    secret_values: Iterable[str] = (),
) -> None:
    assert_safe_text(value, secret_values)
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("x", encoding="utf-8", newline="\n") as destination:
            destination.write(value)
    except FileExistsError as error:
        raise StagingDatabaseError("EVIDENCE_OVERWRITE_REFUSED") from error


def write_json_exclusive(
    path: Path,
    value: object,
    *,
    secret_values: Iterable[str] = (),
) -> None:
    assert_safe_document(value, secret_values)
    write_text_exclusive(
        path,
        canonical_json_bytes(value).decode("utf-8"),
        secret_values=secret_values,
    )


def validate_positive_integer(value: str, code: str) -> int:
    require(POSITIVE_INTEGER_PATTERN.fullmatch(value) is not None and len(value) <= 19, code)
    parsed = int(value)
    require(parsed <= 9_223_372_036_854_775_807, code)
    return parsed


def optional_positive_integer(value: str) -> int | None:
    if POSITIVE_INTEGER_PATTERN.fullmatch(value) is None or len(value) > 19:
        return None
    parsed = int(value)
    return parsed if parsed <= 9_223_372_036_854_775_807 else None


def validate_operation_inputs(
    *,
    operation: str,
    confirmation: str,
    backup_run_id: str,
    backup_artifact_id: str,
    backup_artifact_digest: str,
    validated_plan_run_id: str,
    validated_plan_artifact_id: str,
    validated_plan_artifact_digest: str,
) -> dict[str, Any] | None:
    require(operation in {"plan", "apply", "verify"}, "OPERATION_INVALID")
    apply_only_values = (
        confirmation,
        backup_run_id,
        backup_artifact_id,
        backup_artifact_digest,
        validated_plan_run_id,
        validated_plan_artifact_id,
        validated_plan_artifact_digest,
    )
    if operation != "apply":
        require(not any(apply_only_values), "APPLY_AUTHORITY_UNEXPECTED")
        return None

    require(confirmation == APPLY_CONFIRMATION, "APPLY_CONFIRMATION_INVALID")
    backup_run = validate_positive_integer(backup_run_id, "BACKUP_RUN_ID_INVALID")
    backup_artifact = validate_positive_integer(
        backup_artifact_id, "BACKUP_ARTIFACT_ID_INVALID"
    )
    plan_run = validate_positive_integer(validated_plan_run_id, "PLAN_RUN_ID_INVALID")
    plan_artifact = validate_positive_integer(
        validated_plan_artifact_id, "PLAN_ARTIFACT_ID_INVALID"
    )
    require(backup_run != plan_run, "APPLY_AUTHORITY_RUN_COLLISION")
    require(backup_artifact != plan_artifact, "APPLY_AUTHORITY_ARTIFACT_COLLISION")
    require(
        ARTIFACT_DIGEST_PATTERN.fullmatch(backup_artifact_digest) is not None,
        "BACKUP_ARTIFACT_DIGEST_INVALID",
    )
    require(
        ARTIFACT_DIGEST_PATTERN.fullmatch(validated_plan_artifact_digest) is not None,
        "PLAN_ARTIFACT_DIGEST_INVALID",
    )
    return {
        "backupArtifactDigest": backup_artifact_digest,
        "backupArtifactId": backup_artifact,
        "backupRunId": backup_run,
        "planArtifactDigest": validated_plan_artifact_digest,
        "planArtifactId": plan_artifact,
        "planRunId": plan_run,
    }


def validate_request_identity(
    *,
    repository: str,
    event_name: str,
    github_ref: str,
    github_sha: str,
    checked_out_sha: str,
    expected_sha: str,
    validated_ci_run_id: str,
    run_id: str,
    run_attempt: str,
    actor: str,
    server_url: str,
) -> dict[str, Any]:
    require(repository == EXPECTED_REPOSITORY, "REPOSITORY_NOT_CANONICAL")
    require(event_name == EXPECTED_GITHUB_EVENT, "EVENT_NOT_MANUAL")
    require(github_ref == EXPECTED_GITHUB_REF, "REF_NOT_MAIN")
    require(COMMIT_SHA_PATTERN.fullmatch(expected_sha) is not None, "EXPECTED_SHA_INVALID")
    require(github_sha == expected_sha, "DISPATCH_SHA_DRIFT")
    require(checked_out_sha == expected_sha, "CHECKOUT_SHA_DRIFT")
    ci_run_id = validate_positive_integer(validated_ci_run_id, "CI_RUN_ID_INVALID")
    workflow_run_id = validate_positive_integer(run_id, "WORKFLOW_RUN_ID_INVALID")
    workflow_run_attempt = validate_positive_integer(run_attempt, "WORKFLOW_RUN_ATTEMPT_INVALID")
    require(ci_run_id != workflow_run_id, "CI_RUN_ID_INVALID")
    require(GITHUB_ACTOR_PATTERN.fullmatch(actor) is not None, "WORKFLOW_ACTOR_INVALID")
    require(server_url == "https://github.com", "GITHUB_SERVER_INVALID")
    return {
        "actor": actor,
        "event": EXPECTED_GITHUB_EVENT,
        "expectedSha": expected_sha,
        "ref": EXPECTED_GITHUB_REF,
        "repository": EXPECTED_REPOSITORY,
        "runAttempt": workflow_run_attempt,
        "runId": workflow_run_id,
        "runUrl": f"https://github.com/{EXPECTED_REPOSITORY}/actions/runs/{workflow_run_id}",
        "validatedCiRunId": ci_run_id,
    }


def validate_ci_run(
    document: Mapping[str, Any],
    *,
    expected_run_id: str,
    expected_sha: str,
) -> dict[str, Any]:
    run_id = validate_positive_integer(expected_run_id, "CI_RUN_ID_INVALID")
    repository = document.get("repository")
    workflow_path = str(document.get("path", "")).split("@", maxsplit=1)[0]
    require(document.get("id") == run_id, "CI_RUN_ID_DRIFT")
    require(
        isinstance(repository, dict) and repository.get("full_name") == EXPECTED_REPOSITORY,
        "CI_REPOSITORY_DRIFT",
    )
    require(document.get("head_sha") == expected_sha, "CI_SHA_DRIFT")
    require(document.get("head_branch") == "main", "CI_BRANCH_DRIFT")
    require(document.get("event") == "push", "CI_EVENT_DRIFT")
    require(document.get("status") == "completed", "CI_NOT_COMPLETED")
    require(document.get("conclusion") == "success", "CI_NOT_SUCCESSFUL")
    require(workflow_path == EXPECTED_CI_WORKFLOW, "CI_WORKFLOW_DRIFT")
    expected_url = f"https://github.com/{EXPECTED_REPOSITORY}/actions/runs/{run_id}"
    require(document.get("html_url") == expected_url, "CI_RUN_URL_DRIFT")
    run_attempt = document.get("run_attempt")
    run_number = document.get("run_number")
    require(isinstance(run_attempt, int) and run_attempt > 0, "CI_RUN_ATTEMPT_INVALID")
    require(isinstance(run_number, int) and run_number > 0, "CI_RUN_NUMBER_INVALID")
    return {
        "conclusion": "success",
        "event": "push",
        "headBranch": "main",
        "headSha": expected_sha,
        "runAttempt": run_attempt,
        "runId": run_id,
        "runNumber": run_number,
        "runUrl": expected_url,
        "status": "completed",
        "workflowPath": EXPECTED_CI_WORKFLOW,
    }


def validate_supporting_workflow_run(
    document: Mapping[str, Any],
    *,
    expected_run_id: int,
    expected_sha: str,
    expected_workflow: str,
) -> dict[str, Any]:
    repository = document.get("repository")
    workflow_path = str(document.get("path", "")).split("@", maxsplit=1)[0]
    require(document.get("id") == expected_run_id, "SUPPORTING_RUN_ID_DRIFT")
    require(
        isinstance(repository, dict)
        and repository.get("full_name") == EXPECTED_REPOSITORY
        and isinstance(repository.get("id"), int)
        and repository["id"] > 0,
        "SUPPORTING_RUN_REPOSITORY_DRIFT",
    )
    require(document.get("head_sha") == expected_sha, "SUPPORTING_RUN_SHA_DRIFT")
    require(document.get("head_branch") == "main", "SUPPORTING_RUN_BRANCH_DRIFT")
    require(document.get("event") == EXPECTED_GITHUB_EVENT, "SUPPORTING_RUN_EVENT_DRIFT")
    require(document.get("status") == "completed", "SUPPORTING_RUN_NOT_COMPLETED")
    require(document.get("conclusion") == "success", "SUPPORTING_RUN_NOT_SUCCESSFUL")
    require(workflow_path == expected_workflow, "SUPPORTING_RUN_WORKFLOW_DRIFT")
    run_attempt = document.get("run_attempt")
    require(isinstance(run_attempt, int) and run_attempt > 0, "SUPPORTING_RUN_ATTEMPT_INVALID")
    expected_url = f"https://github.com/{EXPECTED_REPOSITORY}/actions/runs/{expected_run_id}"
    require(document.get("html_url") == expected_url, "SUPPORTING_RUN_URL_DRIFT")
    return {
        "headSha": expected_sha,
        "repositoryId": repository["id"],
        "runAttempt": run_attempt,
        "runId": expected_run_id,
        "runUrl": expected_url,
        "workflowPath": expected_workflow,
    }


def validate_artifact_metadata(
    document: Mapping[str, Any],
    *,
    expected_artifact_id: int,
    expected_run_id: int,
    expected_repository_id: int,
    expected_sha: str,
    expected_name: str,
    expected_digest: str,
) -> dict[str, Any]:
    require(document.get("id") == expected_artifact_id, "ARTIFACT_ID_DRIFT")
    require(document.get("name") == expected_name, "ARTIFACT_NAME_DRIFT")
    require(document.get("expired") is False, "ARTIFACT_EXPIRED")
    require(document.get("digest") == f"sha256:{expected_digest}", "ARTIFACT_DIGEST_DRIFT")
    size_bytes = document.get("size_in_bytes")
    require(isinstance(size_bytes, int) and 0 < size_bytes <= 2_147_483_648, "ARTIFACT_SIZE_INVALID")
    expected_api_url = (
        f"https://api.github.com/repos/{EXPECTED_REPOSITORY}/actions/artifacts/"
        f"{expected_artifact_id}"
    )
    require(document.get("url") == expected_api_url, "ARTIFACT_REPOSITORY_DRIFT")
    require(
        document.get("archive_download_url") == f"{expected_api_url}/zip",
        "ARTIFACT_ARCHIVE_URL_DRIFT",
    )
    workflow_run = document.get("workflow_run")
    require(isinstance(workflow_run, dict), "ARTIFACT_WORKFLOW_RUN_MISSING")
    require(workflow_run.get("id") == expected_run_id, "ARTIFACT_RUN_ID_DRIFT")
    require(
        workflow_run.get("repository_id") == expected_repository_id
        and workflow_run.get("head_repository_id") == expected_repository_id,
        "ARTIFACT_REPOSITORY_DRIFT",
    )
    require(workflow_run.get("head_sha") == expected_sha, "ARTIFACT_SHA_DRIFT")
    require(workflow_run.get("head_branch") == "main", "ARTIFACT_BRANCH_DRIFT")
    created_at = parse_github_timestamp(document.get("created_at"), "ARTIFACT_TIMESTAMP_INVALID")
    updated_at = parse_github_timestamp(document.get("updated_at"), "ARTIFACT_TIMESTAMP_INVALID")
    expires_at = parse_github_timestamp(document.get("expires_at"), "ARTIFACT_TIMESTAMP_INVALID")
    require(created_at <= updated_at < expires_at, "ARTIFACT_TIMESTAMP_INVALID")
    require(expires_at > datetime.now(timezone.utc), "ARTIFACT_EXPIRED")
    return {
        "artifactSha256": expected_digest,
        "artifactId": expected_artifact_id,
        "artifactName": expected_name,
        "artifactUrl": f"https://github.com/{EXPECTED_REPOSITORY}/actions/runs/"
        f"{expected_run_id}/artifacts/{expected_artifact_id}",
        "expired": False,
        "runId": expected_run_id,
        "sizeBytes": size_bytes,
    }


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_artifact_entries(
    archive_path: Path,
    *,
    expected_digest: str,
    required_entries: Sequence[str],
    max_archive_bytes: int = 128 * 1024 * 1024,
    max_total_uncompressed_bytes: int = 128 * 1024 * 1024,
    allow_large_nonrequired_entries: bool = False,
) -> dict[str, bytes]:
    require(archive_path.is_file() and not archive_path.is_symlink(), "ARTIFACT_ARCHIVE_MISSING")
    require(
        0 < archive_path.stat().st_size <= max_archive_bytes,
        "ARTIFACT_ARCHIVE_SIZE_INVALID",
    )
    digest_match = ARTIFACT_DIGEST_PATTERN.fullmatch(expected_digest)
    require(digest_match is not None, "ARTIFACT_DIGEST_INVALID")
    require(_sha256_file(archive_path) == digest_match.group("sha256"), "ARTIFACT_ARCHIVE_DIGEST_DRIFT")
    required = set(required_entries)
    try:
        with zipfile.ZipFile(archive_path, "r") as archive:
            members = archive.infolist()
            require(0 < len(members) <= 512, "ARTIFACT_ARCHIVE_ENTRY_COUNT_INVALID")
            names = [member.filename for member in members]
            require(len(names) == len(set(names)), "ARTIFACT_ARCHIVE_DUPLICATE_ENTRY")
            require(bool(names) and required.issubset(set(names)), "ARTIFACT_RECEIPT_MISSING")
            total_uncompressed = 0
            for member in members:
                path = Path(member.filename)
                require(
                    not member.is_dir()
                    and not path.is_absolute()
                    and ".." not in path.parts
                    and len(path.parts) == 1,
                    "ARTIFACT_ARCHIVE_ENTRY_INVALID",
                )
                require(
                    re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,199}", member.filename)
                    is not None,
                    "ARTIFACT_ARCHIVE_ENTRY_INVALID",
                )
                require((member.external_attr >> 16) & 0o170000 != 0o120000, "ARTIFACT_ARCHIVE_SYMLINK")
                maximum_member_size = (
                    max_total_uncompressed_bytes
                    if allow_large_nonrequired_entries and member.filename not in required
                    else MAX_EVIDENCE_BYTES
                )
                require(
                    0 <= member.file_size <= maximum_member_size,
                    "ARTIFACT_ENTRY_TOO_LARGE",
                )
                total_uncompressed += member.file_size
            require(
                total_uncompressed <= max_total_uncompressed_bytes,
                "ARTIFACT_ARCHIVE_TOO_LARGE",
            )
            if not allow_large_nonrequired_entries:
                require(archive.testzip() is None, "ARTIFACT_ARCHIVE_CORRUPT")
            return {name: archive.read(name) for name in required_entries}
    except StagingDatabaseError:
        raise
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        raise StagingDatabaseError("ARTIFACT_ARCHIVE_INVALID") from error


def _load_internal_json(entries: Mapping[str, bytes], name: str) -> dict[str, Any]:
    try:
        value = json.loads(entries[name].decode("utf-8"))
    except (KeyError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise StagingDatabaseError("ARTIFACT_RECEIPT_INVALID") from error
    require(isinstance(value, dict), "ARTIFACT_RECEIPT_INVALID")
    assert_safe_document(value)
    return value


def _validate_internal_receipt_hash(
    entries: Mapping[str, bytes],
    *,
    receipt_name: str,
    sidecar_name: str,
) -> str:
    receipt_digest = sha256_bytes(entries[receipt_name])
    try:
        sidecar = entries[sidecar_name].decode("ascii")
    except (KeyError, UnicodeDecodeError) as error:
        raise StagingDatabaseError("ARTIFACT_RECEIPT_HASH_INVALID") from error
    require(
        sidecar == f"{receipt_digest}  {receipt_name}\n",
        "ARTIFACT_RECEIPT_HASH_INVALID",
    )
    return receipt_digest


def validate_plan_artifact_bundle(
    *,
    run_document: Mapping[str, Any],
    artifact_document: Mapping[str, Any],
    archive_path: Path,
    plan_run_id: int,
    plan_artifact_id: int,
    plan_artifact_digest: str,
    expected_sha: str,
    validated_ci_run_id: int,
    target_evidence: Mapping[str, Any],
    migration_manifest: Mapping[str, Any],
) -> dict[str, Any]:
    run_evidence = validate_supporting_workflow_run(
        run_document,
        expected_run_id=plan_run_id,
        expected_sha=expected_sha,
        expected_workflow=EXPECTED_DATABASE_WORKFLOW,
    )
    expected_name = (
        f"kwabor-gel-g5-staging-database-plan-{expected_sha}-{run_evidence['runAttempt']}"
    )
    artifact_evidence = validate_artifact_metadata(
        artifact_document,
        expected_artifact_id=plan_artifact_id,
        expected_run_id=plan_run_id,
        expected_repository_id=run_evidence["repositoryId"],
        expected_sha=expected_sha,
        expected_name=expected_name,
        expected_digest=plan_artifact_digest,
    )
    require(artifact_evidence["sizeBytes"] <= 128 * 1024 * 1024, "PLAN_ARTIFACT_TOO_LARGE")
    pending_name = "PLAN-PENDING-CHECK.json"
    manifest_name = "LOCAL-MIGRATION-MANIFEST.json"
    dry_run_name = "PLAN-DRY-RUN.txt"
    entries = load_artifact_entries(
        archive_path,
        expected_digest=plan_artifact_digest,
        required_entries=(
            GEL_FILENAME,
            GEL_HASH_FILENAME,
            pending_name,
            manifest_name,
            dry_run_name,
        ),
    )
    require(
        archive_path.stat().st_size == artifact_evidence["sizeBytes"],
        "ARTIFACT_ARCHIVE_SIZE_DRIFT",
    )
    receipt_digest = _validate_internal_receipt_hash(
        entries,
        receipt_name=GEL_FILENAME,
        sidecar_name=GEL_HASH_FILENAME,
    )
    receipt = _load_internal_json(entries, GEL_FILENAME)
    pending = _load_internal_json(entries, pending_name)
    archived_manifest = _load_internal_json(entries, manifest_name)
    target_digest = sha256_bytes(canonical_json_bytes(target_evidence))
    require(receipt.get("repository") == EXPECTED_REPOSITORY, "PLAN_RECEIPT_REPOSITORY_DRIFT")
    require(receipt.get("operation") == "plan", "PLAN_RECEIPT_OPERATION_DRIFT")
    require(receipt.get("status") == "succeeded", "PLAN_RECEIPT_NOT_SUCCESSFUL")
    require(receipt.get("schemaVersion") == 2, "PLAN_RECEIPT_SCHEMA_DRIFT")
    require(receipt.get("executionDisposition") == "EXECUTED", "PLAN_RECEIPT_NOT_EXECUTED")
    require(receipt.get("expectedSha") == expected_sha, "PLAN_RECEIPT_SHA_DRIFT")
    require(receipt.get("validatedCiRunId") == validated_ci_run_id, "PLAN_RECEIPT_CI_DRIFT")
    receipt_ci = receipt.get("ci")
    require(
        isinstance(receipt_ci, dict)
        and receipt_ci.get("runId") == validated_ci_run_id
        and receipt_ci.get("headSha") == expected_sha
        and receipt_ci.get("workflowPath") == EXPECTED_CI_WORKFLOW
        and receipt_ci.get("status") == "completed"
        and receipt_ci.get("conclusion") == "success",
        "PLAN_RECEIPT_CI_DRIFT",
    )
    require(receipt.get("target") == target_evidence, "PLAN_RECEIPT_TARGET_DRIFT")
    require(receipt.get("targetDigestSha256") == target_digest, "PLAN_RECEIPT_TARGET_DRIFT")
    require(receipt.get("taskId") == TASK_ID, "PLAN_RECEIPT_TASK_DRIFT")
    require(receipt.get("contributesTo") == CONTRIBUTES_TO, "PLAN_RECEIPT_GATE_DRIFT")
    require(
        receipt.get("runId") == plan_run_id
        and receipt.get("runAttempt") == run_evidence["runAttempt"]
        and receipt.get("runUrl") == run_evidence["runUrl"],
        "PLAN_RECEIPT_RUN_DRIFT",
    )
    expected_manifest_summary = {
        "count": migration_manifest["count"],
        "manifestSha256": migration_manifest["manifestSha256"],
    }
    require(
        receipt.get("migrationManifest") == expected_manifest_summary,
        "PLAN_RECEIPT_MANIFEST_DRIFT",
    )
    require(
        archived_manifest.get("manifestSha256") == migration_manifest["manifestSha256"]
        and archived_manifest.get("count") == migration_manifest["count"],
        "PLAN_ARCHIVED_MANIFEST_DRIFT",
    )
    require(pending.get("remoteIsExactLocalPrefix") is True, "PLAN_REMOTE_PREFIX_UNPROVEN")
    pending_versions = pending.get("pendingVersions")
    pending_count = pending.get("pendingCount")
    remote_count = pending.get("remoteCount")
    require(
        isinstance(pending_versions, list)
        and all(
            isinstance(version, str) and re.fullmatch(r"[0-9]{14}", version)
            for version in pending_versions
        )
        and pending_versions == sorted(set(pending_versions)),
        "PLAN_PENDING_EVIDENCE_INVALID",
    )
    require(
        isinstance(pending_count, int)
        and pending_count == len(pending_versions)
        and isinstance(remote_count, int)
        and remote_count >= 0
        and remote_count + pending_count == migration_manifest["count"],
        "PLAN_PENDING_EVIDENCE_INVALID",
    )
    require(
        SHA256_PATTERN.fullmatch(str(pending.get("pendingVersionsSha256", ""))) is not None,
        "PLAN_PENDING_EVIDENCE_INVALID",
    )
    expected_pending_digest = sha256_text(
        "\n".join(pending_versions) + ("\n" if pending_versions else "")
    )
    require(
        pending["pendingVersionsSha256"] == expected_pending_digest,
        "PLAN_PENDING_EVIDENCE_INVALID",
    )
    require(receipt.get("migrationStateEvidence") == pending, "PLAN_PENDING_EVIDENCE_DRIFT")
    receipt_evidence = receipt.get("evidence")
    require(isinstance(receipt_evidence, dict), "PLAN_RECEIPT_EVIDENCE_INVALID")
    for evidence_name in (pending_name, manifest_name, dry_run_name):
        archived_digest = receipt_evidence.get(evidence_name)
        require(
            isinstance(archived_digest, dict)
            and archived_digest.get("sha256") == sha256_bytes(entries[evidence_name]),
            "PLAN_EVIDENCE_FILE_DIGEST_DRIFT",
        )
    return {
        **artifact_evidence,
        "internalReceiptSha256": receipt_digest,
        "pendingCount": pending["pendingCount"],
        "pendingVersionsSha256": pending["pendingVersionsSha256"],
        "runAttempt": run_evidence["runAttempt"],
        "targetDigestSha256": target_digest,
    }


def validate_backup_artifact_bundle(
    *,
    run_document: Mapping[str, Any],
    artifact_document: Mapping[str, Any],
    archive_path: Path,
    backup_run_id: int,
    backup_artifact_id: int,
    backup_artifact_digest: str,
    expected_sha: str,
    validated_ci_run_id: int,
    target_evidence: Mapping[str, Any],
) -> dict[str, Any]:
    run_evidence = validate_supporting_workflow_run(
        run_document,
        expected_run_id=backup_run_id,
        expected_sha=expected_sha,
        expected_workflow=EXPECTED_BACKUP_WORKFLOW,
    )
    expected_name = (
        f"kwabor-gel-g5-staging-database-backup-{expected_sha}-{run_evidence['runAttempt']}"
    )
    artifact_evidence = validate_artifact_metadata(
        artifact_document,
        expected_artifact_id=backup_artifact_id,
        expected_run_id=backup_run_id,
        expected_repository_id=run_evidence["repositoryId"],
        expected_sha=expected_sha,
        expected_name=expected_name,
        expected_digest=backup_artifact_digest,
    )
    entries = load_artifact_entries(
        archive_path,
        expected_digest=backup_artifact_digest,
        required_entries=(BACKUP_GEL_FILENAME, BACKUP_GEL_HASH_FILENAME),
        max_archive_bytes=2_147_483_648,
        max_total_uncompressed_bytes=8 * 1024 * 1024 * 1024,
        allow_large_nonrequired_entries=True,
    )
    require(
        archive_path.stat().st_size == artifact_evidence["sizeBytes"],
        "ARTIFACT_ARCHIVE_SIZE_DRIFT",
    )
    receipt_digest = _validate_internal_receipt_hash(
        entries,
        receipt_name=BACKUP_GEL_FILENAME,
        sidecar_name=BACKUP_GEL_HASH_FILENAME,
    )
    receipt = _load_internal_json(entries, BACKUP_GEL_FILENAME)
    target_digest = sha256_bytes(canonical_json_bytes(target_evidence))
    require(receipt.get("repository") == EXPECTED_REPOSITORY, "BACKUP_RECEIPT_REPOSITORY_DRIFT")
    require(receipt.get("operation") == "backup", "BACKUP_RECEIPT_OPERATION_DRIFT")
    require(receipt.get("status") == "succeeded", "BACKUP_RECEIPT_NOT_SUCCESSFUL")
    require(receipt.get("expectedSha") == expected_sha, "BACKUP_RECEIPT_SHA_DRIFT")
    require(receipt.get("validatedCiRunId") == validated_ci_run_id, "BACKUP_RECEIPT_CI_DRIFT")
    require(
        receipt.get("runId") == backup_run_id
        and receipt.get("runAttempt") == run_evidence["runAttempt"]
        and receipt.get("runUrl") == run_evidence["runUrl"],
        "BACKUP_RECEIPT_RUN_DRIFT",
    )
    require(receipt.get("target") == target_evidence, "BACKUP_RECEIPT_TARGET_DRIFT")
    require(receipt.get("targetDigestSha256") == target_digest, "BACKUP_RECEIPT_TARGET_DRIFT")
    require(receipt.get("restorable") is True, "BACKUP_RECEIPT_NOT_RESTORABLE")
    return {
        **artifact_evidence,
        "internalReceiptSha256": receipt_digest,
        "restorable": True,
        "runAttempt": run_evidence["runAttempt"],
        "targetDigestSha256": target_digest,
    }


def validate_environment_protection(document: Mapping[str, Any]) -> dict[str, Any]:
    require(document.get("name") == EXPECTED_ENVIRONMENT, "ENVIRONMENT_IDENTITY_DRIFT")
    require(document.get("can_admins_bypass") is False, "ENVIRONMENT_ADMIN_BYPASS_ENABLED")
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
    require(reviewer_rule.get("prevent_self_review") is True, "ENVIRONMENT_SELF_REVIEW_ENABLED")
    reviewers = reviewer_rule.get("reviewers")
    require(isinstance(reviewers, list) and bool(reviewers), "ENVIRONMENT_REVIEWER_MISSING")
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
    branch_policy = document.get("deployment_branch_policy")
    require(isinstance(branch_policy, dict), "ENVIRONMENT_BRANCH_POLICY_MISSING")
    require(
        branch_policy.get("protected_branches") is True
        and branch_policy.get("custom_branch_policies") is False,
        "ENVIRONMENT_BRANCH_POLICY_INVALID",
    )
    environment_id = document.get("id")
    require(isinstance(environment_id, int) and environment_id > 0, "ENVIRONMENT_RESOURCE_INVALID")
    updated_at = document.get("updated_at")
    require(isinstance(updated_at, str) and bool(updated_at), "ENVIRONMENT_TIMESTAMP_MISSING")
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
    secret_values: tuple[str, ...] = field(repr=False)

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


def _parse_database_url(database_url: str, project_ref: str) -> tuple[str, str, tuple[str, ...]]:
    require(1 <= len(database_url) <= 4096, "DATABASE_URL_INVALID")
    require(database_url == database_url.strip(), "DATABASE_URL_INVALID")
    require(database_url.isascii(), "DATABASE_URL_INVALID")
    require(not any(character.isspace() or ord(character) < 32 for character in database_url), "DATABASE_URL_INVALID")
    require(database_url.count("@") == 1, "DATABASE_URL_INVALID")
    require("?" not in database_url and "#" not in database_url, "DATABASE_URL_OVERRIDE_FORBIDDEN")
    try:
        parsed = urllib.parse.urlsplit(database_url)
        port = parsed.port
    except ValueError as error:
        raise StagingDatabaseError("DATABASE_URL_INVALID") from error
    require(parsed.scheme == "postgresql", "DATABASE_URL_SCHEME_INVALID")
    require(parsed.query == "" and parsed.fragment == "", "DATABASE_URL_OVERRIDE_FORBIDDEN")
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
        raise StagingDatabaseError("DATABASE_USERINFO_INVALID") from error
    require(1 <= len(decoded_password) <= 1024, "DATABASE_PASSWORD_INVALID")
    require(
        not any(character.isspace() or ord(character) < 32 for character in decoded_password),
        "DATABASE_PASSWORD_INVALID",
    )

    direct_host = f"db.{project_ref}.supabase.co"
    if hostname == direct_host:
        require(username == "postgres", "DATABASE_USERNAME_INVALID")
        endpoint_class = "direct"
    else:
        require(POOLER_HOST_PATTERN.fullmatch(hostname) is not None, "DATABASE_HOST_INVALID")
        require(username == f"postgres.{project_ref}", "DATABASE_USERNAME_INVALID")
        endpoint_class = "session-pooler"
    secret_values = tuple(
        dict.fromkeys(value for value in (database_url, raw_password, decoded_password) if value)
    )
    return endpoint_class, hostname, secret_values


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
    endpoint_class, hostname, secret_values = _parse_database_url(database_url, project_ref)
    return TargetAuthority(
        api_url=api_url,
        project_ref=project_ref,
        project_ref_sha256=project_ref_sha256,
        production_project_ref=production_project_ref,
        database_endpoint_class=endpoint_class,
        database_host_sha256=sha256_text(hostname),
        database_url=database_url,
        secret_values=secret_values,
    )


def validate_command_policy(command: Sequence[str], *, database_url: str) -> None:
    require(bool(command) and command[0] == "supabase", "COMMAND_NOT_SUPABASE")
    require(command.count(database_url) == 1, "COMMAND_DATABASE_URL_INVALID")
    database_url_index = command.index(database_url)
    require(database_url_index > 0 and command[database_url_index - 1] == "--db-url", "COMMAND_DATABASE_URL_INVALID")
    normalized_tokens = {token.lower() for token in command if token != database_url}
    require(not (normalized_tokens & FORBIDDEN_COMMAND_TOKENS), "COMMAND_FORBIDDEN")
    require("production" not in " ".join(normalized_tokens), "COMMAND_PRODUCTION_FORBIDDEN")

    allowed_shapes = {
        ("supabase", "db", "push", "--dry-run", "--db-url", "<DATABASE_URL>"),
        ("supabase", "migration", "list", "--db-url", "<DATABASE_URL>"),
        (
            "supabase",
            "db",
            "lint",
            "--schema",
            "public",
            "--level",
            "warning",
            "--fail-on",
            "warning",
            "--db-url",
            "<DATABASE_URL>",
        ),
        (
            "supabase",
            "db",
            "lint",
            "--schema",
            "app_private",
            "--level",
            "warning",
            "--fail-on",
            "warning",
            "--db-url",
            "<DATABASE_URL>",
        ),
        (
            "supabase",
            "db",
            "advisors",
            "--type",
            "security",
            "--level",
            "warn",
            "--fail-on",
            "warn",
            "--db-url",
            "<DATABASE_URL>",
        ),
        (
            "supabase",
            "db",
            "advisors",
            "--type",
            "performance",
            "--level",
            "warn",
            "--fail-on",
            "none",
            "--db-url",
            "<DATABASE_URL>",
        ),
        (
            "supabase",
            "db",
            "query",
            REMOTE_MIGRATION_QUERY,
            "--output",
            "csv",
            "--agent=no",
            "--db-url",
            "<DATABASE_URL>",
        ),
    }
    shape = tuple("<DATABASE_URL>" if token == database_url else token for token in command)
    require(shape in allowed_shapes, "COMMAND_SHAPE_FORBIDDEN")


def build_command(kind: str, authority: TargetAuthority) -> list[str]:
    database_url = authority.database_url
    commands = {
        "plan": ["supabase", "db", "push", "--dry-run", "--db-url", database_url],
        "migration-list": ["supabase", "migration", "list", "--db-url", database_url],
        "lint-public": [
            "supabase",
            "db",
            "lint",
            "--schema",
            "public",
            "--level",
            "warning",
            "--fail-on",
            "warning",
            "--db-url",
            database_url,
        ],
        "lint-app-private": [
            "supabase",
            "db",
            "lint",
            "--schema",
            "app_private",
            "--level",
            "warning",
            "--fail-on",
            "warning",
            "--db-url",
            database_url,
        ],
        "advisors-security": [
            "supabase",
            "db",
            "advisors",
            "--type",
            "security",
            "--level",
            "warn",
            "--fail-on",
            "warn",
            "--db-url",
            database_url,
        ],
        "advisors-performance": [
            "supabase",
            "db",
            "advisors",
            "--type",
            "performance",
            "--level",
            "warn",
            "--fail-on",
            "none",
            "--db-url",
            database_url,
        ],
        "remote-migrations": [
            "supabase",
            "db",
            "query",
            REMOTE_MIGRATION_QUERY,
            "--output",
            "csv",
            "--agent=no",
            "--db-url",
            database_url,
        ],
    }
    require(kind in commands, "COMMAND_KIND_FORBIDDEN")
    command = commands[kind]
    validate_command_policy(command, database_url=database_url)
    return command


def sanitized_subprocess_environment(source: Mapping[str, str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for key, value in source.items():
        normalized = key.upper()
        if (
            normalized.startswith("PG")
            or normalized.startswith("KWABOR_")
            or normalized.startswith("SUPABASE_")
        ):
            continue
        if normalized == "DATABASE_URL":
            continue
        result[key] = value
    result["NO_COLOR"] = "1"
    result["SUPABASE_TELEMETRY_DISABLED"] = "1"
    return result


def sanitize_process_output(value: str, authority: TargetAuthority) -> str:
    sanitized = value
    for secret in sorted(authority.secret_values, key=len, reverse=True):
        sanitized = sanitized.replace(secret, "[REDACTED]")
    sanitized = DATABASE_URI_PATTERN.sub("[REDACTED_DATABASE_URL]", sanitized)
    sanitized = re.sub(
        r"(?i)\b(password|passwd|pwd)\s*[:=]\s*[^\s,;]+",
        r"\1=[REDACTED]",
        sanitized,
    )
    assert_safe_text(sanitized, authority.secret_values)
    return sanitized


def render_process_output(
    result: subprocess.CompletedProcess[str],
    authority: TargetAuthority,
) -> str:
    stdout = sanitize_process_output(result.stdout or "", authority).rstrip()
    stderr = sanitize_process_output(result.stderr or "", authority).rstrip()
    return (
        f"exitCode={result.returncode}\n"
        "[stdout]\n"
        f"{stdout if stdout else '(empty)'}\n"
        "[stderr]\n"
        f"{stderr if stderr else '(empty)'}\n"
    )


def run_cli(
    *,
    kind: str,
    evidence_path: Path,
    authority: TargetAuthority,
    repository_root: Path,
) -> subprocess.CompletedProcess[str]:
    command = build_command(kind, authority)
    try:
        result = subprocess.run(
            command,
            cwd=repository_root,
            env=sanitized_subprocess_environment(os.environ),
            capture_output=True,
            check=False,
            text=True,
            timeout=900,
        )
    except FileNotFoundError as error:
        raise StagingDatabaseError("SUPABASE_CLI_MISSING") from error
    except subprocess.TimeoutExpired as error:
        stdout = error.stdout.decode("utf-8", errors="replace") if isinstance(error.stdout, bytes) else (error.stdout or "")
        stderr = error.stderr.decode("utf-8", errors="replace") if isinstance(error.stderr, bytes) else (error.stderr or "")
        timed_out = subprocess.CompletedProcess(
            args=command,
            returncode=124,
            stdout=stdout,
            stderr=f"{stderr}\n[command timed out]".strip(),
        )
        write_text_exclusive(
            evidence_path,
            render_process_output(timed_out, authority),
            secret_values=authority.secret_values,
        )
        raise StagingDatabaseError(
            f"SUPABASE_{kind.upper().replace('-', '_')}_TIMEOUT"
        ) from error
    write_text_exclusive(
        evidence_path,
        render_process_output(result, authority),
        secret_values=authority.secret_values,
    )
    require(result.returncode == 0, f"SUPABASE_{kind.upper().replace('-', '_')}_FAILED")
    return result


def verify_cli_version(
    *,
    evidence_path: Path,
    authority: TargetAuthority,
    repository_root: Path,
) -> None:
    try:
        result = subprocess.run(
            ["supabase", "--version"],
            cwd=repository_root,
            env=sanitized_subprocess_environment(os.environ),
            capture_output=True,
            check=False,
            text=True,
            timeout=30,
        )
    except FileNotFoundError as error:
        raise StagingDatabaseError("SUPABASE_CLI_MISSING") from error
    except subprocess.TimeoutExpired as error:
        raise StagingDatabaseError("SUPABASE_CLI_TIMEOUT") from error
    write_text_exclusive(
        evidence_path,
        render_process_output(result, authority),
        secret_values=authority.secret_values,
    )
    require(result.returncode == 0, "SUPABASE_CLI_VERSION_FAILED")
    version_lines = [line.strip() for line in (result.stdout or "").splitlines() if line.strip()]
    require(
        bool(version_lines) and version_lines[0] == EXPECTED_SUPABASE_CLI_VERSION,
        "SUPABASE_CLI_VERSION_DRIFT",
    )


def local_migration_manifest(repository_root: Path) -> tuple[dict[str, Any], list[str]]:
    migration_directory = repository_root / "supabase" / "migrations"
    require(
        migration_directory.is_dir() and not migration_directory.is_symlink(),
        "LOCAL_MIGRATIONS_MISSING",
    )
    files = sorted(path for path in migration_directory.iterdir() if path.is_file())
    require(bool(files), "LOCAL_MIGRATIONS_MISSING")
    entries: list[dict[str, Any]] = []
    versions: list[str] = []
    for path in files:
        require(not path.is_symlink(), "LOCAL_MIGRATION_SYMLINK_FORBIDDEN")
        match = MIGRATION_FILENAME_PATTERN.fullmatch(path.name)
        require(match is not None, "LOCAL_MIGRATION_FILENAME_INVALID")
        version = match.group("version")
        require(version not in versions, "LOCAL_MIGRATION_VERSION_DUPLICATE")
        payload = path.read_bytes()
        entries.append(
            {
                "filename": path.name,
                "sha256": sha256_bytes(payload),
                "sizeBytes": len(payload),
                "version": version,
            }
        )
        versions.append(version)
    manifest_payload = {
        "count": len(entries),
        "migrations": entries,
        "schemaVersion": 1,
    }
    manifest_payload["manifestSha256"] = sha256_bytes(canonical_json_bytes(manifest_payload))
    return manifest_payload, versions


def parse_remote_migration_versions(csv_output: str) -> list[str]:
    lines = [line for line in csv_output.replace("\r\n", "\n").split("\n") if line.strip()]
    header_index = next(
        (index for index, line in enumerate(lines) if line.lstrip("\ufeff").strip() == "version"),
        None,
    )
    require(header_index is not None, "REMOTE_MIGRATION_OUTPUT_INVALID")
    reader = csv.DictReader(lines[header_index:])
    require(reader.fieldnames == ["version"], "REMOTE_MIGRATION_OUTPUT_INVALID")
    versions: list[str] = []
    for row in reader:
        version = str(row.get("version", ""))
        require(re.fullmatch(r"[0-9]{14}", version) is not None, "REMOTE_MIGRATION_OUTPUT_INVALID")
        require(version not in versions, "REMOTE_MIGRATION_VERSION_DUPLICATE")
        versions.append(version)
    require(versions == sorted(versions), "REMOTE_MIGRATION_ORDER_INVALID")
    return versions


def migration_state_evidence(
    *,
    local_versions: Sequence[str],
    remote_versions: Sequence[str],
) -> dict[str, Any]:
    local = list(local_versions)
    remote = list(remote_versions)
    remote_is_prefix = len(remote) <= len(local) and remote == local[: len(remote)]
    pending = local[len(remote) :] if remote_is_prefix else []
    return {
        "localCount": len(local),
        "localVersionsSha256": sha256_text("\n".join(local) + "\n"),
        "pendingCount": len(pending) if remote_is_prefix else None,
        "pendingVersions": pending if remote_is_prefix else [],
        "pendingVersionsSha256": sha256_text("\n".join(pending) + ("\n" if pending else "")),
        "remoteCount": len(remote),
        "remoteIsExactLocalPrefix": remote_is_prefix,
        "remoteVersionsSha256": sha256_text("\n".join(remote) + ("\n" if remote else "")),
        "schemaVersion": 2,
    }


def inspect_remote_migration_state(
    *,
    prefix: str,
    evidence_directory: Path,
    authority: TargetAuthority,
    repository_root: Path,
    local_versions: Sequence[str],
    require_exact_match: bool,
) -> dict[str, Any]:
    remote_result = run_cli(
        kind="remote-migrations",
        evidence_path=evidence_directory / f"{prefix}-REMOTE-MIGRATION-QUERY.txt",
        authority=authority,
        repository_root=repository_root,
    )
    remote_versions = parse_remote_migration_versions(remote_result.stdout or "")
    state = migration_state_evidence(
        local_versions=local_versions,
        remote_versions=remote_versions,
    )
    write_json_exclusive(
        evidence_directory / f"{prefix}-PENDING-CHECK.json",
        state,
        secret_values=authority.secret_values,
    )
    require(state["remoteIsExactLocalPrefix"] is True, "REMOTE_HISTORY_NOT_LOCAL_PREFIX")
    if require_exact_match:
        require(state["pendingCount"] == 0, "PENDING_MIGRATIONS_OR_DRIFT")
    return state


def qualify_database(
    *,
    prefix: str,
    evidence_directory: Path,
    authority: TargetAuthority,
    repository_root: Path,
    local_versions: Sequence[str],
) -> dict[str, Any]:
    run_cli(
        kind="migration-list",
        evidence_path=evidence_directory / f"{prefix}-MIGRATION-LIST.txt",
        authority=authority,
        repository_root=repository_root,
    )
    run_cli(
        kind="lint-public",
        evidence_path=evidence_directory / f"{prefix}-LINT-PUBLIC.txt",
        authority=authority,
        repository_root=repository_root,
    )
    run_cli(
        kind="lint-app-private",
        evidence_path=evidence_directory / f"{prefix}-LINT-APP-PRIVATE.txt",
        authority=authority,
        repository_root=repository_root,
    )
    run_cli(
        kind="advisors-security",
        evidence_path=evidence_directory / f"{prefix}-ADVISORS-SECURITY.txt",
        authority=authority,
        repository_root=repository_root,
    )
    run_cli(
        kind="advisors-performance",
        evidence_path=evidence_directory / f"{prefix}-ADVISORS-PERFORMANCE.txt",
        authority=authority,
        repository_root=repository_root,
    )
    run_cli(
        kind="plan",
        evidence_path=evidence_directory / f"{prefix}-POST-QUALIFICATION-DRY-RUN.txt",
        authority=authority,
        repository_root=repository_root,
    )
    return inspect_remote_migration_state(
        prefix=prefix,
        evidence_directory=evidence_directory,
        authority=authority,
        repository_root=repository_root,
        local_versions=local_versions,
        require_exact_match=True,
    )


def evidence_digests(
    evidence_directory: Path,
    *,
    secret_values: Iterable[str] = (),
) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for path in sorted(evidence_directory.iterdir(), key=lambda candidate: candidate.name):
        if path.name in {GEL_FILENAME, GEL_HASH_FILENAME}:
            continue
        require(path.is_file() and not path.is_symlink(), "EVIDENCE_FILE_INVALID")
        payload = path.read_bytes()
        require(len(payload) <= MAX_EVIDENCE_BYTES, "EVIDENCE_TOO_LARGE")
        try:
            text = payload.decode("utf-8")
        except UnicodeDecodeError as error:
            raise StagingDatabaseError("EVIDENCE_NOT_UTF8") from error
        assert_safe_text(text, secret_values)
        result[path.name] = {"sha256": sha256_bytes(payload), "sizeBytes": len(payload)}
    require(bool(result), "EVIDENCE_EMPTY")
    return result


def write_gel_receipt(
    *,
    evidence_directory: Path,
    operation: str,
    status: str,
    request_evidence: Mapping[str, Any],
    ci_evidence: Mapping[str, Any],
    target_evidence: Mapping[str, Any],
    migration_manifest: Mapping[str, Any],
    backup_evidence: Mapping[str, Any] | None,
    plan_evidence: Mapping[str, Any] | None = None,
    migration_state: Mapping[str, Any] | None = None,
    mutation_state: str = "not_started",
    execution_disposition: str = "EXECUTED",
    error_code: str | None = None,
    secret_values: Iterable[str] = (),
) -> dict[str, Any]:
    require(
        status in {"succeeded", "failed", "prepared_not_executable"},
        "RECEIPT_STATUS_INVALID",
    )
    require(mutation_state == "not_started", "MUTATION_STATE_INVALID")
    require(
        execution_disposition
        in {"EXECUTED", "REJECTED_PREFLIGHT", "PREPARED_NOT_EXECUTABLE"},
        "EXECUTION_DISPOSITION_INVALID",
    )
    target_digest = sha256_bytes(canonical_json_bytes(target_evidence))
    receipt: dict[str, Any] = {
        "actor": request_evidence["actor"],
        "backupProof": backup_evidence,
        "ci": ci_evidence,
        "contributesTo": CONTRIBUTES_TO,
        "createdAtUtc": utc_now(),
        "environment": EXPECTED_ENVIRONMENT,
        "errorCode": error_code,
        "evidence": evidence_digests(
            evidence_directory,
            secret_values=secret_values,
        ),
        "executionDisposition": execution_disposition,
        "expectedSha": request_evidence["expectedSha"],
        "gate": "G5",
        "gateClosed": False,
        "migrationManifest": {
            "count": migration_manifest["count"],
            "manifestSha256": migration_manifest["manifestSha256"],
        },
        "migrationStateEvidence": migration_state,
        "mutationState": mutation_state,
        "operation": operation,
        "planProof": plan_evidence,
        "qualification": (
            "planned"
            if operation == "plan" and status == "succeeded"
            else "verified"
            if status == "succeeded"
            else "not-run"
            if status == "prepared_not_executable"
            else "failed"
        ),
        "repository": EXPECTED_REPOSITORY,
        "runAttempt": request_evidence["runAttempt"],
        "runId": request_evidence["runId"],
        "runUrl": request_evidence["runUrl"],
        "schemaVersion": 2,
        "status": status,
        "target": target_evidence,
        "targetDigestSha256": target_digest,
        "taskId": TASK_ID,
        "validatedCiRunId": request_evidence["validatedCiRunId"],
    }
    assert_safe_document(receipt, secret_values)
    receipt_path = evidence_directory / GEL_FILENAME
    write_json_exclusive(receipt_path, receipt, secret_values=secret_values)
    receipt_digest = sha256_bytes(receipt_path.read_bytes())
    write_text_exclusive(
        evidence_directory / GEL_HASH_FILENAME,
        f"{receipt_digest}  {GEL_FILENAME}\n",
        secret_values=secret_values,
    )
    return receipt


def write_preflight_failure_receipt(
    *,
    evidence_directory: Path,
    args: argparse.Namespace,
    error_code: str,
) -> None:
    if (evidence_directory / GEL_FILENAME).exists():
        return
    failure = {
        "errorCode": error_code,
        "executionDisposition": "REJECTED_PREFLIGHT",
        "mutationState": "not_started",
        "schemaVersion": 1,
    }
    write_json_exclusive(evidence_directory / "PREFLIGHT-FAILURE.json", failure)
    expected_sha = args.expected_sha if COMMIT_SHA_PATTERN.fullmatch(args.expected_sha) else None
    ci_run_id = optional_positive_integer(args.validated_ci_run_id)
    repository_is_canonical = os.environ.get("GITHUB_REPOSITORY", "") == EXPECTED_REPOSITORY
    run_id_raw = os.environ.get("GITHUB_RUN_ID", "")
    run_attempt_raw = os.environ.get("GITHUB_RUN_ATTEMPT", "")
    run_id = optional_positive_integer(run_id_raw) if repository_is_canonical else None
    run_attempt = optional_positive_integer(run_attempt_raw) if run_id is not None else None
    actor_raw = os.environ.get("GITHUB_ACTOR", "")
    actor = actor_raw if GITHUB_ACTOR_PATTERN.fullmatch(actor_raw) else None
    operation = args.operation if args.operation in {"plan", "apply", "verify"} else "unknown"
    receipt: dict[str, Any] = {
        "actor": actor,
        "backupProof": None,
        "ci": None,
        "contributesTo": CONTRIBUTES_TO,
        "createdAtUtc": utc_now(),
        "environment": EXPECTED_ENVIRONMENT,
        "errorCode": error_code,
        "evidence": evidence_digests(evidence_directory),
        "executionDisposition": "REJECTED_PREFLIGHT",
        "expectedSha": expected_sha,
        "gate": "G5",
        "gateClosed": False,
        "migrationManifest": None,
        "migrationStateEvidence": None,
        "mutationState": "not_started",
        "operation": operation,
        "planProof": None,
        "qualification": "not-run",
        "repository": EXPECTED_REPOSITORY,
        "runAttempt": run_attempt,
        "runId": run_id,
        "runUrl": (
            f"https://github.com/{EXPECTED_REPOSITORY}/actions/runs/{run_id}"
            if run_id is not None
            else None
        ),
        "schemaVersion": 2,
        "status": "failed",
        "target": None,
        "targetDigestSha256": None,
        "taskId": TASK_ID,
        "validatedCiRunId": ci_run_id,
    }
    assert_safe_document(receipt)
    receipt_path = evidence_directory / GEL_FILENAME
    write_json_exclusive(receipt_path, receipt)
    write_text_exclusive(
        evidence_directory / GEL_HASH_FILENAME,
        f"{sha256_bytes(receipt_path.read_bytes())}  {GEL_FILENAME}\n",
    )


def _git_head(repository_root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=repository_root,
            capture_output=True,
            check=False,
            text=True,
            timeout=30,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired) as error:
        raise StagingDatabaseError("GIT_HEAD_UNAVAILABLE") from error
    require(result.returncode == 0, "GIT_HEAD_UNAVAILABLE")
    return result.stdout.strip()


def execute(args: argparse.Namespace) -> None:
    repository_root = Path(__file__).resolve().parents[1]
    workspace = Path(os.environ.get("GITHUB_WORKSPACE", "")).resolve()
    evidence_directory = repository_root / "build" / "closed-beta-staging-database-evidence"
    evidence_directory.mkdir(parents=True, exist_ok=True)
    require(
        evidence_directory.is_dir()
        and not evidence_directory.is_symlink()
        and not evidence_directory.parent.is_symlink()
        and evidence_directory.resolve() == evidence_directory,
        "EVIDENCE_DIRECTORY_INVALID",
    )
    require(not any(evidence_directory.iterdir()), "EVIDENCE_DIRECTORY_NOT_EMPTY")
    request_evidence: dict[str, Any] | None = None
    ci_evidence: dict[str, Any] | None = None
    target_evidence: dict[str, Any] | None = None
    migration_manifest: dict[str, Any] | None = None
    authority: TargetAuthority | None = None
    plan_evidence: dict[str, Any] | None = None
    migration_state: dict[str, Any] | None = None
    try:
        require(workspace == repository_root, "GITHUB_WORKSPACE_DRIFT")
        require(
            Path(args.evidence_directory).resolve() == evidence_directory,
            "EVIDENCE_DIRECTORY_INVALID",
        )
        apply_authority = validate_operation_inputs(
            operation=args.operation,
            confirmation=args.apply_confirmation,
            backup_run_id=args.backup_run_id,
            backup_artifact_id=args.backup_artifact_id,
            backup_artifact_digest=args.backup_artifact_digest,
            validated_plan_run_id=args.validated_plan_run_id,
            validated_plan_artifact_id=args.validated_plan_artifact_id,
            validated_plan_artifact_digest=args.validated_plan_artifact_digest,
        )
        request_evidence = validate_request_identity(
            repository=os.environ.get("GITHUB_REPOSITORY", ""),
            event_name=os.environ.get("GITHUB_EVENT_NAME", ""),
            github_ref=os.environ.get("GITHUB_REF", ""),
            github_sha=os.environ.get("GITHUB_SHA", ""),
            checked_out_sha=_git_head(repository_root),
            expected_sha=args.expected_sha,
            validated_ci_run_id=args.validated_ci_run_id,
            run_id=os.environ.get("GITHUB_RUN_ID", ""),
            run_attempt=os.environ.get("GITHUB_RUN_ATTEMPT", ""),
            actor=os.environ.get("GITHUB_ACTOR", ""),
            server_url=os.environ.get("GITHUB_SERVER_URL", ""),
        )
        ci_evidence = validate_ci_run(
            load_json_object(Path(args.ci_run_json)),
            expected_run_id=args.validated_ci_run_id,
            expected_sha=args.expected_sha,
        )
        environment_evidence = validate_environment_protection(
            load_json_object(Path(args.environment_json))
        )
        authority = validate_target_authority(
            environment=os.environ.get("KWABOR_ENVIRONMENT", ""),
            api_url=os.environ.get("KWABOR_SUPABASE_URL", ""),
            project_ref=os.environ.get("KWABOR_SUPABASE_PROJECT_REF", ""),
            production_project_ref=os.environ.get("KWABOR_PRODUCTION_SUPABASE_PROJECT_REF", ""),
            project_ref_sha256=os.environ.get("KWABOR_STAGING_PROJECT_REF_SHA256", ""),
            database_url=os.environ.get("KWABOR_STAGING_DATABASE_URL", ""),
        )
        target_evidence = authority.public_evidence()
        migration_manifest, local_versions = local_migration_manifest(repository_root)

        write_json_exclusive(
            evidence_directory / "GITHUB-CI-PROVENANCE.json",
            ci_evidence,
            secret_values=authority.secret_values,
        )
        write_json_exclusive(
            evidence_directory / "GITHUB-STAGING-ENVIRONMENT.json",
            environment_evidence,
            secret_values=authority.secret_values,
        )
        write_json_exclusive(
            evidence_directory / "STAGING-TARGET-IDENTITY.json",
            target_evidence,
            secret_values=authority.secret_values,
        )
        write_json_exclusive(
            evidence_directory / "LOCAL-MIGRATION-MANIFEST.json",
            migration_manifest,
            secret_values=authority.secret_values,
        )
        verify_cli_version(
            evidence_path=evidence_directory / "SUPABASE-CLI-VERSION.txt",
            authority=authority,
            repository_root=repository_root,
        )
        if args.operation == "plan":
            migration_state = inspect_remote_migration_state(
                prefix="PLAN",
                evidence_directory=evidence_directory,
                authority=authority,
                repository_root=repository_root,
                local_versions=local_versions,
                require_exact_match=False,
            )
            run_cli(
                kind="plan",
                evidence_path=evidence_directory / "PLAN-DRY-RUN.txt",
                authority=authority,
                repository_root=repository_root,
            )
        elif args.operation == "apply":
            require(apply_authority is not None, "APPLY_AUTHORITY_MISSING")
            require(
                apply_authority["planRunId"] != request_evidence["runId"],
                "PLAN_RUN_IS_CURRENT_RUN",
            )
            require(
                apply_authority["backupRunId"] != request_evidence["runId"],
                "BACKUP_RUN_IS_CURRENT_RUN",
            )
            plan_evidence = validate_plan_artifact_bundle(
                run_document=load_json_object(Path(args.plan_run_json)),
                artifact_document=load_json_object(Path(args.plan_artifact_json)),
                archive_path=Path(args.plan_artifact_zip),
                plan_run_id=apply_authority["planRunId"],
                plan_artifact_id=apply_authority["planArtifactId"],
                plan_artifact_digest=apply_authority["planArtifactDigest"],
                expected_sha=args.expected_sha,
                validated_ci_run_id=request_evidence["validatedCiRunId"],
                target_evidence=target_evidence,
                migration_manifest=migration_manifest,
            )
            write_json_exclusive(
                evidence_directory / "VALIDATED-PLAN-PROOF.json",
                plan_evidence,
                secret_values=authority.secret_values,
            )
            preparation = {
                "backupProducerAvailable": BACKUP_PRODUCER_AVAILABLE,
                "blockedByTaskId": "B6.02",
                "executionDisposition": "PREPARED_NOT_EXECUTABLE",
                "missingProducerWorkflow": EXPECTED_BACKUP_WORKFLOW,
                "mutationState": "not_started",
                "requestedBackupArtifactId": apply_authority["backupArtifactId"],
                "requestedBackupRunId": apply_authority["backupRunId"],
                "schemaVersion": 1,
            }
            write_json_exclusive(
                evidence_directory / "APPLY-PREPARATION.json",
                preparation,
                secret_values=authority.secret_values,
            )
            require(BACKUP_PRODUCER_AVAILABLE is False, "BACKUP_PRODUCER_FLAG_INVALID")
            write_gel_receipt(
                evidence_directory=evidence_directory,
                operation="apply",
                status="prepared_not_executable",
                request_evidence=request_evidence,
                ci_evidence=ci_evidence,
                target_evidence=target_evidence,
                migration_manifest=migration_manifest,
                backup_evidence=None,
                plan_evidence=plan_evidence,
                migration_state=None,
                mutation_state="not_started",
                execution_disposition="PREPARED_NOT_EXECUTABLE",
                error_code="BACKUP_PRODUCER_MISSING",
                secret_values=authority.secret_values,
            )
            raise StagingDatabaseError("APPLY_PREPARED_NOT_EXECUTABLE")
        else:
            migration_state = qualify_database(
                prefix="VERIFY",
                evidence_directory=evidence_directory,
                authority=authority,
                repository_root=repository_root,
                local_versions=local_versions,
            )
    except StagingDatabaseError as error:
        if not (evidence_directory / GEL_FILENAME).exists():
            if all(
                value is not None
                for value in (
                    request_evidence,
                    ci_evidence,
                    target_evidence,
                    migration_manifest,
                    authority,
                )
            ):
                write_gel_receipt(
                    evidence_directory=evidence_directory,
                    operation=args.operation,
                    status="failed",
                    request_evidence=request_evidence,
                    ci_evidence=ci_evidence,
                    target_evidence=target_evidence,
                    migration_manifest=migration_manifest,
                    backup_evidence=None,
                    plan_evidence=plan_evidence,
                    migration_state=migration_state,
                    mutation_state="not_started",
                    execution_disposition="REJECTED_PREFLIGHT",
                    error_code=error.code,
                    secret_values=authority.secret_values,
                )
            else:
                write_preflight_failure_receipt(
                    evidence_directory=evidence_directory,
                    args=args,
                    error_code=error.code,
                )
        raise

    write_gel_receipt(
        evidence_directory=evidence_directory,
        operation=args.operation,
        status="succeeded",
        request_evidence=request_evidence,
        ci_evidence=ci_evidence,
        target_evidence=target_evidence,
        migration_manifest=migration_manifest,
        backup_evidence=None,
        plan_evidence=plan_evidence,
        migration_state=migration_state,
        mutation_state="not_started",
        execution_disposition="EXECUTED",
        secret_values=authority.secret_values,
    )
    print(f"OK staging database operation={args.operation} GEL={GEL_FILENAME}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Fail-closed Supabase staging database migration and qualification runner."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)
    execute_parser = subparsers.add_parser("execute")
    execute_parser.add_argument("--operation", choices=("plan", "apply", "verify"), required=True)
    execute_parser.add_argument("--expected-sha", required=True)
    execute_parser.add_argument("--validated-ci-run-id", required=True)
    execute_parser.add_argument("--apply-confirmation", default="")
    execute_parser.add_argument("--backup-run-id", default="")
    execute_parser.add_argument("--backup-artifact-id", default="")
    execute_parser.add_argument("--backup-artifact-digest", default="")
    execute_parser.add_argument("--validated-plan-run-id", default="")
    execute_parser.add_argument("--validated-plan-artifact-id", default="")
    execute_parser.add_argument("--validated-plan-artifact-digest", default="")
    execute_parser.add_argument("--ci-run-json", required=True)
    execute_parser.add_argument("--environment-json", required=True)
    execute_parser.add_argument("--plan-run-json", required=True)
    execute_parser.add_argument("--plan-artifact-json", required=True)
    execute_parser.add_argument("--plan-artifact-zip", required=True)
    execute_parser.add_argument("--backup-run-json", required=True)
    execute_parser.add_argument("--backup-artifact-json", required=True)
    execute_parser.add_argument("--evidence-directory", required=True)
    execute_parser.set_defaults(handler=execute)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        args.handler(args)
    except StagingDatabaseError as error:
        print(f"ERROR closed-beta staging database: {error.code}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
