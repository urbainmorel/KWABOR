from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock


TOOLS_DIRECTORY = Path(__file__).resolve().parent
REPOSITORY_ROOT = TOOLS_DIRECTORY.parent
MODULE_PATH = TOOLS_DIRECTORY / "closed-beta-staging-auth.py"
WORKFLOW_PATH = REPOSITORY_ROOT / ".github" / "workflows" / "closed-beta-staging-auth.yml"
SPEC = importlib.util.spec_from_file_location("closed_beta_staging_auth", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
auth = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = auth
SPEC.loader.exec_module(auth)


EXPECTED_SHA = "a" * 40
STAGING_REF = "s" * 20
PRODUCTION_REF = "p" * 20
STAGING_DIGEST = hashlib.sha256(STAGING_REF.encode("utf-8")).hexdigest()
GOOGLE_WEB_CLIENT_ID = "123456-web.apps.googleusercontent.com"
GOOGLE_IOS_CLIENT_ID = "123456-ios.apps.googleusercontent.com"
GOOGLE_REVERSED_CLIENT_ID = "com.googleusercontent.apps.123456-ios"


def valid_environment(*, include_mutation_secrets: bool = True) -> dict[str, str]:
    environment = {
        "KWABOR_GOOGLE_WEB_CLIENT_ID": GOOGLE_WEB_CLIENT_ID,
        "KWABOR_GOOGLE_SERVER_CLIENT_ID": GOOGLE_WEB_CLIENT_ID,
        "KWABOR_GOOGLE_IOS_CLIENT_ID": GOOGLE_IOS_CLIENT_ID,
        "KWABOR_GOOGLE_REVERSED_CLIENT_ID": GOOGLE_REVERSED_CLIENT_ID,
        "KWABOR_AUTH_SMTP_ADMIN_EMAIL": "auth@kwabor.test",
        "KWABOR_AUTH_SMTP_HOST": "smtp.kwabor.test",
        "KWABOR_AUTH_SMTP_PORT": "587",
    }
    if include_mutation_secrets:
        environment.update(
            {
                "KWABOR_GOOGLE_WEB_CLIENT_SECRET": "google-provider-credential",
                "KWABOR_AUTH_SMTP_USER": "smtp-identity",
                "KWABOR_AUTH_SMTP_PASSWORD": "smtp-credential",
            }
        )
    return environment


def make_repository(root: Path) -> None:
    templates = root / "supabase" / "templates"
    templates.mkdir(parents=True)
    (templates / "magic_link.html").write_text(
        "<html>Votre code {{ .Token }}</html>\n",
        encoding="utf-8",
    )
    (templates / "recovery.html").write_text(
        "<html>Récupération {{ .Token }}</html>\n",
        encoding="utf-8",
    )


class AuthorityTests(unittest.TestCase):
    def test_accepts_exact_staging_authority(self) -> None:
        authority = auth.validate_target_authority(
            environment="staging",
            api_url=f"https://{STAGING_REF}.supabase.co",
            project_ref=STAGING_REF,
            production_project_ref=PRODUCTION_REF,
            project_ref_sha256=STAGING_DIGEST,
        )

        self.assertEqual(authority.project_ref, STAGING_REF)

    def test_rejects_same_staging_and_production_project(self) -> None:
        with self.assertRaisesRegex(auth.StagingAuthError, "STAGING_PRODUCTION_PROJECTS_IDENTICAL"):
            auth.validate_target_authority(
                environment="staging",
                api_url=f"https://{STAGING_REF}.supabase.co",
                project_ref=STAGING_REF,
                production_project_ref=STAGING_REF,
                project_ref_sha256=STAGING_DIGEST,
            )


class DesiredConfigurationTests(unittest.TestCase):
    def test_builds_exact_mobile_auth_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            make_repository(repository)

            desired = auth.build_desired_configuration(
                environment=valid_environment(),
                repository_root=repository,
                require_mutation_secrets=True,
            )

        self.assertEqual(desired.public["site_url"], "kwabor://app/home")
        self.assertEqual(desired.public["uri_allow_list"], "kwabor://auth/promoter-activate")
        self.assertFalse(desired.public["disable_signup"])
        self.assertEqual(desired.public["password_min_length"], 8)
        self.assertEqual(desired.public["mailer_otp_length"], 6)
        self.assertEqual(desired.public["smtp_max_frequency"], 30)
        self.assertFalse(desired.public["mailer_autoconfirm"])
        self.assertFalse(desired.public["security_captcha_enabled"])
        self.assertFalse(desired.public["external_google_skip_nonce_check"])
        self.assertEqual(desired.public["external_apple_client_id"], "com.kwabor.ios")
        self.assertEqual(
            desired.patch["external_google_client_id"],
            f"{GOOGLE_WEB_CLIENT_ID},{GOOGLE_IOS_CLIENT_ID}",
        )
        self.assertNotIn("external_google_additional_client_ids", desired.patch)
        self.assertNotIn("external_apple_additional_client_ids", desired.patch)
        self.assertEqual(set(desired.write_only), set(auth.WRITE_ONLY_FIELDS))

    def test_verify_contract_does_not_require_provider_or_smtp_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            make_repository(repository)

            desired = auth.build_desired_configuration(
                environment=valid_environment(include_mutation_secrets=False),
                repository_root=repository,
                require_mutation_secrets=False,
            )

        self.assertEqual(desired.write_only, {})
        expected = auth.expected_snapshot(desired)
        self.assertTrue(expected["googleProviderCredentialConfigured"])
        self.assertTrue(expected["smtpIdentityConfigured"])
        self.assertTrue(expected["smtpCredentialConfigured"])

    def test_rejects_mismatched_google_server_audience(self) -> None:
        environment = valid_environment()
        environment["KWABOR_GOOGLE_SERVER_CLIENT_ID"] = GOOGLE_IOS_CLIENT_ID
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            make_repository(repository)
            with self.assertRaisesRegex(auth.StagingAuthError, "GOOGLE_SERVER_CLIENT_ID_MISMATCH"):
                auth.build_desired_configuration(
                    environment=environment,
                    repository_root=repository,
                    require_mutation_secrets=True,
                )

    def test_rejects_link_based_email_template(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            make_repository(repository)
            (repository / "supabase" / "templates" / "recovery.html").write_text(
                "{{ .Token }} {{ .ConfirmationURL }}",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(auth.StagingAuthError, "RECOVERY_TEMPLATE_LINK_FLOW_FORBIDDEN"):
                auth.build_desired_configuration(
                    environment=valid_environment(),
                    repository_root=repository,
                    require_mutation_secrets=True,
                )

    def test_mutation_fingerprint_binds_write_only_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            make_repository(repository)
            first = auth.build_desired_configuration(
                environment=valid_environment(),
                repository_root=repository,
                require_mutation_secrets=True,
            )
            changed_environment = valid_environment()
            changed_environment["KWABOR_AUTH_SMTP_PASSWORD"] = "different-high-entropy-credential"
            second = auth.build_desired_configuration(
                environment=changed_environment,
                repository_root=repository,
                require_mutation_secrets=True,
            )

        self.assertEqual(auth.configuration_fingerprint(first), auth.configuration_fingerprint(second))
        self.assertNotEqual(
            auth.mutation_configuration_fingerprint(first),
            auth.mutation_configuration_fingerprint(second),
        )


class DriftAndEvidenceTests(unittest.TestCase):
    def setUp(self) -> None:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        self.repository = Path(temporary_directory.name)
        make_repository(self.repository)
        self.desired = auth.build_desired_configuration(
            environment=valid_environment(),
            repository_root=self.repository,
            require_mutation_secrets=True,
        )

    def fully_configured_document(self) -> dict[str, object]:
        return {
            **self.desired.public,
            **self.desired.templates,
            "external_google_secret": "configured",
            "smtp_user": "configured",
            "smtp_pass": "configured",
        }

    def test_live_baseline_drift_is_detected_without_secret_values(self) -> None:
        live = self.fully_configured_document()
        live.update(
            {
                "site_url": "http://localhost:3000",
                "uri_allow_list": "",
                "password_min_length": 6,
                "mailer_otp_length": 8,
                "smtp_max_frequency": 60,
                "security_captcha_enabled": True,
                "external_google_enabled": False,
                "external_apple_enabled": False,
                "external_google_secret": None,
                "smtp_user": None,
                "smtp_pass": None,
            }
        )

        drift = auth.auth_config_drift(live, self.desired)
        snapshot = auth.safe_snapshot(live)

        self.assertIn("site_url", drift)
        self.assertIn("security_captcha_enabled", drift)
        self.assertIn("googleProviderCredentialConfigured", drift)
        self.assertIn("smtpCredentialConfigured", drift)
        self.assertNotIn("external_google_secret", snapshot)
        self.assertNotIn("smtp_pass", snapshot)
        self.assertNotIn("smtp_admin_email", snapshot)
        self.assertNotIn("smtp_host", snapshot)
        self.assertNotIn("auth@kwabor.test", json.dumps(snapshot, sort_keys=True))
        self.assertNotIn("smtp.kwabor.test", json.dumps(snapshot, sort_keys=True))
        self.assertTrue(snapshot["smtpAdminEmailConfigured"])
        self.assertTrue(snapshot["smtpHostConfigured"])

    def test_equivalent_comma_lists_and_empty_apple_audience_have_no_drift(self) -> None:
        current = self.fully_configured_document()
        current["uri_allow_list"] = "  kwabor://auth/promoter-activate  "
        current["external_apple_additional_client_ids"] = None

        self.assertEqual(auth.auth_config_drift(current, self.desired), [])

    def test_unexpected_provider_or_apple_web_credential_blocks_qualification(self) -> None:
        current = self.fully_configured_document()
        current["external_github_enabled"] = True
        current["external_apple_secret"] = "unexpected-web-credential"

        drift = auth.auth_config_drift(current, self.desired)
        snapshot = auth.safe_snapshot(current)

        self.assertIn("unexpectedExternalProvidersEnabled", drift)
        self.assertIn("appleWebCredentialMustBeAbsent", drift)
        self.assertEqual(snapshot["unexpectedExternalProvidersEnabled"], ["external_github_enabled"])
        self.assertTrue(snapshot["appleWebCredentialConfigured"])

    def test_receipt_writer_refuses_literal_secret_leak(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(auth.StagingAuthError, "SECRET_LEAK_IN_EVIDENCE"):
                auth.write_receipt(
                    directory=Path(temporary_directory),
                    receipt={"accidental": "smtp-credential"},
                    secret_values=["smtp-credential"],
                )

    def test_ci_provenance_is_bound_to_exact_sha_and_run(self) -> None:
        provenance = {
            "schemaVersion": 1,
            "kind": "github-actions-run-provenance",
            "repository": "urbainmorel/KWABOR",
            "headBranch": "main",
            "headSha": EXPECTED_SHA,
            "runId": 123,
            "workflow": ".github/workflows/ci.yml",
            "status": "completed",
            "conclusion": "success",
            "event": "push",
        }
        path = self.repository / "ci.json"
        path.write_text(json.dumps(provenance), encoding="utf-8")

        digest = auth.validate_ci_provenance(
            path,
            expected_sha=EXPECTED_SHA,
            validated_ci_run_id="123",
        )

        self.assertRegex(digest, r"^[0-9a-f]{64}$")

    def test_apply_plan_artifact_is_bound_to_exact_authority_and_remote_snapshot(self) -> None:
        plan_run_id = 900
        artifact_id = 901
        validated_ci_run_id = 123
        current_before = auth.safe_snapshot(self.fully_configured_document())
        plan_receipt = {
            "schemaVersion": 1,
            "gate": "G5",
            "taskId": "B6.AUTH",
            "workflow": auth.EXPECTED_WORKFLOW,
            "operation": "plan",
            "environment": "staging",
            "repository": auth.EXPECTED_REPOSITORY,
            "expectedSha": EXPECTED_SHA,
            "validatedCiRunId": validated_ci_run_id,
            "runId": plan_run_id,
            "runAttempt": 2,
            "runUrl": f"https://github.com/{auth.EXPECTED_REPOSITORY}/actions/runs/{plan_run_id}",
            "status": "succeeded",
            "gateDecision": "ready-to-apply",
            "mutationState": "not-requested",
            "executionDisposition": "PLANNED",
            "errorCode": None,
            "projectRefSha256": STAGING_DIGEST,
            "ciProvenanceSha256": "d" * 64,
            "configurationFingerprint": auth.configuration_fingerprint(self.desired),
            "mutationConfigurationFingerprint": auth.mutation_configuration_fingerprint(
                self.desired
            ),
            "expected": auth.expected_snapshot(self.desired),
            "templateDigests": {
                field: auth.sha256_text(content)
                for field, content in sorted(self.desired.templates.items())
            },
            "currentBefore": current_before,
            "driftFields": [],
        }
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            archive_path = root / "plan.zip"
            with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr(
                    auth.RECEIPT_NAME,
                    json.dumps(plan_receipt, ensure_ascii=False, sort_keys=True) + "\n",
                )
            artifact_digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
            run_document = {
                "id": plan_run_id,
                "event": "workflow_dispatch",
                "head_branch": "main",
                "head_sha": EXPECTED_SHA,
                "path": auth.EXPECTED_WORKFLOW,
                "status": "completed",
                "conclusion": "success",
                "repository": {"full_name": auth.EXPECTED_REPOSITORY},
                "run_attempt": 2,
                "html_url": f"https://github.com/{auth.EXPECTED_REPOSITORY}/actions/runs/{plan_run_id}",
            }
            artifact_document = {
                "id": artifact_id,
                "name": f"kwabor-gel-g5-staging-auth-plan-{EXPECTED_SHA}-2",
                "expired": False,
                "digest": f"sha256:{artifact_digest}",
                "size_in_bytes": archive_path.stat().st_size,
                "expires_at": "2099-01-01T00:00:00Z",
                "workflow_run": {
                    "id": plan_run_id,
                    "head_sha": EXPECTED_SHA,
                    "head_branch": "main",
                },
            }
            run_path = root / "run.json"
            artifact_path = root / "artifact.json"
            run_path.write_text(json.dumps(run_document), encoding="utf-8")
            artifact_path.write_text(json.dumps(artifact_document), encoding="utf-8")

            proof = auth.validate_plan_artifact_bundle(
                run_document_path=run_path,
                artifact_document_path=artifact_path,
                archive_path=archive_path,
                plan_run_id=plan_run_id,
                plan_artifact_id=artifact_id,
                plan_artifact_digest=artifact_digest,
                expected_sha=EXPECTED_SHA,
                validated_ci_run_id=validated_ci_run_id,
                project_ref_sha256=STAGING_DIGEST,
                desired=self.desired,
            )

            self.assertEqual(proof["currentBefore"], current_before)
            changed_environment = valid_environment()
            changed_environment["KWABOR_AUTH_SMTP_PASSWORD"] = "changed-secret"
            changed = auth.build_desired_configuration(
                environment=changed_environment,
                repository_root=self.repository,
                require_mutation_secrets=True,
            )
            with self.assertRaisesRegex(
                auth.StagingAuthError,
                "PLAN_RECEIPT_MUTATIONCONFIGURATIONFINGERPRINT_DRIFT",
            ):
                auth.validate_plan_artifact_bundle(
                    run_document_path=run_path,
                    artifact_document_path=artifact_path,
                    archive_path=archive_path,
                    plan_run_id=plan_run_id,
                    plan_artifact_id=artifact_id,
                    plan_artifact_digest=artifact_digest,
                    expected_sha=EXPECTED_SHA,
                    validated_ci_run_id=validated_ci_run_id,
                    project_ref_sha256=STAGING_DIGEST,
                    desired=changed,
                )

    def test_plan_inputs_are_required_only_for_apply(self) -> None:
        authority = auth.validate_plan_inputs(
            operation="apply",
            plan_run_id="900",
            plan_artifact_id="901",
            plan_artifact_digest="c" * 64,
        )
        self.assertEqual(authority["runId"], 900)
        self.assertIsNone(
            auth.validate_plan_inputs(
                operation="plan",
                plan_run_id="",
                plan_artifact_id="",
                plan_artifact_digest="",
            )
        )
        with self.assertRaisesRegex(auth.StagingAuthError, "PLAN_RUN_ID_INVALID"):
            auth.validate_plan_inputs(
                operation="apply",
                plan_run_id="",
                plan_artifact_id="901",
                plan_artifact_digest="c" * 64,
            )


class WorkflowContractTests(unittest.TestCase):
    def test_manual_workflow_keeps_apply_explicit_and_protected(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")

        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotIn("pull_request:", workflow)
        self.assertNotIn("push:", workflow)
        self.assertIn("environment: staging", workflow)
        self.assertIn("APPLY-EXACT-STAGING-AUTH", workflow)
        self.assertIn("validated_plan_run_id:", workflow)
        self.assertIn("validated_plan_artifact_id:", workflow)
        self.assertIn("validated_plan_artifact_digest:", workflow)
        self.assertIn("SUPABASE_ACCESS_TOKEN", workflow)
        self.assertIn("KWABOR_GOOGLE_WEB_CLIENT_SECRET", workflow)
        self.assertIn("KWABOR_AUTH_SMTP_PASSWORD", workflow)
        self.assertNotIn("supabase start", workflow)
        self.assertNotIn("docker", workflow.lower())


if __name__ == "__main__":
    unittest.main()
