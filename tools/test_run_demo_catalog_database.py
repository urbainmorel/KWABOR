from __future__ import annotations

import importlib.util
import os
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("run-demo-catalog-database.py")
WORKFLOW_PATH = (
    Path(__file__).resolve().parents[1]
    / ".github"
    / "workflows"
    / "closed-beta-demo-catalog.yml"
)
SPEC = importlib.util.spec_from_file_location("demo_catalog_database", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
database = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = database
SPEC.loader.exec_module(database)


class DemoCatalogDatabaseTest(unittest.TestCase):
    def test_workflow_requires_a_protected_staging_environment(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")

        self.assertIn("environment: staging", workflow)
        self.assertIn(".can_admins_bypass == false", workflow)
        self.assertIn(".deployment_branch_policy.protected_branches == true", workflow)
        self.assertIn(".deployment_branch_policy.custom_branch_policies == false", workflow)
        self.assertIn(".prevent_self_review == true", workflow)
        self.assertIn("validated_ci_run_id:", workflow)
        self.assertIn("validate-github-run", workflow)
        self.assertIn("validated-ci-provenance.json", workflow)
        self.assertIn("--ci-provenance CI-RUN-PROVENANCE.json", workflow)
        self.assertIn("--workflow-path .github/workflows/ci.yml", workflow)
        self.assertIn("--allowed-event push", workflow)
        self.assertIn("group: closed-beta-demo-staging-operations", workflow)
        self.assertIn("--result-json", workflow)
        self.assertNotIn("--allow-absent-for-storage-rollback", workflow)
        operation_index = workflow.rindex(
            'python3 -B tools/run-demo-catalog-database.py "${args[@]}"'
        )
        receipt_index = workflow.index("Write and verify sanitized database GEL receipt")
        self.assertLess(operation_index, receipt_index)
        self.assertIn("tools/closed-beta-gel.py write", workflow)
        self.assertIn("tools/closed-beta-gel.py verify", workflow)
        self.assertIn(
            "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a",
            workflow,
        )
        self.assertIn("retention-days: 90", workflow)

    def test_direct_and_pooler_urls_identify_exact_project(self) -> None:
        project_ref = "abcdefghijklmnopqrst"
        self.assertEqual(
            database._project_ref_from_database_url(
                f"postgresql://postgres:secret@db.{project_ref}.supabase.co:5432/postgres?sslmode=require"
            ),
            project_ref,
        )
        self.assertEqual(
            database._project_ref_from_database_url(
                f"postgresql://postgres.{project_ref}:secret@aws-0-eu-west-1.pooler.supabase.com:5432/postgres?sslmode=require"
            ),
            project_ref,
        )

    def test_database_url_rejects_non_tls_foreign_and_wrong_database(self) -> None:
        unsafe_urls = (
            "postgresql://postgres:secret@localhost:5432/postgres?sslmode=require",
            "postgresql://postgres:secret@db.abcdefghijklmnopqrst.supabase.co:5432/postgres",
            "postgresql://postgres:secret@db.abcdefghijklmnopqrst.supabase.co:5432/other?sslmode=require",
            "postgresql://postgres:secret@db.abcdefghijklmnopqrst.supabase.co:5432/postgres?sslmode=require&host=db.zyxwvutsrqponmlkjihg.supabase.co",
            "postgresql://postgres:secret@db.abcdefghijklmnopqrst.supabase.co:5432/postgres?sslmode=require&hostaddr=127.0.0.1",
            "postgresql://postgres:secret@db.abcdefghijklmnopqrst.supabase.co:5432/postgres?sslmode=require&service=unsafe",
            "postgresql://postgres:secret@db.abcdefghijklmnopqrst.supabase.co:5432/postgres?sslmode=require&sslmode=disable",
            "postgresql://postgres:secret@db.abcdefghijklmnopqrst.supabase.co:5432/postgres?sslmode=require#override",
        )
        for unsafe_url in unsafe_urls:
            with self.subTest(url=unsafe_url), self.assertRaises(database.DatabaseOperationError):
                database._project_ref_from_database_url(unsafe_url)

    def test_environment_requires_distinct_exact_staging_identity(self) -> None:
        staging_ref = "abcdefghijklmnopqrst"
        production_ref = "zyxwvutsrqponmlkjihg"
        safe = {
            "KWABOR_ENVIRONMENT": "staging",
            "KWABOR_SUPABASE_PROJECT_REF": staging_ref,
            "KWABOR_PRODUCTION_SUPABASE_PROJECT_REF": production_ref,
            "KWABOR_STAGING_DATABASE_URL": (
                f"postgresql://postgres:secret@db.{staging_ref}.supabase.co:5432/postgres?sslmode=require"
            ),
        }
        with patch.dict(os.environ, safe, clear=True):
            _, actual_ref = database._validated_environment()
        self.assertEqual(actual_ref, staging_ref)

        safe["KWABOR_PRODUCTION_SUPABASE_PROJECT_REF"] = staging_ref
        with patch.dict(os.environ, safe, clear=True), self.assertRaises(database.DatabaseOperationError):
            database._validated_environment()

    def test_verify_requires_exact_parent_published_and_media_counts(self) -> None:
        demo = type(
            "Completed",
            (),
            {"returncode": 0, "stdout": "60|60|60|0|180\n", "stderr": ""},
        )()
        global_count = type("Completed", (), {"returncode": 0, "stdout": "60\n", "stderr": ""})()
        with patch.object(database, "_run_psql", side_effect=(demo, global_count)):
            database._verify("safe", expected_published=60)
        demo.stdout = "60|60|59|0|180\n"
        with patch.object(database, "_run_psql", return_value=demo), self.assertRaises(
            database.DatabaseOperationError
        ):
            database._verify("safe", expected_published=60)

    def test_verify_rejects_additional_published_staging_listings(self) -> None:
        demo = type(
            "Completed",
            (),
            {"returncode": 0, "stdout": "60|60|60|0|180\n", "stderr": ""},
        )()
        global_count = type("Completed", (), {"returncode": 0, "stdout": "64\n", "stderr": ""})()
        with patch.object(database, "_run_psql", side_effect=(demo, global_count)), self.assertRaises(
            database.DatabaseOperationError
        ):
            database._verify("safe", expected_published=60)

    def test_zero_snapshot_is_an_exact_idempotent_rollback_state(self) -> None:
        empty = type(
            "Completed",
            (),
            {"returncode": 0, "stdout": "0|0|0|0|0\n", "stderr": ""},
        )()
        with patch.object(database, "_run_psql", return_value=empty):
            self.assertEqual(database._catalog_state("safe"), database.ABSENT_STATE)

        result = database._operation_result(
            "rollback",
            "already-absent",
            database.ABSENT_STATE,
            database.ABSENT_STATE,
        )
        self.assertEqual(result["counts"]["beforeTargetListings"], 0)
        self.assertEqual(result["counts"]["afterMedia"], 0)

    def test_absent_rollback_is_a_read_only_idempotent_success(self) -> None:
        arguments = SimpleNamespace(
            command="rollback",
            confirm_rollback=database.ROLLBACK_CONFIRMATION,
            allow_absent_for_storage_rollback=True,
            result_json=None,
        )
        with (
            patch.object(database, "parse_args", return_value=arguments),
            patch.object(
                database,
                "_validated_environment",
                return_value=("safe", "abcdefghijklmnopqrst"),
            ),
            patch.object(database, "_manifest_ids", return_value=["id"] * 60),
            patch.object(
                database,
                "_catalog_state",
                side_effect=(database.ABSENT_STATE, database.ABSENT_STATE),
            ),
            patch.object(database, "_execute") as execute,
        ):
            database.main()

        execute.assert_not_called()

    def test_standalone_absent_rollback_fails_closed(self) -> None:
        arguments = SimpleNamespace(
            command="rollback",
            confirm_rollback=database.ROLLBACK_CONFIRMATION,
            allow_absent_for_storage_rollback=False,
            result_json=None,
        )
        with (
            patch.object(database, "parse_args", return_value=arguments),
            patch.object(
                database,
                "_validated_environment",
                return_value=("safe", "abcdefghijklmnopqrst"),
            ),
            patch.object(database, "_manifest_ids", return_value=["id"] * 60),
            patch.object(database, "_catalog_state", return_value=database.ABSENT_STATE),
            patch.object(database, "_execute") as execute,
            self.assertRaisesRegex(database.DatabaseOperationError, "Standalone"),
        ):
            database.main()

        execute.assert_not_called()

    def test_partial_snapshot_is_neither_absent_nor_safe_to_rollback(self) -> None:
        partial = database.CatalogState(1, 0, 0, 0, 0)
        self.assertNotIn(
            partial,
            {database.ABSENT_STATE, database.ARCHIVED_STATE, database.PUBLISHED_STATE},
        )

    def test_non_published_non_archived_rows_are_not_treated_as_rollback_complete(self) -> None:
        pending = database.CatalogState(60, 60, 0, 0, 180)
        self.assertNotEqual(pending, database.ARCHIVED_STATE)
        self.assertNotIn(
            pending,
            {database.ABSENT_STATE, database.ARCHIVED_STATE, database.PUBLISHED_STATE},
        )

    def test_already_archived_rollback_reexecutes_identity_proof_sql(self) -> None:
        arguments = SimpleNamespace(
            command="rollback",
            confirm_rollback=database.ROLLBACK_CONFIRMATION,
            allow_absent_for_storage_rollback=False,
            result_json=None,
        )
        with (
            patch.object(database, "parse_args", return_value=arguments),
            patch.object(
                database,
                "_validated_environment",
                return_value=("safe", "abcdefghijklmnopqrst"),
            ),
            patch.object(database, "_manifest_ids", return_value=["id"] * 60),
            patch.object(database, "_catalog_state", return_value=database.ARCHIVED_STATE),
            patch.object(database, "_execute") as execute,
            patch.object(database, "_verify", return_value=database.ARCHIVED_STATE),
        ):
            database.main()

        execute.assert_called_once_with("safe", database.ROLLBACK_PATH)

    def test_psql_keeps_database_secret_out_of_process_arguments(self) -> None:
        database_url = "postgresql://postgres:secret@db.abcdefghijklmnopqrst.supabase.co/postgres"
        completed = type("Completed", (), {"returncode": 0, "stdout": "", "stderr": ""})()
        with patch.object(database.subprocess, "run", return_value=completed) as run:
            database._run_psql(database_url, "--command", "select 1")

        arguments = run.call_args.args[0]
        environment = run.call_args.kwargs["env"]
        self.assertNotIn(database_url, arguments)
        self.assertEqual(environment["PGDATABASE"], database_url)

    def test_execute_requires_the_exact_staging_storage_base_url(self) -> None:
        unsafe = {
            "KWABOR_SUPABASE_URL": "https://abcdefghijklmnopqrst.supabase.co",
            "KWABOR_DEMO_MEDIA_BASE_URL": "https://cdn.example.test/catalog/",
        }
        with patch.dict(os.environ, unsafe, clear=True), self.assertRaises(database.DatabaseOperationError):
            database._execute("safe", database.SEED_PATH)


if __name__ == "__main__":
    unittest.main()
