from __future__ import annotations

import hashlib
import importlib.util
import json
import plistlib
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path


TOOLS_DIRECTORY = Path(__file__).resolve().parent
REPOSITORY_ROOT = TOOLS_DIRECTORY.parent
MODULE_PATH = TOOLS_DIRECTORY / "ios-closed-beta-release.py"
WORKFLOW_PATH = REPOSITORY_ROOT / ".github/workflows/ios-archive.yml"
SPEC = importlib.util.spec_from_file_location("ios_closed_beta_release", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


EXPECTED_SHA = "a" * 40
TEAM_ID = "A1B2C3D4E5"
BUNDLE_ID = "com.kwabor.ios"
PROFILE_NAME = "Kwabor Staging App Store"
CERTIFICATE = b"distribution-certificate-der"
CERTIFICATE_SHA1 = hashlib.sha1(CERTIFICATE).hexdigest().upper()


def valid_profile(now: datetime) -> dict[str, object]:
    return {
        "DeveloperCertificates": [CERTIFICATE],
        "Entitlements": {
            "application-identifier": f"{TEAM_ID}.{BUNDLE_ID}",
            "aps-environment": "production",
            "beta-reports-active": True,
            "com.apple.developer.applesignin": ["Default"],
            "com.apple.developer.team-identifier": TEAM_ID,
            "get-task-allow": False,
        },
        "ExpirationDate": now + timedelta(days=90),
        "Name": PROFILE_NAME,
        "TeamIdentifier": [TEAM_ID],
        "UUID": "11111111-2222-3333-4444-555555555555",
    }


def profile_metadata() -> dict[str, object]:
    return {
        "bundleId": BUNDLE_ID,
        "certificateSha1": CERTIFICATE_SHA1,
        "expirationDateUtc": "2027-01-01T00:00:00Z",
        "name": PROFILE_NAME,
        "teamId": TEAM_ID,
        "uuid": "11111111-2222-3333-4444-555555555555",
    }


class FakeAscClient:
    def __init__(self, *, audience: str = "INTERNAL_ONLY", existing: bool = True) -> None:
        self.audience = audience
        self.existing = existing
        self.associated = False
        self.calls: list[tuple[str, str]] = []

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, object] | None = None,
        expected_statuses: tuple[int, ...] = (200,),
    ) -> dict[str, object] | None:
        del expected_statuses
        self.calls.append((method, path))
        if path.startswith("/v1/apps?"):
            return {"data": [{"type": "apps", "id": "1234567890", "attributes": {"bundleId": BUNDLE_ID}}]}
        if path == "/v1/betaGroups/internal-group?include=app":
            return {
                "data": {
                    "type": "betaGroups",
                    "id": "internal-group",
                    "attributes": {
                        "name": "Kwabor closed beta",
                        "isInternalGroup": True,
                        "hasAccessToAllBuilds": False,
                        "publicLinkEnabled": None,
                    },
                    "relationships": {"app": {"data": {"type": "apps", "id": "1234567890"}}},
                }
            }
        if path.startswith("/v1/builds?"):
            data: list[dict[str, object]] = []
            if self.existing:
                data.append(
                    {
                        "type": "builds",
                        "id": "processed-build",
                        "attributes": {
                            "version": "42",
                            "uploadedDate": "2026-08-12T12:01:00Z",
                            "expired": False,
                            "processingState": "VALID",
                            "buildAudienceType": self.audience,
                        },
                    }
                )
            return {"data": data}
        if path == "/v1/builds/processed-build/preReleaseVersion":
            return {
                "data": {
                    "type": "preReleaseVersions",
                    "id": "pre-release",
                    "attributes": {"version": "1.2.3", "platform": "IOS"},
                }
            }
        if path.startswith("/v1/builds/processed-build/betaBuildLocalizations?"):
            return {"data": []}
        if method == "POST" and path == "/v1/betaBuildLocalizations":
            assert body is not None
            return {
                "data": {
                    "type": "betaBuildLocalizations",
                    "id": "fr-localization",
                    "attributes": {"locale": "fr-FR"},
                }
            }
        if path.startswith("/v1/betaGroups/internal-group/relationships/builds?"):
            data = []
            if self.associated:
                data.append({"type": "builds", "id": "processed-build"})
            return {"data": data}
        if method == "POST" and path == "/v1/betaGroups/internal-group/relationships/builds":
            self.associated = True
            return None
        raise AssertionError(f"Unexpected request: {method} {path}")


