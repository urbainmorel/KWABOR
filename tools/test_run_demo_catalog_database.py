from __future__ import annotations

import importlib.util
import os
import unittest
from pathlib import Path
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
SPEC.loader.exec_module(database)


class DemoCatalogDatabaseTest(unittest.TestCase):
    def test_workflow_requires_a_protected_staging_environment(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")

        self.assertIn("environment: staging", workflow)
        self.assertIn(".can_admins_bypass == false", workflow)
        self.assertIn(".deployment_branch_policy.protected_branches == true", workflow)
        self.assertIn(".deployment_branch_policy.custom_branch_policies == false", workflow)
        self.assertIn(".prevent_self_review == true", workflow)

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
        demo = type("Completed", (), {"returncode": 0, "stdout": "60|60|180\n", "stderr": ""})()
        global_count = type("Completed", (), {"returncode": 0, "stdout": "60\n", "stderr": ""})()
        with patch.object(database, "_run_psql", side_effect=(demo, global_count)):
            database._verify("safe", expected_published=60)
        demo.stdout = "60|59|180\n"
        with patch.object(database, "_run_psql", return_value=demo), self.assertRaises(
            database.DatabaseOperationError
        ):
            database._verify("safe", expected_published=60)

    def test_verify_rejects_additional_published_staging_listings(self) -> None:
        demo = type("Completed", (), {"returncode": 0, "stdout": "60|60|180\n", "stderr": ""})()
        global_count = type("Completed", (), {"returncode": 0, "stdout": "64\n", "stderr": ""})()
        with patch.object(database, "_run_psql", side_effect=(demo, global_count)), self.assertRaises(
            database.DatabaseOperationError
        ):
            database._verify("safe", expected_published=60)

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
