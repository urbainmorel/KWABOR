from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = ROOT / "tools" / "closed-beta-staging-database-backup.py"
WORKFLOW_PATH = ROOT / ".github" / "workflows" / "closed-beta-staging-database-backup.yml"
SPEC = importlib.util.spec_from_file_location("closed_beta_staging_database_backup", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
backup = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = backup
SPEC.loader.exec_module(backup)


SHA = "d34e6b8441194c3ebb5eb989465118c81ba4a13a"
STAGING_REF = "abcdefghijklmnopqrst"
PRODUCTION_REF = "zyxwvutsrqponmlkjihg"
RECIPIENT = "age1" + ("q" * 58)
IDENTITY = "AGE-SECRET-KEY-1" + ("Q" * 58)
RUN_ID = 90210
RUN_ATTEMPT = 3
CI_RUN_ID = 90100
SOURCE_REPOSITORY_ID = 1001
VAULT_REPOSITORY_ID = 2002


def timestamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def write_raw_json(path: Path, document: dict[str, object]) -> None:
    path.write_bytes(backup.canonical_json_bytes(document))


class ValidationTests(unittest.TestCase):
    def test_normalized_sql_only_removes_pg_dump_invocation_noise(self) -> None:
        source = (
            b"-- Dumped from database version 17.4\n"
            b"-- Dumped by pg_dump version 17.5\n"
            b"-- Started on 2026-08-20 10:00:00 UTC\n"
            b"\\restrict abc\n"
            b"CREATE TABLE public.place(id bigint);   \n"
            b"\\unrestrict abc\n"
            b"-- Completed on 2026-08-20 10:00:02 UTC\n"
        )
        self.assertEqual(
            backup.normalized_sql_bytes(source),
            b"CREATE TABLE public.place(id bigint);   \n",
        )

    def test_normalized_sql_preserves_copy_data_trailing_spaces(self) -> None:
        self.assertNotEqual(
            backup.normalized_sql_bytes(b"COPY public.place (name) FROM stdin;\nfoo\n\\.\n"),
            backup.normalized_sql_bytes(b"COPY public.place (name) FROM stdin;\nfoo \n\\.\n"),
        )

    def test_payload_fingerprint_is_exact_and_order_stable(self) -> None:
        dumps = {
            "schema": {"normalizedSha256": "b" * 64},
            "roles": {"normalizedSha256": "a" * 64},
            "data": {"normalizedSha256": "c" * 64},
        }
        first = backup._payload_fingerprint(
            dumps,
            migration_prefix_sha256="d" * 64,
            postgres_major=17,
        )
        second = backup._payload_fingerprint(
            dict(reversed(list(dumps.items()))),
            migration_prefix_sha256="d" * 64,
            postgres_major=17,
        )
        self.assertRegex(first, r"^[0-9a-f]{64}$")
        self.assertEqual(first, second)
        changed = dict(dumps)
        changed["data"] = {"normalizedSha256": "e" * 64}
        self.assertNotEqual(
            first,
            backup._payload_fingerprint(
                changed,
                migration_prefix_sha256="d" * 64,
                postgres_major=17,
            ),
        )

    def test_migration_history_is_dumped_and_recomputed_after_restore(self) -> None:
        self.assertEqual(
            backup.DUMP_MODES,
            ("roles", "schema", "data", "migration_schema", "migration_data"),
        )
        source = SCRIPT_PATH.read_text(encoding="utf-8")
        self.assertIn("restored_migration_versions = _migration_versions(local_database_url", source)
        self.assertIn('"RESTORED_MIGRATION_PREFIX_DRIFT"', source)

    def test_safe_evidence_rejects_credentials_and_sensitive_keys(self) -> None:
        unsafe_values = (
            {"databaseUrl": "redacted"},
            {"value": "postgresql://postgres:password@db.example.test:5432/postgres"},
            {"value": IDENTITY},
        )
        for value in unsafe_values:
            with self.subTest(value=value), self.assertRaises(backup.BackupError):
                backup.assert_safe_document(value)

    def test_target_authority_accepts_only_exact_staging_project(self) -> None:
        target = backup.validate_target_authority(
            environment="staging",
            api_url=f"https://{STAGING_REF}.supabase.co",
            project_ref=STAGING_REF,
            production_project_ref=PRODUCTION_REF,
            project_ref_sha256=backup.sha256_text(STAGING_REF),
            database_url=(
                f"postgresql://postgres.{STAGING_REF}:encoded-password@"
                "aws-0-eu-west-1.pooler.supabase.com:5432/postgres"
            ),
        )
        self.assertEqual(target.project_ref, STAGING_REF)
        self.assertNotIn("password", json.dumps(target.public_evidence()).lower())
        with self.assertRaisesRegex(backup.BackupError, "PRODUCTION_TARGET_FORBIDDEN"):
            backup.validate_target_authority(
                environment="staging",
                api_url=f"https://{STAGING_REF}.supabase.co",
                project_ref=STAGING_REF,
                production_project_ref=STAGING_REF,
                project_ref_sha256=backup.sha256_text(STAGING_REF),
                database_url=f"postgresql://postgres:encoded-password@db.{STAGING_REF}.supabase.co:5432/postgres",
            )

    def test_environment_requires_review_and_no_admin_bypass(self) -> None:
        document = environment_document()
        validated = backup.validate_environment(document)
        self.assertTrue(validated["preventSelfReview"])
        document["can_admins_bypass"] = True
        with self.assertRaisesRegex(backup.BackupError, "ENVIRONMENT_ADMIN_BYPASS_ENABLED"):
            backup.validate_environment(document)

    def test_vault_must_be_private_separate_and_immutable(self) -> None:
        evidence = backup.validate_vault(
            vault_repository_document(),
            {"enabled": True, "enforced_by_owner": False},
            configured_repository="urbainmorel/KWABOR-backup-vault",
            source_repository_id=SOURCE_REPOSITORY_ID,
        )
        self.assertEqual(evidence["repositoryId"], VAULT_REPOSITORY_ID)
        with self.assertRaisesRegex(backup.BackupError, "VAULT_IMMUTABLE_RELEASES_DISABLED"):
            backup.validate_vault(
                vault_repository_document(),
                {"fetchStatus": "failed"},
                configured_repository="urbainmorel/KWABOR-backup-vault",
                source_repository_id=SOURCE_REPOSITORY_ID,
            )

    def test_escrow_must_be_in_the_immutable_release_and_cover_retention(self) -> None:
        now = datetime(2026, 8, 20, 12, 0, tzinfo=timezone.utc)
        receipt = escrow_receipt(now)
        receipt_sha = backup.sha256_bytes(backup.canonical_json_bytes(receipt))
        release = escrow_release_document(receipt_sha, now)
        asset = release["assets"][0]
        result = backup.validate_key_escrow(
            release_document=release,
            asset_document=asset,
            receipt_document=receipt,
            receipt_sha256=receipt_sha,
            expected_release_id=710,
            expected_release_tag="kwabor-age-key-escrow-v1",
            expected_asset_id=711,
            expected_asset_sha256=receipt_sha,
            age_recipient_sha256=backup.sha256_text(RECIPIENT),
            minimum_retention_days=180,
            now=now,
        )
        self.assertTrue(result["recoveryKeyStoredOffsite"])
        broken_release = dict(release)
        broken_release["assets"] = []
        with self.assertRaisesRegex(backup.BackupError, "ESCROW_ASSET_NOT_IN_RELEASE"):
            backup.validate_key_escrow(
                release_document=broken_release,
                asset_document=asset,
                receipt_document=receipt,
                receipt_sha256=receipt_sha,
                expected_release_id=710,
                expected_release_tag="kwabor-age-key-escrow-v1",
                expected_asset_id=711,
                expected_asset_sha256=receipt_sha,
                age_recipient_sha256=backup.sha256_text(RECIPIENT),
                minimum_retention_days=180,
                now=now,
            )

    def test_child_commands_do_not_inherit_backup_secrets(self) -> None:
        environment = {
            "PATH": os.environ.get("PATH", ""),
            "KWABOR_STAGING_DATABASE_URL": "database-secret",
            "KWABOR_STAGING_BACKUP_AGE_IDENTITY": "age-secret",
            "KWABOR_BACKUP_VAULT_TOKEN": "vault-secret",
            "GITHUB_TOKEN": "source-secret",
            "GH_TOKEN": "source-secret",
            "ACTIONS_RUNTIME_TOKEN": "runner-secret",
        }
        with mock.patch.dict(os.environ, environment, clear=True):
            sanitized = backup.sanitized_environment(read_only=True)
        self.assertEqual(sanitized["PGOPTIONS"], "-c default_transaction_read_only=on")
        for key in backup.BACKUP_SECRET_KEYS:
            self.assertNotIn(key, sanitized)
        self.assertNotIn("ACTIONS_RUNTIME_TOKEN", sanitized)

    def test_capture_requires_a_github_hosted_runner(self) -> None:
        evidence = {"expectedSha": SHA, "runId": RUN_ID, "runAttempt": RUN_ATTEMPT}
        with mock.patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(
            backup.BackupError,
            "GITHUB_ACTIONS_REQUIRED",
        ):
            backup._require_github_hosted_runner(evidence)


class PreflightTests(unittest.TestCase):
    def test_readiness_is_non_executable_and_never_restorable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args, environment = build_preflight_fixture(root, operation="readiness")
            with mock.patch.dict(os.environ, environment, clear=True), mock.patch.object(
                backup,
                "utc_now",
                return_value="2026-08-20T12:00:00Z",
            ):
                backup.preflight(args)
            preflight = backup.load_json(root / "evidence" / backup.PREFLIGHT_FILENAME)
            gel = backup.load_json(root / "evidence" / backup.GEL_FILENAME)
            self.assertEqual(preflight["status"], "prepared_not_executable")
            self.assertFalse(preflight["liveEnabled"])
            self.assertFalse(gel["restorable"])
            self.assertEqual(gel["errorCode"], "LIVE_BACKUP_NOT_REQUESTED")
            self.assertNotIn(IDENTITY, (root / "evidence" / backup.PREFLIGHT_FILENAME).read_text(encoding="utf-8"))

    def test_backup_fails_closed_while_live_flag_is_false(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args, environment = build_preflight_fixture(root, operation="backup")
            with mock.patch.dict(os.environ, environment, clear=True), self.assertRaisesRegex(
                backup.BackupError,
                "LIVE_BACKUP_DISABLED",
            ):
                backup.preflight(args)

    def test_backup_requires_exact_confirmation_after_live_enablement(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args, environment = build_preflight_fixture(root, operation="backup")
            environment["KWABOR_STAGING_BACKUP_LIVE_ENABLED"] = "true"
            args.capture_confirmation = "wrong"
            with mock.patch.dict(os.environ, environment, clear=True), self.assertRaisesRegex(
                backup.BackupError,
                "CAPTURE_CONFIRMATION_INVALID",
            ):
                backup.preflight(args)

    def test_missing_restore_secret_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args, environment = build_preflight_fixture(root, operation="readiness")
            environment["KWABOR_STAGING_BACKUP_AGE_IDENTITY"] = ""
            with mock.patch.dict(os.environ, environment, clear=True), self.assertRaisesRegex(
                backup.BackupError,
                "AGE_RESTORE_IDENTITY_MISSING",
            ):
                backup.preflight(args)


class FinalizeTests(unittest.TestCase):
    def finalize(self, args: argparse.Namespace) -> None:
        with mock.patch.object(
            backup,
            "now_utc",
            return_value=datetime(2026, 8, 20, 12, 1, tzinfo=timezone.utc),
        ):
            backup.finalize(args)

    def test_final_receipt_is_restorable_only_after_all_exact_checks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = build_finalize_fixture(root)
            self.finalize(args)
            receipt_path = root / backup.GEL_FILENAME
            receipt = backup.load_json(receipt_path)
            self.assertTrue(receipt["restorable"])
            self.assertEqual(receipt["ref"], "refs/heads/main")
            self.assertEqual(receipt["expectedSha"], SHA)
            self.assertEqual(receipt["runId"], RUN_ID)
            self.assertEqual(receipt["runAttempt"], RUN_ATTEMPT)
            self.assertEqual(
                receipt["databaseFingerprint"]["sourceSha256"],
                receipt["databaseFingerprint"]["restoredSha256"],
            )
            self.assertEqual(receipt["databaseFingerprint"]["postgresMajor"], 17)
            self.assertEqual(receipt["databaseFingerprint"]["type"], "targeted-logical")
            self.assertFalse(receipt["databaseFingerprint"]["managedAuthStorageDataIncluded"])
            self.assertEqual(receipt["ageEncryption"]["recipientSha256"], backup.sha256_text(RECIPIENT))
            self.assertTrue(receipt["offsiteRetention"]["immutable"])
            self.assertTrue(receipt["offsiteRetention"]["redownloadVerified"])
            self.assertEqual(receipt["rpo"]["observedSeconds"], 50)
            self.assertEqual(
                receipt["retention"]["githubArtifactExpirationAuthority"],
                "github-actions-artifact-api",
            )
            sidecar = (root / backup.GEL_HASH_FILENAME).read_text(encoding="ascii")
            self.assertEqual(sidecar, f"{backup.sha256_file(receipt_path)}  {backup.GEL_FILENAME}\n")

    def test_finalize_rejects_source_restore_fingerprint_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = build_finalize_fixture(root)
            capture_path = root / backup.CAPTURE_FILENAME
            capture = backup.load_json(capture_path)
            capture["restore"]["databaseFingerprintSha256"] = "f" * 64
            write_raw_json(capture_path, capture)
            with self.assertRaisesRegex(backup.BackupError, "CAPTURE_FINGERPRINT_MISMATCH"):
                self.finalize(args)

    def test_finalize_rejects_late_publication_rpo(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = build_finalize_fixture(root)
            release_path = Path(args.vault_release_json)
            release = backup.load_json(release_path)
            release["published_at"] = "2026-08-20T12:10:01Z"
            write_raw_json(release_path, release)
            with self.assertRaisesRegex(backup.BackupError, "RPO_TARGET_EXCEEDED"):
                self.finalize(args)

    def test_finalize_rejects_mutable_release_and_redownload_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = build_finalize_fixture(root)
            release_path = Path(args.vault_release_json)
            release = backup.load_json(release_path)
            release["immutable"] = False
            write_raw_json(release_path, release)
            with self.assertRaisesRegex(backup.BackupError, "VAULT_RELEASE_NOT_IMMUTABLE"):
                self.finalize(args)
            release["immutable"] = True
            write_raw_json(release_path, release)
            Path(args.vault_redownload_path).write_bytes(b"different")
            with self.assertRaisesRegex(backup.BackupError, "VAULT_REDOWNLOAD_DIGEST_DRIFT"):
                self.finalize(args)

    def test_intermediate_documents_never_claim_restorable(self) -> None:
        source = SCRIPT_PATH.read_text(encoding="utf-8")
        self.assertEqual(source.count('"restorable": True'), 1)
        self.assertIn('"restorable": False', source)


class WorkflowContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = WORKFLOW_PATH.read_text(encoding="utf-8")

    def test_workflow_is_manual_main_sha_and_environment_guarded(self) -> None:
        self.assertIn("workflow_dispatch:", self.source)
        self.assertNotIn("schedule:", self.source)
        self.assertIn("environment: staging", self.source)
        self.assertIn("group: closed-beta-demo-staging-operations", self.source)
        self.assertIn("--github-ref \"$GITHUB_REF\"", self.source)
        self.assertIn("KWABOR_STAGING_BACKUP_LIVE_ENABLED", self.source)
        self.assertIn("permissions:\n  actions: read\n  contents: read", self.source)

    def test_actions_and_supabase_cli_are_immutable_pins(self) -> None:
        self.assertIn("actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0", self.source)
        self.assertIn("actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a", self.source)
        self.assertIn("supabase/setup-cli@3c2f5e2ae34c34e428e8e206e2c4d21fa2d20fbf", self.source)
        self.assertIn("version: 2.111.0", self.source)

    def test_only_ciphertext_and_public_evidence_are_uploaded(self) -> None:
        self.assertIn('["age", "--encrypt"', SCRIPT_PATH.read_text(encoding="utf-8"))
        self.assertIn("*.tar.gz.age", self.source)
        self.assertIn("path: build/closed-beta-staging-database-backup-evidence", self.source)
        self.assertNotIn("path: $RUNNER_TEMP", self.source)
        self.assertNotIn("path: '*.sql'", self.source)
        self.assertNotIn("path: '*.tar.gz'", self.source)

    def test_offsite_release_is_draft_then_immutable_and_redownloaded(self) -> None:
        self.assertIn("/immutable-releases", self.source)
        self.assertIn("api_version=2026-03-10", self.source)
        self.assertIn("draft:true", self.source)
        self.assertIn("--data-binary '{\"draft\":false}'", self.source)
        self.assertIn("'.immutable // false'", self.source)
        self.assertIn("Accept: application/octet-stream", self.source)

    def test_qualified_artifact_name_matches_beta_004_contract(self) -> None:
        self.assertIn(
            "name: kwabor-gel-g5-staging-database-backup-${{ github.sha }}-${{ github.run_attempt }}",
            self.source,
        )
        self.assertIn("retention-days: 90", self.source)
        self.assertIn("steps.qualified_artifact.outputs.artifact-digest", self.source)
        self.assertIn('[[ "$ARTIFACT_DIGEST" =~ ^[0-9a-f]{64}$ ]]', self.source)

    def test_no_mutating_database_or_paid_pitr_path_exists(self) -> None:
        combined = self.source.lower() + SCRIPT_PATH.read_text(encoding="utf-8").lower()
        for forbidden in ("supabase db push", "supabase db reset", "point-in-time", "pitr"):
            self.assertNotIn(forbidden, combined)
        self.assertIn("default_transaction_read_only=on", combined)
        self.assertIn('["supabase", "db", "start"]', combined)

    def test_secrets_are_not_dispatch_inputs(self) -> None:
        inputs_block = self.source.split("    inputs:\n", maxsplit=1)[1].split(
            "\npermissions:",
            maxsplit=1,
        )[0]
        inputs = set(re.findall(r"(?m)^      ([a-z_]+):$", inputs_block))
        self.assertEqual(
            inputs,
            {"operation", "expected_sha", "validated_ci_run_id", "capture_confirmation"},
        )


def source_repository_document() -> dict[str, object]:
    return {
        "id": SOURCE_REPOSITORY_ID,
        "full_name": backup.EXPECTED_REPOSITORY,
        "default_branch": "main",
        "archived": False,
        "disabled": False,
    }


def ci_run_document(now: datetime) -> dict[str, object]:
    return {
        "id": CI_RUN_ID,
        "head_sha": SHA,
        "head_branch": "main",
        "event": "push",
        "path": backup.EXPECTED_CI_WORKFLOW,
        "status": "completed",
        "conclusion": "success",
        "repository": {"id": SOURCE_REPOSITORY_ID, "full_name": backup.EXPECTED_REPOSITORY},
        "run_attempt": 1,
        "created_at": timestamp(now - timedelta(minutes=5)),
        "updated_at": timestamp(now - timedelta(minutes=1)),
        "html_url": f"https://github.com/{backup.EXPECTED_REPOSITORY}/actions/runs/{CI_RUN_ID}",
    }


def environment_document() -> dict[str, object]:
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
        "updated_at": "2026-08-01T00:00:00Z",
    }


def vault_repository_document() -> dict[str, object]:
    return {
        "id": VAULT_REPOSITORY_ID,
        "full_name": "urbainmorel/KWABOR-backup-vault",
        "private": True,
        "archived": False,
        "disabled": False,
        "default_branch": "main",
    }


def escrow_receipt(now: datetime) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "type": "kwabor-age-key-escrow",
        "status": "active",
        "ageRecipientSha256": backup.sha256_text(RECIPIENT),
        "recoveryIdentityStoredOffsite": True,
        "custodyMode": "offline-two-person",
        "minimumCustodians": 2,
        "recoveryTestedAt": timestamp(now - timedelta(days=1)),
        "validUntil": timestamp(now + timedelta(days=365)),
    }


def escrow_release_document(receipt_sha: str, now: datetime) -> dict[str, object]:
    asset = {
        "id": 711,
        "name": "kwabor-age-key-escrow.json",
        "state": "uploaded",
        "digest": f"sha256:{receipt_sha}",
    }
    return {
        "id": 710,
        "tag_name": "kwabor-age-key-escrow-v1",
        "draft": False,
        "immutable": True,
        "published_at": timestamp(now - timedelta(days=2)),
        "assets": [asset],
    }


def build_preflight_fixture(root: Path, *, operation: str) -> tuple[argparse.Namespace, dict[str, str]]:
    now = datetime.now(timezone.utc).replace(microsecond=0)
    receipt = escrow_receipt(now)
    paths = {
        "source_repository_json": root / "source.json",
        "ci_run_json": root / "ci.json",
        "environment_json": root / "environment.json",
        "vault_repository_json": root / "vault.json",
        "vault_immutable_json": root / "immutable.json",
        "escrow_release_json": root / "escrow-release.json",
        "escrow_asset_json": root / "escrow-asset.json",
        "escrow_receipt_json": root / "escrow-receipt.json",
    }
    write_raw_json(paths["source_repository_json"], source_repository_document())
    write_raw_json(paths["ci_run_json"], ci_run_document(now))
    write_raw_json(paths["environment_json"], environment_document())
    write_raw_json(paths["vault_repository_json"], vault_repository_document())
    write_raw_json(paths["vault_immutable_json"], {"enabled": True, "enforced_by_owner": False})
    write_raw_json(paths["escrow_receipt_json"], receipt)
    receipt_sha = backup.sha256_file(paths["escrow_receipt_json"])
    release = escrow_release_document(receipt_sha, now)
    write_raw_json(paths["escrow_release_json"], release)
    write_raw_json(paths["escrow_asset_json"], release["assets"][0])
    args = argparse.Namespace(
        operation=operation,
        expected_sha=SHA,
        validated_ci_run_id=str(CI_RUN_ID),
        capture_confirmation=backup.CAPTURE_CONFIRMATION,
        head_sha=SHA,
        github_repository=backup.EXPECTED_REPOSITORY,
        github_event_name="workflow_dispatch",
        github_ref="refs/heads/main",
        github_sha=SHA,
        github_run_id=str(RUN_ID),
        github_run_attempt=str(RUN_ATTEMPT),
        github_workflow_ref=(
            f"{backup.EXPECTED_REPOSITORY}/{backup.EXPECTED_WORKFLOW}@refs/heads/main"
        ),
        evidence_directory=str(root / "evidence"),
        **{name: str(path) for name, path in paths.items()},
    )
    environment = {
        "KWABOR_ENVIRONMENT": "staging",
        "KWABOR_SUPABASE_URL": f"https://{STAGING_REF}.supabase.co",
        "KWABOR_SUPABASE_PROJECT_REF": STAGING_REF,
        "KWABOR_PRODUCTION_SUPABASE_PROJECT_REF": PRODUCTION_REF,
        "KWABOR_STAGING_PROJECT_REF_SHA256": backup.sha256_text(STAGING_REF),
        "KWABOR_STAGING_DATABASE_URL": (
            f"postgresql://postgres.{STAGING_REF}:encoded-password@"
            "aws-0-eu-west-1.pooler.supabase.com:5432/postgres"
        ),
        "KWABOR_STAGING_BACKUP_AGE_RECIPIENT": RECIPIENT,
        "KWABOR_STAGING_BACKUP_AGE_IDENTITY": IDENTITY,
        "KWABOR_STAGING_BACKUP_OFFSITE_RETENTION_DAYS": "180",
        "KWABOR_STAGING_BACKUP_MAX_RPO_SECONDS": "600",
        "KWABOR_STAGING_BACKUP_MAX_RTO_SECONDS": "1800",
        "KWABOR_STAGING_BACKUP_VAULT_REPOSITORY": "urbainmorel/KWABOR-backup-vault",
        "KWABOR_BACKUP_VAULT_TOKEN": "present-but-never-persisted",
        "KWABOR_STAGING_BACKUP_KEY_ESCROW_RELEASE_ID": "710",
        "KWABOR_STAGING_BACKUP_KEY_ESCROW_RELEASE_TAG": "kwabor-age-key-escrow-v1",
        "KWABOR_STAGING_BACKUP_KEY_ESCROW_ASSET_ID": "711",
        "KWABOR_STAGING_BACKUP_KEY_ESCROW_ASSET_SHA256": receipt_sha,
        "KWABOR_STAGING_BACKUP_LIVE_ENABLED": "false",
    }
    return args, environment


def build_finalize_fixture(root: Path) -> argparse.Namespace:
    source_fingerprint = "a" * 64
    migration_fingerprint = "b" * 64
    bundle_path = root / f"kwabor-staging-deadbeef-{source_fingerprint}.tar.gz.age"
    bundle_path.write_bytes(b"ciphertext-only")
    bundle_sha = backup.sha256_file(bundle_path)
    bundle_bytes = bundle_path.stat().st_size
    preflight = {
        "ageRecipientSha256": backup.sha256_text(RECIPIENT),
        "ci": {
            "conclusion": "success",
            "event": "push",
            "headBranch": "main",
            "headSha": SHA,
            "runAttempt": 1,
            "runId": CI_RUN_ID,
            "runUrl": f"https://github.com/{backup.EXPECTED_REPOSITORY}/actions/runs/{CI_RUN_ID}",
            "status": "completed",
            "workflowPath": backup.EXPECTED_CI_WORKFLOW,
        },
        "environmentEvidence": {"name": "staging", "preventSelfReview": True},
        "event": "workflow_dispatch",
        "expectedSha": SHA,
        "keyEscrow": {
            "assetId": 711,
            "assetSha256": "c" * 64,
            "custodyMode": "offline-two-person",
            "minimumCustodians": 2,
            "recoveryKeyStoredOffsite": True,
            "recoveryTestedAt": "2026-08-19T12:00:00Z",
            "releaseId": 710,
            "releaseTag": "kwabor-age-key-escrow-v1",
            "validUntil": "2027-08-20T12:00:00Z",
        },
        "maxRpoSeconds": 600,
        "maxRtoSeconds": 1800,
        "offsiteRetentionDays": 180,
        "ref": "refs/heads/main",
        "repository": backup.EXPECTED_REPOSITORY,
        "runAttempt": RUN_ATTEMPT,
        "runId": RUN_ID,
        "runUrl": f"https://github.com/{backup.EXPECTED_REPOSITORY}/actions/runs/{RUN_ID}",
        "source": {
            "defaultBranch": "main",
            "repository": backup.EXPECTED_REPOSITORY,
            "repositoryId": SOURCE_REPOSITORY_ID,
        },
        "target": {
            "environment": "staging",
            "projectRef": STAGING_REF,
            "projectRefSha256": backup.sha256_text(STAGING_REF),
        },
        "targetDigestSha256": "d" * 64,
        "validatedCiRunId": CI_RUN_ID,
        "vault": {
            "defaultBranch": "main",
            "immutableReleasesEnabled": True,
            "repository": "urbainmorel/KWABOR-backup-vault",
            "repositoryDigestSha256": "e" * 64,
            "repositoryId": VAULT_REPOSITORY_ID,
        },
    }
    preflight["targetDigestSha256"] = backup.sha256_bytes(
        backup.canonical_json_bytes(preflight["target"])
    )
    capture = {
        "captureCompletedAt": "2026-08-20T12:00:40Z",
        "captureDurationSeconds": 40,
        "captureStartedAt": "2026-08-20T12:00:00Z",
        "databaseFingerprintSha256": source_fingerprint,
        "encryptedBundle": {
            "bytes": bundle_bytes,
            "fileName": bundle_path.name,
            "sha256": bundle_sha,
        },
        "expectedSha": SHA,
        "migrationPrefixCount": 12,
        "migrationPrefixSha256": migration_fingerprint,
        "postgresMajor": 17,
        "qualificationStatus": "restore-verified-awaiting-offsite-immutability",
        "restore": {
            "databaseFingerprintSha256": source_fingerprint,
            "executionBoundary": "github-actions-disposable-supabase",
            "fingerprintMatch": True,
            "rtoSeconds": 120,
            "rtoTargetSeconds": 1800,
            "verified": True,
        },
        "runAttempt": RUN_ATTEMPT,
        "runId": RUN_ID,
    }
    write_raw_json(root / backup.PREFLIGHT_FILENAME, preflight)
    write_raw_json(root / backup.CAPTURE_FILENAME, capture)
    manifest_path = root / backup.VAULT_MANIFEST_FILENAME
    write_raw_json(manifest_path, backup._vault_manifest(preflight, capture))
    backup.write_sidecar(root / backup.VAULT_MANIFEST_HASH_FILENAME, manifest_path)
    release_tag = f"kwabor-staging-backup-{SHA}-{RUN_ID}-{RUN_ATTEMPT}"
    asset_definitions = (
        ("bundle-asset.json", 802, bundle_path),
        ("manifest-asset.json", 803, manifest_path),
        ("manifest-hash-asset.json", 804, root / backup.VAULT_MANIFEST_HASH_FILENAME),
    )
    asset_paths: list[Path] = []
    asset_documents: list[dict[str, object]] = []
    for json_name, asset_id, source in asset_definitions:
        asset_path = root / json_name
        asset_document = {
            "id": asset_id,
            "name": source.name,
            "state": "uploaded",
            "size": source.stat().st_size,
            "digest": f"sha256:{backup.sha256_file(source)}",
        }
        write_raw_json(asset_path, asset_document)
        asset_paths.append(asset_path)
        asset_documents.append(asset_document)
    release_path = root / "release.json"
    write_raw_json(
        release_path,
        {
            "id": 801,
            "tag_name": release_tag,
            "draft": False,
            "prerelease": False,
            "immutable": True,
            "target_commitish": "main",
            "published_at": "2026-08-20T12:00:50Z",
            "html_url": (
                "https://github.com/urbainmorel/KWABOR-backup-vault/releases/tag/"
                f"{release_tag}"
            ),
            "assets": asset_documents,
        },
    )
    redownload = root / "redownload.age"
    redownload.write_bytes(bundle_path.read_bytes())
    return argparse.Namespace(
        expected_sha=SHA,
        evidence_directory=str(root),
        vault_release_json=str(release_path),
        vault_bundle_asset_json=str(asset_paths[0]),
        vault_manifest_asset_json=str(asset_paths[1]),
        vault_manifest_hash_asset_json=str(asset_paths[2]),
        vault_redownload_path=str(redownload),
    )


if __name__ == "__main__":
    unittest.main()