class ProvisioningProfileTest(unittest.TestCase):
    def test_exact_app_store_profile_and_certificate_are_accepted(self) -> None:
        now = datetime(2026, 8, 12, tzinfo=timezone.utc)
        metadata = release.validate_profile(
            valid_profile(now),
            expected_team_id=TEAM_ID,
            expected_bundle_id=BUNDLE_ID,
            expected_profile_name=PROFILE_NAME,
            certificate_sha1=CERTIFICATE_SHA1,
            now=now,
        )
        self.assertEqual(metadata["teamId"], TEAM_ID)
        self.assertEqual(metadata["certificateSha1"], CERTIFICATE_SHA1)

    def test_wildcard_debug_adhoc_and_wrong_certificate_are_rejected(self) -> None:
        now = datetime(2026, 8, 12, tzinfo=timezone.utc)
        unsafe_profiles = []
        wildcard = valid_profile(now)
        wildcard["Entitlements"]["application-identifier"] = f"{TEAM_ID}.*"  # type: ignore[index]
        unsafe_profiles.append((wildcard, CERTIFICATE_SHA1))
        debuggable = valid_profile(now)
        debuggable["Entitlements"]["get-task-allow"] = True  # type: ignore[index]
        unsafe_profiles.append((debuggable, CERTIFICATE_SHA1))
        ad_hoc = valid_profile(now)
        ad_hoc["ProvisionedDevices"] = ["device"]
        unsafe_profiles.append((ad_hoc, CERTIFICATE_SHA1))
        unsafe_profiles.append((valid_profile(now), "0" * 40))
        for profile, certificate_sha1 in unsafe_profiles:
            with self.subTest(profile=profile), self.assertRaises(release.ReleaseError):
                release.validate_profile(
                    profile,
                    expected_team_id=TEAM_ID,
                    expected_bundle_id=BUNDLE_ID,
                    expected_profile_name=PROFILE_NAME,
                    certificate_sha1=certificate_sha1,
                    now=now,
                )


class ExportOptionsTest(unittest.TestCase):
    def test_upload_is_permanently_internal_only(self) -> None:
        options = release.create_export_options(
            destination="upload",
            team_id=TEAM_ID,
            bundle_id=BUNDLE_ID,
            profile_name=PROFILE_NAME,
        )
        self.assertEqual(options["method"], "app-store-connect")
        self.assertEqual(options["destination"], "upload")
        self.assertIs(options["testFlightInternalTestingOnly"], True)
        self.assertIs(options["manageAppVersionAndBuildNumber"], False)

    def test_local_ipa_export_never_claims_an_upload(self) -> None:
        options = release.create_export_options(
            destination="export",
            team_id=TEAM_ID,
            bundle_id=BUNDLE_ID,
            profile_name=PROFILE_NAME,
        )
        self.assertNotIn("testFlightInternalTestingOnly", options)


