from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import sys
import tempfile
import unittest
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPOSITORY_ROOT / "tools" / "closed-beta-staging-database-backup.py"
WORKFLOW_PATH = (
    REPOSITORY_ROOT / ".github" / "workflows" / "closed-beta-staging-database-backup.yml"
)
spec = importlib.util.spec_from_file_location("closed_beta_staging_database_backup", SCRIPT_PATH)
if spec is None or spec.loader is None:
    raise RuntimeError("Unable to load B6.02 runner")
backup = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = backup
spec.loader.exec_module(backup)

DATABASE_SCRIPT_PATH = REPOSITORY_ROOT / "tools" / "closed-beta-staging-database.py"
database_spec = importlib.util.spec_from_file_location(
    "closed_beta_staging_database_consumer_for_backup_test",
    DATABASE_SCRIPT_PATH,
)
if database_spec is None or database_spec.loader is None:
    raise RuntimeError("Unable to load B6.01 backup consumer")
database_consumer = importlib.util.module_from_spec(database_spec)
sys.modules[database_spec.name] = database_consumer
database_spec.loader.exec_module(database_consumer)


SHA = "a" * 40
STAGING_REF = "abcdefghijklmnopqrst"
PRODUCTION_REF = "tsrqponmlkjihgfedcba"
DATABASE_URL = (
    f"postgresql://postgres.{STAGING_REF}:encoded-password@"
    "aws-1-eu-west-1.pooler.supabase.com:5432/postgres?sslmode=require"
)
RECIPIENT = "age1" + "q" * 58


@contextmanager
def patched_environment(values: dict[str, str]):
    with mock.patch.dict(os.environ, values, clear=True):
        yield


def valid_environment_document() -> dict[str, object]:
    return {
        "id": 77,
        "name": "staging",
        "can_admins_bypass": False,
        "deployment_branch_policy": {
            "protected_branches": True,
            "custom_branch_policies": False,
        },
        "protection_rules": [
            {
                "type": "required_reviewers",
                "prevent_self_review": True,
                "reviewers": [{"type": "User", "reviewer": {"id": 42}}],
            },
            {"type": "branch_policy"},
        ],
        "updated_at": "2026-08-20T12:00:00Z",
    }


def valid_ci_document() -> dict[str, object]:
    return {
        "id": 100,
        "head_sha": SHA,
        "head_branch": "main",
        "event": "push",
        "path": backup.EXPECTED_CI_WORKFLOW,
        "status": "completed",
        "conclusion": "success",
        "repository": {"id": 10, "full_name": backup.EXPECTED_REPOSITORY},
        "run_attempt": 2,
        "html_url": f"https://github.com/{backup.EXPECTED_REPOSITORY}/actions/runs/100",
    }


def valid_snapshot_document() -> dict[str, object]:
    managed = [
        {
            "schema": schema,
            "table": table,
            "required": required,
            "exists": True,
            "rowCount": 0,
        }
        for schema, table, required in backup.MANAGED_DATA_TABLES
    ]
    return {
        "constraintInventory": [
            {
                "constraintName": "listings_city_id_fkey",
                "constraintType": "f",
                "deferred": False,
                "deferrable": False,
                "definition": "FOREIGN KEY (city_id) REFERENCES cities(id)",
                "namespace": "public",
                "relationName": "listings",
                "relationSchema": "public",
                "validated": True,
            },
            {
                "constraintName": "listings_pkey",
                "constraintType": "p",
                "deferred": False,
                "deferrable": False,
                "definition": "PRIMARY KEY (id)",
                "namespace": "public",
                "relationName": "listings",
                "relationSchema": "public",
                "validated": True,
            },
        ],
        "managedTables": managed,
        "managedSchemaTables": [
            {"schema": schema, "table": table}
            for schema, table in backup.EXPECTED_MANAGED_SCHEMA_TABLES
        ],
        "migrationVersions": ["20260820083427", "20260820084207"],
        "postgresMajor": 17,
    }


