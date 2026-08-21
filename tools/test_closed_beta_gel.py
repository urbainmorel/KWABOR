from __future__ import annotations

from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


MODULE_PATH = Path(__file__).with_name("closed-beta-gel.py")
REPOSITORY_ROOT = MODULE_PATH.parents[1]
SPEC = importlib.util.spec_from_file_location("closed_beta_gel", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
gel = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = gel
SPEC.loader.exec_module(gel)

EXPECTED_SHA = "a" * 40
CI_RUN_ID = "100"
RUN_ID = "200"
RUN_ATTEMPT = "2"


def storage_result(operation: str) -> dict[str, object]:
    values = {
        "publish": ("published-and-verified", 180, 180, 0, 0),
        "verify": ("verified", 0, 180, 0, 0),
        "rollback": ("rolled-back-exact-manifest", 0, 175, 175, 5),
    }
    mode, created, verified, deleted, absent = values[operation]
    return {
        "counts": {
            "alreadyAbsentObjects": absent,
            "createdObjects": created,
            "deletedObjects": deleted,
            "manifestObjects": 180,
            "verifiedObjects": verified,
        },
        "kind": "demo-storage-operation",
        "mode": mode,
        "operation": operation,
        "outcome": "succeeded",
        "schemaVersion": 1,
    }


def database_result(operation: str, mode: str = "published-and-verified") -> dict[str, object]:
    states = {
        "published-and-verified": ((0, 0, 0, 0, 0), (60, 60, 60, 0, 180)),
        "verified": ((60, 60, 60, 0, 180), (60, 60, 60, 0, 180)),
        "archived-exact-catalog": ((60, 60, 60, 0, 180), (60, 60, 0, 60, 180)),
        "already-archived": ((60, 60, 0, 60, 180), (60, 60, 0, 60, 180)),
        "already-absent": ((0, 0, 0, 0, 0), (0, 0, 0, 0, 0)),
    }
    before, after = states[mode]
    return {
        "counts": {
            "afterArchivedListings": after[3],
            "afterMedia": after[4],
            "afterPublishedListings": after[2],
            "afterTaggedListings": after[1],
            "afterTargetListings": after[0],
            "beforeArchivedListings": before[3],
            "beforeMedia": before[4],
            "beforePublishedListings": before[2],
            "beforeTaggedListings": before[1],
            "beforeTargetListings": before[0],
        },
        "kind": "demo-catalog-database-operation",
        "mode": mode,
        "operation": operation,
        "outcome": "succeeded",
        "schemaVersion": 1,
    }


class ClosedBetaGelTest(unittest.TestCase):
    def test_staging_writers_share_exact_group_and_database_uses_fresh_empty_v2(self) -> None:
        workflow_paths = (
            ".github/workflows/closed-beta-demo-storage.yml",
            ".github/workflows/closed-beta-demo-catalog.yml",
            ".github/workflows/closed-beta-staging-database.yml",
        )
        for workflow_path in workflow_paths:
            workflow = (REPOSITORY_ROOT / workflow_path).read_text(encoding="utf-8")
            groups = [
                line.strip()
                for line in workflow.splitlines()
                if line.strip().startswith("group:")
            ]
            with self.subTest(workflow_path=workflow_path):
                self.assertEqual(groups, ["group: closed-beta-demo-staging-operations"])

        database_runner = (
            REPOSITORY_ROOT / "tools" / "closed-beta-staging-database.py"
        ).read_text(encoding="utf-8")
        self.assertIn(
            'FRESH_EMPTY_PROOF_POLICY = "zero-objects-and-types-public-app-private-v2"',
            database_runner,
        )
        self.assertIn('"application_type_count": "applicationTypeCount"', database_runner)

    def common(self, directory: Path, workflow: str, operation: str) -> dict[str, object]:
        self.write_json(
            directory / "CI-RUN-PROVENANCE.json",
            {
                "actor": "ci-owner",
                "conclusion": "success",
                "event": "push",
                "headBranch": "main",
                "headSha": EXPECTED_SHA,
                "kind": "github-actions-run-provenance",
                "repository": gel.EXPECTED_REPOSITORY,
                "runAttempt": 3,
                "runClassification": "rerun",
                "runId": int(CI_RUN_ID),
                "runUrl": gel.github_run_url(CI_RUN_ID),
                "schemaVersion": 1,
                "status": "completed",
                "triggeringActor": "ci-rerun-owner",
                "workflow": ".github/workflows/ci.yml",
            },
        )
        return {
            "directory": directory,
            "workflow": workflow,
            "operation": operation,
            "expected_sha": EXPECTED_SHA,
            "validated_ci_run_id": CI_RUN_ID,
            "repository": gel.EXPECTED_REPOSITORY,
            "actor": "release-owner",
            "ci_provenance_filename": "CI-RUN-PROVENANCE.json",
            "run_id": RUN_ID,
            "run_attempt": RUN_ATTEMPT,
            "run_url": gel.github_run_url(RUN_ID),
            "created_at": datetime(2026, 8, 20, 10, 0, tzinfo=timezone.utc),
        }

    def write_json(self, path: Path, document: dict[str, object]) -> None:
        path.write_text(json.dumps(document), encoding="utf-8")

    def test_storage_receipt_binds_real_result_and_detects_tampering(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            (directory / "manifest.json").write_bytes(b"manifest")
            self.write_json(directory / "STORAGE-RESULT.json", storage_result("publish"))
            receipt = gel.build_receipt(
                **self.common(directory, gel.STORAGE_WORKFLOW, "publish"),
                artifacts={"manifest": "manifest.json"},
                digests={"stagingProjectRefSha256": "b" * 64},
                counters={},
                details={},
                result_filename="STORAGE-RESULT.json",
            )
            gel.verify_receipt(
                receipt,
                directory=directory,
                workflow=gel.STORAGE_WORKFLOW,
                operation="publish",
                expected_sha=EXPECTED_SHA,
                validated_ci_run_id=CI_RUN_ID,
                run_id=RUN_ID,
                run_attempt=RUN_ATTEMPT,
            )
            self.assertEqual(receipt["counters"]["verifiedObjects"], 180)
            self.assertEqual(receipt["taskId"], "B6.03")
            self.assertEqual(receipt["gateDecision"], "not-closed-by-receipt")
            self.assertEqual(receipt["validatedCiProvenance"]["runAttempt"], 3)
            self.assertEqual(
                receipt["validatedCiProvenance"]["triggeringActor"],
                "ci-rerun-owner",
            )
            tampered_provenance = json.loads(json.dumps(receipt))
            tampered_provenance["validatedCiProvenance"]["runAttempt"] = 4
            with self.assertRaisesRegex(gel.GelError, "frozen CI provenance mismatch"):
                gel.verify_receipt(
                    tampered_provenance,
                    directory=directory,
                    workflow=gel.STORAGE_WORKFLOW,
                    operation="publish",
                    expected_sha=EXPECTED_SHA,
                    validated_ci_run_id=CI_RUN_ID,
                    run_id=RUN_ID,
                    run_attempt=RUN_ATTEMPT,
                )
            (directory / "manifest.json").write_bytes(b"tampered")
            with self.assertRaisesRegex(gel.GelError, "digest or size mismatch"):
                gel.verify_receipt(
                    receipt,
                    directory=directory,
                    workflow=gel.STORAGE_WORKFLOW,
                    operation="publish",
                    expected_sha=EXPECTED_SHA,
                    validated_ci_run_id=CI_RUN_ID,
                    run_id=RUN_ID,
                    run_attempt=RUN_ATTEMPT,
                )

    def test_storage_rollback_includes_idempotent_database_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            (directory / "manifest.json").write_bytes(b"manifest")
            self.write_json(directory / "STORAGE-RESULT.json", storage_result("rollback"))
            self.write_json(
                directory / "DATABASE-RESULT.json",
                database_result("rollback", "already-absent"),
            )
            receipt = gel.build_receipt(
                **self.common(directory, gel.STORAGE_WORKFLOW, "rollback"),
                artifacts={"manifest": "manifest.json"},
                digests={"stagingProjectRefSha256": "b" * 64},
                counters={},
                details={},
                result_filename="STORAGE-RESULT.json",
                related_result_filename="DATABASE-RESULT.json",
            )
            self.assertEqual(receipt["details"]["databaseOperationMode"], "already-absent")
            self.assertEqual(receipt["counters"]["databaseAfterTargetListings"], 0)

    def test_database_result_rejects_partial_or_mislabeled_state(self) -> None:
        partial = database_result("rollback", "archived-exact-catalog")
        partial["counts"]["beforeTargetListings"] = 59  # type: ignore[index]
        with self.assertRaisesRegex(gel.GelError, "rollback state"):
            gel._validate_database_result(partial, "rollback")
        with self.assertRaisesRegex(gel.GelError, "operation mismatch"):
            gel._validate_database_result(database_result("verify", "verified"), "publish")
        with self.assertRaisesRegex(gel.GelError, "coordinated Storage cleanup"):
            gel._validate_database_result(
                database_result("rollback", "already-absent"),
                "rollback",
            )

    def test_every_storage_and_database_operation_has_a_valid_result_contract(self) -> None:
        for operation in ("publish", "verify", "rollback"):
            with self.subTest(kind="storage", operation=operation):
                counters, details = gel._validate_storage_result(
                    storage_result(operation),
                    operation,
                )
                self.assertEqual(counters["manifestObjects"], 180)
                self.assertTrue(details["operationMode"])
        database_modes = {
            "publish": "published-and-verified",
            "verify": "verified",
            "rollback": "archived-exact-catalog",
        }
        for operation, mode in database_modes.items():
            with self.subTest(kind="database", operation=operation):
                counters, details = gel._validate_database_result(
                    database_result(operation, mode),
                    operation,
                )
                self.assertIn("afterArchivedListings", counters)
                self.assertEqual(details["operationMode"], mode)

    def test_android_build_and_play_profiles_are_exact(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            filenames = {
                "aab": "kwabor.aab",
                "checksums": "KWABOR-SHA256SUMS.txt",
                "mapping": "mapping.txt",
                "provenance": "KWABOR-ANDROID-PROVENANCE.json",
            }
            for label, filename in filenames.items():
                (directory / filename).write_bytes(label.encode("ascii"))
            build = gel.build_receipt(
                **self.common(directory, gel.ANDROID_WORKFLOW, "build-aab"),
                artifacts=dict(filenames),
                digests={
                    "firebaseProjectIdSha256": "1" * 64,
                    "stagingProjectRefSha256": "2" * 64,
                    "uploadCertificateSha256": "3" * 64,
                },
                counters={"versionCode": "42"},
                details={
                    "applicationId": "com.kwabor.android",
                    "variant": "staging",
                    "versionName": "1.0.0-rc.1",
                },
            )
            self.assertEqual(build["taskId"], "B7.02")
            duplicate = json.loads(json.dumps(build))
            duplicate["artifacts"]["mapping"] = duplicate["artifacts"]["checksums"]
            with self.assertRaisesRegex(gel.GelError, "unique filenames"):
                gel.verify_receipt(
                    duplicate,
                    directory=directory,
                    workflow=gel.ANDROID_WORKFLOW,
                    operation="build-aab",
                    expected_sha=EXPECTED_SHA,
                    validated_ci_run_id=CI_RUN_ID,
                    run_id=RUN_ID,
                    run_attempt=RUN_ATTEMPT,
                )
            self.write_json(directory / "GEL-G6-ANDROID-AAB.json", build)
            play_artifacts = {
                **filenames,
                "buildEvidence": "GEL-G6-ANDROID-AAB.json",
            }
            play = gel.build_receipt(
                **self.common(directory, gel.ANDROID_WORKFLOW, "publish-play-internal"),
                artifacts=play_artifacts,
                digests={
                    "sourceArtifactSha256": "4" * 64,
                    "uploadCertificateSha256": "3" * 64,
                },
                counters={"versionCode": "42"},
                details={
                    "gateDecision": "not-closed-by-receipt",
                    "packageName": "com.kwabor.android",
                    "publicationOutcome": "upload-action-succeeded",
                    "requestedStatus": "completed",
                    "sourceArtifactName": "kwabor-android-staging-1.0.0-rc.1-42",
                    "track": "internal",
                    "versionName": "1.0.0-rc.1",
                },
            )
            self.assertEqual(play["taskId"], "B7.04")
            self.assertEqual(play["details"]["gateDecision"], "not-closed-by-receipt")
            self.assertEqual(
                play["validatedCiProvenance"]["runClassification"],
                "rerun",
            )
            bad_details = dict(play["details"])
            bad_details["track"] = "production"
            with self.assertRaisesRegex(gel.GelError, "not internal"):
                gel._validate_android_values("publish-play-internal", play["counters"], bad_details)
            closed_gate_details = dict(play["details"])
            closed_gate_details["gateDecision"] = "closed"
            with self.assertRaisesRegex(gel.GelError, "must not close G6"):
                gel._validate_android_values(
                    "publish-play-internal",
                    play["counters"],
                    closed_gate_details,
                )

    def test_sensitive_keys_and_suspicious_values_are_refused(self) -> None:
        documents = (
            {"database_url": "safe-looking"},
            {"value": "postgresql://postgres:password@example.test/postgres"},
            {"value": "-----BEGIN PRIVATE KEY-----"},
            {"value": "eyJabcdefghijk.abcdefghijklmnop.abcdefghijklmnop"},
            {"value": "A" * 100},
            {"value": "https://abcdefghijklmnopqrst.supabase.co"},
        )
        for document in documents:
            with self.subTest(document=document), self.assertRaises(gel.GelError):
                gel._assert_safe_document(document)

    def test_github_run_must_be_successful_push_on_main_exact_head(self) -> None:
        document = {
            "actor": {"login": "ci-owner"},
            "id": 100,
            "repository": {"full_name": gel.EXPECTED_REPOSITORY},
            "head_sha": EXPECTED_SHA,
            "head_branch": "main",
            "status": "completed",
            "conclusion": "success",
            "path": ".github/workflows/ci.yml@refs/heads/main",
            "event": "push",
            "html_url": gel.github_run_url(CI_RUN_ID),
            "run_attempt": 2,
            "triggering_actor": {"login": "ci-rerun-owner"},
        }
        provenance = gel.validate_github_run(
            document,
            expected_run_id=CI_RUN_ID,
            expected_sha=EXPECTED_SHA,
            expected_workflow_path=".github/workflows/ci.yml",
            allowed_events=("push",),
        )
        self.assertEqual(provenance["runAttempt"], 2)
        self.assertEqual(provenance["runClassification"], "rerun")
        self.assertEqual(provenance["actor"], "ci-owner")
        self.assertEqual(provenance["triggeringActor"], "ci-rerun-owner")
        document["event"] = "workflow_dispatch"
        with self.assertRaisesRegex(gel.GelError, "event"):
            gel.validate_github_run(
                document,
                expected_run_id=CI_RUN_ID,
                expected_sha=EXPECTED_SHA,
                expected_workflow_path=".github/workflows/ci.yml",
                allowed_events=("push",),
            )


if __name__ == "__main__":
    unittest.main()
