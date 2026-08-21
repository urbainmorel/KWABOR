from __future__ import annotations

import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock


TOOLS_DIRECTORY = Path(__file__).resolve().parent
REPOSITORY_ROOT = TOOLS_DIRECTORY.parent
MODULE_PATH = TOOLS_DIRECTORY / "closed-beta-staging-database.py"
WORKFLOW_PATH = REPOSITORY_ROOT / ".github" / "workflows" / "closed-beta-staging-database.yml"
SPEC = importlib.util.spec_from_file_location("closed_beta_staging_database", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
database = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = database
SPEC.loader.exec_module(database)


EXPECTED_SHA = "a" * 40
STAGING_REF = "s" * 20
PRODUCTION_REF = "p" * 20
STAGING_REF_SHA256 = hashlib.sha256(STAGING_REF.encode("utf-8")).hexdigest()
DATABASE_PASSWORD = "safe%40database%21password"
DATABASE_URL = (
    f"postgresql://postgres.{STAGING_REF}:{DATABASE_PASSWORD}@"
    "aws-1-eu-west-1.pooler.supabase.com:5432/postgres?sslmode=require"
)


def valid_authority(database_url: str = DATABASE_URL) -> database.TargetAuthority:
    return database.validate_target_authority(
        environment="staging",
        api_url=f"https://{STAGING_REF}.supabase.co",
        project_ref=STAGING_REF,
        production_project_ref=PRODUCTION_REF,
        project_ref_sha256=STAGING_REF_SHA256,
        database_url=database_url,
    )


def valid_ci_run() -> dict[str, object]:
    return {
        "conclusion": "success",
        "event": "push",
        "head_branch": "main",
        "head_sha": EXPECTED_SHA,
        "html_url": "https://github.com/urbainmorel/KWABOR/actions/runs/100",
        "id": 100,
        "path": ".github/workflows/ci.yml@refs/heads/main",
        "repository": {"full_name": "urbainmorel/KWABOR"},
        "run_attempt": 1,
        "run_number": 50,
        "status": "completed",
    }


def valid_supporting_run(
    *,
    run_id: int = 300,
    workflow: str = database.EXPECTED_DATABASE_WORKFLOW,
    run_attempt: int = 2,
) -> dict[str, object]:
    return {
        "conclusion": "success",
        "event": "workflow_dispatch",
        "head_branch": "main",
        "head_sha": EXPECTED_SHA,
        "html_url": f"https://github.com/urbainmorel/KWABOR/actions/runs/{run_id}",
        "id": run_id,
        "path": f"{workflow}@refs/heads/main",
        "repository": {"full_name": "urbainmorel/KWABOR", "id": 1234},
        "run_attempt": run_attempt,
        "status": "completed",
    }


def valid_artifact(
    *,
    artifact_id: int,
    run_id: int,
    run_attempt: int,
    name_prefix: str,
    digest: str,
    size_bytes: int,
) -> dict[str, object]:
    api_url = (
        "https://api.github.com/repos/urbainmorel/KWABOR/actions/artifacts/"
        f"{artifact_id}"
    )
    return {
        "archive_download_url": f"{api_url}/zip",
        "created_at": "2026-08-20T12:00:00Z",
        "digest": f"sha256:{digest}",
        "expired": False,
        "expires_at": "2026-11-18T12:00:00Z",
        "id": artifact_id,
        "name": f"{name_prefix}-{EXPECTED_SHA}-{run_attempt}",
        "size_in_bytes": size_bytes,
        "updated_at": "2026-08-20T12:00:01Z",
        "url": api_url,
        "workflow_run": {
            "head_branch": "main",
            "head_repository_id": 1234,
            "head_sha": EXPECTED_SHA,
            "id": run_id,
            "repository_id": 1234,
        },
    }


def create_plan_archive(
    root: Path,
    *,
    receipt_mutation: dict[str, object] | None = None,
) -> tuple[Path, str, dict[str, object]]:
    evidence = root / "plan-evidence"
    evidence.mkdir()
    authority = valid_authority()
    target = authority.public_evidence()
    manifest = {
        "count": 2,
        "manifestSha256": "d" * 64,
        "migrations": [],
        "schemaVersion": 1,
    }
    pending = database.migration_state_evidence(
        local_versions=["20260703004103", "20260703093622"],
        remote_versions=[],
    )
    fresh_empty = database.build_fresh_empty_evidence(virgin_fresh_counts())
    database.write_json_exclusive(evidence / "LOCAL-MIGRATION-MANIFEST.json", manifest)
    database.write_json_exclusive(evidence / "PLAN-PENDING-CHECK.json", pending)
    database.write_json_exclusive(evidence / "PLAN-FRESH-EMPTY-CHECK.json", fresh_empty)
    database.write_text_exclusive(
        evidence / "PLAN-FRESH-EMPTY-QUERY.txt",
        "exitCode=0\n[stdout]\naggregated counts only\n[stderr]\n(empty)\n",
    )
    database.write_text_exclusive(
        evidence / "PLAN-DRY-RUN.txt",
        "exitCode=0\n[stdout]\nWould push 20260703093622\n[stderr]\n(empty)\n",
    )
    request = {
        "actor": "release-owner",
        "expectedSha": EXPECTED_SHA,
        "runAttempt": 2,
        "runId": 300,
        "runUrl": "https://github.com/urbainmorel/KWABOR/actions/runs/300",
        "validatedCiRunId": 100,
    }
    receipt = database.write_gel_receipt(
        evidence_directory=evidence,
        operation="plan",
        status="succeeded",
        request_evidence=request,
        ci_evidence=database.validate_ci_run(
            valid_ci_run(), expected_run_id="100", expected_sha=EXPECTED_SHA
        ),
        target_evidence=target,
        migration_manifest=manifest,
        backup_evidence=None,
        migration_state=pending,
        fresh_empty_evidence=fresh_empty,
    )
    if receipt_mutation:
        receipt.update(receipt_mutation)
        receipt_path = evidence / database.GEL_FILENAME
        receipt_path.write_bytes(database.canonical_json_bytes(receipt))
        receipt_digest = hashlib.sha256(receipt_path.read_bytes()).hexdigest()
        (evidence / database.GEL_HASH_FILENAME).write_text(
            f"{receipt_digest}  {database.GEL_FILENAME}\n",
            encoding="utf-8",
        )
    archive = root / "plan.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as destination:
        for path in sorted(evidence.iterdir()):
            destination.write(path, arcname=path.name)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    return archive, digest, manifest


def create_backup_archive(
    root: Path,
    *,
    receipt_mutation: dict[str, object] | None = None,
    database_fingerprint_override: str | None = None,
    ciphertext_project_prefix_override: str | None = None,
) -> tuple[Path, str]:
    target = valid_authority().public_evidence()
    managed_tables = [
        {
            "exists": True,
            "required": required,
            "rowCount": 0,
            "schema": schema,
            "table": table,
        }
        for schema, table, required in database.MANAGED_DATA_TABLES
    ]
    migration_versions = ["20260820083427", "20260820084207"]
    migration_payload = "\n".join(migration_versions) + "\n"
    migration_sha256 = database.sha256_text(migration_payload)
    managed_catalog = [
        {"schema": schema, "table": table}
        for schema, table in database.EXPECTED_MANAGED_SCHEMA_TABLES
    ]
    constraint_inventory_sha256 = "c" * 64
    managed_proof = {
        "constraintCount": 12,
        "constraintInventorySha256": constraint_inventory_sha256,
        "foreignKeyCount": 4,
        "managedDataEmpty": True,
        "managedSchemaTableCount": len(managed_catalog),
        "managedSchemaTableSha256": database.sha256_bytes(
            database.canonical_json_bytes(managed_catalog)
        ),
        "managedTables": managed_tables,
        "migrationVersions": migration_versions,
        "postgresMajor": 17,
        "schemaVersion": 2,
        "unvalidatedConstraintCount": 0,
    }
    logical_sha256 = "e" * 64
    computed_fingerprint = database.sha256_bytes(
        database.canonical_json_bytes(
            {
                "logicalSqlNormalizedSha256": logical_sha256,
                "migrationPrefixSha256": migration_sha256,
                "postgresMajor": 17,
                "schemas": ["app_private", "public", "supabase_migrations"],
            }
        )
    )
    fingerprint = database_fingerprint_override or computed_fingerprint
    ciphertext_project_prefix = (
        ciphertext_project_prefix_override or target["projectRefSha256"][:16]
    )
    ciphertext_name = (
        f"kwabor-staging-{ciphertext_project_prefix}-{fingerprint}.tar.gz.age"
    )
    ciphertext = b"age-encrypted-test-payload"
    recipient_sha256 = "f" * 64
    receipt: dict[str, object] = {
        "ageEscrow": {
            "custodyMode": "offline-two-person",
            "maxRecoveryTestAgeDays": 90,
            "recipientSha256": recipient_sha256,
            "recoveryTestedAt": "2026-08-19T12:00:00Z",
            "status": "provisioned",
            "validUntil": "2100-01-01T00:00:00Z",
        },
        "artifactPolicy": {
            "actualDigestValidatedByConsumer": True,
            "estimatedExpiresAt": "2026-11-18T12:00:00Z",
            "expectedName": (
                f"kwabor-gel-g5-staging-database-backup-{EXPECTED_SHA}-1"
            ),
            "expirationAuthority": "github-actions-artifact-api",
            "retentionDays": 90,
        },
        "ci": {
            "conclusion": "success",
            "event": "push",
            "headBranch": "main",
            "headSha": EXPECTED_SHA,
            "runAttempt": 1,
            "runId": 100,
            "runUrl": "https://github.com/urbainmorel/KWABOR/actions/runs/100",
            "status": "completed",
            "workflowPath": database.EXPECTED_CI_WORKFLOW,
        },
        "contributesTo": "G5",
        "databaseScope": {
            "dumpModes": ["roles", "single-consistent-application-dump"],
            "managedAuthStorageDataIncluded": False,
            "managedAuthStorageEmpty": True,
            "schemas": ["app_private", "public", "supabase_migrations"],
            "type": "targeted-logical",
        },
        "encryption": {
            "algorithm": "age-x25519",
            "ciphertextBytes": len(ciphertext),
            "ciphertextFileName": ciphertext_name,
            "ciphertextSha256": hashlib.sha256(ciphertext).hexdigest(),
            "encryptedBeforeArtifactBoundary": True,
            "plaintextArtifactCount": 0,
            "recipientSha256": recipient_sha256,
        },
        "environmentEvidence": {
            "canAdminsBypass": False,
            "environmentId": 10,
            "name": "staging",
            "preventSelfReview": True,
            "protectedBranchesOnly": True,
            "reviewerCount": 1,
            "schemaVersion": 1,
            "updatedAt": "2026-08-20T11:00:00Z",
        },
        "errorCode": None,
        "expectedSha": EXPECTED_SHA,
        "operation": "backup",
        "qualifiedAt": "2026-08-20T12:00:00Z",
        "ref": "refs/heads/main",
        "repository": "urbainmorel/KWABOR",
        "restorable": True,
        "restore": {
            "allConstraintsValidated": True,
            "constraintCount": 12,
            "constraintInventorySha256": constraint_inventory_sha256,
            "databaseFingerprintSha256": fingerprint,
            "executionBoundary": "github-actions-disposable-supabase",
            "fingerprintMatch": True,
            "foreignKeyCount": 4,
            "logicalSqlNormalizedSha256": logical_sha256,
            "sessionReplicationRoleUsed": False,
            "unvalidatedConstraintCount": 0,
            "verified": True,
        },
        "rpo": {
            "applyValidUntil": "2026-08-20T12:28:00Z",
            "captureSeconds": 120,
            "maxSeconds": 1800,
            "met": True,
        },
        "rto": {"maxSeconds": 1800, "met": True, "observedSeconds": 90},
        "runAttempt": 1,
        "runId": 400,
        "runUrl": "https://github.com/urbainmorel/KWABOR/actions/runs/400",
        "schemaVersion": 2,
        "snapshot": {
            "applicationDumpAndManagedProofShareSnapshot": True,
            "exportedByDedicatedSession": True,
            "identifierSha256": "a" * 64,
            "isolation": "repeatable-read-read-only",
            "mechanism": "pg-export-snapshot",
            "snapshotEstablishedAt": "2026-08-20T11:58:00Z",
        },
        "source": {
            "databaseFingerprintSha256": fingerprint,
            "logicalSqlNormalizedSha256": logical_sha256,
            "managedDataProof": managed_proof,
            "managedDataProofSha256": hashlib.sha256(
                database.canonical_json_bytes(managed_proof)
            ).hexdigest(),
            "migrationPrefixCount": len(migration_versions),
            "migrationPrefixSha256": migration_sha256,
            "postgresMajor": 17,
        },
        "status": "succeeded",
        "target": target,
        "targetDigestSha256": hashlib.sha256(
            database.canonical_json_bytes(target)
        ).hexdigest(),
        "taskId": "B6.02",
        "validatedCiRunId": 100,
        "workflowPath": database.EXPECTED_BACKUP_WORKFLOW,
    }
    if receipt_mutation:
        receipt.update(receipt_mutation)
    receipt_payload = database.canonical_json_bytes(receipt)
    sidecar = (
        f"{hashlib.sha256(receipt_payload).hexdigest()}  {database.BACKUP_GEL_FILENAME}\n"
    ).encode("ascii")
    archive = root / "backup.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as destination:
        destination.writestr(database.BACKUP_GEL_FILENAME, receipt_payload)
        destination.writestr(database.BACKUP_GEL_HASH_FILENAME, sidecar)
        destination.writestr(ciphertext_name, ciphertext)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    return archive, digest


def valid_environment() -> dict[str, object]:
    return {
        "can_admins_bypass": False,
        "deployment_branch_policy": {
            "custom_branch_policies": False,
            "protected_branches": True,
        },
        "id": 10,
        "name": "staging",
        "protection_rules": [
            {
                "prevent_self_review": True,
                "reviewers": [
                    {"reviewer": {"id": 42, "login": "reviewer"}, "type": "User"}
                ],
                "type": "required_reviewers",
            },
            {"type": "branch_policy"},
        ],
        "updated_at": "2026-08-20T12:00:00Z",
    }


def virgin_fresh_counts(**mutations: int) -> dict[str, int]:
    counts = {
        "applicationMigrationCount": 0,
        "applicationRelationCount": 0,
        "applicationRoutineCount": 0,
        "applicationTypeCount": 0,
        "authRelevantRowCount": 0,
        "authUserCount": 0,
        "managedSchemaTableDriftCount": 0,
        "publicSchemaCount": 1,
        "requiredSystemTableCount": 3,
        "storageBucketCount": 0,
        "storageObjectCount": 0,
        "storageRelevantRowCount": 0,
    }
    counts.update(mutations)
    return counts


class RequestAndProviderGuardTest(unittest.TestCase):
    def test_exact_main_identity_is_accepted(self) -> None:
        result = database.validate_request_identity(
            repository="urbainmorel/KWABOR",
            event_name="workflow_dispatch",
            github_ref="refs/heads/main",
            github_sha=EXPECTED_SHA,
            checked_out_sha=EXPECTED_SHA,
            expected_sha=EXPECTED_SHA,
            validated_ci_run_id="100",
            run_id="200",
            run_attempt="1",
            actor="release-owner",
            server_url="https://github.com",
        )
        self.assertEqual(result["expectedSha"], EXPECTED_SHA)
        self.assertEqual(result["validatedCiRunId"], 100)

    def test_sha_ref_repository_and_event_drift_are_rejected(self) -> None:
        safe = {
            "repository": "urbainmorel/KWABOR",
            "event_name": "workflow_dispatch",
            "github_ref": "refs/heads/main",
            "github_sha": EXPECTED_SHA,
            "checked_out_sha": EXPECTED_SHA,
            "expected_sha": EXPECTED_SHA,
            "validated_ci_run_id": "100",
            "run_id": "200",
            "run_attempt": "1",
            "actor": "release-owner",
            "server_url": "https://github.com",
        }
        unsafe = (
            {"repository": "fork/KWABOR"},
            {"event_name": "push"},
            {"github_ref": "refs/heads/develop"},
            {"github_sha": "b" * 40},
            {"checked_out_sha": "b" * 40},
            {"validated_ci_run_id": "0"},
        )
        for mutation in unsafe:
            values = dict(safe)
            values.update(mutation)
            with self.subTest(mutation=mutation), self.assertRaises(database.StagingDatabaseError):
                database.validate_request_identity(**values)

    def test_ci_must_be_exact_successful_push_main_run(self) -> None:
        result = database.validate_ci_run(
            valid_ci_run(), expected_run_id="100", expected_sha=EXPECTED_SHA
        )
        self.assertEqual(result["workflowPath"], ".github/workflows/ci.yml")
        unsafe = (
            {"id": 101},
            {"head_sha": "b" * 40},
            {"head_branch": "develop"},
            {"event": "workflow_dispatch"},
            {"conclusion": "failure"},
            {"status": "in_progress"},
            {"path": ".github/workflows/android-release.yml"},
            {"repository": {"full_name": "fork/KWABOR"}},
        )
        for mutation in unsafe:
            document = valid_ci_run()
            document.update(mutation)
            with self.subTest(mutation=mutation), self.assertRaises(database.StagingDatabaseError):
                database.validate_ci_run(
                    document, expected_run_id="100", expected_sha=EXPECTED_SHA
                )

    def test_environment_requires_reviewer_self_review_block_and_protected_branches(self) -> None:
        evidence = database.validate_environment_protection(valid_environment())
        self.assertFalse(evidence["canAdminsBypass"])
        self.assertTrue(evidence["preventSelfReview"])
        serialized = json.dumps(evidence)
        self.assertNotIn('"login"', serialized)
        self.assertNotIn('"reviewer":', serialized)

        bypass = valid_environment()
        bypass["can_admins_bypass"] = True
        missing_reviewer = valid_environment()
        missing_reviewer["protection_rules"] = [{"type": "branch_policy"}]
        self_review = valid_environment()
        self_review["protection_rules"][0]["prevent_self_review"] = False  # type: ignore[index]
        unprotected = valid_environment()
        unprotected["deployment_branch_policy"] = {
            "custom_branch_policies": True,
            "protected_branches": False,
        }
        for document in (bypass, missing_reviewer, self_review, unprotected):
            with self.subTest(document=document), self.assertRaises(database.StagingDatabaseError):
                database.validate_environment_protection(document)


class TargetAuthorityTest(unittest.TestCase):
    def test_only_tls_session_pooler_url_is_accepted(self) -> None:
        pooler = valid_authority()
        self.assertEqual(pooler.database_endpoint_class, "session-pooler")
        self.assertEqual(pooler.public_evidence()["tlsMode"], "require")
        self.assertNotIn(DATABASE_PASSWORD, json.dumps(pooler.public_evidence()))

    def test_production_identity_is_never_accepted(self) -> None:
        with self.assertRaisesRegex(database.StagingDatabaseError, "PRODUCTION_TARGET_FORBIDDEN"):
            database.validate_target_authority(
                environment="staging",
                api_url=f"https://{STAGING_REF}.supabase.co",
                project_ref=STAGING_REF,
                production_project_ref=STAGING_REF,
                project_ref_sha256=STAGING_REF_SHA256,
                database_url=DATABASE_URL,
            )

    def test_api_ref_digest_and_database_ref_drift_are_rejected(self) -> None:
        mutations = (
            {"api_url": f"https://{PRODUCTION_REF}.supabase.co"},
            {"project_ref_sha256": "0" * 64},
            {
                "database_url": (
                    f"postgresql://postgres.{PRODUCTION_REF}:{DATABASE_PASSWORD}@"
                    "aws-1-eu-west-1.pooler.supabase.com:5432/postgres?sslmode=require"
                )
            },
        )
        safe = {
            "environment": "staging",
            "api_url": f"https://{STAGING_REF}.supabase.co",
            "project_ref": STAGING_REF,
            "production_project_ref": PRODUCTION_REF,
            "project_ref_sha256": STAGING_REF_SHA256,
            "database_url": DATABASE_URL,
        }
        for mutation in mutations:
            values = dict(safe)
            values.update(mutation)
            with self.subTest(mutation=mutation), self.assertRaises(database.StagingDatabaseError):
                database.validate_target_authority(**values)

    def test_tls_query_direct_host_and_other_overrides_are_rejected(self) -> None:
        invalid = (
            DATABASE_URL.replace("?sslmode=require", ""),
            DATABASE_URL.replace("sslmode=require", "sslmode=disable"),
            DATABASE_URL + "&host=evil.example",
            DATABASE_URL + "#host=evil.example",
            (
                f"postgresql://postgres:{DATABASE_PASSWORD}@db.{STAGING_REF}.supabase.co:"
                "5432/postgres?sslmode=require"
            ),
        )
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(database.StagingDatabaseError):
                valid_authority(value)

    def test_unsafe_database_shapes_are_rejected(self) -> None:
        unsafe_urls = (
            DATABASE_URL.replace(":5432/", ":6543/"),
            DATABASE_URL.replace("aws-1-eu-west-1.pooler.supabase.com", "db.evil.example"),
            DATABASE_URL.replace("/postgres", "/template1"),
            DATABASE_URL.replace("postgresql://", "postgres://"),
            DATABASE_URL.replace(DATABASE_PASSWORD, "raw!password"),
            DATABASE_URL.replace(f"postgres.{STAGING_REF}:", "admin:"),
        )
        for value in unsafe_urls:
            with self.subTest(value=value), self.assertRaises(database.StagingDatabaseError):
                valid_authority(value)

    def test_process_environment_cannot_override_database_target(self) -> None:
        result = database.sanitized_subprocess_environment(
            {
                "PATH": "/usr/bin",
                "PGHOST": "production.example",
                "PGHOSTADDR": "127.0.0.1",
                "PGSERVICE": "production",
                "DATABASE_URL": "production",
                "SUPABASE_DB_PASSWORD": "secret",
                "SUPABASE_ACCESS_TOKEN": "token",
                "KWABOR_STAGING_DATABASE_URL": DATABASE_URL,
            }
        )
        self.assertEqual(result["PATH"], "/usr/bin")
        for forbidden in (
            "PGHOST",
            "PGHOSTADDR",
            "PGSERVICE",
            "DATABASE_URL",
            "SUPABASE_DB_PASSWORD",
            "SUPABASE_ACCESS_TOKEN",
            "KWABOR_STAGING_DATABASE_URL",
        ):
            self.assertNotIn(forbidden, result)


class ApplyAuthorityAndCommandPolicyTest(unittest.TestCase):
    def test_apply_requires_exact_confirmation_plan_and_complete_optional_backup(self) -> None:
        valid = database.validate_operation_inputs(
            operation="apply",
            confirmation="APPLY-EXACT-STAGING-MIGRATIONS",
            backup_run_id="",
            backup_artifact_id="",
            backup_artifact_digest="",
            validated_plan_run_id="300",
            validated_plan_artifact_id="301",
            validated_plan_artifact_digest="c" * 64,
        )
        self.assertEqual(valid["planRunId"], 300)
        self.assertIsNone(valid["backup"])
        with_backup = database.validate_operation_inputs(
            operation="apply",
            confirmation="APPLY-EXACT-STAGING-MIGRATIONS",
            backup_run_id="400",
            backup_artifact_id="401",
            backup_artifact_digest="b" * 64,
            validated_plan_run_id="300",
            validated_plan_artifact_id="301",
            validated_plan_artifact_digest="c" * 64,
        )
        self.assertEqual(with_backup["backup"]["runId"], 400)
        unsafe = (
            {"confirmation": ""},
            {"confirmation": "APPLY-STAGING-MIGRATIONS"},
            {"backup_run_id": "400"},
            {"backup_artifact_id": "401"},
            {"backup_artifact_digest": "b" * 64},
            {
                "backup_run_id": "400",
                "backup_artifact_id": "401",
                "backup_artifact_digest": "B" * 64,
            },
            {"validated_plan_run_id": ""},
            {"validated_plan_artifact_id": "0"},
            {"validated_plan_artifact_digest": "C" * 64},
        )
        base = {
            "operation": "apply",
            "confirmation": "APPLY-EXACT-STAGING-MIGRATIONS",
            "backup_run_id": "",
            "backup_artifact_id": "",
            "backup_artifact_digest": "",
            "validated_plan_run_id": "300",
            "validated_plan_artifact_id": "301",
            "validated_plan_artifact_digest": "c" * 64,
        }
        for mutation in unsafe:
            values = dict(base)
            values.update(mutation)
            with self.subTest(mutation=mutation), self.assertRaises(database.StagingDatabaseError):
                database.validate_operation_inputs(**values)

    def test_non_apply_operations_reject_apply_only_inputs(self) -> None:
        for operation in ("plan", "verify"):
            self.assertIsNone(
                database.validate_operation_inputs(
                    operation=operation,
                    confirmation="",
                    backup_run_id="",
                    backup_artifact_id="",
                    backup_artifact_digest="",
                    validated_plan_run_id="",
                    validated_plan_artifact_id="",
                    validated_plan_artifact_digest="",
                )
            )
            with self.assertRaises(database.StagingDatabaseError):
                database.validate_operation_inputs(
                    operation=operation,
                    confirmation="APPLY-EXACT-STAGING-MIGRATIONS",
                    backup_run_id="",
                    backup_artifact_id="",
                    backup_artifact_digest="",
                    validated_plan_run_id="",
                    validated_plan_artifact_id="",
                    validated_plan_artifact_digest="",
                )

    def test_only_fixed_read_only_dry_run_or_exact_apply_commands_are_constructible(self) -> None:
        authority = valid_authority()
        expected_kinds = {
            "apply",
            "advisors-performance",
            "advisors-security",
            "fresh-empty",
            "lint-app-private",
            "lint-public",
            "migration-list",
            "plan",
            "remote-migrations",
        }
        for kind in expected_kinds:
            command = database.build_command(kind, authority)
            database.validate_command_policy(command, database_url=DATABASE_URL)
            rendered = " ".join(command).lower()
            for forbidden in (" --linked", " --local", "--include-seed", " db reset", " link"):
                self.assertNotIn(forbidden, rendered)
            if kind == "apply":
                self.assertEqual(
                    command,
                    ["supabase", "db", "push", "--yes", "--db-url", DATABASE_URL],
                )
            else:
                self.assertNotIn("--yes", rendered)
        with self.assertRaisesRegex(database.StagingDatabaseError, "COMMAND_FORBIDDEN"):
            database.validate_command_policy(
                ["supabase", "db", "reset", "--db-url", DATABASE_URL],
                database_url=DATABASE_URL,
            )
        with self.assertRaisesRegex(database.StagingDatabaseError, "COMMAND_FORBIDDEN"):
            database.validate_command_policy(
                ["supabase", "link", "--db-url", DATABASE_URL],
                database_url=DATABASE_URL,
            )
        with self.assertRaisesRegex(database.StagingDatabaseError, "COMMAND_FORBIDDEN"):
            database.validate_command_policy(
                ["supabase", "db", "push", "--include-seed", "--db-url", DATABASE_URL],
                database_url=DATABASE_URL,
            )

    def test_remote_history_query_is_exactly_read_only(self) -> None:
        self.assertIn("to_regclass('supabase_migrations.schema_migrations')", database.REMOTE_MIGRATION_QUERY)
        self.assertIn("query_to_xml", database.REMOTE_MIGRATION_QUERY)
        self.assertIn("where false", database.REMOTE_MIGRATION_QUERY)
        self.assertNotRegex(
            database.REMOTE_MIGRATION_QUERY,
            r"(?i)\b(insert|update|delete|alter|drop|create|grant|revoke|truncate|call|do)\b",
        )

    def test_fresh_empty_query_is_aggregate_read_only_and_covers_required_surfaces(self) -> None:
        query = database.FRESH_EMPTY_QUERY
        for fragment in (
            "('supabase_migrations','schema_migrations','migration',false)",
            "pg_catalog.pg_class",
            "pg_catalog.pg_proc",
            "pg_catalog.pg_type",
            "c.oid=t.typrelid",
            "c.relkind in ('r','p','v','m','f')",
            "as application_type_count",
            "('auth','users','auth',true)",
            "('auth','mfa_amr_claims','auth',false)",
            "('auth','saml_providers','auth',false)",
            "('auth','oauth_authorizations','auth',false)",
            "('auth','oauth_client_states','auth',false)",
            "('auth','webauthn_credentials','auth',false)",
            "('storage','objects','storage',true)",
            "('storage','buckets','storage',true)",
            "('storage','buckets_analytics','storage',false)",
            "('storage','buckets_vectors','storage',false)",
            "managed_catalog_drift",
            "n.nspname in ('public','app_private')",
        ):
            self.assertIn(fragment, query)
        self.assertNotRegex(
            query,
            r"(?i)\b(insert|update|delete|alter|drop|create|grant|revoke|truncate|call|do)\b",
        )

    def test_apply_accepts_only_fresh_empty_or_qualified_b602(self) -> None:
        self.assertTrue(database.BACKUP_PRODUCER_AVAILABLE)
        self.assertEqual(
            database.EXPECTED_BACKUP_WORKFLOW,
            ".github/workflows/closed-beta-staging-database-backup.yml",
        )
        self.assertTrue((REPOSITORY_ROOT / database.EXPECTED_BACKUP_WORKFLOW).exists())
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertIn("PREPARED_NOT_EXECUTABLE", source)
        self.assertIn('["supabase", "db", "push", "--yes"', source)
        self.assertIn("FRESH_EMPTY_DATABASE_DRIFT", source)
        self.assertIn("BACKUP_B6_02_REQUIRED", source)

    def test_apply_mutation_records_success_failure_and_timeout_without_retry(self) -> None:
        authority = valid_authority()
        cases = (
            (
                0,
                ("applied\n", ""),
                None,
                0,
                False,
            ),
            (
                1,
                ("", "push failed\n"),
                "SUPABASE_APPLY_FAILED",
                1,
                False,
            ),
            (
                None,
                [
                    subprocess.TimeoutExpired(
                        cmd=[],
                        timeout=database.APPLY_TIMEOUT_SECONDS,
                        output="partial stdout\n",
                        stderr="partial stderr\n",
                    ),
                    ("partial stdout\n", "partial stderr\n"),
                ],
                "SUPABASE_APPLY_TIMEOUT",
                124,
                True,
            ),
        )
        for returncode, communicate_result, expected_error, expected_exit, expected_timeout in cases:
            with self.subTest(expected_error=expected_error), tempfile.TemporaryDirectory() as temporary_directory:
                evidence_path = Path(temporary_directory) / "apply.txt"
                process = mock.Mock()
                process.returncode = returncode
                if isinstance(communicate_result, list):
                    process.communicate.side_effect = communicate_result
                else:
                    process.communicate.return_value = communicate_result
                with mock.patch.object(
                    database.subprocess, "Popen", return_value=process
                ) as popen:
                    attempt = database.run_apply_mutation(
                        evidence_path=evidence_path,
                        authority=authority,
                        repository_root=REPOSITORY_ROOT,
                    )

                self.assertEqual(attempt.error_code, expected_error)
                self.assertEqual(attempt.exit_code, expected_exit)
                self.assertEqual(attempt.timed_out, expected_timeout)
                self.assertEqual(popen.call_count, 1)
                self.assertEqual(
                    popen.call_args.args[0],
                    ["supabase", "db", "push", "--yes", "--db-url", DATABASE_URL],
                )
                process.communicate.assert_any_call(timeout=database.APPLY_TIMEOUT_SECONDS)
                evidence = evidence_path.read_text(encoding="utf-8")
                self.assertNotIn(DATABASE_URL, evidence)
                self.assertNotIn(DATABASE_PASSWORD, evidence)
                if expected_timeout:
                    self.assertIn("[command timed out]", evidence)

        for spawn_error, expected_error, expected_exit in (
            (FileNotFoundError(), "SUPABASE_APPLY_MISSING", 127),
            (OSError(), "SUPABASE_APPLY_SPAWN_FAILED", 126),
        ):
            with self.subTest(expected_error=expected_error), tempfile.TemporaryDirectory() as temporary_directory:
                evidence_path = Path(temporary_directory) / "apply.txt"
                with mock.patch.object(
                    database.subprocess,
                    "Popen",
                    side_effect=spawn_error,
                ):
                    attempt = database.run_apply_mutation(
                        evidence_path=evidence_path,
                        authority=authority,
                        repository_root=REPOSITORY_ROOT,
                    )
                self.assertEqual(attempt.error_code, expected_error)
                self.assertEqual(attempt.exit_code, expected_exit)
                self.assertFalse(attempt.timed_out)

    def test_apply_mutation_oserror_after_spawn_is_indeterminate(self) -> None:
        authority = valid_authority()
        process = mock.Mock()
        process.returncode = None
        process.communicate.side_effect = [OSError(), ("", "")]
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_path = Path(temporary_directory) / "apply.txt"
            with mock.patch.object(database.subprocess, "Popen", return_value=process):
                attempt = database.run_apply_mutation(
                    evidence_path=evidence_path,
                    authority=authority,
                    repository_root=REPOSITORY_ROOT,
                )
        self.assertEqual(attempt.error_code, "SUPABASE_APPLY_EXECUTION_UNCERTAIN")
        self.assertEqual(attempt.exit_code, 125)
        self.assertFalse(attempt.timed_out)
        self.assertNotIn(
            attempt.error_code,
            {"SUPABASE_APPLY_MISSING", "SUPABASE_APPLY_SPAWN_FAILED"},
        )

    def test_apply_mutation_preserves_reconciliation_when_output_is_unsafe(self) -> None:
        authority = valid_authority()
        unsafe_jwt = "eyJabcdefghij.abcdefghijk.abcdefghijkl"
        result = subprocess.CompletedProcess(
            args=[], returncode=1, stdout=unsafe_jwt, stderr=""
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_path = Path(temporary_directory) / "apply.txt"
            process = mock.Mock()
            process.returncode = result.returncode
            process.communicate.return_value = (result.stdout, result.stderr)
            with mock.patch.object(
                database.subprocess, "Popen", return_value=process
            ) as popen:
                attempt = database.run_apply_mutation(
                    evidence_path=evidence_path,
                    authority=authority,
                    repository_root=REPOSITORY_ROOT,
                )

            self.assertEqual(popen.call_count, 1)
            self.assertEqual(attempt.error_code, "SUPABASE_APPLY_FAILED")
            evidence = evidence_path.read_text(encoding="utf-8")
            self.assertIn("evidenceGuardError=JWT_IN_EVIDENCE", evidence)
            self.assertNotIn(unsafe_jwt, evidence)


class GitHubArtifactProofTest(unittest.TestCase):
    def test_supporting_run_rejects_repository_workflow_sha_event_and_status_drift(self) -> None:
        mutations = (
            {"repository": {"full_name": "fork/KWABOR", "id": 1234}},
            {"path": ".github/workflows/ci.yml@refs/heads/main"},
            {"head_sha": "b" * 40},
            {"head_branch": "develop"},
            {"event": "push"},
            {"status": "in_progress"},
            {"conclusion": "failure"},
        )
        for mutation in mutations:
            document = valid_supporting_run()
            document.update(mutation)
            with self.subTest(mutation=mutation), self.assertRaises(database.StagingDatabaseError):
                database.validate_supporting_workflow_run(
                    document,
                    expected_run_id=300,
                    expected_sha=EXPECTED_SHA,
                    expected_workflow=database.EXPECTED_DATABASE_WORKFLOW,
                )

    def test_artifact_metadata_is_bound_to_repository_run_name_sha_and_digest(self) -> None:
        digest = "a" * 64
        run = database.validate_supporting_workflow_run(
            valid_supporting_run(),
            expected_run_id=300,
            expected_sha=EXPECTED_SHA,
            expected_workflow=database.EXPECTED_DATABASE_WORKFLOW,
        )
        base = valid_artifact(
            artifact_id=301,
            run_id=300,
            run_attempt=2,
            name_prefix="kwabor-gel-g5-staging-database-plan",
            digest=digest,
            size_bytes=123,
        )
        evidence = database.validate_artifact_metadata(
            base,
            expected_artifact_id=301,
            expected_run_id=300,
            expected_repository_id=run["repositoryId"],
            expected_sha=EXPECTED_SHA,
            expected_name=f"kwabor-gel-g5-staging-database-plan-{EXPECTED_SHA}-2",
            expected_digest=digest,
        )
        self.assertFalse(evidence["expired"])
        mutations = (
            {"id": 302},
            {"name": "forged"},
            {"expired": True},
            {"expires_at": "2000-08-19T12:00:00Z"},
            {"digest": "sha256:" + "b" * 64},
            {"url": "https://api.github.com/repos/fork/KWABOR/actions/artifacts/301"},
            {"workflow_run": {**base["workflow_run"], "id": 999}},
            {"workflow_run": {**base["workflow_run"], "head_sha": "b" * 40}},
            {"workflow_run": {**base["workflow_run"], "repository_id": 999}},
        )
        for mutation in mutations:
            document = dict(base)
            document.update(mutation)
            with self.subTest(mutation=mutation), self.assertRaises(database.StagingDatabaseError):
                database.validate_artifact_metadata(
                    document,
                    expected_artifact_id=301,
                    expected_run_id=300,
                    expected_repository_id=run["repositoryId"],
                    expected_sha=EXPECTED_SHA,
                    expected_name=f"kwabor-gel-g5-staging-database-plan-{EXPECTED_SHA}-2",
                    expected_digest=digest,
                )

    def test_plan_artifact_requires_internal_succeeded_receipt_and_same_authority(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            archive, digest, manifest = create_plan_archive(root)
            artifact = valid_artifact(
                artifact_id=301,
                run_id=300,
                run_attempt=2,
                name_prefix="kwabor-gel-g5-staging-database-plan",
                digest=digest,
                size_bytes=archive.stat().st_size,
            )
            proof = database.validate_plan_artifact_bundle(
                run_document=valid_supporting_run(),
                artifact_document=artifact,
                archive_path=archive,
                plan_run_id=300,
                plan_artifact_id=301,
                plan_artifact_digest=digest,
                expected_sha=EXPECTED_SHA,
                validated_ci_run_id=100,
                target_evidence=valid_authority().public_evidence(),
                migration_manifest=manifest,
            )
            self.assertEqual(proof["pendingCount"], 2)
            self.assertEqual(proof["remoteCount"], 0)
            self.assertTrue(proof["freshEmptyEvidence"]["freshEmptyEligible"])
            self.assertRegex(proof["internalReceiptSha256"], r"^[0-9a-f]{64}$")

        mutations = (
            {"status": "failed"},
            {"expectedSha": "b" * 40},
            {"validatedCiRunId": 101},
            {"target": {"environment": "production"}},
            {"targetDigestSha256": "0" * 64},
            {"migrationManifest": {"count": 2, "manifestSha256": "0" * 64}},
            {"freshEmptyProof": None},
        )
        for mutation in mutations:
            with tempfile.TemporaryDirectory() as temporary_directory:
                root = Path(temporary_directory)
                archive, digest, manifest = create_plan_archive(
                    root, receipt_mutation=mutation
                )
                artifact = valid_artifact(
                    artifact_id=301,
                    run_id=300,
                    run_attempt=2,
                    name_prefix="kwabor-gel-g5-staging-database-plan",
                    digest=digest,
                    size_bytes=archive.stat().st_size,
                )
                with self.subTest(mutation=mutation), self.assertRaises(
                    database.StagingDatabaseError
                ):
                    database.validate_plan_artifact_bundle(
                        run_document=valid_supporting_run(),
                        artifact_document=artifact,
                        archive_path=archive,
                        plan_run_id=300,
                        plan_artifact_id=301,
                        plan_artifact_digest=digest,
                        expected_sha=EXPECTED_SHA,
                        validated_ci_run_id=100,
                        target_evidence=valid_authority().public_evidence(),
                        migration_manifest=manifest,
                    )

    def test_plan_archive_bytes_must_match_the_github_artifact_digest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            archive, _, manifest = create_plan_archive(root)
            forged_digest = "f" * 64
            artifact = valid_artifact(
                artifact_id=301,
                run_id=300,
                run_attempt=2,
                name_prefix="kwabor-gel-g5-staging-database-plan",
                digest=forged_digest,
                size_bytes=archive.stat().st_size,
            )
            with self.assertRaisesRegex(
                database.StagingDatabaseError, "ARTIFACT_ARCHIVE_DIGEST_DRIFT"
            ):
                database.validate_plan_artifact_bundle(
                    run_document=valid_supporting_run(),
                    artifact_document=artifact,
                    archive_path=archive,
                    plan_run_id=300,
                    plan_artifact_id=301,
                    plan_artifact_digest=forged_digest,
                    expected_sha=EXPECTED_SHA,
                    validated_ci_run_id=100,
                    target_evidence=valid_authority().public_evidence(),
                    migration_manifest=manifest,
                )

    def test_plan_artifact_without_fresh_empty_proof_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            archive, _, manifest = create_plan_archive(root)
            missing = root / "missing-fresh-proof.zip"
            with zipfile.ZipFile(archive, "r") as source, zipfile.ZipFile(
                missing, "w", compression=zipfile.ZIP_DEFLATED
            ) as destination:
                for name in source.namelist():
                    if name != "PLAN-FRESH-EMPTY-CHECK.json":
                        destination.writestr(name, source.read(name))
            digest = hashlib.sha256(missing.read_bytes()).hexdigest()
            artifact = valid_artifact(
                artifact_id=301,
                run_id=300,
                run_attempt=2,
                name_prefix="kwabor-gel-g5-staging-database-plan",
                digest=digest,
                size_bytes=missing.stat().st_size,
            )
            with self.assertRaisesRegex(
                database.StagingDatabaseError, "ARTIFACT_RECEIPT_MISSING"
            ):
                database.validate_plan_artifact_bundle(
                    run_document=valid_supporting_run(),
                    artifact_document=artifact,
                    archive_path=missing,
                    plan_run_id=300,
                    plan_artifact_id=301,
                    plan_artifact_digest=digest,
                    expected_sha=EXPECTED_SHA,
                    validated_ci_run_id=100,
                    target_evidence=valid_authority().public_evidence(),
                    migration_manifest=manifest,
                )

    def test_backup_bundle_contract_checks_internal_sha_target_and_restorable_receipt(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            archive, digest = create_backup_archive(root)
            artifact = valid_artifact(
                artifact_id=401,
                run_id=400,
                run_attempt=1,
                name_prefix="kwabor-gel-g5-staging-database-backup",
                digest=digest,
                size_bytes=archive.stat().st_size,
            )
            proof = database.validate_backup_artifact_bundle(
                run_document=valid_supporting_run(
                    run_id=400,
                    workflow=database.EXPECTED_BACKUP_WORKFLOW,
                    run_attempt=1,
                ),
                artifact_document=artifact,
                archive_path=archive,
                backup_run_id=400,
                backup_artifact_id=401,
                backup_artifact_digest=digest,
                expected_sha=EXPECTED_SHA,
                validated_ci_run_id=100,
                target_evidence=valid_authority().public_evidence(),
                now=datetime(2026, 8, 20, 12, 5, tzinfo=timezone.utc),
            )
            self.assertTrue(proof["restorable"])

        for mutation in (
            {"expectedSha": "b" * 40},
            {"target": {"environment": "production"}},
            {"targetDigestSha256": "0" * 64},
            {"restorable": False},
            {"schemaVersion": 1},
            {"workflowPath": ".github/workflows/ci.yml"},
            {"databaseScope": {}},
            {"snapshot": {}},
            {
                "snapshot": {
                    "applicationDumpAndManagedProofShareSnapshot": True,
                    "exportedByDedicatedSession": True,
                    "identifierSha256": "a" * 64,
                    "isolation": "repeatable-read-read-only",
                    "mechanism": "pg-export-snapshot",
                    "snapshotEstablishedAt": "2000-01-01T00:00:00Z",
                }
            },
            {"source": {}},
            {"restore": {}},
            {"encryption": {}},
            {"ageEscrow": {}},
            {"rpo": {}},
            {"rto": {}},
            {"artifactPolicy": {}},
        ):
            with tempfile.TemporaryDirectory() as temporary_directory:
                root = Path(temporary_directory)
                archive, digest = create_backup_archive(root, receipt_mutation=mutation)
                artifact = valid_artifact(
                    artifact_id=401,
                    run_id=400,
                    run_attempt=1,
                    name_prefix="kwabor-gel-g5-staging-database-backup",
                    digest=digest,
                    size_bytes=archive.stat().st_size,
                )
                with self.subTest(mutation=mutation), self.assertRaises(
                    database.StagingDatabaseError
                ):
                    database.validate_backup_artifact_bundle(
                        run_document=valid_supporting_run(
                            run_id=400,
                            workflow=database.EXPECTED_BACKUP_WORKFLOW,
                            run_attempt=1,
                        ),
                        artifact_document=artifact,
                        archive_path=archive,
                        backup_run_id=400,
                        backup_artifact_id=401,
                        backup_artifact_digest=digest,
                        expected_sha=EXPECTED_SHA,
                        validated_ci_run_id=100,
                        target_evidence=valid_authority().public_evidence(),
                        now=datetime(2026, 8, 20, 12, 5, tzinfo=timezone.utc),
                    )

    def test_backup_bundle_recomputes_fingerprint_and_binds_ciphertext_name(self) -> None:
        cases = (
            (
                {"database_fingerprint_override": "d" * 64},
                "BACKUP_RECEIPT_SOURCE_FINGERPRINT_INVALID",
            ),
            (
                {"ciphertext_project_prefix_override": "0" * 16},
                "BACKUP_RECEIPT_CIPHERTEXT_NAME_INVALID",
            ),
        )
        for archive_arguments, expected_error in cases:
            with (
                self.subTest(expected_error=expected_error),
                tempfile.TemporaryDirectory() as temporary_directory,
            ):
                root = Path(temporary_directory)
                archive, digest = create_backup_archive(root, **archive_arguments)
                artifact = valid_artifact(
                    artifact_id=401,
                    run_id=400,
                    run_attempt=1,
                    name_prefix="kwabor-gel-g5-staging-database-backup",
                    digest=digest,
                    size_bytes=archive.stat().st_size,
                )
                with self.assertRaisesRegex(
                    database.StagingDatabaseError,
                    expected_error,
                ):
                    database.validate_backup_artifact_bundle(
                        run_document=valid_supporting_run(
                            run_id=400,
                            workflow=database.EXPECTED_BACKUP_WORKFLOW,
                            run_attempt=1,
                        ),
                        artifact_document=artifact,
                        archive_path=archive,
                        backup_run_id=400,
                        backup_artifact_id=401,
                        backup_artifact_digest=digest,
                        expected_sha=EXPECTED_SHA,
                        validated_ci_run_id=100,
                        target_evidence=valid_authority().public_evidence(),
                        now=datetime(2026, 8, 20, 12, 5, tzinfo=timezone.utc),
                    )

    def test_backup_managed_data_proof_requires_the_exact_catalog_and_sane_counts(self) -> None:
        def valid_proof() -> dict[str, object]:
            managed_catalog = [
                {"schema": schema, "table": table}
                for schema, table in database.EXPECTED_MANAGED_SCHEMA_TABLES
            ]
            return {
                "constraintCount": 12,
                "constraintInventorySha256": "c" * 64,
                "foreignKeyCount": 4,
                "managedDataEmpty": True,
                "managedSchemaTableCount": len(managed_catalog),
                "managedSchemaTableSha256": database.sha256_bytes(
                    database.canonical_json_bytes(managed_catalog)
                ),
                "managedTables": [
                    {
                        "exists": True,
                        "required": required,
                        "rowCount": 0,
                        "schema": schema,
                        "table": table,
                    }
                    for schema, table, required in database.MANAGED_DATA_TABLES
                ],
                "migrationVersions": ["20260820083427", "20260820084207"],
                "postgresMajor": 17,
                "schemaVersion": 2,
                "unvalidatedConstraintCount": 0,
            }

        self.assertTrue(database._validate_backup_managed_data_proof(valid_proof()))
        missing = valid_proof()
        missing["managedTables"] = missing["managedTables"][:-1]
        unknown = valid_proof()
        unknown["managedTables"][0] = {
            "exists": True,
            "required": False,
            "rowCount": 0,
            "schema": "auth",
            "table": "unexpected_table",
        }
        missing_optional = valid_proof()
        optional = next(
            table for table in missing_optional["managedTables"] if not table["required"]
        )
        optional["exists"] = False
        optional["rowCount"] = None
        impossible_counts = valid_proof()
        impossible_counts["foreignKeyCount"] = 13
        for document in (missing, unknown, missing_optional, impossible_counts):
            with self.subTest(document=document), self.assertRaises(
                database.StagingDatabaseError
            ):
                database._validate_backup_managed_data_proof(document)


class FreshEmptyAndReconciliationTest(unittest.TestCase):
    def test_backup_scope_accepts_only_byte_reviewed_pending_migrations(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            migrations = root / "supabase" / "migrations"
            migrations.mkdir(parents=True)
            reviewed = migrations / "20260821012638_restrict_team_table_and_default_privileges.sql"
            reviewed.write_bytes(
                (
                    REPOSITORY_ROOT
                    / "supabase"
                    / "migrations"
                    / reviewed.name
                ).read_bytes()
            )
            evidence = database.validate_pending_migration_scope(root, ["20260821012638"])
            self.assertEqual(
                evidence["policy"],
                "reviewed-pending-migration-sha-allowlist-v2",
            )

            bypasses = (
                "alter table only auth.users add column unsafe text;\n",
                "set search_path=auth; delete from users;\n",
                "copy auth.users from stdin;\n",
                "alter default privileges in schema auth grant all on tables to anon;\n",
                "do $$ begin execute 'delete from ' || 'auth.users'; end $$;\n",
            )
            for source in bypasses:
                reviewed.write_text(source, encoding="utf-8")
                with self.subTest(source=source), self.assertRaisesRegex(
                    database.StagingDatabaseError,
                    "BACKUP_PENDING_MIGRATION_NOT_REVIEWED_FOR_EXCLUDED_SCHEMAS",
                ):
                    database.validate_pending_migration_scope(root, ["20260821012638"])

    def test_fresh_empty_csv_requires_one_exact_non_negative_aggregate_row(self) -> None:
        header = ",".join(database.FRESH_EMPTY_CSV_COLUMNS)
        parsed = database.parse_fresh_empty_counts(
            "informational line\n" + header + "\n1,0,3,0,0,0,0,0,0,0,0,0\n"
        )
        self.assertEqual(parsed, virgin_fresh_counts())
        invalid_outputs = (
            "",
            header + "\n1,0,3,0,0,0,0,0,0,0,0\n",
            header + "\n1,0,3,0,-1,0,0,0,0,0,0,0\n",
            header + "\n1,0,3,0,0,0,0,0,0,0,0,0\n1,0,3,0,0,0,0,0,0,0,0,0\n",
            header.replace("auth_user_count", "email")
            + "\n1,0,3,0,0,0,0,0,0,0,0,0\n",
        )
        for output in invalid_outputs:
            with self.subTest(output=output), self.assertRaises(database.StagingDatabaseError):
                database.parse_fresh_empty_counts(output)

    def test_fresh_empty_eligibility_requires_all_simultaneous_zero_proofs(self) -> None:
        eligible = database.build_fresh_empty_evidence(virgin_fresh_counts())
        self.assertTrue(eligible["freshEmptyEligible"])
        self.assertIsNone(eligible["backupRequiredTaskId"])
        for key in (
            "applicationMigrationCount",
            "applicationRelationCount",
            "applicationRoutineCount",
            "applicationTypeCount",
            "managedSchemaTableDriftCount",
            "authRelevantRowCount",
            "authUserCount",
            "storageBucketCount",
            "storageObjectCount",
            "storageRelevantRowCount",
        ):
            blocked = database.build_fresh_empty_evidence(
                virgin_fresh_counts(**{key: 1})
            )
            with self.subTest(key=key):
                self.assertFalse(blocked["freshEmptyEligible"])
                self.assertEqual(blocked["backupRequiredTaskId"], "B6.02")
        for mutation in (
            {"publicSchemaCount": 0},
            {"publicSchemaCount": 2},
            {"requiredSystemTableCount": 2},
            {"requiredSystemTableCount": 4},
        ):
            blocked = database.build_fresh_empty_evidence(
                virgin_fresh_counts(**mutation)
            )
            self.assertFalse(blocked["freshEmptyEligible"])

    def test_create_type_enum_forces_prepared_not_executable_receipt(self) -> None:
        enum_ddl = "CREATE TYPE public.demo_status AS ENUM ('draft', 'published');"
        self.assertRegex(
            enum_ddl,
            r"^CREATE TYPE public\.[a-z_]+ AS ENUM \('[a-z]+', '[a-z]+'\);$",
        )
        query = database.FRESH_EMPTY_QUERY
        self.assertIn("from pg_catalog.pg_type t", query)
        self.assertIn("c.oid=t.typrelid", query)
        self.assertIn("c.relkind in ('r','p','v','m','f')", query)
        self.assertNotIn("t.typtype in", query.lower())

        enum_catalog_evidence = database.build_fresh_empty_evidence(
            virgin_fresh_counts(applicationTypeCount=1)
        )
        self.assertFalse(enum_catalog_evidence["freshEmptyEligible"])
        self.assertEqual(enum_catalog_evidence["backupRequiredTaskId"], "B6.02")
        self.assertEqual(enum_catalog_evidence["schemaVersion"], 2)

        authority = valid_authority()
        request = {
            "actor": "release-owner",
            "expectedSha": EXPECTED_SHA,
            "runAttempt": 1,
            "runId": 200,
            "runUrl": "https://github.com/urbainmorel/KWABOR/actions/runs/200",
            "validatedCiRunId": 100,
        }
        ci = database.validate_ci_run(
            valid_ci_run(), expected_run_id="100", expected_sha=EXPECTED_SHA
        )
        migration_state = database.migration_state_evidence(
            local_versions=["20260703004103"], remote_versions=[]
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_directory = Path(temporary_directory)
            with self.assertRaisesRegex(
                database.StagingDatabaseError, "APPLY_PREPARED_NOT_EXECUTABLE"
            ):
                database.write_apply_prepared_not_executable(
                    evidence_directory=evidence_directory,
                    reason_code="FRESH_EMPTY_DATABASE_DRIFT",
                    request_evidence=request,
                    ci_evidence=ci,
                    target_evidence=authority.public_evidence(),
                    migration_manifest={"count": 1, "manifestSha256": "d" * 64},
                    plan_evidence=None,
                    migration_state=migration_state,
                    fresh_empty_evidence=enum_catalog_evidence,
                    secret_values=authority.secret_values,
                )

            receipt_path = evidence_directory / database.GEL_FILENAME
            receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
            self.assertEqual(receipt["status"], "prepared_not_executable")
            self.assertEqual(receipt["executionDisposition"], "PREPARED_NOT_EXECUTABLE")
            self.assertEqual(receipt["retryDisposition"], "BACKUP_B6_02_REQUIRED")
            self.assertEqual(
                receipt["freshEmptyProof"]["counts"]["applicationTypeCount"], 1
            )
            expected_hash = hashlib.sha256(receipt_path.read_bytes()).hexdigest()
            self.assertEqual(
                (evidence_directory / database.GEL_HASH_FILENAME).read_text(
                    encoding="utf-8"
                ),
                f"{expected_hash}  {database.GEL_FILENAME}\n",
            )

    def test_fresh_empty_evidence_rejects_digest_policy_and_boolean_tampering(self) -> None:
        base = database.build_fresh_empty_evidence(virgin_fresh_counts())
        self.assertEqual(database.validate_fresh_empty_evidence(base), base)
        for mutation in (
            {"countsSha256": "0" * 64},
            {"proofPolicy": "allowlist-latest"},
            {"proofPolicy": "zero-objects-public-app-private-v1"},
            {"freshEmptyEligible": False},
            {"backupRequiredTaskId": "B6.02"},
            {"applicationSchemas": ["public"]},
        ):
            document = dict(base)
            document.update(mutation)
            with self.subTest(mutation=mutation), self.assertRaises(
                database.StagingDatabaseError
            ):
                database.validate_fresh_empty_evidence(document)

    def test_fresh_proof_and_remote_history_count_must_match(self) -> None:
        fresh = database.build_fresh_empty_evidence(virgin_fresh_counts())
        state = database.migration_state_evidence(
            local_versions=["20260703004103"], remote_versions=[]
        )
        database.require_fresh_history_consistency(fresh, state, code="TEST_DRIFT")
        drifted = database.migration_state_evidence(
            local_versions=["20260703004103"], remote_versions=["20260703004103"]
        )
        with self.assertRaisesRegex(database.StagingDatabaseError, "TEST_DRIFT"):
            database.require_fresh_history_consistency(fresh, drifted, code="TEST_DRIFT")

    def test_reconciliation_none_full_partial_and_unknown_are_fail_closed(self) -> None:
        local = ["20260703004103", "20260703093622"]
        fresh = database.build_fresh_empty_evidence(virgin_fresh_counts())
        none = database.classify_apply_reconciliation(
            local_versions=local,
            migration_state=database.migration_state_evidence(
                local_versions=local, remote_versions=[]
            ),
            fresh_empty=fresh,
        )
        self.assertEqual(none["outcome"], "failed_safe")
        self.assertEqual(none["mutationState"], "not_committed")
        self.assertEqual(none["retryDisposition"], "NEW_PLAN_AND_APPROVAL_REQUIRED")

        full = database.classify_apply_reconciliation(
            local_versions=local,
            migration_state=database.migration_state_evidence(
                local_versions=local, remote_versions=local
            ),
            fresh_empty=None,
        )
        self.assertEqual(full["outcome"], "success_recovered")
        self.assertEqual(full["mutationState"], "committed")

        partial = database.classify_apply_reconciliation(
            local_versions=local,
            migration_state=database.migration_state_evidence(
                local_versions=local, remote_versions=local[:1]
            ),
            fresh_empty=None,
        )
        populated_baseline = database.migration_state_evidence(
            local_versions=local,
            remote_versions=local[:1],
        )
        pre_mutation_failure = database.classify_apply_reconciliation(
            local_versions=local,
            migration_state=populated_baseline,
            fresh_empty=None,
            pre_apply_migration_state=populated_baseline,
            mutation_proven_impossible=True,
        )
        unproven_failure = database.classify_apply_reconciliation(
            local_versions=local,
            migration_state=populated_baseline,
            fresh_empty=None,
            pre_apply_migration_state=populated_baseline,
            mutation_proven_impossible=False,
        )
        self.assertEqual(pre_mutation_failure["outcome"], "failed_safe")
        self.assertEqual(
            pre_mutation_failure["classification"],
            "none_applied_pre_mutation_failure",
        )
        self.assertEqual(unproven_failure["outcome"], "indeterminate")
        unknown = database.classify_apply_reconciliation(
            local_versions=local,
            migration_state=None,
            fresh_empty=None,
        )
        dirty_without_history = database.classify_apply_reconciliation(
            local_versions=local,
            migration_state=database.migration_state_evidence(
                local_versions=local, remote_versions=[]
            ),
            fresh_empty=database.build_fresh_empty_evidence(
                virgin_fresh_counts(applicationRelationCount=1)
            ),
        )
        for evidence in (partial, unknown, dirty_without_history):
            self.assertEqual(evidence["outcome"], "indeterminate")
            self.assertEqual(evidence["mutationState"], "indeterminate")
            self.assertEqual(evidence["retryDisposition"], "DO_NOT_RETRY")


class PendingAndReceiptTest(unittest.TestCase):
    def test_remote_migration_history_must_be_strict_sorted_csv(self) -> None:
        self.assertEqual(
            database.parse_remote_migration_versions(
                "version\n20260703004103\n20260703093622\n"
            ),
            ["20260703004103", "20260703093622"],
        )
        for invalid in (
            "[]",
            "version\nnot-a-version\n",
            "version\n20260703093622\n20260703004103\n",
            "version\n20260703004103\n20260703004103\n",
        ):
            with self.subTest(invalid=invalid), self.assertRaises(database.StagingDatabaseError):
                database.parse_remote_migration_versions(invalid)

    def test_plan_remote_history_must_be_an_exact_local_prefix_with_pending_hash(self) -> None:
        local = ["20260703004103", "20260703093622", "20260704100000"]
        state = database.migration_state_evidence(
            local_versions=local,
            remote_versions=local[:1],
        )
        self.assertTrue(state["remoteIsExactLocalPrefix"])
        self.assertEqual(state["pendingCount"], 2)
        self.assertEqual(state["pendingVersions"], local[1:])
        self.assertEqual(
            state["pendingVersionsSha256"],
            hashlib.sha256(("\n".join(local[1:]) + "\n").encode()).hexdigest(),
        )
        divergent = database.migration_state_evidence(
            local_versions=local,
            remote_versions=[local[1]],
        )
        self.assertFalse(divergent["remoteIsExactLocalPrefix"])
        self.assertIsNone(divergent["pendingCount"])
        self.assertEqual(divergent["pendingVersions"], [])

    def test_gel_receipt_is_hashed_bound_and_contains_only_safe_fields(self) -> None:
        authority = valid_authority()
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_directory = Path(temporary_directory)
            (evidence_directory / "PLAN-DRY-RUN.txt").write_text(
                "exitCode=0\n[stdout]\nDry run complete\n[stderr]\n(empty)\n",
                encoding="utf-8",
            )
            request = {
                "actor": "release-owner",
                "expectedSha": EXPECTED_SHA,
                "runAttempt": 2,
                "runId": 200,
                "runUrl": "https://github.com/urbainmorel/KWABOR/actions/runs/200",
                "validatedCiRunId": 100,
            }
            ci = database.validate_ci_run(
                valid_ci_run(), expected_run_id="100", expected_sha=EXPECTED_SHA
            )
            manifest = {"count": 19, "manifestSha256": "d" * 64}
            receipt = database.write_gel_receipt(
                evidence_directory=evidence_directory,
                operation="plan",
                status="succeeded",
                request_evidence=request,
                ci_evidence=ci,
                target_evidence=authority.public_evidence(),
                migration_manifest=manifest,
                backup_evidence=None,
                secret_values=authority.secret_values,
            )
            self.assertEqual(receipt["gate"], "G5")
            self.assertEqual(receipt["contributesTo"], "G5")
            self.assertEqual(receipt["taskId"], "B6.01.database-migrations")
            self.assertFalse(receipt["gateClosed"])
            self.assertEqual(receipt["expectedSha"], EXPECTED_SHA)
            self.assertEqual(receipt["validatedCiRunId"], 100)
            self.assertEqual(receipt["runAttempt"], 2)
            self.assertEqual(receipt["executionDisposition"], "EXECUTED")
            self.assertEqual(receipt["mutationState"], "not_started")
            receipt_path = evidence_directory / database.GEL_FILENAME
            digest_line = (evidence_directory / database.GEL_HASH_FILENAME).read_text(
                encoding="utf-8"
            )
            expected_digest = hashlib.sha256(receipt_path.read_bytes()).hexdigest()
            self.assertEqual(digest_line, f"{expected_digest}  {database.GEL_FILENAME}\n")
            combined = "\n".join(
                path.read_text(encoding="utf-8") for path in evidence_directory.iterdir()
            )
            self.assertNotIn(DATABASE_URL, combined)
            self.assertNotIn(urllib_unquoted_password(), combined)

    def test_preflight_failure_always_creates_non_sensitive_hashed_receipt(self) -> None:
        parser = database.build_parser()
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_directory = Path(temporary_directory)
            args = parser.parse_args(
                [
                    "execute",
                    "--operation",
                    "plan",
                    "--expected-sha",
                    "not-a-sha",
                    "--validated-ci-run-id",
                    "not-an-id",
                    "--ci-run-json",
                    "unused",
                    "--environment-json",
                    "unused",
                    "--plan-run-json",
                    "unused",
                    "--plan-artifact-json",
                    "unused",
                    "--plan-artifact-zip",
                    "unused",
                    "--evidence-directory",
                    str(evidence_directory),
                ]
            )
            database.write_preflight_failure_receipt(
                evidence_directory=evidence_directory,
                args=args,
                error_code="EXPECTED_SHA_INVALID",
            )
            receipt_path = evidence_directory / database.GEL_FILENAME
            receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
            self.assertEqual(receipt["status"], "failed")
            self.assertEqual(receipt["executionDisposition"], "REJECTED_PREFLIGHT")
            self.assertEqual(receipt["mutationState"], "not_started")
            self.assertIsNone(receipt["expectedSha"])
            sidecar = (evidence_directory / database.GEL_HASH_FILENAME).read_text(
                encoding="utf-8"
            )
            self.assertEqual(
                sidecar,
                f"{hashlib.sha256(receipt_path.read_bytes()).hexdigest()}  "
                f"{database.GEL_FILENAME}\n",
            )

    def test_indeterminate_apply_receipt_requires_do_not_retry_contract(self) -> None:
        authority = valid_authority()
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_directory = Path(temporary_directory)
            (evidence_directory / "APPLY-PREPARATION.json").write_text(
                '{"executionDisposition":"PREPARED_NOT_EXECUTABLE"}\n',
                encoding="utf-8",
            )
            request = {
                "actor": "release-owner",
                "expectedSha": EXPECTED_SHA,
                "runAttempt": 1,
                "runId": 200,
                "runUrl": "https://github.com/urbainmorel/KWABOR/actions/runs/200",
                "validatedCiRunId": 100,
            }
            with self.assertRaisesRegex(
                database.StagingDatabaseError, "INDETERMINATE_RECEIPT_INVALID"
            ):
                database.write_gel_receipt(
                    evidence_directory=evidence_directory,
                    operation="apply",
                    status="failed",
                    request_evidence=request,
                    ci_evidence=database.validate_ci_run(
                        valid_ci_run(), expected_run_id="100", expected_sha=EXPECTED_SHA
                    ),
                    target_evidence=authority.public_evidence(),
                    migration_manifest={"count": 2, "manifestSha256": "d" * 64},
                    backup_evidence=None,
                    mutation_state="indeterminate",
                    execution_disposition="INDETERMINATE",
                    retry_disposition="DO_NOT_RETRY",
                    error_code="SUPABASE_APPLY_TIMEOUT",
                    secret_values=authority.secret_values,
                )

        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_directory = Path(temporary_directory)
            (evidence_directory / "APPLY-RECONCILIATION.json").write_text(
                '{"outcome":"indeterminate"}\n', encoding="utf-8"
            )
            request = {
                "actor": "release-owner",
                "expectedSha": EXPECTED_SHA,
                "runAttempt": 1,
                "runId": 200,
                "runUrl": "https://github.com/urbainmorel/KWABOR/actions/runs/200",
                "validatedCiRunId": 100,
            }
            receipt = database.write_gel_receipt(
                evidence_directory=evidence_directory,
                operation="apply",
                status="indeterminate",
                request_evidence=request,
                ci_evidence=database.validate_ci_run(
                    valid_ci_run(), expected_run_id="100", expected_sha=EXPECTED_SHA
                ),
                target_evidence=authority.public_evidence(),
                migration_manifest={"count": 2, "manifestSha256": "d" * 64},
                backup_evidence=None,
                reconciliation_evidence={"outcome": "indeterminate"},
                mutation_state="indeterminate",
                execution_disposition="INDETERMINATE",
                retry_disposition="DO_NOT_RETRY",
                error_code="SUPABASE_APPLY_TIMEOUT",
                secret_values=authority.secret_values,
            )
            self.assertEqual(receipt["status"], "indeterminate")
            self.assertEqual(receipt["retryDisposition"], "DO_NOT_RETRY")

    def test_pre_mutation_failure_receipt_is_explicitly_not_executed(self) -> None:
        authority = valid_authority()
        reconciliation = {
            "classification": "none_applied_pre_mutation_failure",
            "outcome": "failed_safe",
            "mutationState": "not_committed",
            "retryDisposition": "NEW_PLAN_AND_APPROVAL_REQUIRED",
        }
        request = {
            "actor": "release-owner",
            "expectedSha": EXPECTED_SHA,
            "runAttempt": 1,
            "runId": 200,
            "runUrl": "https://github.com/urbainmorel/KWABOR/actions/runs/200",
            "validatedCiRunId": 100,
        }
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_directory = Path(temporary_directory)
            (evidence_directory / "APPLY-RECONCILIATION.json").write_text(
                json.dumps(reconciliation) + "\n",
                encoding="utf-8",
            )
            receipt = database.write_gel_receipt(
                evidence_directory=evidence_directory,
                operation="apply",
                status="failed",
                request_evidence=request,
                ci_evidence=database.validate_ci_run(
                    valid_ci_run(), expected_run_id="100", expected_sha=EXPECTED_SHA
                ),
                target_evidence=authority.public_evidence(),
                migration_manifest={"count": 2, "manifestSha256": "d" * 64},
                backup_evidence=None,
                reconciliation_evidence=reconciliation,
                mutation_state="not_committed",
                execution_disposition="EXECUTION_NOT_STARTED",
                retry_disposition="NEW_PLAN_AND_APPROVAL_REQUIRED",
                error_code="SUPABASE_APPLY_MISSING",
                secret_values=authority.secret_values,
            )
            self.assertEqual(receipt["executionDisposition"], "EXECUTION_NOT_STARTED")

        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_directory = Path(temporary_directory)
            (evidence_directory / "APPLY-RECONCILIATION.json").write_text(
                json.dumps(reconciliation) + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                database.StagingDatabaseError,
                "NOT_COMMITTED_RECEIPT_INVALID",
            ):
                database.write_gel_receipt(
                    evidence_directory=evidence_directory,
                    operation="apply",
                    status="failed",
                    request_evidence=request,
                    ci_evidence=database.validate_ci_run(
                        valid_ci_run(), expected_run_id="100", expected_sha=EXPECTED_SHA
                    ),
                    target_evidence=authority.public_evidence(),
                    migration_manifest={"count": 2, "manifestSha256": "d" * 64},
                    backup_evidence=None,
                    reconciliation_evidence=reconciliation,
                    mutation_state="not_committed",
                    execution_disposition="EXECUTED",
                    retry_disposition="NEW_PLAN_AND_APPROVAL_REQUIRED",
                    error_code="SUPABASE_APPLY_MISSING",
                    secret_values=authority.secret_values,
                )

    def test_sensitive_receipt_fields_and_values_are_rejected(self) -> None:
        for document in (
            {"databaseUrl": DATABASE_URL},
            {"password": "value"},
            {"nested": {"serviceRoleKey": "value"}},
            {"safe": DATABASE_URL},
            {"safe": "eyJabcdefghij.abcdefghijk.abcdefghijkl"},
        ):
            with self.subTest(document=document), self.assertRaises(database.StagingDatabaseError):
                database.assert_safe_document(document)

    def test_cli_output_is_expurgated_before_evidence_is_written(self) -> None:
        authority = valid_authority()
        result = subprocess.CompletedProcess(
            args=[],
            returncode=1,
            stdout=f"Connecting with {DATABASE_URL}\n",
            stderr=(
                f"password={urllib_unquoted_password()} "
                "postgresql://other:other%21password@db.example:5432/postgres\n"
            ),
        )
        rendered = database.render_process_output(result, authority)
        self.assertNotIn(DATABASE_URL, rendered)
        self.assertNotIn(urllib_unquoted_password(), rendered)
        self.assertNotIn("postgresql://", rendered)
        self.assertIn("[REDACTED]", rendered)


def urllib_unquoted_password() -> str:
    return "safe@database!password"


class WorkflowStaticPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        cls.runner = MODULE_PATH.read_text(encoding="utf-8")

    def test_workflow_is_manual_main_staging_and_constant_concurrency(self) -> None:
        self.assertIn("workflow_dispatch:", self.workflow)
        self.assertNotRegex(self.workflow, r"(?m)^\s{2}(push|pull_request|schedule):")
        self.assertIn("environment: staging", self.workflow)
        self.assertIn("group: closed-beta-demo-staging-operations", self.workflow)
        self.assertNotRegex(
            self.workflow,
            r"group:\s*closed-beta-demo-staging-operations.*\$\{\{",
        )
        self.assertNotIn("environment: production", self.workflow)

    def test_exact_sha_ci_and_environment_guards_are_present(self) -> None:
        required = (
            "expected_sha:",
            "validated_ci_run_id:",
            'EXPECTED_GITHUB_REF = "refs/heads/main"',
            'require(github_sha == expected_sha, "DISPATCH_SHA_DRIFT")',
            "/environments/staging",
            "KWABOR_STAGING_PROJECT_REF_SHA256",
            "KWABOR_PRODUCTION_SUPABASE_PROJECT_REF",
            "APPLY-EXACT-STAGING-MIGRATIONS",
            "secrets.KWABOR_STAGING_DATABASE_URL",
        )
        for fragment in required:
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, self.workflow + self.runner)

    def test_apply_inputs_plan_download_and_fresh_empty_mutation_gate_are_explicit(self) -> None:
        for fragment in (
            "backup_run_id:",
            "backup_artifact_id:",
            "backup_artifact_digest:",
            "validated_plan_run_id:",
            "validated_plan_artifact_id:",
            "validated_plan_artifact_digest:",
            'fetch_id_json "runs" "$VALIDATED_PLAN_RUN_ID"',
            'fetch_id_json "artifacts" "$VALIDATED_PLAN_ARTIFACT_ID"',
            "/actions/artifacts/$VALIDATED_PLAN_ARTIFACT_ID/zip",
            "X-GitHub-Api-Version: 2022-11-28",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, self.workflow)
        self.assertIn("BACKUP_PRODUCER_AVAILABLE = True", self.runner)
        self.assertIn("PREPARED_NOT_EXECUTABLE", self.runner)
        self.assertIn("$BACKUP_ARTIFACT_ID/zip", self.workflow)
        self.assertIn('fetch_id_json "runs" "$BACKUP_RUN_ID"', self.workflow)
        self.assertIn('fetch_id_json "artifacts" "$BACKUP_ARTIFACT_ID"', self.workflow)
        self.assertIn('["supabase", "db", "push", "--yes"', self.runner)
        self.assertIn('prefix="PRE-APPLY"', self.runner)
        self.assertIn('f"{prefix}-FRESH-EMPTY-CHECK.json"', self.runner)
        self.assertIn("APPLY-RECONCILIATION.json", self.runner)
        self.assertIn("APPLY_INDETERMINATE_DO_NOT_RETRY", self.runner)

    def test_database_url_cannot_be_supplied_as_dispatch_input_or_logged(self) -> None:
        dispatch_prefix = self.workflow.split("permissions:", maxsplit=1)[0].lower()
        self.assertNotIn("database_url", dispatch_prefix)
        self.assertNotIn("database-url", dispatch_prefix)
        self.assertNotRegex(self.workflow, r"(?m)^\s*echo\s+.*STAGING_DATABASE_URL")

    def test_no_destructive_or_linked_supabase_command_is_embedded(self) -> None:
        self.assertNotRegex(
            self.workflow,
            r"(?i)supabase\s+(?:link|unlink|seed|db\s+reset|migration\s+repair)",
        )
        self.assertNotIn("--include-seed", self.workflow)
        self.assertNotIn("--linked", self.workflow)

    def test_gel_upload_and_every_external_action_are_commit_pinned(self) -> None:
        self.assertIn("GEL evidence", self.workflow)
        self.assertIn("retention-days: 90", self.workflow)
        self.assertIn("if-no-files-found: error", self.workflow)
        self.assertIn("if: always()", self.workflow)
        self.assertEqual(self.workflow.count("version: 2.111.0"), 1)
        self.assertEqual(database.EXPECTED_SUPABASE_CLI_VERSION, "2.111.0")
        uses = re.findall(r"(?m)^\s*-?\s*uses:\s*([^\s#]+)", self.workflow)
        self.assertEqual(len(uses), 3)
        for action in uses:
            with self.subTest(action=action):
                self.assertRegex(action, r"^[^@]+@[0-9a-f]{40}$")


if __name__ == "__main__":
    unittest.main()
