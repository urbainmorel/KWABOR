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
from datetime import datetime, timedelta, timezone
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
BACKUP_PRODUCER_AVAILABLE = True
FRESH_EMPTY_PROOF_POLICY = "zero-objects-types-and-managed-catalog-v3"
FRESH_EMPTY_REQUIRED_SYSTEM_TABLES = 3
READ_ONLY_TIMEOUT_SECONDS = 180
APPLY_TIMEOUT_SECONDS = 600
TASK_ID = "B6.01.database-migrations"
CONTRIBUTES_TO = "G5"
GEL_FILENAME = "GEL-G5-STAGING-DATABASE.json"
GEL_HASH_FILENAME = f"{GEL_FILENAME}.sha256"
BACKUP_GEL_FILENAME = "GEL-G5-STAGING-DATABASE-BACKUP.json"
BACKUP_GEL_HASH_FILENAME = f"{BACKUP_GEL_FILENAME}.sha256"
BACKUP_SCHEMA_VERSION = 2
BACKUP_ARTIFACT_RETENTION_DAYS = 90
# B6.02 intentionally excludes managed Auth/Storage rows. A lexical SQL filter
# cannot prove absence of dynamic or search_path-based writes, so every pending
# migration applied against a non-empty staging database must be reviewed and
# pinned byte-for-byte here. Unknown or modified migrations fail closed.
BACKUP_EXCLUDED_MANAGED_DATA_PENDING_ALLOWLIST = {
    "20260821012638": "f5d307628db654c49464b607729df1a59776725070113e93e5cc9a8e372434fc",
}
REMOTE_MIGRATION_QUERY = (
    "select unnest(xpath('/table/row/version/text()', query_to_xml("
    "case when to_regclass('supabase_migrations.schema_migrations') is null "
    "then 'select null::text as version where false' "
    "else 'select version::text as version from "
    "supabase_migrations.schema_migrations order by version' end,"
    "true,false,'')))::text as version;"
)
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
REQUIRED_MANAGED_DATA_TABLES = frozenset(
    {("auth", "users"), ("storage", "buckets"), ("storage", "objects")}
)
MANAGED_DATA_CATALOG_EXCLUSIONS = frozenset(
    {
        ("auth", "instances"),
        ("auth", "schema_migrations"),
        ("storage", "migrations"),
    }
)
MANAGED_DATA_TABLES = tuple(
    (schema, table, (schema, table) in REQUIRED_MANAGED_DATA_TABLES)
    for schema, table in EXPECTED_MANAGED_SCHEMA_TABLES
    if (schema, table) not in MANAGED_DATA_CATALOG_EXCLUSIONS
)
EXPECTED_MANAGED_SCHEMA_TABLE_VALUES_SQL = ",".join(
    f"('{schema}','{table}')" for schema, table in EXPECTED_MANAGED_SCHEMA_TABLES
)
FRESH_EMPTY_QUERY = (
    "with expected_managed_tables(schema_name,table_name) as (values "
    + EXPECTED_MANAGED_SCHEMA_TABLE_VALUES_SQL
    + "), actual_managed_tables(schema_name,table_name) as (select n.nspname,c.relname "
    "from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace "
    "where n.nspname in ('auth','storage') and c.relkind in ('r','p')), "
    "managed_catalog_drift as ((select * from expected_managed_tables except select * from "
    "actual_managed_tables) union all (select * from actual_managed_tables except select * from "
    "expected_managed_tables)), relevant_tables(schema_name,table_name,category,is_required) as (values "
    "('supabase_migrations','schema_migrations','migration',false),"
    "('auth','users','auth',true),('auth','identities','auth',false),"
    "('auth','sessions','auth',false),('auth','refresh_tokens','auth',false),"
    "('auth','mfa_factors','auth',false),('auth','mfa_challenges','auth',false),"
    "('auth','mfa_amr_claims','auth',false),"
    "('auth','one_time_tokens','auth',false),('auth','flow_state','auth',false),"
    "('auth','audit_log_entries','auth',false),"
    "('auth','saml_providers','auth',false),"
    "('auth','saml_relay_states','auth',false),('auth','sso_domains','auth',false),"
    "('auth','sso_providers','auth',false),"
    "('auth','oauth_clients','auth',false),"
    "('auth','oauth_client_states','auth',false),"
    "('auth','oauth_authorizations','auth',false),"
    "('auth','oauth_consents','auth',false),"
    "('auth','custom_oauth_providers','auth',false),"
    "('auth','webauthn_credentials','auth',false),"
    "('auth','webauthn_challenges','auth',false),"
    "('storage','objects','storage',true),('storage','buckets','storage',true),"
    "('storage','buckets_analytics','storage',false),"
    "('storage','buckets_vectors','storage',false),"
    "('storage','s3_multipart_uploads','storage',false),"
    "('storage','s3_multipart_uploads_parts','storage',false),"
    "('storage','vector_indexes','storage',false)),"
    "relevant_counts as (select schema_name,table_name,category,is_required,"
    "to_regclass(format('%I.%I',schema_name,table_name)) is not null as relation_exists,"
    "case when to_regclass(format('%I.%I',schema_name,table_name)) is null then 0::bigint "
    "else coalesce((xpath('/table/row/value/text()',query_to_xml("
    "format('select count(*)::bigint as value from %I.%I',schema_name,table_name),"
    "true,false,'')))[1]::text::bigint,0::bigint) end as row_count "
    "from relevant_tables) select "
    "(select count(*)::bigint from pg_catalog.pg_namespace where nspname='public') "
    "as public_schema_count,"
    "(select count(*)::bigint from managed_catalog_drift) as managed_schema_table_drift_count,"
    "(select count(*)::bigint from relevant_counts where is_required and relation_exists) "
    "as required_system_table_count,"
    "(select coalesce(sum(row_count),0)::bigint from relevant_counts "
    "where category='migration') as application_migration_count,"
    "(select count(*)::bigint from pg_catalog.pg_class c join pg_catalog.pg_namespace n "
    "on n.oid=c.relnamespace where n.nspname in ('public','app_private') "
    "and c.relkind in ('r','p','v','m','S','f')) as application_relation_count,"
    "(select count(*)::bigint from pg_catalog.pg_type t join pg_catalog.pg_namespace n "
    "on n.oid=t.typnamespace where n.nspname in ('public','app_private') and not exists "
    "(select 1 from pg_catalog.pg_class c where c.oid=t.typrelid "
    "and c.relnamespace=t.typnamespace and c.relkind in ('r','p','v','m','f'))) "
    "as application_type_count,"
    "(select count(*)::bigint from pg_catalog.pg_proc p join pg_catalog.pg_namespace n "
    "on n.oid=p.pronamespace where n.nspname in ('public','app_private')) "
    "as application_routine_count,"
    "(select row_count from relevant_counts where schema_name='auth' and table_name='users') "
    "as auth_user_count,"
    "(select coalesce(sum(row_count),0)::bigint from relevant_counts where category='auth') "
    "as auth_relevant_row_count,"
    "(select row_count from relevant_counts where schema_name='storage' "
    "and table_name='objects') as storage_object_count,"
    "(select row_count from relevant_counts where schema_name='storage' "
    "and table_name='buckets') as storage_bucket_count,"
    "(select coalesce(sum(row_count),0)::bigint from relevant_counts "
    "where category='storage') as storage_relevant_row_count;"
)
FRESH_EMPTY_CSV_COLUMNS = (
    "public_schema_count",
    "managed_schema_table_drift_count",
    "required_system_table_count",
    "application_migration_count",
    "application_relation_count",
    "application_type_count",
    "application_routine_count",
    "auth_user_count",
    "auth_relevant_row_count",
    "storage_object_count",
    "storage_bucket_count",
    "storage_relevant_row_count",
)
FRESH_EMPTY_COUNT_KEYS = {
    "public_schema_count": "publicSchemaCount",
    "managed_schema_table_drift_count": "managedSchemaTableDriftCount",
    "required_system_table_count": "requiredSystemTableCount",
    "application_migration_count": "applicationMigrationCount",
    "application_relation_count": "applicationRelationCount",
    "application_type_count": "applicationTypeCount",
    "application_routine_count": "applicationRoutineCount",
    "auth_user_count": "authUserCount",
    "auth_relevant_row_count": "authRelevantRowCount",
    "storage_object_count": "storageObjectCount",
    "storage_bucket_count": "storageBucketCount",
    "storage_relevant_row_count": "storageRelevantRowCount",
}

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


