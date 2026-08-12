from __future__ import annotations

import importlib.util
import hashlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("publish-demo-catalog-storage.py")
SQL_GUARDRAIL_PATH = (
    Path(__file__).resolve().parents[1]
    / "supabase"
    / "tests"
    / "closed_beta_demo_storage_guardrails_test.sql"
)
WORKFLOW_PATH = (
    Path(__file__).resolve().parents[1]
    / ".github"
    / "workflows"
    / "closed-beta-demo-storage.yml"
)
SPEC = importlib.util.spec_from_file_location("publish_demo_catalog_storage", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
storage = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = storage
SPEC.loader.exec_module(storage)


class FakeStorageClient:
    def __init__(self, *, existing: set[str] | None = None) -> None:
        self.existing = set(existing or set())
        self.calls: list[tuple[str, object]] = []
        self.download_failure_for: str | None = None
        self.download_metadata_failure_for: str | None = None
        self.upload_uncertain_for: str | None = None
        self.upload_uncertain_commits = False

    def get_bucket(self, bucket: str) -> dict[str, object] | None:
        self.calls.append(("get_bucket", bucket))
        return {
            "id": bucket,
            "name": bucket,
            "public": True,
            "file_size_limit": storage.EXPECTED_FILE_SIZE_LIMIT,
            "allowed_mime_types": [storage.EXPECTED_CONTENT_TYPE],
        }

    def create_bucket(self, bucket: str) -> None:
        self.calls.append(("create_bucket", bucket))

    def object_exists(self, bucket: str, path: str) -> bool:
        self.calls.append(("exists", path))
        return path in self.existing

    def upload(self, bucket: str, media: object, cache_control: str) -> None:
        path = media.path
        self.calls.append(("upload", path))
        if path == self.upload_uncertain_for:
            if self.upload_uncertain_commits:
                self.existing.add(path)
            raise storage.StorageRequestUncertain("simulated lost POST response")
        self.existing.add(path)

    def download_public(self, bucket: str, path: str) -> object:
        self.calls.append(("download", path))
        if path == self.download_failure_for:
            payload = b"corrupt"
        else:
            if path not in self.existing:
                raise storage.PublicationError("Storage GET failed with HTTP 404: missing")
            payload = self.payloads[path]
        cache_control = storage.EXPECTED_CACHE_CONTROL
        if path == self.download_metadata_failure_for:
            cache_control = "public,max-age=60"
        return storage.DownloadedObject(
            body=payload,
            content_type=storage.EXPECTED_CONTENT_TYPE,
            cache_control=cache_control,
            content_length=len(payload),
        )

    def remove_exact(self, bucket: str, paths: list[str]) -> tuple[str, ...]:
        self.calls.append(("remove", tuple(paths)))
        self.existing.difference_update(paths)
        return tuple(paths)


class PublishDemoCatalogStorageTest(unittest.TestCase):
    def test_workflow_runs_local_gates_before_supabase_network_access(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        local_gate_index = workflow.index("python3 -m py_compile")
        credential_guard_index = workflow.index(
            "Guard staging authority and credentials"
        )
        publication_index = workflow.index(
            "python3 tools/publish-demo-catalog-storage.py publish"
        )

        self.assertLess(local_gate_index, credential_guard_index)
        self.assertLess(credential_guard_index, publication_index)
        self.assertIn("environment: staging", workflow)
        self.assertIn('github.repository }}" != "urbainmorel/KWABOR"', workflow)
        self.assertIn("KWABOR_STAGING_PROJECT_REF_SHA256", workflow)
        self.assertIn(".can_admins_bypass == false", workflow)
        self.assertIn(".deployment_branch_policy.protected_branches == true", workflow)
        self.assertIn(".deployment_branch_policy.custom_branch_policies == false", workflow)
        self.assertIn(".prevent_self_review == true", workflow)

    def test_storage_pgtap_plan_matches_all_top_level_assertions(self) -> None:
        sql = SQL_GUARDRAIL_PATH.read_text(encoding="utf-8")
        top_level_ok = sum(line == "select ok(" for line in sql.splitlines())
        top_level_is = sum(line == "select is(" for line in sql.splitlines())

        self.assertIn("select plan(15);", sql)
        self.assertEqual(top_level_ok, 5)
        self.assertEqual(top_level_is, 10)
        self.assertEqual(top_level_ok + top_level_is, 15)
        self.assertEqual(sql.count("select * from finish();"), 1)
        self.assertNotIn("closed_beta_statement_fails_as", sql)
        self.assertEqual(sql.count("  tests.closed_beta_statement_sqlstate_as("), 2)
        self.assertEqual(sql.count("  tests.closed_beta_affected_rows_as("), 2)
        self.assertEqual(sql.count("  tests.closed_beta_statement_succeeds_as("), 2)
        self.assertEqual(sql.count("  '42501',"), 2)
        self.assertEqual(sql.count("  0::bigint,"), 2)
        self.assertEqual(sql.count("and policy_record.cmd in ('ALL', 'DELETE')"), 2)
        self.assertEqual(sql.count("tests.closed_beta_policy_role_applies_to("), 7)
        self.assertIn("pg_catalog.pg_has_role(client_role::text, policy_role::text, 'USAGE')", sql)
        self.assertNotRegex(sql, r"(?is)\bdelete\s+from\s+storage\.objects\b")
        self.assertIn("role_record.rolbypassrls", sql)

    def test_staging_target_requires_matching_distinct_project_ref(self) -> None:
        staging_ref = "abcdefghijklmnopqrst"
        staging_ref_digest = hashlib.sha256(staging_ref.encode("ascii")).hexdigest()
        with patch.dict(
            os.environ,
            {
                "KWABOR_ENVIRONMENT": "staging",
                "KWABOR_PRODUCTION_SUPABASE_PROJECT_REF": "zyxwvutsrqponmlkjihg",
                "KWABOR_STAGING_PROJECT_REF_SHA256": staging_ref_digest,
            },
            clear=True,
        ):
            self.assertEqual(
                storage._validate_staging_target(
                    f"https://{staging_ref}.supabase.co",
                    staging_ref,
                ),
                f"https://{staging_ref}.supabase.co",
            )
            for unsafe_url in (
                f"http://{staging_ref}.supabase.co",
                f"https://{staging_ref}.supabase.co:443",
                f"https://user@{staging_ref}.supabase.co",
                f"https://{staging_ref}.supabase.co/storage/v1",
            ):
                with self.subTest(unsafe_url=unsafe_url):
                    with self.assertRaises(storage.PublicationError):
                        storage._validate_staging_target(unsafe_url, staging_ref)

        unsafe_environments = (
            {
                "KWABOR_ENVIRONMENT": "production",
                "KWABOR_PRODUCTION_SUPABASE_PROJECT_REF": "zyxwvutsrqponmlkjihg",
                "KWABOR_STAGING_PROJECT_REF_SHA256": staging_ref_digest,
            },
            {
                "KWABOR_ENVIRONMENT": "staging",
                "KWABOR_PRODUCTION_SUPABASE_PROJECT_REF": staging_ref,
                "KWABOR_STAGING_PROJECT_REF_SHA256": staging_ref_digest,
            },
            {
                "KWABOR_ENVIRONMENT": "staging",
                "KWABOR_PRODUCTION_SUPABASE_PROJECT_REF": "zyxwvutsrqponmlkjihg",
            },
            {
                "KWABOR_ENVIRONMENT": "staging",
                "KWABOR_PRODUCTION_SUPABASE_PROJECT_REF": "zyxwvutsrqponmlkjihg",
                "KWABOR_STAGING_PROJECT_REF_SHA256": "0" * 64,
            },
        )
        for environment in unsafe_environments:
            with self.subTest(environment=environment):
                with patch.dict(os.environ, environment, clear=True):
                    with self.assertRaises(storage.PublicationError):
                        storage._validate_staging_target(
                            f"https://{staging_ref}.supabase.co",
                            staging_ref,
                        )

    def test_manifest_contract_rejects_wrong_environment_and_duplicate_paths(self) -> None:
        media_path = storage.MEDIA_ROOT / "v1/example/file.jpg"
        manifest = {
            "environment": "production",
            "mediaRightsApproval": {
                "status": "approved-by-product-owner",
                "approvedBy": "Kwabor product owner",
                "approvedAt": "2026-08-12",
                "scope": "closed-beta-demo-only",
            },
            "counts": {"media": storage.EXPECTED_MEDIA_COUNT},
            "storage": {
                "bucket": storage.EXPECTED_BUCKET,
                "publicRead": True,
                "clientWrites": False,
                "contentType": storage.EXPECTED_CONTENT_TYPE,
                "cacheControl": storage.EXPECTED_CACHE_CONTROL,
                "upsert": False,
            },
            "listings": [],
        }
        with tempfile.TemporaryDirectory() as temp_directory:
            manifest_path = Path(temp_directory) / "manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(storage.PublicationError, "not staging-only"):
                storage._load_contract(manifest_path)

        self.assertFalse(media_path.exists())

    def test_manifest_contract_rejects_pending_media_rights(self) -> None:
        manifest = {
            "environment": "staging-only",
            "mediaRightsApproval": {
                "status": "pending-product-owner-confirmation",
                "approvedBy": "Kwabor product owner",
                "approvedAt": "2026-08-12",
                "scope": "closed-beta-demo-only",
            },
            "counts": {"media": storage.EXPECTED_MEDIA_COUNT},
            "storage": {},
            "listings": [],
        }
        with tempfile.TemporaryDirectory() as temp_directory:
            manifest_path = Path(temp_directory) / "manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(storage.PublicationError, "rights are not approved"):
                storage._load_contract(manifest_path)

    def test_publish_refuses_any_existing_object_before_upload(self) -> None:
        with tempfile.TemporaryDirectory() as temp_directory:
            payload = b"immutable"
            local_path = Path(temp_directory) / "asset.jpg"
            local_path.write_bytes(payload)
            media = storage.MediaObject(
                "v1/example/asset.jpg",
                hashlib.sha256(payload).hexdigest(),
                len(payload),
                local_path,
            )
            contract = storage.CatalogContract(
                storage.EXPECTED_BUCKET,
                storage.EXPECTED_CACHE_CONTROL,
                (media,),
            )
            client = FakeStorageClient(existing={media.path})
            client.payloads = {media.path: payload}

            with self.assertRaisesRegex(storage.PublicationError, "Refusing to overwrite"):
                storage._publish(client, contract)
            self.assertFalse(any(call[0] == "upload" for call in client.calls))

    def test_publish_verifies_each_object_immediately_after_upload(self) -> None:
        with tempfile.TemporaryDirectory() as temp_directory:
            payload = b"verified"
            local_path = Path(temp_directory) / "asset.jpg"
            local_path.write_bytes(payload)
            media = storage.MediaObject(
                "v1/example/asset.jpg",
                hashlib.sha256(payload).hexdigest(),
                len(payload),
                local_path,
            )
            contract = storage.CatalogContract(
                storage.EXPECTED_BUCKET,
                storage.EXPECTED_CACHE_CONTROL,
                (media,),
            )
            client = FakeStorageClient()
            client.payloads = {media.path: payload}

            storage._publish(client, contract)
            actions = [call[0] for call in client.calls]
            self.assertLess(actions.index("upload"), actions.index("download"))

    def test_publish_compensates_exact_created_paths_after_verification_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temp_directory:
            payload = b"verified"
            local_path = Path(temp_directory) / "asset.jpg"
            local_path.write_bytes(payload)
            media = storage.MediaObject(
                "v1/example/asset.jpg",
                hashlib.sha256(payload).hexdigest(),
                len(payload),
                local_path,
            )
            contract = storage.CatalogContract(
                storage.EXPECTED_BUCKET,
                storage.EXPECTED_CACHE_CONTROL,
                (media,),
            )
            client = FakeStorageClient()
            client.payloads = {media.path: payload}
            client.download_failure_for = media.path

            with self.assertRaisesRegex(storage.PublicationError, "Downloaded size mismatch"):
                storage._publish(client, contract)
            self.assertNotIn(media.path, client.existing)
            self.assertIn(("remove", (media.path,)), client.calls)

    def test_lost_upload_response_reconciles_head_and_hash_then_rolls_back(self) -> None:
        with tempfile.TemporaryDirectory() as temp_directory:
            payload = b"committed-before-response-loss"
            local_path = Path(temp_directory) / "asset.jpg"
            local_path.write_bytes(payload)
            media = storage.MediaObject(
                "v1/example/asset.jpg",
                hashlib.sha256(payload).hexdigest(),
                len(payload),
                local_path,
            )
            contract = storage.CatalogContract(
                storage.EXPECTED_BUCKET,
                storage.EXPECTED_CACHE_CONTROL,
                (media,),
            )
            client = FakeStorageClient()
            client.payloads = {media.path: payload}
            client.upload_uncertain_for = media.path
            client.upload_uncertain_commits = True

            with self.assertRaisesRegex(storage.PublicationError, "response was lost"):
                storage._publish(client, contract)

            actions = [call[0] for call in client.calls]
            upload_index = actions.index("upload")
            reconciliation_exists_index = actions.index("exists", upload_index + 1)
            self.assertLess(upload_index, reconciliation_exists_index)
            self.assertLess(reconciliation_exists_index, actions.index("download"))
            self.assertLess(actions.index("download"), actions.index("remove"))
            self.assertNotIn(media.path, client.existing)
            self.assertIn(("remove", (media.path,)), client.calls)

    def test_lost_upload_response_without_commit_rolls_back_only_known_objects(self) -> None:
        with tempfile.TemporaryDirectory() as temp_directory:
            payload_one = b"first"
            payload_two = b"second"
            first_path = Path(temp_directory) / "first.jpg"
            second_path = Path(temp_directory) / "second.jpg"
            first_path.write_bytes(payload_one)
            second_path.write_bytes(payload_two)
            media_one = storage.MediaObject(
                "v1/example/first.jpg",
                hashlib.sha256(payload_one).hexdigest(),
                len(payload_one),
                first_path,
            )
            media_two = storage.MediaObject(
                "v1/example/second.jpg",
                hashlib.sha256(payload_two).hexdigest(),
                len(payload_two),
                second_path,
            )
            contract = storage.CatalogContract(
                storage.EXPECTED_BUCKET,
                storage.EXPECTED_CACHE_CONTROL,
                (media_one, media_two),
            )
            client = FakeStorageClient()
            client.payloads = {
                media_one.path: payload_one,
                media_two.path: payload_two,
            }
            client.upload_uncertain_for = media_two.path

            with self.assertRaisesRegex(storage.PublicationError, "response was lost"):
                storage._publish(client, contract)

            self.assertEqual(client.existing, set())
            self.assertIn(("remove", (media_one.path,)), client.calls)
            self.assertNotIn(("remove", (media_two.path,)), client.calls)

    def test_lost_upload_response_never_deletes_an_unmatched_remote_digest(self) -> None:
        with tempfile.TemporaryDirectory() as temp_directory:
            expected_payload = b"expected"
            remote_payload = b"conflict"
            local_path = Path(temp_directory) / "asset.jpg"
            local_path.write_bytes(expected_payload)
            media = storage.MediaObject(
                "v1/example/asset.jpg",
                hashlib.sha256(expected_payload).hexdigest(),
                len(expected_payload),
                local_path,
            )
            contract = storage.CatalogContract(
                storage.EXPECTED_BUCKET,
                storage.EXPECTED_CACHE_CONTROL,
                (media,),
            )
            client = FakeStorageClient()
            client.payloads = {media.path: remote_payload}
            client.upload_uncertain_for = media.path
            client.upload_uncertain_commits = True

            with self.assertRaisesRegex(storage.PublicationError, "SHA-256 mismatch"):
                storage._publish(client, contract)

            self.assertIn(media.path, client.existing)
            self.assertFalse(any(call[0] == "remove" for call in client.calls))

    def test_verified_payload_with_wrong_cache_metadata_is_rolled_back(self) -> None:
        with tempfile.TemporaryDirectory() as temp_directory:
            payload = b"right-bytes-wrong-metadata"
            local_path = Path(temp_directory) / "asset.jpg"
            local_path.write_bytes(payload)
            media = storage.MediaObject(
                "v1/example/asset.jpg",
                hashlib.sha256(payload).hexdigest(),
                len(payload),
                local_path,
            )
            contract = storage.CatalogContract(
                storage.EXPECTED_BUCKET,
                storage.EXPECTED_CACHE_CONTROL,
                (media,),
            )
            client = FakeStorageClient()
            client.payloads = {media.path: payload}
            client.download_metadata_failure_for = media.path

            with self.assertRaisesRegex(storage.PublicationError, "Cache-Control mismatch"):
                storage._publish(client, contract)

            self.assertNotIn(media.path, client.existing)
            self.assertIn(("remove", (media.path,)), client.calls)

    def test_compensating_rollback_uses_reverse_creation_order(self) -> None:
        with tempfile.TemporaryDirectory() as temp_directory:
            payload_one = b"first"
            payload_two = b"second"
            first_path = Path(temp_directory) / "first.jpg"
            second_path = Path(temp_directory) / "second.jpg"
            first_path.write_bytes(payload_one)
            second_path.write_bytes(payload_two)
            media_one = storage.MediaObject(
                "v1/example/first.jpg",
                hashlib.sha256(payload_one).hexdigest(),
                len(payload_one),
                first_path,
            )
            media_two = storage.MediaObject(
                "v1/example/second.jpg",
                hashlib.sha256(payload_two).hexdigest(),
                len(payload_two),
                second_path,
            )
            contract = storage.CatalogContract(
                storage.EXPECTED_BUCKET,
                storage.EXPECTED_CACHE_CONTROL,
                (media_one, media_two),
            )
            client = FakeStorageClient()
            client.payloads = {
                media_one.path: payload_one,
                media_two.path: payload_two,
            }
            client.download_metadata_failure_for = media_two.path

            with self.assertRaisesRegex(storage.PublicationError, "Cache-Control mismatch"):
                storage._publish(client, contract)

            self.assertIn(
                ("remove", (media_two.path, media_one.path)),
                client.calls,
            )

    def test_rollback_requires_confirmation_and_deletes_exact_paths_only(self) -> None:
        payload = b"x"
        media = storage.MediaObject(
            "v1/example/asset.jpg",
            hashlib.sha256(payload).hexdigest(),
            len(payload),
            Path("unused"),
        )
        contract = storage.CatalogContract(
            storage.EXPECTED_BUCKET,
            storage.EXPECTED_CACHE_CONTROL,
            (media,),
        )
        client = FakeStorageClient(existing={media.path, "v1/unrelated.jpg"})
        client.payloads = {media.path: payload, "v1/unrelated.jpg": b"y"}

        with self.assertRaisesRegex(storage.PublicationError, "confirmation"):
            storage._rollback(client, contract, None)

        storage._rollback(client, contract, "DELETE-EXACT-DEMO-MANIFEST")
        self.assertEqual(client.existing, {"v1/unrelated.jpg"})
        remove_calls = [call for call in client.calls if call[0] == "remove"]
        self.assertEqual(remove_calls, [("remove", (media.path,))])

    def test_explicit_rollback_refuses_unverified_bytes_before_any_delete(self) -> None:
        payload = b"wrong"
        media = storage.MediaObject(
            "v1/example/asset.jpg",
            hashlib.sha256(b"expected").hexdigest(),
            len(payload),
            Path("unused"),
        )
        contract = storage.CatalogContract(
            storage.EXPECTED_BUCKET,
            storage.EXPECTED_CACHE_CONTROL,
            (media,),
        )
        client = FakeStorageClient(existing={media.path})
        client.payloads = {media.path: payload}

        with self.assertRaisesRegex(storage.PublicationError, "SHA-256 mismatch"):
            storage._rollback(
                client,
                contract,
                "DELETE-EXACT-DEMO-MANIFEST",
            )

        self.assertIn(media.path, client.existing)
        self.assertFalse(any(call[0] == "remove" for call in client.calls))

    def test_explicit_rollback_accepts_an_already_absent_manifest_path(self) -> None:
        missing_media = storage.MediaObject(
            "v1/example/missing.jpg",
            hashlib.sha256(b"missing").hexdigest(),
            len(b"missing"),
            Path("unused"),
        )
        payload = b"present"
        present_media = storage.MediaObject(
            "v1/example/present.jpg",
            hashlib.sha256(payload).hexdigest(),
            len(payload),
            Path("unused"),
        )
        contract = storage.CatalogContract(
            storage.EXPECTED_BUCKET,
            storage.EXPECTED_CACHE_CONTROL,
            (missing_media, present_media),
        )
        client = FakeStorageClient(existing={present_media.path})
        client.payloads = {present_media.path: payload}

        storage._rollback(
            client,
            contract,
            "DELETE-EXACT-DEMO-MANIFEST",
        )

        self.assertEqual(client.existing, set())
        self.assertIn(("remove", (present_media.path,)), client.calls)
        self.assertNotIn(("download", missing_media.path), client.calls)

    def test_exact_deletion_batches_at_the_documented_api_limit(self) -> None:
        paths = [f"v1/example/{index:04d}.jpg" for index in range(1_001)]
        client = FakeStorageClient(existing=set(paths))
        client.payloads = {path: b"x" for path in paths}

        deleted = storage._delete_exact_batches(
            client,
            storage.EXPECTED_BUCKET,
            paths,
        )

        self.assertEqual(set(deleted), set(paths))
        remove_calls = [call[1] for call in client.calls if call[0] == "remove"]
        self.assertEqual([len(call) for call in remove_calls], [1_000, 1])

    def test_storage_delete_rejects_incomplete_response_metadata(self) -> None:
        client = storage.StorageClient(
            "https://abcdefghijklmnopqrst.supabase.co",
            "test-key",
            1.0,
        )
        with patch.object(
            client,
            "_request_json",
            return_value=[{"name": "v1/example/other.jpg"}],
        ):
            with self.assertRaisesRegex(
                storage.PublicationError,
                "does not match",
            ):
                client.remove_exact(
                    storage.EXPECTED_BUCKET,
                    ["v1/example/asset.jpg"],
                )


if __name__ == "__main__":
    unittest.main()