class EvidenceTest(unittest.TestCase):
    def test_artifact_hashes_bind_archive_to_exact_head_and_ci(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            for filename, payload in (
                ("Kwabor.xcarchive.zip", b"archive"),
                ("Kwabor.ipa", b"ipa"),
                ("Kwabor.dSYM.zip", b"symbols"),
            ):
                (directory / filename).write_bytes(payload)
            evidence = release.release_evidence(
                directory=directory,
                archive_filename="Kwabor.xcarchive.zip",
                ipa_filename="Kwabor.ipa",
                dsym_filename="Kwabor.dSYM.zip",
                expected_sha=EXPECTED_SHA,
                validated_ci_run_id="100",
                version_name="1.2.3",
                build_number="42",
                profile_metadata=profile_metadata(),
                repository=release.EXPECTED_REPOSITORY,
                actor="release-owner",
                run_id="200",
                run_attempt="1",
                run_url="https://github.com/urbainmorel/KWABOR/actions/runs/200",
            )
            release.verify_release_evidence(
                evidence,
                directory=directory,
                expected_sha=EXPECTED_SHA,
                validated_ci_run_id="100",
                version_name="1.2.3",
                build_number="42",
                archive_run_id="200",
                current_profile_metadata=profile_metadata(),
            )
            (directory / "Kwabor.ipa").write_bytes(b"tampered")
            with self.assertRaisesRegex(release.ReleaseError, "digest or size mismatch"):
                release.verify_release_evidence(
                    evidence,
                    directory=directory,
                    expected_sha=EXPECTED_SHA,
                    validated_ci_run_id="100",
                    version_name="1.2.3",
                    build_number="42",
                    archive_run_id="200",
                    current_profile_metadata=profile_metadata(),
                )

            evidence["runUrl"] = "https://github.com/urbainmorel/KWABOR/actions/runs/999"
            with self.assertRaisesRegex(release.ReleaseError, "run URL mismatch"):
                release.verify_release_evidence(
                    evidence,
                    directory=directory,
                    expected_sha=EXPECTED_SHA,
                    validated_ci_run_id="100",
                    version_name="1.2.3",
                    build_number="42",
                    archive_run_id="200",
                    current_profile_metadata=profile_metadata(),
                )

    def test_github_run_must_be_successful_main_exact_head(self) -> None:
        document = {
            "id": 100,
            "repository": {"full_name": release.EXPECTED_REPOSITORY},
            "head_sha": EXPECTED_SHA,
            "head_branch": "main",
            "status": "completed",
            "conclusion": "success",
            "path": ".github/workflows/ci.yml@refs/heads/main",
            "event": "push",
        }
        release.validate_github_run(
            document,
            expected_run_id="100",
            expected_sha=EXPECTED_SHA,
            expected_workflow_path=".github/workflows/ci.yml",
            allowed_events=("push",),
        )
        document["conclusion"] = "failure"
        with self.assertRaises(release.ReleaseError):
            release.validate_github_run(
                document,
                expected_run_id="100",
                expected_sha=EXPECTED_SHA,
                expected_workflow_path=".github/workflows/ci.yml",
                allowed_events=("push",),
            )


class AppStoreConnectTest(unittest.TestCase):
    def test_preflight_rejects_reused_version_build(self) -> None:
        with self.assertRaisesRegex(release.ReleaseError, "already exists"):
            release.asc_preflight(
                FakeAscClient(existing=True),
                bundle_id=BUNDLE_ID,
                app_id="1234567890",
                group_id="internal-group",
                version_name="1.2.3",
                build_number="42",
            )
        result = release.asc_preflight(
            FakeAscClient(existing=False),
            bundle_id=BUNDLE_ID,
            app_id="1234567890",
            group_id="internal-group",
            version_name="1.2.3",
            build_number="42",
        )
        self.assertIs(result["internalOnly"], True)

    def test_processed_internal_build_gets_notes_and_exact_group(self) -> None:
        client = FakeAscClient()
        result = release.publish_internal_build(
            client,
            bundle_id=BUNDLE_ID,
            app_id="1234567890",
            group_id="internal-group",
            version_name="1.2.3",
            build_number="42",
            release_notes="Tester Explorer et Compte sur le catalogue fermé.",
            expected_sha=EXPECTED_SHA,
            validated_ci_run_id="100",
            archive_run_id="200",
            upload_run_id="300",
            upload_run_attempt="1",
            upload_run_url="https://github.com/urbainmorel/KWABOR/actions/runs/300",
            actor="release-owner",
            uploaded_after=datetime(2026, 8, 12, 12, 0, tzinfo=timezone.utc),
            timeout_seconds=1,
            poll_seconds=0,
        )
        self.assertEqual(result["buildAudienceType"], "INTERNAL_ONLY")
        self.assertEqual(result["groupId"], "internal-group")
        self.assertTrue(client.associated)

    def test_app_store_eligible_build_is_never_accepted(self) -> None:
        with self.assertRaisesRegex(release.ReleaseError, "App Store eligible"):
            release.publish_internal_build(
                FakeAscClient(audience="APP_STORE_ELIGIBLE"),
                bundle_id=BUNDLE_ID,
                app_id="1234567890",
                group_id="internal-group",
                version_name="1.2.3",
                build_number="42",
                release_notes="Test interne uniquement.",
                expected_sha=EXPECTED_SHA,
                validated_ci_run_id="100",
                archive_run_id="200",
                upload_run_id="300",
                upload_run_attempt="1",
                upload_run_url="https://github.com/urbainmorel/KWABOR/actions/runs/300",
                actor="release-owner",
                uploaded_after=datetime(2026, 8, 12, 12, 0, tzinfo=timezone.utc),
                timeout_seconds=1,
                poll_seconds=0,
            )


class WorkflowPolicyTest(unittest.TestCase):
    def test_workflow_is_staging_manual_exact_head_and_internal_only(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        required_fragments = (
            "expected_sha:",
            "validated_ci_run_id:",
            "archive_run_id:",
            "upload-testflight-internal",
            "UPLOAD-TESTFLIGHT-INTERNAL",
            "environment: staging",
            "environment: testflight-internal",
            "testFlightInternalTestingOnly",
            "KWABOR_TESTFLIGHT_INTERNAL_GROUP_ID",
            "KWABOR_ASC_PRIVATE_KEY_BASE64",
            "ref: ${{ inputs.expected_sha }}",
            "actions: read",
            "validate-environment",
            "KWABOR_IOS_USES_NON_EXEMPT_ENCRYPTION",
        )
        for fragment in required_fragments:
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, workflow)
        archive_job_start = workflow.index("\n  archive:\n")
        upload_job_start = workflow.index("\n  upload_testflight:\n")
        archive_job = workflow[archive_job_start:upload_job_start]
        upload_job = workflow[upload_job_start:]
        self.assertIn("environment: staging", archive_job)
        self.assertNotIn("environment: testflight-internal", archive_job)
        self.assertIn("--name staging", archive_job)
        self.assertIn("environment: testflight-internal", upload_job)
        self.assertIn("for environment_name in staging testflight-internal", upload_job)
        helper = MODULE_PATH.read_text(encoding="utf-8")
        self.assertIn('document.get("can_admins_bypass") is False', helper)
        self.assertIn('reviewer_rule.get("prevent_self_review") is True', helper)
        self.assertNotIn("environment: production", workflow)
        self.assertNotIn("- production", workflow)
        self.assertNotIn("--upload-app", workflow)


class EnvironmentProtectionTest(unittest.TestCase):
    def safe_environment(self, name: str) -> dict[str, object]:
        return {
            "id": 123,
            "name": name,
            "updated_at": "2026-08-12T10:00:00Z",
            "can_admins_bypass": False,
            "protection_rules": [
                {
                    "id": 1,
                    "type": "required_reviewers",
                    "prevent_self_review": True,
                    "reviewers": [
                        {"type": "User", "reviewer": {"id": 42, "login": "owner"}}
                    ],
                },
                {"id": 2, "type": "branch_policy"},
            ],
            "deployment_branch_policy": {
                "protected_branches": True,
                "custom_branch_policies": False,
            },
        }

    def test_protected_environment_is_sanitized_for_gel(self) -> None:
        result = release.validate_environment_protection(
            self.safe_environment("testflight-internal"),
            expected_name="testflight-internal",
        )
        self.assertEqual(result["reviewerCount"], 1)
        self.assertEqual(result["reviewerTypes"], ["User"])
        self.assertNotIn("owner", json.dumps(result))

    def test_missing_reviewer_unprotected_branch_and_admin_bypass_are_rejected(self) -> None:
        missing_reviewer = self.safe_environment("staging")
        missing_reviewer["protection_rules"] = [
            {"id": 2, "type": "branch_policy"}
        ]
        unprotected = self.safe_environment("staging")
        unprotected["deployment_branch_policy"] = {
            "protected_branches": False,
            "custom_branch_policies": True,
        }
        bypass = self.safe_environment("staging")
        bypass["can_admins_bypass"] = True
        self_review = self.safe_environment("staging")
        self_review["protection_rules"][0]["prevent_self_review"] = False  # type: ignore[index]
        for document in (missing_reviewer, unprotected, bypass, self_review):
            with self.subTest(document=document), self.assertRaises(release.ReleaseError):
                release.validate_environment_protection(document, expected_name="staging")


class JwtSignatureTest(unittest.TestCase):
    def test_der_ecdsa_signature_is_converted_to_fixed_es256_shape(self) -> None:
        raw = release.ecdsa_der_to_raw(bytes.fromhex("3006020101020102"))
        self.assertEqual(len(raw), 64)
        self.assertEqual(raw[:32], b"\0" * 31 + b"\x01")
        self.assertEqual(raw[32:], b"\0" * 31 + b"\x02")


if __name__ == "__main__":
    unittest.main()
