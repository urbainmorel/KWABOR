from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile


TOOLS_ROOT = Path(__file__).resolve().parent
REPOSITORY_ROOT = TOOLS_ROOT.parent
MODULE_PATH = TOOLS_ROOT / "closed-beta-staging-fixture-archive.py"
WORKFLOW_PATH = REPOSITORY_ROOT / ".github" / "workflows" / "closed-beta-staging-fixture-archive.yml"
SPEC = importlib.util.spec_from_file_location("closed_beta_staging_fixture_archive", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
archive = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = archive
SPEC.loader.exec_module(archive)

APPLY_VALID_UNTIL = datetime(2026, 8, 21, 12, 30, tzinfo=timezone.utc)


EXPECTED_SHA = "a" * 40
PLAN_RUN_ID = 300
PLAN_ATTEMPT = 2
PLAN_ARTIFACT_ID = 400


def raw_state(mode: str = "published", **mutations: object) -> dict[str, object]:
    published, archived = (4, 0) if mode == "published" else (0, 4)
    value: dict[str, object] = {
        "archivedListings": archived,
        "businessContentExact": True,
        "childRowCount": archive.EXPECTED_CHILD_ROW_COUNT,
        "childSetSha256": archive.EXPECTED_CHILD_SET_SHA256,
        "createdAtSetSha256": archive.EXPECTED_CREATED_AT_SET_SHA256,
        "fixtureSetSha256": archive.EXPECTED_FIXTURE_SET_SHA256,
        "identityExact": True,
        "listingTriggerCount": archive.EXPECTED_LISTING_TRIGGER_COUNT,
        "listingTriggerSha256": archive.EXPECTED_LISTING_TRIGGER_SHA256,
        "lifecycleSetSha256": (
            archive.EXPECTED_PUBLISHED_LIFECYCLE_SET_SHA256 if mode == "published" else "4" * 64
        ),
        "otherPublishedListings": 0,
        "publicForeignKeyCount": archive.EXPECTED_PUBLIC_FK_COUNT,
        "publicForeignKeySha256": archive.EXPECTED_PUBLIC_FK_SHA256,
        "publishedAtSemanticsExact": True,
        "publishedListings": published,
        "schemaVersion": archive.SCHEMA_VERSION,
        "targetListings": 4,
    }
    value.update(mutations)
    return value


def backup_summary() -> dict[str, object]:
    return {
        "applyValidUntil": "2026-08-21T12:30:00Z",
        "artifactId": 200,
        "artifactName": f"kwabor-gel-g5-staging-database-backup-{EXPECTED_SHA}-1",
        "artifactSha256": "d" * 64,
        "databaseFingerprintSha256": "e" * 64,
        "expiresAt": "2026-11-19T12:00:00Z",
        "internalReceiptSha256": "f" * 64,
        "migrationPrefixCount": 22,
        "migrationPrefixSha256": "1" * 64,
        "restorable": True,
        "runAttempt": 1,
        "runId": 100,
        "targetDigestSha256": "2" * 64,
    }


def environment_evidence() -> dict[str, object]:
    return {
        "canAdminsBypass": False,
        "environmentId": 20,
        "name": "staging",
        "preventSelfReview": True,
        "protectedBranchesOnly": True,
        "reviewerCount": 1,
        "reviewerTypes": ["User"],
        "schemaVersion": 1,
        "updatedAt": "2026-08-21T10:00:00Z",
    }


class FakeGuard:
    def __init__(self, archive_size: int) -> None:
        self.archive_size = archive_size

    def validate_supporting_workflow_run(self, document: object, **kwargs: object) -> dict[str, object]:
        del document
        assert kwargs["expected_run_id"] == PLAN_RUN_ID
        assert kwargs["expected_sha"] == EXPECTED_SHA
        assert kwargs["expected_workflow"] == archive.EXPECTED_WORKFLOW
        return {
            "repositoryId": 10,
            "runAttempt": PLAN_ATTEMPT,
            "runId": PLAN_RUN_ID,
            "runUrl": f"https://github.com/{archive.EXPECTED_REPOSITORY}/actions/runs/{PLAN_RUN_ID}",
        }

    def validate_artifact_metadata(self, document: object, **kwargs: object) -> dict[str, object]:
        del document
        assert kwargs["expected_artifact_id"] == PLAN_ARTIFACT_ID
        return {
            "artifactId": PLAN_ARTIFACT_ID,
            "artifactName": kwargs["expected_name"],
            "artifactSha256": kwargs["expected_digest"],
            "expiresAt": "2026-11-19T12:00:00Z",
            "sizeBytes": self.archive_size,
        }

    def load_artifact_entries(
        self,
        path: Path,
        *,
        expected_digest: str,
        required_entries: tuple[str, ...],
        **_: object,
    ) -> dict[str, bytes]:
        assert hashlib.sha256(path.read_bytes()).hexdigest() == expected_digest
        with zipfile.ZipFile(path, "r") as source:
            return {name: source.read(name) for name in required_entries}

    def assert_safe_document(self, document: object) -> None:
        payload = json.dumps(document)
        assert "postgresql://" not in payload
        assert "password" not in payload.lower()


class SafeDocumentGuard:
    def assert_safe_document(self, document: object) -> None:
        payload = json.dumps(document)
        assert "postgresql://" not in payload


class FixtureIdentityAndStateTest(unittest.TestCase):
    def test_exact_historical_fixture_identity_and_business_hashes_are_locked(self) -> None:
        self.assertEqual(
            [item[0] for item in archive.FIXTURES],
            [
                "00000000-0000-4000-8000-000000000101",
                "00000000-0000-4000-8000-000000000102",
                "00000000-0000-4000-8000-000000000103",
                "00000000-0000-4000-8000-000000000104",
            ],
        )
        self.assertEqual(len({item[1] for item in archive.FIXTURES}), 4)
        for _, _, digest in archive.FIXTURES:
            self.assertRegex(digest, r"^[0-9a-f]{64}$")
            self.assertIn(digest, archive.state_select_sql())

    def test_only_exact_published_or_archived_states_are_accepted(self) -> None:
        published = archive.validate_state(raw_state("published"))
        archived = archive.validate_state(raw_state("archived"), required_mode="archived")
        self.assertEqual(published["mode"], "published")
        self.assertEqual(archived["mode"], "archived")
        self.assertRegex(str(published["stateSha256"]), r"^[0-9a-f]{64}$")
        self.assertEqual(archive.validate_state(published), published)

    def test_identity_content_status_other_published_and_schema_drift_fail_closed(self) -> None:
        mutations = (
            ("FIXTURE_IDENTITY_DRIFT", {"identityExact": False}),
            ("FIXTURE_CONTENT_DRIFT", {"businessContentExact": False}),
            ("FIXTURE_LIFECYCLE_DRIFT", {"publishedAtSemanticsExact": False}),
            ("FIXTURE_SET_DRIFT", {"fixtureSetSha256": "0" * 64}),
            ("FIXTURE_CHILD_DRIFT", {"childSetSha256": "0" * 64}),
            ("FIXTURE_CHILD_DRIFT", {"childRowCount": archive.EXPECTED_CHILD_ROW_COUNT + 1}),
            ("FIXTURE_CREATED_AT_DRIFT", {"createdAtSetSha256": "0" * 64}),
            ("FIXTURE_LIFECYCLE_DRIFT", {"lifecycleSetSha256": "0" * 64}),
            ("OTHER_PUBLISHED_LISTING_FOUND", {"otherPublishedListings": 1}),
            ("PUBLIC_FK_SCHEMA_DRIFT", {"publicForeignKeyCount": 66}),
            ("LISTING_TRIGGER_DRIFT", {"listingTriggerSha256": "0" * 64}),
            ("FIXTURE_STATUS_DRIFT", {"publishedListings": 2, "archivedListings": 2}),
            ("FIXTURE_STATUS_DRIFT", {"targetListings": 3}),
        )
        for code, mutation in mutations:
            with self.subTest(code=code), self.assertRaisesRegex(archive.FixtureArchiveError, code):
                archive.validate_state(raw_state(**mutation))

    def test_state_output_requires_lock_and_one_json_document(self) -> None:
        document = raw_state()
        parsed = archive.parse_state_output(
            "ADVISORY_LOCK_ACQUIRED\n" + json.dumps(document, separators=(",", ":")) + "\n"
        )
        self.assertEqual(parsed, document)
        with self.assertRaisesRegex(archive.FixtureArchiveError, "STAGING_OPERATION_LOCKED"):
            archive.parse_state_output("ADVISORY_LOCK_REFUSED\n")


class SqlSafetyTest(unittest.TestCase):
    def test_read_path_is_repeatable_read_only_and_apply_is_serialized(self) -> None:
        read_sql = archive.read_state_sql().lower()
        apply_sql = archive.apply_sql(
            archive.validate_state(raw_state()),
            apply_valid_until=APPLY_VALID_UNTIL,
        ).lower()
        self.assertIn("repeatable read read only", read_sql)
        self.assertIn("pg_try_advisory_xact_lock_shared", read_sql)
        self.assertIn("begin isolation level serializable", apply_sql)
        self.assertEqual(apply_sql.count("clock_timestamp() >="), 2)
        self.assertIn("backup_apply_window_expired", apply_sql)
        final_deadline_guard = apply_sql.index("$backup_window_final$")
        first_update = apply_sql.index("update public.listings")
        self.assertGreater(final_deadline_guard, apply_sql.index("$guard$;"))
        self.assertLess(final_deadline_guard, first_update)
        self.assertIn("pg_try_advisory_xact_lock(", apply_sql)
        self.assertIn("lock table public.listings in share row exclusive mode", apply_sql)
        for table in archive.CLOSURE_TABLES:
            self.assertIn(f"lock table {table} in share mode", apply_sql)

    def test_apply_archives_event_before_venue_without_delete_and_proves_children(self) -> None:
        sql = archive.apply_sql(
            archive.validate_state(raw_state()),
            apply_valid_until=APPLY_VALID_UNTIL,
        )
        event_position = sql.index(f"where id = '{archive.EVENT_FIXTURE_ID}'::uuid")
        venue_position = sql.index("where id in (", event_position)
        self.assertLess(event_position, venue_position)
        self.assertEqual(sql.lower().count("update public.listings"), 2)
        self.assertNotRegex(sql, r"(?i)\bdelete\b|\btruncate\b|\bdrop\s+table\s+public\.")
        self.assertIn("set constraints all immediate", sql.lower())
        self.assertIn("childSetSha256", sql)
        self.assertIn("childRowCount", sql)
        self.assertIn("FIXTURE_ARCHIVE_POSTCONDITION_DRIFT", sql)

    def test_archived_plan_only_allows_idempotent_archived_apply(self) -> None:
        sql = archive.apply_sql(
            archive.validate_state(raw_state("archived")),
            apply_valid_until=APPLY_VALID_UNTIL,
        )
        self.assertIn("mode not in ('archived')", sql)
        self.assertNotIn("mode not in ('published', 'archived')", sql)

    def test_backup_apply_window_is_rechecked_at_the_mutation_boundary(self) -> None:
        guard = archive.load_database_guard()
        backup = backup_summary()
        self.assertEqual(
            archive.require_backup_apply_window(
                guard,
                backup,
                now=datetime(2026, 8, 21, 12, 29, 59, tzinfo=timezone.utc),
            ),
            APPLY_VALID_UNTIL,
        )
        with self.assertRaisesRegex(
            archive.FixtureArchiveError,
            "BACKUP_APPLY_WINDOW_EXPIRED",
        ):
            archive.require_backup_apply_window(
                guard,
                backup,
                now=APPLY_VALID_UNTIL,
            )

    def test_child_closure_covers_direct_and_recursive_dependants(self) -> None:
        sql = archive.child_rows_sql()
        for relation in (
            "public.listing_media",
            "public.event_details",
            "public.ticket_tiers",
            "public.room_types",
            "public.payments",
            "public.social_media",
            "public.guide_service_languages",
        ):
            self.assertIn(relation, sql)


class ProcessBoundaryTest(unittest.TestCase):
    def test_database_url_is_environment_only_and_unrelated_secrets_are_removed(self) -> None:
        database_url = "postgresql://postgres.project:secret@pooler.supabase.com:5432/postgres?sslmode=require"
        with mock.patch.dict(
            os.environ,
            {
                "PATH": "safe-path",
                "SUPABASE_ACCESS_TOKEN": "must-not-cross",
                "KWABOR_SUPABASE_SERVICE_ROLE_KEY": "must-not-cross-either",
            },
            clear=False,
        ):
            environment = archive.sanitized_psql_environment(database_url)
        self.assertEqual(environment["PGDATABASE"], database_url)
        self.assertNotIn("SUPABASE_ACCESS_TOKEN", environment)
        self.assertNotIn("KWABOR_SUPABASE_SERVICE_ROLE_KEY", environment)

        completed = subprocess.CompletedProcess(args=[], returncode=0, stdout="{}\n", stderr="")
        with mock.patch.object(archive.subprocess, "run", return_value=completed) as runner:
            archive.run_psql(database_url, "select 1")
        command = runner.call_args.args[0]
        self.assertNotIn(database_url, command)
        self.assertEqual(runner.call_args.kwargs["env"]["PGDATABASE"], database_url)

    def test_apply_output_proves_published_to_archived_transition(self) -> None:
        payload = {
            "before": raw_state("published"),
            "after": raw_state("archived"),
            "schemaVersion": archive.SCHEMA_VERSION,
        }
        before, after = archive.parse_apply_output(json.dumps(payload, separators=(",", ":")))
        self.assertEqual(before["mode"], "published")
        self.assertEqual(after["mode"], "archived")

    def test_workflow_failure_receipt_is_sanitized_hashed_and_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            arguments = [
                "--operation",
                "apply",
                "--expected-sha",
                EXPECTED_SHA,
                "--failure-code",
                "FIXTURE_ARCHIVE_WORKFLOW_STEP_FAILED",
                "--evidence-directory",
                temporary,
            ]
            with mock.patch.object(archive, "load_database_guard", return_value=SafeDocumentGuard()):
                self.assertEqual(archive.emit_workflow_failure(arguments), 0)
                self.assertEqual(archive.emit_workflow_failure(arguments), 0)
            receipt_path = Path(temporary) / archive.GEL_FILENAME
            receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
            self.assertEqual(receipt["errorCode"], "FIXTURE_ARCHIVE_WORKFLOW_STEP_FAILED")
            self.assertEqual(
                (Path(temporary) / archive.GEL_HASH_FILENAME).read_text(encoding="ascii"),
                f"{hashlib.sha256(receipt_path.read_bytes()).hexdigest()}  {archive.GEL_FILENAME}\n",
            )


class PlanReceiptTest(unittest.TestCase):
    def create_plan_bundle(
        self,
        root: Path,
        *,
        state_mutation: dict[str, object] | None = None,
    ) -> tuple[Path, str, dict[str, object], dict[str, object]]:
        state = archive.validate_state(raw_state())
        if state_mutation:
            state.update(state_mutation)
        target = {"environment": "staging", "schemaVersion": 1}
        backup = backup_summary()
        state_bytes = archive.canonical_json_bytes(state)
        receipt = {
            "backup": backup,
            "ci": {
                "conclusion": "success",
                "event": "push",
                "headBranch": "main",
                "headSha": EXPECTED_SHA,
                "runAttempt": 1,
                "runId": 100,
                "runUrl": f"https://github.com/{archive.EXPECTED_REPOSITORY}/actions/runs/100",
                "status": "completed",
                "workflowPath": archive.EXPECTED_CI_WORKFLOW,
            },
            "contributesTo": archive.CONTRIBUTES_TO,
            "environmentEvidence": environment_evidence(),
            "errorCode": None,
            "evidence": {
                "filename": archive.STATE_FILENAME,
                "sha256": hashlib.sha256(state_bytes).hexdigest(),
            },
            "expectedSha": EXPECTED_SHA,
            "gateClosed": False,
            "mutationState": "not_started",
            "operation": "plan",
            "ref": archive.EXPECTED_REF,
            "repository": archive.EXPECTED_REPOSITORY,
            "runAttempt": PLAN_ATTEMPT,
            "runId": PLAN_RUN_ID,
            "runUrl": f"https://github.com/{archive.EXPECTED_REPOSITORY}/actions/runs/{PLAN_RUN_ID}",
            "schemaVersion": archive.SCHEMA_VERSION,
            "state": state,
            "status": "succeeded",
            "target": target,
            "taskId": archive.TASK_ID,
            "transition": "plan-published",
            "validatedCiRunId": 100,
            "workflowPath": archive.EXPECTED_WORKFLOW,
        }
        receipt_bytes = archive.canonical_json_bytes(receipt)
        sidecar = f"{hashlib.sha256(receipt_bytes).hexdigest()}  {archive.GEL_FILENAME}\n".encode()
        path = root / "plan.zip"
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as destination:
            destination.writestr(archive.GEL_FILENAME, receipt_bytes)
            destination.writestr(archive.GEL_HASH_FILENAME, sidecar)
            destination.writestr(archive.STATE_FILENAME, state_bytes)
        return path, hashlib.sha256(path.read_bytes()).hexdigest(), target, backup

    def test_plan_artifact_is_hash_bound_to_state_backup_target_and_run(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path, digest, target, backup = self.create_plan_bundle(Path(temporary))
            guard = FakeGuard(path.stat().st_size)
            result = archive.validate_plan_artifact(
                guard,
                run_document={},
                artifact_document={},
                archive_path=path,
                plan_run_id=PLAN_RUN_ID,
                plan_artifact_id=PLAN_ARTIFACT_ID,
                plan_artifact_digest=digest,
                expected_sha=EXPECTED_SHA,
                validated_ci_run_id=100,
                environment_evidence=environment_evidence(),
                target_evidence=target,
                backup_summary=backup,
            )
            self.assertEqual(result["state"]["mode"], "published")
            self.assertRegex(result["internalReceiptSha256"], r"^[0-9a-f]{64}$")

    def test_plan_with_child_drift_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path, digest, target, backup = self.create_plan_bundle(
                Path(temporary), state_mutation={"childSetSha256": "9" * 64}
            )
            guard = FakeGuard(path.stat().st_size)
            with self.assertRaisesRegex(archive.FixtureArchiveError, "FIXTURE_CHILD_DRIFT"):
                archive.validate_plan_artifact(
                    guard,
                    run_document={},
                    artifact_document={},
                    archive_path=path,
                    plan_run_id=PLAN_RUN_ID,
                    plan_artifact_id=PLAN_ARTIFACT_ID,
                    plan_artifact_digest=digest,
                    expected_sha=EXPECTED_SHA,
                    validated_ci_run_id=100,
                    environment_evidence=environment_evidence(),
                    target_evidence=target,
                    backup_summary=backup,
                )


class WorkflowPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW_PATH.read_text(encoding="utf-8")

    def test_workflow_is_manual_protected_staging_with_shared_serialization(self) -> None:
        self.assertIn("workflow_dispatch:", self.workflow)
        self.assertNotRegex(self.workflow, r"(?m)^  (push|pull_request|schedule):")
        self.assertIn("environment: staging", self.workflow)
        self.assertNotIn("environment: production", self.workflow)
        self.assertIn("group: closed-beta-demo-staging-operations", self.workflow)
        self.assertIn("cancel-in-progress: false", self.workflow)

    def test_apply_requires_plan_confirmation_and_qualified_backup_authority(self) -> None:
        dispatch = self.workflow.split("permissions:", maxsplit=1)[0]
        for name in (
            "expected_sha:",
            "validated_ci_run_id:",
            "backup_run_id:",
            "backup_artifact_id:",
            "backup_artifact_digest:",
            "plan_run_id:",
            "plan_artifact_id:",
            "plan_artifact_digest:",
        ):
            self.assertIn(name, dispatch)
        self.assertIn(archive.APPLY_CONFIRMATION, self.workflow)
        self.assertIn("/environments/staging", self.workflow)
        self.assertIn('fetch_id_json runs "$BACKUP_RUN_ID"', self.workflow)
        self.assertIn('fetch_id_json artifacts "$BACKUP_ARTIFACT_ID"', self.workflow)
        self.assertIn('download_artifact "$BACKUP_ARTIFACT_ID"', self.workflow)
        self.assertIn('fetch_id_json runs "$PLAN_RUN_ID"', self.workflow)
        self.assertIn('fetch_id_json artifacts "$PLAN_ARTIFACT_ID"', self.workflow)
        self.assertIn('download_artifact "$PLAN_ARTIFACT_ID"', self.workflow)
        self.assertNotIn("database_url:", dispatch.lower())

    def test_external_actions_are_commit_pinned_and_evidence_is_retained(self) -> None:
        actions = []
        for line in self.workflow.splitlines():
            stripped = line.strip()
            if stripped.startswith("- uses:") or stripped.startswith("uses:"):
                actions.append(stripped.split("uses:", maxsplit=1)[1].split("#", maxsplit=1)[0].strip())
        self.assertEqual(len(actions), 2)
        for action in actions:
            self.assertRegex(action, r"^[^@]+@[0-9a-f]{40}$")
        self.assertIn("if: always()", self.workflow)
        self.assertIn("if: failure()", self.workflow)
        self.assertIn("FIXTURE_ARCHIVE_WORKFLOW_STEP_FAILED", self.workflow)
        self.assertIn("retention-days: 90", self.workflow)
        self.assertIn("artifact-digest", self.workflow)


if __name__ == "__main__":
    unittest.main()