def format_github_timestamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


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
    backup_values = (backup_run_id, backup_artifact_id, backup_artifact_digest)
    backup_authority: dict[str, Any] | None = None
    if any(backup_values):
        require(all(backup_values), "BACKUP_AUTHORITY_INCOMPLETE")
        backup_authority = {
            "artifactDigest": backup_artifact_digest,
            "artifactId": validate_positive_integer(
                backup_artifact_id, "BACKUP_ARTIFACT_ID_INVALID"
            ),
            "runId": validate_positive_integer(backup_run_id, "BACKUP_RUN_ID_INVALID"),
        }
        require(
            ARTIFACT_DIGEST_PATTERN.fullmatch(backup_artifact_digest) is not None,
            "BACKUP_ARTIFACT_DIGEST_INVALID",
        )
    plan_run = validate_positive_integer(validated_plan_run_id, "PLAN_RUN_ID_INVALID")
    plan_artifact = validate_positive_integer(
        validated_plan_artifact_id, "PLAN_ARTIFACT_ID_INVALID"
    )
    require(
        ARTIFACT_DIGEST_PATTERN.fullmatch(validated_plan_artifact_digest) is not None,
        "PLAN_ARTIFACT_DIGEST_INVALID",
    )
    return {
        "backup": backup_authority,
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
        "createdAt": format_github_timestamp(created_at),
        "expired": False,
        "expiresAt": format_github_timestamp(expires_at),
        "runId": expected_run_id,
        "sizeBytes": size_bytes,
        "updatedAt": format_github_timestamp(updated_at),
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
    fresh_check_name = "PLAN-FRESH-EMPTY-CHECK.json"
    fresh_query_name = "PLAN-FRESH-EMPTY-QUERY.txt"
    manifest_name = "LOCAL-MIGRATION-MANIFEST.json"
    dry_run_name = "PLAN-DRY-RUN.txt"
    entries = load_artifact_entries(
        archive_path,
        expected_digest=plan_artifact_digest,
        required_entries=(
            GEL_FILENAME,
            GEL_HASH_FILENAME,
            pending_name,
            fresh_check_name,
            fresh_query_name,
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
    fresh_empty = validate_fresh_empty_evidence(
        _load_internal_json(entries, fresh_check_name)
    )
    archived_manifest = _load_internal_json(entries, manifest_name)
    target_digest = sha256_bytes(canonical_json_bytes(target_evidence))
    require(receipt.get("repository") == EXPECTED_REPOSITORY, "PLAN_RECEIPT_REPOSITORY_DRIFT")
    require(receipt.get("operation") == "plan", "PLAN_RECEIPT_OPERATION_DRIFT")
    require(receipt.get("status") == "succeeded", "PLAN_RECEIPT_NOT_SUCCESSFUL")
    require(receipt.get("schemaVersion") == 3, "PLAN_RECEIPT_SCHEMA_DRIFT")
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
    require_fresh_history_consistency(
        fresh_empty,
        pending,
        code="PLAN_FRESH_HISTORY_DRIFT",
    )
    require(
        receipt.get("freshEmptyProof") == fresh_empty,
        "PLAN_FRESH_EMPTY_EVIDENCE_DRIFT",
    )
    receipt_evidence = receipt.get("evidence")
    require(isinstance(receipt_evidence, dict), "PLAN_RECEIPT_EVIDENCE_INVALID")
    for evidence_name in (
        pending_name,
        fresh_check_name,
        fresh_query_name,
        manifest_name,
        dry_run_name,
    ):
        archived_digest = receipt_evidence.get(evidence_name)
        require(
            isinstance(archived_digest, dict)
            and archived_digest.get("sha256") == sha256_bytes(entries[evidence_name]),
            "PLAN_EVIDENCE_FILE_DIGEST_DRIFT",
        )
    return {
        **artifact_evidence,
        "internalReceiptSha256": receipt_digest,
        "freshEmptyEvidence": fresh_empty,
        "freshEmptyEvidenceSha256": sha256_bytes(canonical_json_bytes(fresh_empty)),
        "migrationStateEvidenceSha256": sha256_bytes(canonical_json_bytes(pending)),
        "pendingCount": pending["pendingCount"],
        "pendingVersions": pending["pendingVersions"],
        "remoteCount": pending["remoteCount"],
        "pendingVersionsSha256": pending["pendingVersionsSha256"],
        "runAttempt": run_evidence["runAttempt"],
        "targetDigestSha256": target_digest,
    }


def _validate_backup_managed_data_proof(document: object) -> dict[str, Any]:
    require(isinstance(document, dict), "BACKUP_MANAGED_DATA_PROOF_INVALID")
    expected_managed_catalog = [
        {"schema": schema, "table": table}
        for schema, table in EXPECTED_MANAGED_SCHEMA_TABLES
    ]
    require(
        document.get("managedDataEmpty") is True
        and document.get("postgresMajor") == 17
        and document.get("schemaVersion") == 2
        and document.get("managedSchemaTableCount") == len(expected_managed_catalog)
        and document.get("managedSchemaTableSha256")
        == sha256_bytes(canonical_json_bytes(expected_managed_catalog))
        and type(document.get("constraintCount")) is int
        and document["constraintCount"] > 0
        and type(document.get("foreignKeyCount")) is int
        and document["foreignKeyCount"] > 0
        and document["foreignKeyCount"] <= document["constraintCount"]
        and document.get("unvalidatedConstraintCount") == 0
        and SHA256_PATTERN.fullmatch(
            str(document.get("constraintInventorySha256", ""))
        )
        is not None,
        "BACKUP_MANAGED_DATA_PROOF_INVALID",
    )
    versions = document.get("migrationVersions")
    require(
        isinstance(versions, list)
        and all(
            isinstance(version, str) and re.fullmatch(r"[0-9]{14}", version)
            for version in versions
        )
        and versions == sorted(set(versions)),
        "BACKUP_MIGRATION_PREFIX_INVALID",
    )
    managed_tables = document.get("managedTables")
    expected_tables = {
        (schema, table): required for schema, table, required in MANAGED_DATA_TABLES
    }
    require(
        isinstance(managed_tables, list) and len(managed_tables) == len(expected_tables),
        "BACKUP_MANAGED_DATA_PROOF_INVALID",
    )
    observed_tables: set[tuple[str, str]] = set()
    for table in managed_tables:
        require(isinstance(table, dict), "BACKUP_MANAGED_DATA_PROOF_INVALID")
        key = (str(table.get("schema", "")), str(table.get("table", "")))
        require(
            key in expected_tables
            and key not in observed_tables,
            "BACKUP_MANAGED_DATA_PROOF_INVALID",
        )
        observed_tables.add(key)
        exists = table.get("exists")
        required = table.get("required")
        row_count = table.get("rowCount")
        require(
            isinstance(exists, bool) and required is expected_tables[key],
            "BACKUP_MANAGED_DATA_PROOF_INVALID",
        )
        if required:
            require(exists, "BACKUP_MANAGED_REQUIRED_TABLE_MISSING")
        require(
            exists,
            "BACKUP_MANAGED_SCHEMA_CATALOG_DRIFT",
        )
        require(
            type(row_count) is int and row_count == 0,
            "BACKUP_MANAGED_DATA_NOT_EMPTY",
        )
    require(observed_tables == set(expected_tables), "BACKUP_MANAGED_DATA_PROOF_INVALID")
    return dict(document)


def _validate_backup_ciphertext(
    archive_path: Path,
    *,
    expected_name: str,
    expected_sha256: str,
    expected_bytes: int,
) -> None:
    require(
        re.fullmatch(r"kwabor-staging-[0-9a-f]{16}-[0-9a-f]{64}\.tar\.gz\.age", expected_name)
        is not None,
        "BACKUP_CIPHERTEXT_NAME_INVALID",
    )
    require(SHA256_PATTERN.fullmatch(expected_sha256) is not None, "BACKUP_CIPHERTEXT_DIGEST_INVALID")
    require(type(expected_bytes) is int and 0 < expected_bytes <= 1_900_000_000, "BACKUP_CIPHERTEXT_SIZE_INVALID")
    try:
        with zipfile.ZipFile(archive_path, "r") as archive:
            members = archive.infolist()
            require(
                {member.filename for member in members}
                == {BACKUP_GEL_FILENAME, BACKUP_GEL_HASH_FILENAME, expected_name},
                "BACKUP_ARTIFACT_CONTENT_SET_INVALID",
            )
            ciphertext = archive.getinfo(expected_name)
            require(ciphertext.file_size == expected_bytes, "BACKUP_CIPHERTEXT_SIZE_DRIFT")
            digest = hashlib.sha256()
            with archive.open(ciphertext, "r") as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
            require(digest.hexdigest() == expected_sha256, "BACKUP_CIPHERTEXT_DIGEST_DRIFT")
    except StagingDatabaseError:
        raise
    except (OSError, KeyError, zipfile.BadZipFile, RuntimeError) as error:
        raise StagingDatabaseError("BACKUP_ARTIFACT_INVALID") from error


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
    now: datetime | None = None,
) -> dict[str, Any]:
    evaluation_time = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
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
        max_total_uncompressed_bytes=2_000_000_000,
        allow_large_nonrequired_entries=True,
    )
    require(archive_path.stat().st_size == artifact_evidence["sizeBytes"], "ARTIFACT_ARCHIVE_SIZE_DRIFT")
    receipt_digest = _validate_internal_receipt_hash(
        entries,
        receipt_name=BACKUP_GEL_FILENAME,
        sidecar_name=BACKUP_GEL_HASH_FILENAME,
    )
    receipt = _load_internal_json(entries, BACKUP_GEL_FILENAME)
    target_digest = sha256_bytes(canonical_json_bytes(target_evidence))
    require(receipt.get("schemaVersion") == BACKUP_SCHEMA_VERSION, "BACKUP_RECEIPT_SCHEMA_DRIFT")
    require(receipt.get("taskId") == "B6.02" and receipt.get("contributesTo") == "G5", "BACKUP_RECEIPT_TASK_DRIFT")
    require(receipt.get("repository") == EXPECTED_REPOSITORY, "BACKUP_RECEIPT_REPOSITORY_DRIFT")
    require(receipt.get("workflowPath") == EXPECTED_BACKUP_WORKFLOW, "BACKUP_RECEIPT_WORKFLOW_DRIFT")
    require(receipt.get("ref") == EXPECTED_GITHUB_REF, "BACKUP_RECEIPT_REF_DRIFT")
    require(receipt.get("operation") == "backup", "BACKUP_RECEIPT_OPERATION_DRIFT")
    require(receipt.get("status") == "succeeded", "BACKUP_RECEIPT_NOT_SUCCESSFUL")
    require(receipt.get("restorable") is True and receipt.get("errorCode") is None, "BACKUP_RECEIPT_NOT_RESTORABLE")
    require(receipt.get("expectedSha") == expected_sha, "BACKUP_RECEIPT_SHA_DRIFT")
    require(receipt.get("validatedCiRunId") == validated_ci_run_id, "BACKUP_RECEIPT_CI_DRIFT")
    require(
        receipt.get("runId") == backup_run_id
        and receipt.get("runAttempt") == run_evidence["runAttempt"]
        and receipt.get("runUrl") == run_evidence["runUrl"],
        "BACKUP_RECEIPT_RUN_DRIFT",
    )
    receipt_ci = receipt.get("ci")
    require(
        isinstance(receipt_ci, dict)
        and receipt_ci.get("runId") == validated_ci_run_id
        and receipt_ci.get("headSha") == expected_sha
        and receipt_ci.get("event") == "push"
        and receipt_ci.get("headBranch") == "main"
        and receipt_ci.get("workflowPath") == EXPECTED_CI_WORKFLOW
        and receipt_ci.get("status") == "completed"
        and receipt_ci.get("conclusion") == "success",
        "BACKUP_RECEIPT_CI_DRIFT",
    )
    require(receipt.get("target") == target_evidence, "BACKUP_RECEIPT_TARGET_DRIFT")
    require(receipt.get("targetDigestSha256") == target_digest, "BACKUP_RECEIPT_TARGET_DRIFT")
    environment = receipt.get("environmentEvidence")
    require(
        isinstance(environment, dict)
        and environment.get("name") == "staging"
        and environment.get("canAdminsBypass") is False
        and environment.get("preventSelfReview") is True
        and environment.get("protectedBranchesOnly") is True,
        "BACKUP_RECEIPT_ENVIRONMENT_DRIFT",
    )
    scope = receipt.get("databaseScope")
    require(
        scope
        == {
            "dumpModes": ["roles", "single-consistent-application-dump"],
            "managedAuthStorageDataIncluded": False,
            "managedAuthStorageEmpty": True,
            "schemas": ["app_private", "public", "supabase_migrations"],
            "type": "targeted-logical",
        },
        "BACKUP_RECEIPT_SCOPE_DRIFT",
    )
    qualified_at = parse_github_timestamp(
        receipt.get("qualifiedAt"),
        "BACKUP_RECEIPT_TIME_INVALID",
    )
    snapshot = receipt.get("snapshot")
    require(
        isinstance(snapshot, dict)
        and snapshot.get("mechanism") == "pg-export-snapshot"
        and snapshot.get("isolation") == "repeatable-read-read-only"
        and snapshot.get("exportedByDedicatedSession") is True
        and snapshot.get("applicationDumpAndManagedProofShareSnapshot") is True
        and SHA256_PATTERN.fullmatch(str(snapshot.get("identifierSha256", ""))) is not None,
        "BACKUP_RECEIPT_SNAPSHOT_DRIFT",
    )
    snapshot_established_at = parse_github_timestamp(
        snapshot.get("snapshotEstablishedAt"),
        "BACKUP_RECEIPT_SNAPSHOT_DRIFT",
    )
    require(snapshot_established_at <= qualified_at, "BACKUP_RECEIPT_SNAPSHOT_DRIFT")
    source = receipt.get("source")
    restore = receipt.get("restore")
    require(isinstance(source, dict) and isinstance(restore, dict), "BACKUP_RECEIPT_RESTORE_INVALID")
    source_fingerprint = str(source.get("databaseFingerprintSha256", ""))
    source_logical = str(source.get("logicalSqlNormalizedSha256", ""))
    migration_sha256 = str(source.get("migrationPrefixSha256", ""))
    require(
        source.get("postgresMajor") == 17
        and type(source.get("migrationPrefixCount")) is int
        and source["migrationPrefixCount"] >= 0
        and SHA256_PATTERN.fullmatch(source_fingerprint) is not None
        and SHA256_PATTERN.fullmatch(source_logical) is not None
        and SHA256_PATTERN.fullmatch(migration_sha256) is not None,
        "BACKUP_RECEIPT_SOURCE_INVALID",
    )
    managed_proof = _validate_backup_managed_data_proof(source.get("managedDataProof"))
    require(
        source.get("managedDataProofSha256") == sha256_bytes(canonical_json_bytes(managed_proof))
        and source["migrationPrefixCount"] == len(managed_proof["migrationVersions"])
        and migration_sha256
        == sha256_text(
            "\n".join(managed_proof["migrationVersions"])
            + ("\n" if managed_proof["migrationVersions"] else "")
        ),
        "BACKUP_RECEIPT_SOURCE_INVALID",
    )
    expected_source_fingerprint = sha256_bytes(
        canonical_json_bytes(
            {
                "logicalSqlNormalizedSha256": source_logical,
                "migrationPrefixSha256": migration_sha256,
                "postgresMajor": 17,
                "schemas": ["app_private", "public", "supabase_migrations"],
            }
        )
    )
    require(
        source_fingerprint == expected_source_fingerprint,
        "BACKUP_RECEIPT_SOURCE_FINGERPRINT_INVALID",
    )
    require(
        restore.get("verified") is True
        and restore.get("executionBoundary") == "github-actions-disposable-supabase"
        and restore.get("fingerprintMatch") is True
        and restore.get("databaseFingerprintSha256") == source_fingerprint
        and restore.get("logicalSqlNormalizedSha256") == source_logical
        and restore.get("sessionReplicationRoleUsed") is False
        and restore.get("allConstraintsValidated") is True
        and restore.get("unvalidatedConstraintCount") == 0
        and type(restore.get("constraintCount")) is int
        and restore.get("constraintCount") == managed_proof["constraintCount"]
        and type(restore.get("foreignKeyCount")) is int
        and restore.get("foreignKeyCount") == managed_proof["foreignKeyCount"]
        and restore.get("constraintInventorySha256")
        == managed_proof["constraintInventorySha256"],
        "BACKUP_RECEIPT_RESTORE_INVALID",
    )
    encryption = receipt.get("encryption")
    require(isinstance(encryption, dict), "BACKUP_RECEIPT_ENCRYPTION_INVALID")
    require(
        encryption.get("algorithm") == "age-x25519"
        and encryption.get("encryptedBeforeArtifactBoundary") is True
        and encryption.get("plaintextArtifactCount") == 0
        and SHA256_PATTERN.fullmatch(str(encryption.get("recipientSha256", ""))) is not None,
        "BACKUP_RECEIPT_ENCRYPTION_INVALID",
    )
    expected_ciphertext_name = (
        f"kwabor-staging-{target_evidence['projectRefSha256'][:16]}-"
        f"{source_fingerprint}.tar.gz.age"
    )
    require(
        encryption.get("ciphertextFileName") == expected_ciphertext_name,
        "BACKUP_RECEIPT_CIPHERTEXT_NAME_INVALID",
    )
    _validate_backup_ciphertext(
        archive_path,
        expected_name=str(encryption.get("ciphertextFileName", "")),
        expected_sha256=str(encryption.get("ciphertextSha256", "")),
        expected_bytes=encryption.get("ciphertextBytes"),
    )
    escrow = receipt.get("ageEscrow")
    require(
        isinstance(escrow, dict)
        and escrow.get("status") == "provisioned"
        and escrow.get("custodyMode") == "offline-two-person"
        and escrow.get("recipientSha256") == encryption.get("recipientSha256")
        and escrow.get("maxRecoveryTestAgeDays") == 90,
        "BACKUP_RECEIPT_ESCROW_INVALID",
    )
    recovery_tested_at = parse_github_timestamp(escrow.get("recoveryTestedAt"), "BACKUP_RECEIPT_ESCROW_INVALID")
    escrow_valid_until = parse_github_timestamp(escrow.get("validUntil"), "BACKUP_RECEIPT_ESCROW_INVALID")
    require(
        recovery_tested_at <= qualified_at
        and qualified_at - recovery_tested_at <= timedelta(days=90)
        and escrow_valid_until > evaluation_time,
        "BACKUP_RECEIPT_ESCROW_INVALID",
    )
    rpo = receipt.get("rpo")
    rto = receipt.get("rto")
    require(isinstance(rpo, dict) and isinstance(rto, dict), "BACKUP_RECEIPT_RECOVERY_OBJECTIVE_INVALID")
    apply_valid_until = parse_github_timestamp(rpo.get("applyValidUntil"), "BACKUP_RECEIPT_RPO_INVALID")
    require(
        rpo.get("met") is True
        and type(rpo.get("maxSeconds")) is int
        and 60 <= rpo["maxSeconds"] <= 3600
        and type(rpo.get("captureSeconds")) is int
        and 0 < rpo["captureSeconds"] <= rpo["maxSeconds"]
        and apply_valid_until
        == snapshot_established_at + timedelta(seconds=rpo["maxSeconds"])
        and qualified_at < apply_valid_until
        and evaluation_time < apply_valid_until,
        "BACKUP_RECEIPT_RPO_INVALID",
    )
    require(
        rto.get("met") is True
        and type(rto.get("maxSeconds")) is int
        and 60 <= rto["maxSeconds"] <= 7200
        and type(rto.get("observedSeconds")) is int
        and 0 < rto["observedSeconds"] <= rto["maxSeconds"],
        "BACKUP_RECEIPT_RTO_INVALID",
    )
    artifact_policy = receipt.get("artifactPolicy")
    require(isinstance(artifact_policy, dict), "BACKUP_RECEIPT_ARTIFACT_POLICY_INVALID")
    estimated_expiry = parse_github_timestamp(
        artifact_policy.get("estimatedExpiresAt"), "BACKUP_RECEIPT_ARTIFACT_POLICY_INVALID"
    )
    actual_expiry = parse_github_timestamp(artifact_evidence["expiresAt"], "BACKUP_RECEIPT_ARTIFACT_POLICY_INVALID")
    artifact_created_at = parse_github_timestamp(artifact_evidence["createdAt"], "BACKUP_RECEIPT_ARTIFACT_POLICY_INVALID")
    require(
        artifact_policy.get("expectedName") == expected_name
        and artifact_policy.get("retentionDays") == BACKUP_ARTIFACT_RETENTION_DAYS
        and artifact_policy.get("expirationAuthority") == "github-actions-artifact-api"
        and artifact_policy.get("actualDigestValidatedByConsumer") is True
        and qualified_at <= artifact_created_at
        and abs(
            (
                actual_expiry
                - (artifact_created_at + timedelta(days=BACKUP_ARTIFACT_RETENTION_DAYS))
            ).total_seconds()
        )
        <= 300
        and actual_expiry >= estimated_expiry
        and actual_expiry > apply_valid_until
        and escrow_valid_until >= actual_expiry,
        "BACKUP_RECEIPT_ARTIFACT_POLICY_INVALID",
    )
    return {
        **artifact_evidence,
        "applyValidUntil": format_github_timestamp(apply_valid_until),
        "databaseFingerprintSha256": source_fingerprint,
        "internalReceiptSha256": receipt_digest,
        "migrationPrefixCount": source["migrationPrefixCount"],
        "migrationPrefixSha256": migration_sha256,
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
            "tlsMode": "require",
        }


def _parse_database_url(database_url: str, project_ref: str) -> tuple[str, str, tuple[str, ...]]:
    require(1 <= len(database_url) <= 4096, "DATABASE_URL_INVALID")
    require(database_url == database_url.strip(), "DATABASE_URL_INVALID")
    require(database_url.isascii(), "DATABASE_URL_INVALID")
    require(not any(character.isspace() or ord(character) < 32 for character in database_url), "DATABASE_URL_INVALID")
    require(database_url.count("@") == 1, "DATABASE_URL_INVALID")
    require("#" not in database_url, "DATABASE_URL_OVERRIDE_FORBIDDEN")
    try:
        parsed = urllib.parse.urlsplit(database_url)
        port = parsed.port
    except ValueError as error:
        raise StagingDatabaseError("DATABASE_URL_INVALID") from error
    require(parsed.scheme == "postgresql", "DATABASE_URL_SCHEME_INVALID")
    require(parsed.query == "sslmode=require" and parsed.fragment == "", "DATABASE_TLS_REQUIRED")
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

    require(hostname != f"db.{project_ref}.supabase.co", "SESSION_POOLER_REQUIRED")
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
        ("supabase", "db", "push", "--yes", "--db-url", "<DATABASE_URL>"),
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
        (
            "supabase",
            "db",
            "query",
            FRESH_EMPTY_QUERY,
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
        "apply": ["supabase", "db", "push", "--yes", "--db-url", database_url],
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
        "fresh-empty": [
            "supabase",
            "db",
            "query",
            FRESH_EMPTY_QUERY,
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


@dataclass(frozen=True)
class MutationAttempt:
    error_code: str | None
    exit_code: int
    timed_out: bool


def write_mutation_process_evidence(
    *,
    evidence_path: Path,
    result: subprocess.CompletedProcess[str],
    authority: TargetAuthority,
) -> None:
    try:
        rendered = render_process_output(result, authority)
        write_text_exclusive(
            evidence_path,
            rendered,
            secret_values=authority.secret_values,
        )
    except StagingDatabaseError as error:
        # Evidence safety must never erase knowledge that a remote mutation was attempted.
        # A stable omission record preserves the reconciliation path without retaining output.
        require(not evidence_path.exists(), "MUTATION_EVIDENCE_WRITE_FAILED")
        write_text_exclusive(
            evidence_path,
            f"exitCode={result.returncode}\n"
            "[stdout]\n[omitted by evidence safety guard]\n"
            "[stderr]\n[omitted by evidence safety guard]\n"
            f"evidenceGuardError={error.code}\n",
            secret_values=authority.secret_values,
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
            encoding="utf-8",
            errors="replace",
            timeout=READ_ONLY_TIMEOUT_SECONDS,
        )
    except FileNotFoundError as error:
        raise StagingDatabaseError("SUPABASE_CLI_MISSING") from error
    except OSError as error:
        raise StagingDatabaseError("SUPABASE_CLI_EXECUTION_FAILED") from error
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


def run_apply_mutation(
    *,
    evidence_path: Path,
    authority: TargetAuthority,
    repository_root: Path,
) -> MutationAttempt:
    command = build_command("apply", authority)
    try:
        process = subprocess.Popen(
            command,
            cwd=repository_root,
            env=sanitized_subprocess_environment(os.environ),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except FileNotFoundError:
        result = subprocess.CompletedProcess(
            args=command,
            returncode=127,
            stdout="",
            stderr="[supabase CLI unavailable before mutation]",
        )
        write_mutation_process_evidence(
            evidence_path=evidence_path,
            result=result,
            authority=authority,
        )
        return MutationAttempt(
            error_code="SUPABASE_APPLY_MISSING",
            exit_code=result.returncode,
            timed_out=False,
        )
    except OSError:
        result = subprocess.CompletedProcess(
            args=command,
            returncode=126,
            stdout="",
            stderr="[supabase CLI could not be spawned before mutation]",
        )
        write_mutation_process_evidence(
            evidence_path=evidence_path,
            result=result,
            authority=authority,
        )
        return MutationAttempt(
            error_code="SUPABASE_APPLY_SPAWN_FAILED",
            exit_code=result.returncode,
            timed_out=False,
        )
    try:
        stdout, stderr = process.communicate(timeout=APPLY_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired as error:
        try:
            process.kill()
        except OSError:
            pass
        try:
            final_stdout, final_stderr = process.communicate()
        except OSError:
            final_stdout, final_stderr = "", ""
        stdout = final_stdout or (
            error.stdout.decode("utf-8", errors="replace")
            if isinstance(error.stdout, bytes)
            else (error.stdout or "")
        )
        stderr = final_stderr or (
            error.stderr.decode("utf-8", errors="replace")
            if isinstance(error.stderr, bytes)
            else (error.stderr or "")
        )
        result = subprocess.CompletedProcess(
            args=command,
            returncode=124,
            stdout=stdout,
            stderr=f"{stderr}\n[command timed out]".strip(),
        )
        write_mutation_process_evidence(
            evidence_path=evidence_path,
            result=result,
            authority=authority,
        )
        return MutationAttempt(
            error_code="SUPABASE_APPLY_TIMEOUT",
            exit_code=result.returncode,
            timed_out=True,
        )
    except OSError:
        # Popen succeeded, so the child may already have mutated the database.
        # Communication/wait failures must never be misreported as pre-spawn.
        try:
            process.kill()
        except OSError:
            pass
        try:
            stdout, stderr = process.communicate()
        except OSError:
            stdout, stderr = "", ""
        result = subprocess.CompletedProcess(
            args=command,
            returncode=125,
            stdout=stdout,
            stderr=f"{stderr}\n[process state unavailable after launch]".strip(),
        )
        write_mutation_process_evidence(
            evidence_path=evidence_path,
            result=result,
            authority=authority,
        )
        return MutationAttempt(
            error_code="SUPABASE_APPLY_EXECUTION_UNCERTAIN",
            exit_code=result.returncode,
            timed_out=False,
        )
    returncode = process.returncode
    if type(returncode) is not int:
        result = subprocess.CompletedProcess(
            args=command,
            returncode=125,
            stdout=stdout,
            stderr=f"{stderr}\n[process exit status unavailable after launch]".strip(),
        )
        write_mutation_process_evidence(
            evidence_path=evidence_path,
            result=result,
            authority=authority,
        )
        return MutationAttempt(
            error_code="SUPABASE_APPLY_EXECUTION_UNCERTAIN",
            exit_code=result.returncode,
            timed_out=False,
        )
    result = subprocess.CompletedProcess(
        args=command,
        returncode=returncode,
        stdout=stdout,
        stderr=stderr,
    )
    write_mutation_process_evidence(
        evidence_path=evidence_path,
        result=result,
        authority=authority,
    )
    return MutationAttempt(
        error_code=None if result.returncode == 0 else "SUPABASE_APPLY_FAILED",
        exit_code=result.returncode,
        timed_out=False,
    )


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
            encoding="utf-8",
            errors="replace",
            timeout=30,
        )
    except FileNotFoundError as error:
        raise StagingDatabaseError("SUPABASE_CLI_MISSING") from error
    except OSError as error:
        raise StagingDatabaseError("SUPABASE_CLI_EXECUTION_FAILED") from error
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


def validate_pending_migration_scope(
    repository_root: Path,
    pending_versions: Sequence[str],
) -> dict[str, Any]:
    versions = list(pending_versions)
    require(
        versions == sorted(set(versions))
        and all(re.fullmatch(r"[0-9]{14}", version) for version in versions),
        "BACKUP_PENDING_SCOPE_INVALID",
    )
    migration_directory = repository_root / "supabase" / "migrations"
    checked: list[dict[str, Any]] = []
    for version in versions:
        candidates = sorted(migration_directory.glob(f"{version}_*.sql"))
        require(len(candidates) == 1 and not candidates[0].is_symlink(), "BACKUP_PENDING_MIGRATION_MISSING")
        try:
            payload = candidates[0].read_bytes()
            payload.decode("utf-8")
        except (OSError, UnicodeDecodeError) as error:
            raise StagingDatabaseError("BACKUP_PENDING_MIGRATION_INVALID") from error
        digest = sha256_bytes(payload)
        require(
            BACKUP_EXCLUDED_MANAGED_DATA_PENDING_ALLOWLIST.get(version) == digest,
            "BACKUP_PENDING_MIGRATION_NOT_REVIEWED_FOR_EXCLUDED_SCHEMAS",
        )
        checked.append(
            {
                "filename": candidates[0].name,
                "sha256": digest,
                "version": version,
            }
        )
    return {
        "checkedMigrations": checked,
        "checkedVersionsSha256": sha256_text(
            "\n".join(versions) + ("\n" if versions else "")
        ),
        "managedSchemas": ["auth", "storage"],
        "policy": "reviewed-pending-migration-sha-allowlist-v2",
        "schemaVersion": 2,
    }


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


def parse_fresh_empty_counts(csv_output: str) -> dict[str, int]:
    normalized_lines = [
        line for line in csv_output.replace("\r\n", "\n").split("\n") if line.strip()
    ]
    expected_header = ",".join(FRESH_EMPTY_CSV_COLUMNS)
    header_index = next(
        (
            index
            for index, line in enumerate(normalized_lines)
            if line.lstrip("\ufeff").strip() == expected_header
        ),
        None,
    )
    require(header_index is not None, "FRESH_EMPTY_OUTPUT_INVALID")
    reader = csv.DictReader(normalized_lines[header_index:])
    require(
        tuple(reader.fieldnames or ()) == FRESH_EMPTY_CSV_COLUMNS,
        "FRESH_EMPTY_OUTPUT_INVALID",
    )
    rows = list(reader)
    require(len(rows) == 1, "FRESH_EMPTY_OUTPUT_INVALID")
    counts: dict[str, int] = {}
    for csv_key, evidence_key in FRESH_EMPTY_COUNT_KEYS.items():
        raw_value = str(rows[0].get(csv_key, ""))
        require(re.fullmatch(r"0|[1-9][0-9]*", raw_value) is not None, "FRESH_EMPTY_OUTPUT_INVALID")
        parsed = int(raw_value)
        require(parsed <= 9_223_372_036_854_775_807, "FRESH_EMPTY_OUTPUT_INVALID")
        counts[evidence_key] = parsed
    return counts


def build_fresh_empty_evidence(counts: Mapping[str, Any]) -> dict[str, Any]:
    expected_keys = set(FRESH_EMPTY_COUNT_KEYS.values())
    require(set(counts) == expected_keys, "FRESH_EMPTY_COUNTS_INVALID")
    normalized: dict[str, int] = {}
    for key in sorted(expected_keys):
        value = counts[key]
        require(type(value) is int and 0 <= value <= 9_223_372_036_854_775_807, "FRESH_EMPTY_COUNTS_INVALID")
        normalized[key] = value
    zero_keys = expected_keys - {"publicSchemaCount", "requiredSystemTableCount"}
    eligible = (
        normalized["publicSchemaCount"] == 1
        and normalized["requiredSystemTableCount"] == FRESH_EMPTY_REQUIRED_SYSTEM_TABLES
        and all(normalized[key] == 0 for key in zero_keys)
    )
    return {
        "applicationSchemas": ["app_private", "public"],
        "backupRequiredTaskId": None if eligible else "B6.02",
        "counts": normalized,
        "countsSha256": sha256_bytes(canonical_json_bytes(normalized)),
        "freshEmptyEligible": eligible,
        "proofPolicy": FRESH_EMPTY_PROOF_POLICY,
        "schemaVersion": 2,
    }


def validate_fresh_empty_evidence(document: Mapping[str, Any]) -> dict[str, Any]:
    require(
        set(document)
        == {
            "applicationSchemas",
            "backupRequiredTaskId",
            "counts",
            "countsSha256",
            "freshEmptyEligible",
            "proofPolicy",
            "schemaVersion",
        },
        "FRESH_EMPTY_EVIDENCE_INVALID",
    )
    require(isinstance(document.get("counts"), dict), "FRESH_EMPTY_EVIDENCE_INVALID")
    expected = build_fresh_empty_evidence(document["counts"])
    require(dict(document) == expected, "FRESH_EMPTY_EVIDENCE_INVALID")
    return expected


def inspect_fresh_empty_state(
    *,
    prefix: str,
    evidence_directory: Path,
    authority: TargetAuthority,
    repository_root: Path,
) -> dict[str, Any]:
    result = run_cli(
        kind="fresh-empty",
        evidence_path=evidence_directory / f"{prefix}-FRESH-EMPTY-QUERY.txt",
        authority=authority,
        repository_root=repository_root,
    )
    evidence = build_fresh_empty_evidence(parse_fresh_empty_counts(result.stdout or ""))
    write_json_exclusive(
        evidence_directory / f"{prefix}-FRESH-EMPTY-CHECK.json",
        evidence,
        secret_values=authority.secret_values,
    )
    return evidence


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


def require_fresh_history_consistency(
    fresh_empty: Mapping[str, Any],
    migration_state: Mapping[str, Any],
    *,
    code: str,
) -> None:
    validated = validate_fresh_empty_evidence(fresh_empty)
    counts = validated["counts"]
    require(
        counts["applicationMigrationCount"] == migration_state.get("remoteCount"),
        code,
    )


def classify_apply_reconciliation(
    *,
    local_versions: Sequence[str],
    migration_state: Mapping[str, Any] | None,
    fresh_empty: Mapping[str, Any] | None,
    pre_apply_migration_state: Mapping[str, Any] | None = None,
    mutation_proven_impossible: bool = False,
) -> dict[str, Any]:
    local_count = len(local_versions)
    if migration_state is None:
        classification = "unknown"
        mutation_state = "indeterminate"
        outcome = "indeterminate"
    elif (
        migration_state.get("remoteIsExactLocalPrefix") is True
        and migration_state.get("remoteCount") == local_count
        and migration_state.get("pendingCount") == 0
        and local_count > 0
    ):
        classification = "full_exact"
        mutation_state = "committed"
        outcome = "success_recovered"
    elif (
        mutation_proven_impossible
        and pre_apply_migration_state is not None
        and dict(migration_state) == dict(pre_apply_migration_state)
    ):
        classification = "none_applied_pre_mutation_failure"
        mutation_state = "not_committed"
        outcome = "failed_safe"
    elif (
        migration_state.get("remoteIsExactLocalPrefix") is True
        and migration_state.get("remoteCount") == 0
        and migration_state.get("pendingCount") == local_count
        and fresh_empty is not None
        and validate_fresh_empty_evidence(fresh_empty)["freshEmptyEligible"] is True
    ):
        classification = "none_applied"
        mutation_state = "not_committed"
        outcome = "failed_safe"
    else:
        classification = "partial_or_unknown"
        mutation_state = "indeterminate"
        outcome = "indeterminate"
    return {
        "classification": classification,
        "migrationStateSha256": (
            sha256_bytes(canonical_json_bytes(migration_state))
            if migration_state is not None
            else None
        ),
        "mutationState": mutation_state,
        "mutationProvenImpossible": mutation_proven_impossible,
        "outcome": outcome,
        "preApplyMigrationStateSha256": (
            sha256_bytes(canonical_json_bytes(pre_apply_migration_state))
            if pre_apply_migration_state is not None
            else None
        ),
        "retryDisposition": (
            "NEW_PLAN_AND_APPROVAL_REQUIRED"
            if outcome == "failed_safe"
            else "DO_NOT_RETRY"
            if outcome == "indeterminate"
            else "NOT_APPLICABLE"
        ),
        "schemaVersion": 1,
    }


def inspect_remote_migration_state(
    *,
    prefix: str,
    evidence_directory: Path,
    authority: TargetAuthority,
    repository_root: Path,
    local_versions: Sequence[str],
    require_exact_match: bool,
    require_local_prefix: bool = True,
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
    if require_local_prefix:
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
    fresh_empty_evidence: Mapping[str, Any] | None = None,
    reconciliation_evidence: Mapping[str, Any] | None = None,
    mutation_state: str = "not_started",
    execution_disposition: str = "EXECUTED",
    retry_disposition: str | None = None,
    recovered_error_code: str | None = None,
    error_code: str | None = None,
    secret_values: Iterable[str] = (),
) -> dict[str, Any]:
    require(
        status in {"succeeded", "failed", "indeterminate", "prepared_not_executable"},
        "RECEIPT_STATUS_INVALID",
    )
    require(
        mutation_state
        in {
            "not_started",
            "not_committed",
            "committed",
            "committed_unqualified",
            "indeterminate",
        },
        "MUTATION_STATE_INVALID",
    )
    require(
        execution_disposition
        in {
            "EXECUTED",
            "EXECUTED_RECOVERED",
            "EXECUTION_NOT_STARTED",
            "INDETERMINATE",
            "REJECTED_PREFLIGHT",
            "PREPARED_NOT_EXECUTABLE",
        },
        "EXECUTION_DISPOSITION_INVALID",
    )
    if retry_disposition is None:
        retry_disposition = (
            "BACKUP_B6_02_REQUIRED"
            if status == "prepared_not_executable"
            else "DO_NOT_RETRY"
            if status == "indeterminate"
            else "NEW_DISPATCH_REQUIRED"
            if status == "failed"
            else "NOT_APPLICABLE"
        )
    require(
        retry_disposition
        in {
            "BACKUP_B6_02_REQUIRED",
            "DO_NOT_RETRY",
            "NEW_DISPATCH_REQUIRED",
            "NEW_PLAN_AND_APPROVAL_REQUIRED",
            "NOT_APPLICABLE",
        },
        "RETRY_DISPOSITION_INVALID",
    )
    if operation in {"plan", "verify"}:
        require(mutation_state == "not_started", "MUTATION_STATE_INVALID")
    if status == "prepared_not_executable":
        require(
            mutation_state == "not_started"
            and execution_disposition == "PREPARED_NOT_EXECUTABLE"
            and retry_disposition == "BACKUP_B6_02_REQUIRED",
            "PREPARED_RECEIPT_INVALID",
        )
    if status == "indeterminate":
        require(
            operation == "apply"
            and mutation_state == "indeterminate"
            and execution_disposition == "INDETERMINATE"
            and retry_disposition == "DO_NOT_RETRY",
            "INDETERMINATE_RECEIPT_INVALID",
        )
        require(
            isinstance(reconciliation_evidence, Mapping)
            and reconciliation_evidence.get("outcome") == "indeterminate",
            "INDETERMINATE_RECEIPT_INVALID",
        )
    if mutation_state == "indeterminate":
        require(status == "indeterminate", "INDETERMINATE_RECEIPT_INVALID")
    if mutation_state == "not_committed":
        require(
            operation == "apply"
            and status == "failed"
            and retry_disposition == "NEW_PLAN_AND_APPROVAL_REQUIRED"
            and isinstance(reconciliation_evidence, Mapping)
            and reconciliation_evidence.get("outcome") == "failed_safe",
            "NOT_COMMITTED_RECEIPT_INVALID",
        )
        reconciliation_classification = reconciliation_evidence.get("classification")
        if execution_disposition == "EXECUTION_NOT_STARTED":
            require(
                reconciliation_classification == "none_applied_pre_mutation_failure",
                "NOT_COMMITTED_RECEIPT_INVALID",
            )
        else:
            require(
                execution_disposition == "EXECUTED"
                and reconciliation_classification != "none_applied_pre_mutation_failure",
                "NOT_COMMITTED_RECEIPT_INVALID",
            )
    if execution_disposition == "EXECUTION_NOT_STARTED":
        require(
            operation == "apply"
            and status == "failed"
            and mutation_state == "not_committed",
            "NOT_COMMITTED_RECEIPT_INVALID",
        )
    if mutation_state == "committed_unqualified":
        require(
            operation == "apply" and status == "failed" and retry_disposition == "DO_NOT_RETRY",
            "COMMITTED_UNQUALIFIED_RECEIPT_INVALID",
        )
    if operation == "apply" and status == "succeeded":
        require(
            mutation_state == "committed"
            and execution_disposition in {"EXECUTED", "EXECUTED_RECOVERED"},
            "APPLY_SUCCESS_RECEIPT_INVALID",
        )
    if execution_disposition == "EXECUTED_RECOVERED":
        require(
            operation == "apply"
            and mutation_state in {"committed", "committed_unqualified"}
            and recovered_error_code is not None
            and isinstance(reconciliation_evidence, Mapping)
            and reconciliation_evidence.get("outcome") == "success_recovered",
            "RECOVERED_RECEIPT_INVALID",
        )
    else:
        require(recovered_error_code is None, "RECOVERED_RECEIPT_INVALID")
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
        "freshEmptyProof": fresh_empty_evidence,
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
            else "indeterminate"
            if status == "indeterminate"
            else "not-run"
            if status == "prepared_not_executable"
            else "failed"
        ),
        "reconciliation": reconciliation_evidence,
        "recoveredErrorCode": recovered_error_code,
        "repository": EXPECTED_REPOSITORY,
        "retryDisposition": retry_disposition,
        "runAttempt": request_evidence["runAttempt"],
        "runId": request_evidence["runId"],
        "runUrl": request_evidence["runUrl"],
        "schemaVersion": 3,
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
        "freshEmptyProof": None,
        "gate": "G5",
        "gateClosed": False,
        "migrationManifest": None,
        "migrationStateEvidence": None,
        "mutationState": "not_started",
        "operation": operation,
        "planProof": None,
        "qualification": "not-run",
        "reconciliation": None,
        "recoveredErrorCode": None,
        "repository": EXPECTED_REPOSITORY,
        "retryDisposition": "NEW_DISPATCH_REQUIRED",
        "runAttempt": run_attempt,
        "runId": run_id,
        "runUrl": (
            f"https://github.com/{EXPECTED_REPOSITORY}/actions/runs/{run_id}"
            if run_id is not None
            else None
        ),
        "schemaVersion": 3,
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
            encoding="utf-8",
            errors="replace",
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise StagingDatabaseError("GIT_HEAD_UNAVAILABLE") from error
    require(result.returncode == 0, "GIT_HEAD_UNAVAILABLE")
    return result.stdout.strip()


def write_apply_prepared_not_executable(
    *,
    evidence_directory: Path,
    reason_code: str,
    request_evidence: Mapping[str, Any],
    ci_evidence: Mapping[str, Any],
    target_evidence: Mapping[str, Any],
    migration_manifest: Mapping[str, Any],
    plan_evidence: Mapping[str, Any] | None,
    migration_state: Mapping[str, Any] | None,
    fresh_empty_evidence: Mapping[str, Any] | None,
    secret_values: Iterable[str],
) -> None:
    preparation = {
        "backupProducerAvailable": BACKUP_PRODUCER_AVAILABLE,
        "backupRequiredTaskId": "B6.02",
        "executionDisposition": "PREPARED_NOT_EXECUTABLE",
        "freshEmptyEvidenceSha256": (
            sha256_bytes(canonical_json_bytes(fresh_empty_evidence))
            if fresh_empty_evidence is not None
            else None
        ),
        "mutationState": "not_started",
        "reasonCode": reason_code,
        "schemaVersion": 2,
    }
    write_json_exclusive(
        evidence_directory / "APPLY-PREPARATION.json",
        preparation,
        secret_values=secret_values,
    )
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
        migration_state=migration_state,
        fresh_empty_evidence=fresh_empty_evidence,
        mutation_state="not_started",
        execution_disposition="PREPARED_NOT_EXECUTABLE",
        retry_disposition="BACKUP_B6_02_REQUIRED",
        error_code=reason_code,
        secret_values=secret_values,
    )
    raise StagingDatabaseError("APPLY_PREPARED_NOT_EXECUTABLE")


def reconcile_apply_attempt(
    *,
    attempt: MutationAttempt,
    evidence_directory: Path,
    authority: TargetAuthority,
    repository_root: Path,
    local_versions: Sequence[str],
    pre_apply_migration_state: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any] | None, dict[str, Any] | None]:
    migration_state: dict[str, Any] | None = None
    fresh_empty: dict[str, Any] | None = None
    read_error_code: str | None = None
    try:
        migration_state = inspect_remote_migration_state(
            prefix="APPLY-RECONCILIATION",
            evidence_directory=evidence_directory,
            authority=authority,
            repository_root=repository_root,
            local_versions=local_versions,
            require_exact_match=False,
            require_local_prefix=False,
        )
    except StagingDatabaseError as error:
        read_error_code = error.code
    if migration_state is not None and migration_state.get("remoteCount") == 0:
        try:
            candidate = inspect_fresh_empty_state(
                prefix="APPLY-RECONCILIATION",
                evidence_directory=evidence_directory,
                authority=authority,
                repository_root=repository_root,
            )
            require_fresh_history_consistency(
                candidate,
                migration_state,
                code="RECONCILIATION_FRESH_HISTORY_DRIFT",
            )
            fresh_empty = candidate
        except StagingDatabaseError as error:
            read_error_code = error.code
    reconciliation = classify_apply_reconciliation(
        local_versions=local_versions,
        migration_state=migration_state,
        fresh_empty=fresh_empty,
        pre_apply_migration_state=pre_apply_migration_state,
        mutation_proven_impossible=attempt.error_code
        in {"SUPABASE_APPLY_MISSING", "SUPABASE_APPLY_SPAWN_FAILED"},
    )
    reconciliation.update(
        {
            "freshEmptyEvidenceSha256": (
                sha256_bytes(canonical_json_bytes(fresh_empty))
                if fresh_empty is not None
                else None
            ),
            "readErrorCode": read_error_code,
            "triggerErrorCode": attempt.error_code,
            "triggerExitCode": attempt.exit_code,
            "triggerTimedOut": attempt.timed_out,
        }
    )
    write_json_exclusive(
        evidence_directory / "APPLY-RECONCILIATION.json",
        reconciliation,
        secret_values=authority.secret_values,
    )
    return reconciliation, migration_state, fresh_empty


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
    backup_evidence: dict[str, Any] | None = None
    migration_state: dict[str, Any] | None = None
    fresh_empty_evidence: dict[str, Any] | None = None
    reconciliation_evidence: dict[str, Any] | None = None
    mutation_state = "not_started"
    execution_disposition = "EXECUTED"
    recovered_error_code: str | None = None
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
            fresh_empty_evidence = inspect_fresh_empty_state(
                prefix="PLAN",
                evidence_directory=evidence_directory,
                authority=authority,
                repository_root=repository_root,
            )
            require_fresh_history_consistency(
                fresh_empty_evidence,
                migration_state,
                code="PLAN_FRESH_HISTORY_DRIFT",
            )
        elif args.operation == "apply":
            require(apply_authority is not None, "APPLY_AUTHORITY_MISSING")
            require(
                apply_authority["planRunId"] != request_evidence["runId"],
                "PLAN_RUN_IS_CURRENT_RUN",
            )
            try:
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
            except StagingDatabaseError as error:
                write_json_exclusive(
                    evidence_directory / "PLAN-PROOF-VALIDATION-FAILURE.json",
                    {
                        "errorCode": error.code,
                        "executionDisposition": "PREPARED_NOT_EXECUTABLE",
                        "schemaVersion": 1,
                    },
                    secret_values=authority.secret_values,
                )
                write_apply_prepared_not_executable(
                    evidence_directory=evidence_directory,
                    reason_code="VALIDATED_PLAN_PROOF_UNAVAILABLE",
                    request_evidence=request_evidence,
                    ci_evidence=ci_evidence,
                    target_evidence=target_evidence,
                    migration_manifest=migration_manifest,
                    plan_evidence=None,
                    migration_state=None,
                    fresh_empty_evidence=None,
                    secret_values=authority.secret_values,
                )
            require(plan_evidence is not None, "VALIDATED_PLAN_PROOF_UNAVAILABLE")
            write_json_exclusive(
                evidence_directory / "VALIDATED-PLAN-PROOF.json",
                plan_evidence,
                secret_values=authority.secret_values,
            )
            uses_fresh_empty_exception = apply_authority["backup"] is None
            if not uses_fresh_empty_exception:
                backup_authority = apply_authority["backup"]
                require(isinstance(backup_authority, dict), "BACKUP_AUTHORITY_MISSING")
                require(
                    backup_authority["runId"]
                    not in {request_evidence["runId"], apply_authority["planRunId"]},
                    "BACKUP_RUN_ID_INVALID",
                )
                try:
                    backup_evidence = validate_backup_artifact_bundle(
                        run_document=load_json_object(Path(args.backup_run_json)),
                        artifact_document=load_json_object(Path(args.backup_artifact_json)),
                        archive_path=Path(args.backup_artifact_zip),
                        backup_run_id=backup_authority["runId"],
                        backup_artifact_id=backup_authority["artifactId"],
                        backup_artifact_digest=backup_authority["artifactDigest"],
                        expected_sha=args.expected_sha,
                        validated_ci_run_id=request_evidence["validatedCiRunId"],
                        target_evidence=target_evidence,
                    )
                except StagingDatabaseError as error:
                    write_json_exclusive(
                        evidence_directory / "BACKUP-PROOF-VALIDATION-FAILURE.json",
                        {
                            "errorCode": error.code,
                            "executionDisposition": "PREPARED_NOT_EXECUTABLE",
                            "schemaVersion": 1,
                        },
                        secret_values=authority.secret_values,
                    )
                    write_apply_prepared_not_executable(
                        evidence_directory=evidence_directory,
                        reason_code="VALIDATED_BACKUP_PROOF_UNAVAILABLE",
                        request_evidence=request_evidence,
                        ci_evidence=ci_evidence,
                        target_evidence=target_evidence,
                        migration_manifest=migration_manifest,
                        plan_evidence=plan_evidence,
                        migration_state=None,
                        fresh_empty_evidence=None,
                        secret_values=authority.secret_values,
                    )
                require(backup_evidence is not None, "VALIDATED_BACKUP_PROOF_UNAVAILABLE")
                write_json_exclusive(
                    evidence_directory / "VALIDATED-BACKUP-PROOF.json",
                    backup_evidence,
                    secret_values=authority.secret_values,
                )
                pending_scope = validate_pending_migration_scope(
                    repository_root,
                    plan_evidence["pendingVersions"],
                )
                write_json_exclusive(
                    evidence_directory / "BACKUP-PENDING-MIGRATION-SCOPE.json",
                    pending_scope,
                    secret_values=authority.secret_values,
                )
            plan_fresh_empty = validate_fresh_empty_evidence(
                plan_evidence["freshEmptyEvidence"]
            )
            if uses_fresh_empty_exception and (
                plan_fresh_empty["freshEmptyEligible"] is not True
                or plan_evidence["remoteCount"] != 0
                or plan_evidence["pendingCount"] != migration_manifest["count"]
            ):
                write_apply_prepared_not_executable(
                    evidence_directory=evidence_directory,
                    reason_code="FRESH_EMPTY_PLAN_PROOF_NOT_ELIGIBLE",
                    request_evidence=request_evidence,
                    ci_evidence=ci_evidence,
                    target_evidence=target_evidence,
                    migration_manifest=migration_manifest,
                    plan_evidence=plan_evidence,
                    migration_state=None,
                    fresh_empty_evidence=plan_fresh_empty,
                    secret_values=authority.secret_values,
                )
            try:
                pre_apply_state = inspect_remote_migration_state(
                    prefix="PRE-APPLY",
                    evidence_directory=evidence_directory,
                    authority=authority,
                    repository_root=repository_root,
                    local_versions=local_versions,
                    require_exact_match=False,
                    require_local_prefix=False,
                )
            except StagingDatabaseError:
                write_apply_prepared_not_executable(
                    evidence_directory=evidence_directory,
                    reason_code="FRESH_EMPTY_REMOTE_HISTORY_PROOF_UNAVAILABLE",
                    request_evidence=request_evidence,
                    ci_evidence=ci_evidence,
                    target_evidence=target_evidence,
                    migration_manifest=migration_manifest,
                    plan_evidence=plan_evidence,
                    migration_state=None,
                    fresh_empty_evidence=plan_fresh_empty,
                    secret_values=authority.secret_values,
                )
            if sha256_bytes(canonical_json_bytes(pre_apply_state)) != plan_evidence[
                "migrationStateEvidenceSha256"
            ]:
                write_apply_prepared_not_executable(
                    evidence_directory=evidence_directory,
                    reason_code="FRESH_EMPTY_REMOTE_HISTORY_DRIFT",
                    request_evidence=request_evidence,
                    ci_evidence=ci_evidence,
                    target_evidence=target_evidence,
                    migration_manifest=migration_manifest,
                    plan_evidence=plan_evidence,
                    migration_state=pre_apply_state,
                    fresh_empty_evidence=plan_fresh_empty,
                    secret_values=authority.secret_values,
                )
            if backup_evidence is not None:
                require(
                    backup_evidence["migrationPrefixCount"] == pre_apply_state["remoteCount"]
                    and backup_evidence["migrationPrefixSha256"]
                    == pre_apply_state["remoteVersionsSha256"],
                    "BACKUP_MIGRATION_PREFIX_DRIFT",
                )
            run_cli(
                kind="plan",
                evidence_path=evidence_directory / "PRE-APPLY-DRY-RUN.txt",
                authority=authority,
                repository_root=repository_root,
            )
            current_fresh_empty: dict[str, Any] | None = None
            try:
                current_fresh_empty = inspect_fresh_empty_state(
                    prefix="PRE-APPLY",
                    evidence_directory=evidence_directory,
                    authority=authority,
                    repository_root=repository_root,
                )
                require_fresh_history_consistency(
                    current_fresh_empty,
                    pre_apply_state,
                    code="PRE_APPLY_FRESH_HISTORY_DRIFT",
                )
            except StagingDatabaseError:
                write_apply_prepared_not_executable(
                    evidence_directory=evidence_directory,
                    reason_code="FRESH_EMPTY_RECHECK_UNAVAILABLE",
                    request_evidence=request_evidence,
                    ci_evidence=ci_evidence,
                    target_evidence=target_evidence,
                    migration_manifest=migration_manifest,
                    plan_evidence=plan_evidence,
                    migration_state=pre_apply_state,
                    fresh_empty_evidence=current_fresh_empty or plan_fresh_empty,
                    secret_values=authority.secret_values,
                )
            require(current_fresh_empty is not None, "FRESH_EMPTY_RECHECK_UNAVAILABLE")
            fresh_empty_evidence = current_fresh_empty
            if uses_fresh_empty_exception and (
                current_fresh_empty["freshEmptyEligible"] is not True
                or current_fresh_empty["countsSha256"]
                != plan_fresh_empty["countsSha256"]
            ):
                write_apply_prepared_not_executable(
                    evidence_directory=evidence_directory,
                    reason_code="FRESH_EMPTY_DATABASE_DRIFT",
                    request_evidence=request_evidence,
                    ci_evidence=ci_evidence,
                    target_evidence=target_evidence,
                    migration_manifest=migration_manifest,
                    plan_evidence=plan_evidence,
                    migration_state=pre_apply_state,
                    fresh_empty_evidence=current_fresh_empty,
                    secret_values=authority.secret_values,
                )
            if backup_evidence is not None:
                counts = current_fresh_empty["counts"]
                require(
                    counts["authUserCount"] == 0
                    and counts["authRelevantRowCount"] == 0
                    and counts["storageObjectCount"] == 0
                    and counts["storageBucketCount"] == 0
                    and counts["storageRelevantRowCount"] == 0,
                    "BACKUP_MANAGED_DATA_DRIFT",
                )
                require(
                    parse_github_timestamp(
                        backup_evidence["applyValidUntil"],
                        "BACKUP_RECEIPT_RPO_INVALID",
                    )
                    > datetime.now(timezone.utc),
                    "BACKUP_RECEIPT_RPO_INVALID",
                )
            attempt = run_apply_mutation(
                evidence_path=evidence_directory / "APPLY-DB-PUSH.txt",
                authority=authority,
                repository_root=repository_root,
            )
            if attempt.error_code is not None:
                (
                    reconciliation_evidence,
                    migration_state,
                    reconciled_fresh_empty,
                ) = reconcile_apply_attempt(
                    attempt=attempt,
                    evidence_directory=evidence_directory,
                    authority=authority,
                    repository_root=repository_root,
                    local_versions=local_versions,
                    pre_apply_migration_state=pre_apply_state,
                )
                outcome = reconciliation_evidence["outcome"]
                if outcome == "failed_safe":
                    execution_disposition = (
                        "EXECUTION_NOT_STARTED"
                        if reconciliation_evidence.get("classification")
                        == "none_applied_pre_mutation_failure"
                        else "EXECUTED"
                    )
                    write_gel_receipt(
                        evidence_directory=evidence_directory,
                        operation="apply",
                        status="failed",
                        request_evidence=request_evidence,
                        ci_evidence=ci_evidence,
                        target_evidence=target_evidence,
                        migration_manifest=migration_manifest,
                        backup_evidence=backup_evidence,
                        plan_evidence=plan_evidence,
                        migration_state=migration_state,
                        fresh_empty_evidence=reconciled_fresh_empty,
                        reconciliation_evidence=reconciliation_evidence,
                        mutation_state="not_committed",
                        execution_disposition=execution_disposition,
                        retry_disposition="NEW_PLAN_AND_APPROVAL_REQUIRED",
                        error_code=attempt.error_code,
                        secret_values=authority.secret_values,
                    )
                    raise StagingDatabaseError("APPLY_FAILED_SAFE_RECONCILED")
                if outcome == "success_recovered":
                    recovered_error_code = attempt.error_code
                    mutation_state = "committed"
                    execution_disposition = "EXECUTED_RECOVERED"
                    try:
                        migration_state = qualify_database(
                            prefix="POST-APPLY",
                            evidence_directory=evidence_directory,
                            authority=authority,
                            repository_root=repository_root,
                            local_versions=local_versions,
                        )
                    except StagingDatabaseError:
                        mutation_state = "committed_unqualified"
                        raise
                    write_gel_receipt(
                        evidence_directory=evidence_directory,
                        operation="apply",
                        status="succeeded",
                        request_evidence=request_evidence,
                        ci_evidence=ci_evidence,
                        target_evidence=target_evidence,
                        migration_manifest=migration_manifest,
                        backup_evidence=backup_evidence,
                        plan_evidence=plan_evidence,
                        migration_state=migration_state,
                        fresh_empty_evidence=fresh_empty_evidence,
                        reconciliation_evidence=reconciliation_evidence,
                        mutation_state="committed",
                        execution_disposition="EXECUTED_RECOVERED",
                        recovered_error_code=recovered_error_code,
                        secret_values=authority.secret_values,
                    )
                    print(f"OK staging database operation=apply GEL={GEL_FILENAME}")
                    return
                write_gel_receipt(
                    evidence_directory=evidence_directory,
                    operation="apply",
                    status="indeterminate",
                    request_evidence=request_evidence,
                    ci_evidence=ci_evidence,
                    target_evidence=target_evidence,
                    migration_manifest=migration_manifest,
                    backup_evidence=backup_evidence,
                    plan_evidence=plan_evidence,
                    migration_state=migration_state,
                    fresh_empty_evidence=reconciled_fresh_empty,
                    reconciliation_evidence=reconciliation_evidence,
                    mutation_state="indeterminate",
                    execution_disposition="INDETERMINATE",
                    retry_disposition="DO_NOT_RETRY",
                    error_code=attempt.error_code,
                    secret_values=authority.secret_values,
                )
                raise StagingDatabaseError("APPLY_INDETERMINATE_DO_NOT_RETRY")
            mutation_state = "committed"
            try:
                migration_state = qualify_database(
                    prefix="POST-APPLY",
                    evidence_directory=evidence_directory,
                    authority=authority,
                    repository_root=repository_root,
                    local_versions=local_versions,
                )
            except StagingDatabaseError:
                mutation_state = "committed_unqualified"
                raise
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
                    backup_evidence=backup_evidence,
                    plan_evidence=plan_evidence,
                    migration_state=migration_state,
                    fresh_empty_evidence=fresh_empty_evidence,
                    reconciliation_evidence=reconciliation_evidence,
                    mutation_state=mutation_state,
                    execution_disposition=(
                        execution_disposition
                        if mutation_state in {"committed", "committed_unqualified"}
                        else "REJECTED_PREFLIGHT"
                    ),
                    retry_disposition=(
                        "DO_NOT_RETRY"
                        if mutation_state in {"committed", "committed_unqualified"}
                        else "NEW_DISPATCH_REQUIRED"
                    ),
                    recovered_error_code=recovered_error_code,
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
        backup_evidence=backup_evidence,
        plan_evidence=plan_evidence,
        migration_state=migration_state,
        fresh_empty_evidence=fresh_empty_evidence,
        reconciliation_evidence=reconciliation_evidence,
        mutation_state=mutation_state,
        execution_disposition=execution_disposition,
        recovered_error_code=recovered_error_code,
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
    execute_parser.add_argument("--backup-run-json", default="")
    execute_parser.add_argument("--backup-artifact-json", default="")
    execute_parser.add_argument("--backup-artifact-zip", default="")
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
