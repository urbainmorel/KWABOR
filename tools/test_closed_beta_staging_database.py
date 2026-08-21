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
from pathlib import Path


TOOLS_DIRECTORY = Path(__file__).resolve().parent
REPOSITORY_ROOT = TOOLS_DIRECTORY.parent
MODULE_PATH = TOOLS_DIRECTORY / "closed-beta-staging-database.py"
WORKFLOW_PATH = REPOSITORY_ROOT / ".github" / "workflows" / "closed-beta-staging-database.yml"
STORAGE_WORKFLOW_PATH = (
    REPOSITORY_ROOT / ".github" / "workflows" / "closed-beta-demo-storage.yml"
)
CATALOG_WORKFLOW_PATH = (
    REPOSITORY_ROOT / ".github" / "workflows" / "closed-beta-demo-catalog.yml"
)
STAGING_OPERATION_WORKFLOW_PATHS = (
    STORAGE_WORKFLOW_PATH,
    CATALOG_WORKFLOW_PATH,
    WORKFLOW_PATH,
)
EXPECTED_STAGING_OPERATION_CONCURRENCY_GROUP = "closed-beta-demo-staging-operations"
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
    f"postgresql://postgres:{DATABASE_PASSWORD}@"
    f"db.{STAGING_REF}.supabase.co:5432/postgres"
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
        "expires_at": "2099-11-18T12:00:00Z",
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
        remote_versions=["20260703004103"],
    )
    database.write_json_exclusive(evidence / "LOCAL-MIGRATION-MANIFEST.json", manifest)
    database.write_json_exclusive(evidence / "PLAN-PENDING-CHECK.json", pending)
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
) -> tuple[Path, str]:
    target = valid_authority().public_evidence()
    receipt: dict[str, object] = {
        "expectedSha": EXPECTED_SHA,
        "operation": "backup",
        "repository": "urbainmorel/KWABOR",
        "restorable": True,
        "runAttempt": 1,
        "runId": 400,
        "runUrl": "https://github.com/urbainmorel/KWABOR/actions/runs/400",
        "status": "succeeded",
        "target": target,
        "targetDigestSha256": hashlib.sha256(
            database.canonical_json_bytes(target)
        ).hexdigest(),
        "validatedCiRunId": 100,
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
    def test_exact_direct_and_session_pooler_urls_are_accepted(self) -> None:
        direct = valid_authority()
        self.assertEqual(direct.database_endpoint_class, "direct")
        pooler_url = (
            f"postgresql://postgres.{STAGING_REF}:{DATABASE_PASSWORD}@"
            "aws-0-eu-central-1.pooler.supabase.com:5432/postgres"
        )
        pooler = valid_authority(pooler_url)
        self.assertEqual(pooler.database_endpoint_class, "session-pooler")
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
                    f"postgresql://postgres:{DATABASE_PASSWORD}@"
                    f"db.{PRODUCTION_REF}.supabase.co:5432/postgres"
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

    def test_query_host_hostaddr_service_and_other_overrides_are_rejected(self) -> None:
        overrides = (
            "?host=evil.example",
            "?hostaddr=127.0.0.1",
            "?service=production",
            "?sslmode=disable",
            "#host=evil.example",
        )
        for suffix in overrides:
            with self.subTest(suffix=suffix), self.assertRaisesRegex(
                database.StagingDatabaseError, "DATABASE_URL_OVERRIDE_FORBIDDEN"
            ):
                valid_authority(DATABASE_URL + suffix)

    def test_unsafe_database_shapes_are_rejected(self) -> None:
        unsafe_urls = (
            DATABASE_URL.replace(":5432/", ":6543/"),
            DATABASE_URL.replace(f"db.{STAGING_REF}.supabase.co", "db.evil.example"),
            DATABASE_URL.replace("/postgres", "/template1"),
            DATABASE_URL.replace("postgresql://", "postgres://"),
            DATABASE_URL.replace(DATABASE_PASSWORD, "raw!password"),
            DATABASE_URL.replace("postgres:", "admin:"),
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
    def test_apply_requires_exact_confirmation_backup_and_plan_artifact_authorities(self) -> None:
        valid = database.validate_operation_inputs(
            operation="apply",
            confirmation="APPLY-EXACT-STAGING-MIGRATIONS",
            backup_run_id="400",
            backup_artifact_id="401",
            backup_artifact_digest="b" * 64,
            validated_plan_run_id="300",
            validated_plan_artifact_id="301",
            validated_plan_artifact_digest="c" * 64,
        )
        self.assertEqual(valid["backupArtifactId"], 401)
        self.assertEqual(valid["planRunId"], 300)
        unsafe = (
            {"confirmation": ""},
            {"confirmation": "APPLY-STAGING-MIGRATIONS"},
            {"backup_run_id": ""},
            {"backup_run_id": "9" * 100},
            {"backup_artifact_id": "0"},
            {"backup_artifact_digest": "sha256:" + "b" * 64},
            {"validated_plan_run_id": ""},
            {"validated_plan_artifact_id": "0"},
            {"validated_plan_artifact_digest": "C" * 64},
            {"validated_plan_run_id": "400"},
            {"validated_plan_artifact_id": "401"},
        )
        base = {
            "operation": "apply",
            "confirmation": "APPLY-EXACT-STAGING-MIGRATIONS",
            "backup_run_id": "400",
            "backup_artifact_id": "401",
            "backup_artifact_digest": "b" * 64,
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

    def test_only_fixed_read_only_or_dry_run_commands_are_constructible(self) -> None:
        authority = valid_authority()
        expected_kinds = {
            "advisors-performance",
            "advisors-security",
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
            self.assertNotIn("--yes", rendered)
        with self.assertRaisesRegex(database.StagingDatabaseError, "COMMAND_KIND_FORBIDDEN"):
            database.build_command("apply", authority)
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

    def test_apply_is_explicitly_prepared_but_not_executable_without_backup_producer(self) -> None:
        self.assertFalse(database.BACKUP_PRODUCER_AVAILABLE)
        self.assertEqual(
            database.EXPECTED_BACKUP_WORKFLOW,
            ".github/workflows/closed-beta-staging-database-backup.yml",
        )
        self.assertFalse((REPOSITORY_ROOT / database.EXPECTED_BACKUP_WORKFLOW).exists())
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertIn("PREPARED_NOT_EXECUTABLE", source)
        self.assertNotIn('["supabase", "db", "push", "--yes"', source)


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
            self.assertEqual(proof["pendingCount"], 1)
            self.assertRegex(proof["internalReceiptSha256"], r"^[0-9a-f]{64}$")

        mutations = (
            {"status": "failed"},
            {"expectedSha": "b" * 40},
            {"validatedCiRunId": 101},
            {"target": {"environment": "production"}},
            {"targetDigestSha256": "0" * 64},
            {"migrationManifest": {"count": 2, "manifestSha256": "0" * 64}},
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
            )
            self.assertTrue(proof["restorable"])

        for mutation in (
            {"expectedSha": "b" * 40},
            {"target": {"environment": "production"}},
            {"targetDigestSha256": "0" * 64},
            {"restorable": False},
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
                    )


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
                    "--backup-run-json",
                    "unused",
                    "--backup-artifact-json",
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

    def test_disabled_apply_cannot_emit_a_receipt_claiming_mutation_started(self) -> None:
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
            with self.assertRaisesRegex(database.StagingDatabaseError, "MUTATION_STATE_INVALID"):
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
                    error_code="SUPABASE_APPLY_TIMEOUT",
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
        self.assertIn(
            f"group: {EXPECTED_STAGING_OPERATION_CONCURRENCY_GROUP}",
            self.workflow,
        )
        self.assertNotRegex(
            self.workflow,
            r"group:\s*closed-beta-demo-staging-operations.*\$\{\{",
        )
        self.assertNotIn("environment: production", self.workflow)

    def test_storage_catalog_and_database_share_the_exact_concurrency_group(self) -> None:
        observed_groups: dict[str, str] = {}
        for workflow_path in STAGING_OPERATION_WORKFLOW_PATHS:
            with self.subTest(workflow=workflow_path.name):
                workflow = workflow_path.read_text(encoding="utf-8")
                groups = re.findall(
                    r"(?m)^  group:\s*([^\s#]+)\s*(?:#.*)?$",
                    workflow,
                )
                self.assertEqual(
                    [EXPECTED_STAGING_OPERATION_CONCURRENCY_GROUP],
                    groups,
                )
                observed_groups[workflow_path.name] = groups[0]

        self.assertEqual(
            {EXPECTED_STAGING_OPERATION_CONCURRENCY_GROUP},
            set(observed_groups.values()),
        )

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

    def test_apply_inputs_and_api_plan_download_are_explicit_but_mutation_is_disabled(self) -> None:
        for fragment in (
            "backup_run_id:",
            "backup_artifact_id:",
            "backup_artifact_digest:",
            "validated_plan_run_id:",
            "validated_plan_artifact_id:",
            "validated_plan_artifact_digest:",
            'fetch_id_json "runs" "$VALIDATED_PLAN_RUN_ID"',
            'fetch_id_json "artifacts" "$VALIDATED_PLAN_ARTIFACT_ID"',
            'fetch_id_json "runs" "$BACKUP_RUN_ID"',
            'fetch_id_json "artifacts" "$BACKUP_ARTIFACT_ID"',
            "/actions/artifacts/$VALIDATED_PLAN_ARTIFACT_ID/zip",
            "X-GitHub-Api-Version: 2022-11-28",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, self.workflow)
        self.assertIn("BACKUP_PRODUCER_AVAILABLE = False", self.runner)
        self.assertIn("PREPARED_NOT_EXECUTABLE", self.runner)
        self.assertNotIn("$BACKUP_ARTIFACT_ID/zip", self.workflow)
        self.assertNotIn('"--yes"', self.workflow + self.runner)

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