class TargetAndAuthorityTest(unittest.TestCase):
    def test_only_tls_session_pooler_for_exact_staging_is_accepted(self) -> None:
        authority = backup.validate_target_authority(
            environment="staging",
            api_url=f"https://{STAGING_REF}.supabase.co",
            project_ref=STAGING_REF,
            production_project_ref=PRODUCTION_REF,
            project_ref_sha256=backup.sha256_text(STAGING_REF),
            database_url=DATABASE_URL,
        )
        self.assertEqual(authority.endpoint_class, "session-pooler")
        self.assertEqual(authority.public_evidence()["tlsMode"], "require")

        invalid = (
            DATABASE_URL.replace("?sslmode=require", ""),
            DATABASE_URL.replace("sslmode=require", "sslmode=disable"),
            (
                f"postgresql://postgres:encoded-password@db.{STAGING_REF}.supabase.co:"
                "5432/postgres?sslmode=require"
            ),
            DATABASE_URL.replace(STAGING_REF, PRODUCTION_REF),
        )
        for database_url in invalid:
            with self.subTest(database_url=database_url), self.assertRaises(backup.BackupError):
                backup.validate_target_authority(
                    environment="staging",
                    api_url=f"https://{STAGING_REF}.supabase.co",
                    project_ref=STAGING_REF,
                    production_project_ref=PRODUCTION_REF,
                    project_ref_sha256=backup.sha256_text(STAGING_REF),
                    database_url=database_url,
                )

    def test_ci_and_environment_require_exact_main_success_and_independent_review(self) -> None:
        self.assertEqual(
            backup.validate_ci_run(valid_ci_document(), expected_run_id=100, expected_sha=SHA)[
                "headSha"
            ],
            SHA,
        )
        self.assertTrue(backup.validate_environment(valid_environment_document())["preventSelfReview"])
        for mutation in (
            {"event": "workflow_dispatch"},
            {"conclusion": "failure"},
            {"head_branch": "feature"},
        ):
            document = valid_ci_document()
            document.update(mutation)
            with self.subTest(mutation=mutation), self.assertRaises(backup.BackupError):
                backup.validate_ci_run(document, expected_run_id=100, expected_sha=SHA)

        environment = valid_environment_document()
        environment["can_admins_bypass"] = True
        with self.assertRaisesRegex(backup.BackupError, "ENVIRONMENT_ADMIN_BYPASS_ENABLED"):
            backup.validate_environment(environment)

    def test_request_is_bound_to_manual_main_sha_workflow_and_hosted_runner(self) -> None:
        args = argparse.Namespace(
            operation="backup",
            expected_sha=SHA,
            validated_ci_run_id="100",
            capture_confirmation=backup.CAPTURE_CONFIRMATION,
        )
        environment = {
            "GITHUB_REPOSITORY": backup.EXPECTED_REPOSITORY,
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_REF": "refs/heads/main",
            "GITHUB_SHA": SHA,
            "GITHUB_SERVER_URL": "https://github.com",
            "GITHUB_WORKFLOW_REF": (
                f"{backup.EXPECTED_REPOSITORY}/{backup.EXPECTED_WORKFLOW}@refs/heads/main"
            ),
            "GITHUB_RUN_ID": "200",
            "GITHUB_RUN_ATTEMPT": "1",
            "GITHUB_ACTIONS": "true",
            "RUNNER_ENVIRONMENT": "github-hosted",
        }
        with patched_environment(environment):
            self.assertEqual(backup.validate_request(args)["validatedCiRunId"], 100)
        environment["GITHUB_REF"] = "refs/heads/feature"
        with patched_environment(environment), self.assertRaisesRegex(backup.BackupError, "REF_NOT_MAIN"):
            backup.validate_request(args)


