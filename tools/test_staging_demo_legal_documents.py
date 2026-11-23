"""Verify the scope and reviewed-baseline checks for the staging fixtures."""

import runpy
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNNER = runpy.run_path(str(ROOT / "tools/staging-demo-legal-documents.py"))


class StagingDemoLegalDocumentsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.commit = "a" * 40
        self.documents = RUNNER["documents_for"](self.commit)

    def test_source_is_pinned_to_the_canonical_commit(self) -> None:
        self.assertEqual([row["document_type"] for row in self.documents], ["privacy_policy", "terms"])
        for row in self.documents:
            self.assertIn(f"/urbainmorel/KWABOR/{self.commit}/docs/staging-demo/", row["content_url"])
            self.assertRegex(row["content_sha256"], r"^[0-9a-f]{64}$")
        with self.assertRaises(ValueError):
            RUNNER["documents_for"]("main")

    def test_plan_and_verify_use_read_only_transactions(self) -> None:
        for operation in ("plan", "verify"):
            sql = RUNNER["operation_sql"](operation, self.documents)
            self.assertIn("begin read only", sql)
            self.assertNotIn("insert into", sql)

    def test_apply_binds_the_reviewed_baseline_without_replacing_other_documents(self) -> None:
        sql = RUNNER["operation_sql"]("apply", self.documents, [])
        self.assertIn("lock table public.legal_documents", sql)
        self.assertIn("changed since plan", sql)
        self.assertIn("on conflict (document_type, locale, version) do nothing", sql)
        self.assertNotIn("delete from", sql)
        self.assertNotIn("update public.legal_documents", sql)
        with self.assertRaises(ValueError):
            RUNNER["operation_sql"]("apply", self.documents, [{"document_type": "terms"}])


if __name__ == "__main__":
    unittest.main()