class SnapshotAndFingerprintTest(unittest.TestCase):
    def test_backup_and_consumer_lock_the_same_managed_data_catalog(self) -> None:
        self.assertEqual(
            set(backup.MANAGED_DATA_TABLES),
            set(database_consumer.MANAGED_DATA_TABLES),
        )

    def test_managed_auth_storage_and_migrations_are_one_validated_snapshot_proof(self) -> None:
        parsed = backup.parse_snapshot_proof(json.dumps(valid_snapshot_document()))
        self.assertTrue(parsed["managedDataEmpty"])
        self.assertEqual(parsed["postgresMajor"], 17)
        self.assertEqual(len(parsed["managedTables"]), len(backup.MANAGED_DATA_TABLES))
        self.assertEqual(parsed["constraintCount"], 2)
        self.assertEqual(parsed["foreignKeyCount"], 1)
        self.assertEqual(
            parsed["managedSchemaTableCount"],
            len(backup.EXPECTED_MANAGED_SCHEMA_TABLES),
        )

        nonempty = valid_snapshot_document()
        nonempty["managedTables"][0]["exists"] = True
        nonempty["managedTables"][0]["rowCount"] = 1
        with self.assertRaisesRegex(backup.BackupError, "MANAGED_AUTH_STORAGE_NOT_EMPTY"):
            backup.parse_snapshot_proof(json.dumps(nonempty))

        missing_required = valid_snapshot_document()
        missing_required["managedTables"][0]["exists"] = False
        missing_required["managedTables"][0]["rowCount"] = None
        with self.assertRaisesRegex(backup.BackupError, "MANAGED_REQUIRED_TABLE_MISSING"):
            backup.parse_snapshot_proof(json.dumps(missing_required))

        missing_optional = valid_snapshot_document()
        optional_index = next(
            index
            for index, table in enumerate(missing_optional["managedTables"])
            if table["required"] is False
        )
        missing_optional["managedTables"][optional_index]["exists"] = False
        missing_optional["managedTables"][optional_index]["rowCount"] = None
        with self.assertRaisesRegex(backup.BackupError, "MANAGED_SCHEMA_CATALOG_DRIFT"):
            backup.parse_snapshot_proof(json.dumps(missing_optional))

        catalog_drift = valid_snapshot_document()
        catalog_drift["managedSchemaTables"] = catalog_drift["managedSchemaTables"][:-1]
        with self.assertRaisesRegex(backup.BackupError, "MANAGED_SCHEMA_CATALOG_DRIFT"):
            backup.parse_snapshot_proof(json.dumps(catalog_drift))

        no_constraints = valid_snapshot_document()
        no_constraints["constraintInventory"] = []
        with self.assertRaisesRegex(
            backup.BackupError,
            "SOURCE_CONSTRAINT_INVENTORY_INVALID",
        ):
            backup.parse_snapshot_proof(json.dumps(no_constraints))

    def test_sql_fingerprint_ignores_only_pg_dump_noise(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "first.sql"
            second = root / "second.sql"
            first.write_text(
                "-- Dumped from database version 17.6\n"
                "-- Dumped by pg_dump version 17.6\n"
                "\\restrict RANDOM1\nCREATE TABLE x(id int);\n\\unrestrict RANDOM1\n",
                encoding="utf-8",
            )
            second.write_text(
                "-- Dumped from database version 17.7\n"
                "-- Dumped by pg_dump version 17.7\n"
                "\\restrict RANDOM2\nCREATE TABLE x(id int);\n\\unrestrict RANDOM2\n",
                encoding="utf-8",
            )
            self.assertEqual(
                backup.normalized_sql_sha256(first),
                backup.normalized_sql_sha256(second),
            )
            second.write_text("CREATE TABLE x(id bigint);\n", encoding="utf-8")
            self.assertNotEqual(
                backup.normalized_sql_sha256(first),
                backup.normalized_sql_sha256(second),
            )

    def test_database_fingerprint_binds_sql_migrations_major_and_scope(self) -> None:
        first = backup.database_fingerprint(
            logical_sql_sha256="a" * 64,
            migration_sha256="b" * 64,
        )
        second = backup.database_fingerprint(
            logical_sql_sha256="a" * 64,
            migration_sha256="c" * 64,
        )
        self.assertRegex(first, r"^[0-9a-f]{64}$")
        self.assertNotEqual(first, second)

    def test_elapsed_seconds_round_up_at_the_approved_boundary(self) -> None:
        self.assertEqual(
            backup.elapsed_seconds_ceil(started=100.0, finished=160.0),
            60,
        )
        self.assertEqual(
            backup.elapsed_seconds_ceil(started=100.0, finished=160.01),
            61,
        )
        with self.assertRaisesRegex(backup.BackupError, "MONOTONIC_CLOCK_REGRESSION"):
            backup.elapsed_seconds_ceil(started=100.0, finished=99.9)

    def test_snapshot_query_and_dump_share_the_exported_snapshot_identifier(self) -> None:
        source = SCRIPT_PATH.read_text(encoding="utf-8")
        self.assertIn("select pg_export_snapshot()", source)
        self.assertIn("set transaction snapshot", source.lower())
        self.assertIn('["--snapshot", snapshot_id]', source)
        self.assertIn('"applicationDumpAndManagedProofShareSnapshot": True', source)

    def test_restore_requires_a_nonempty_exact_constraint_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = root / "payload"
            payload.mkdir()
            valid_output = json.dumps(
                {
                    "constraintInventory": valid_snapshot_document()["constraintInventory"],
                    "sessionReplicationRole": "origin",
                }
            )
            with mock.patch.object(
                backup,
                "run_command",
                side_effect=["", "", valid_output],
            ):
                evidence = backup.restore_payload("postgresql://local", payload, cwd=root)
            self.assertEqual(evidence["constraintCount"], 2)
            self.assertEqual(evidence["foreignKeyCount"], 1)
            self.assertRegex(evidence["constraintInventorySha256"], r"^[0-9a-f]{64}$")

            empty_output = json.dumps(
                {"constraintInventory": [], "sessionReplicationRole": "origin"}
            )
            with (
                mock.patch.object(
                    backup,
                    "run_command",
                    side_effect=["", "", empty_output],
                ),
                self.assertRaisesRegex(
                    backup.BackupError,
                    "RESTORE_CONSTRAINT_INVENTORY_INVALID",
                ),
            ):
                backup.restore_payload("postgresql://local", payload, cwd=root)


class EncryptionEscrowAndEvidenceTest(unittest.TestCase):
    def test_age_escrow_must_match_recipient_be_recent_and_cover_retention(self) -> None:
        now = datetime(2026, 8, 21, 12, 0, tzinfo=timezone.utc)
        environment = {
            "KWABOR_STAGING_BACKUP_AGE_RECIPIENT": RECIPIENT,
            "KWABOR_STAGING_BACKUP_AGE_IDENTITY": "AGE-SECRET-KEY-1" + "A" * 58,
            "KWABOR_STAGING_BACKUP_ESCROW_MODE": "offline-two-person",
            "KWABOR_STAGING_BACKUP_ESCROW_RECIPIENT_SHA256": backup.sha256_text(RECIPIENT),
            "KWABOR_STAGING_BACKUP_ESCROW_TESTED_AT": "2026-08-20T12:00:00Z",
            "KWABOR_STAGING_BACKUP_ESCROW_VALID_UNTIL": "2027-08-21T12:00:00Z",
        }
        with patched_environment(environment):
            result = backup.validate_age_and_escrow(now)
        self.assertEqual(result["public"]["custodyMode"], "offline-two-person")
        environment["KWABOR_STAGING_BACKUP_ESCROW_RECIPIENT_SHA256"] = "0" * 64
        with patched_environment(environment), self.assertRaisesRegex(
            backup.BackupError, "AGE_ESCROW_RECIPIENT_DRIFT"
        ):
            backup.validate_age_and_escrow(now)

    def test_evidence_rejects_sensitive_fields_uris_and_private_keys(self) -> None:
        unsafe = (
            {"databaseUrl": "redacted"},
            {"value": "postgresql://postgres:password@example.test/postgres"},
            {"value": "AGE-SECRET-KEY-1" + "A" * 58},
        )
        for document in unsafe:
            with self.subTest(document=document), self.assertRaises(backup.BackupError):
                backup.assert_safe_document(document)

    def test_failure_receipt_is_non_restorable_and_stable(self) -> None:
        args = argparse.Namespace(operation="backup", expected_sha=SHA)
        with patched_environment({"GITHUB_RUN_ID": "200", "GITHUB_RUN_ATTEMPT": "2"}):
            receipt = backup.failure_receipt(args, "LIVE_BACKUP_DISABLED")
        self.assertFalse(receipt["restorable"])
        self.assertEqual(receipt["errorCode"], "LIVE_BACKUP_DISABLED")
        backup.assert_safe_document(receipt)

    def test_age_pair_failure_still_removes_private_identity_workspace(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runner_temp = root / "runner-temp"
            runner_temp.mkdir()
            evidence = root / "evidence"
            evidence.mkdir()
            authority = backup.validate_target_authority(
                environment="staging",
                api_url=f"https://{STAGING_REF}.supabase.co",
                project_ref=STAGING_REF,
                production_project_ref=PRODUCTION_REF,
                project_ref_sha256=backup.sha256_text(STAGING_REF),
                database_url=DATABASE_URL,
            )
            base = {
                "request": {"runId": 200, "runAttempt": 1},
                "target": authority,
                "age": {"identity": "private", "recipient": RECIPIENT},
            }
            args = argparse.Namespace(workspace=str(root), expected_sha=SHA)
            environment = {
                "KWABOR_STAGING_BACKUP_LIVE_ENABLED": "true",
                "RUNNER_TEMP": str(runner_temp),
            }
            with (
                patched_environment(environment),
                mock.patch.object(
                    backup,
                    "validate_age_pair",
                    side_effect=backup.BackupError("AGE_IDENTITY_RECIPIENT_MISMATCH"),
                ),
                self.assertRaisesRegex(
                    backup.BackupError,
                    "AGE_IDENTITY_RECIPIENT_MISMATCH",
                ),
            ):
                backup.capture_and_restore(args, base, evidence)
            self.assertEqual(list(runner_temp.iterdir()), [])


class WorkflowContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        cls.runner = SCRIPT_PATH.read_text(encoding="utf-8")

    def test_manual_protected_main_and_shared_concurrency_are_literal(self) -> None:
        self.assertIn("workflow_dispatch:", self.workflow)
        self.assertNotRegex(self.workflow, r"(?m)^  (push|pull_request|schedule):")
        self.assertIn("environment: staging", self.workflow)
        self.assertIn("group: closed-beta-demo-staging-operations", self.workflow)
        self.assertNotIn("environment: production", self.workflow)

    def test_external_actions_and_cli_are_pinned(self) -> None:
        self.assertIn("actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0", self.workflow)
        self.assertIn("actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a", self.workflow)
        self.assertIn("supabase/setup-cli@3c2f5e2ae34c34e428e8e206e2c4d21fa2d20fbf", self.workflow)
        self.assertIn("version: 2.111.0", self.workflow)

    def test_only_age_ciphertext_and_public_evidence_cross_artifact_boundary(self) -> None:
        self.assertIn('["age", "--encrypt"', self.runner)
        self.assertIn("encryptedBeforeArtifactBoundary", self.runner)
        self.assertIn("path: build/closed-beta-staging-database-backup-evidence", self.workflow)
        self.assertNotIn("path: $RUNNER_TEMP", self.workflow)
        self.assertNotRegex(self.workflow, r"(?m)^\s+path:.*[.]sql")
        self.assertIn("retention-days: 90", self.workflow)
        self.assertIn(".expires_at", self.workflow)
        self.assertIn(".digest == $digest", self.workflow)

    def test_restore_is_disposable_and_never_disables_integrity(self) -> None:
        combined = (self.workflow + self.runner).lower()
        self.assertIn('"supabase", "db", "start"', combined)
        self.assertIn("github-actions-disposable-supabase", combined)
        self.assertNotIn("set session_replication_role", combined)
        self.assertIn("unvalidatedconstraintcount", combined)
        self.assertIn("restored_fingerprint == source_fingerprint", self.runner)

    def test_secrets_are_environment_values_not_dispatch_inputs(self) -> None:
        inputs = self.workflow.split("    inputs:\n", maxsplit=1)[1].split("\npermissions:", maxsplit=1)[0]
        self.assertNotIn("database_url", inputs)
        self.assertNotIn("age_identity", inputs)
        self.assertIn("secrets.KWABOR_STAGING_DATABASE_URL", self.workflow)
        self.assertIn("secrets.KWABOR_STAGING_BACKUP_AGE_IDENTITY", self.workflow)

    def test_operational_failures_still_emit_and_upload_a_hashed_receipt(self) -> None:
        self.assertIn("if: failure()", self.workflow)
        self.assertIn("--failure-code B602_WORKFLOW_STEP_FAILED", self.workflow)
        self.assertIn("if: always()", self.workflow)
        self.assertIn("GEL_HASH_FILENAME", self.runner)


if __name__ == "__main__":
    unittest.main()
