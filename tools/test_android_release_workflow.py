from __future__ import annotations

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "android-release.yml"


class AndroidClosedBetaReleaseWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_is_manual_staging_only_and_exact_head(self) -> None:
        self.assertIn("workflow_dispatch:", self.workflow)
        self.assertNotRegex(self.workflow, r"(?m)^\s{2}(push|pull_request|schedule):")
        self.assertIn("expected_sha:", self.workflow)
        self.assertIn('GITHUB_REF" != "refs/heads/main', self.workflow)
        self.assertIn('EXPECTED_SHA" != "$GITHUB_SHA', self.workflow)
        self.assertIn('KWABOR_ENVIRONMENT: staging', self.workflow)
        self.assertIn('environment: staging', self.workflow)
        self.assertNotIn("bundleRelease", self.workflow)
        self.assertNotIn("KWABOR_ENVIRONMENT: production", self.workflow)
        self.assertIn(":androidApp:bundleStaging", self.workflow)

    def test_build_and_publication_are_separate_and_fail_closed(self) -> None:
        self.assertRegex(self.workflow, r"(?m)^  build:\s*$")
        self.assertRegex(self.workflow, r"(?m)^  publish:\s*$")
        self.assertIn("needs: build", self.workflow)
        self.assertIn("if: inputs.publish_to_play_internal", self.workflow)
        self.assertIn("environment: play-internal", self.workflow)
        self.assertIn("PUBLISH-EXACT-AAB-TO-PLAY-INTERNAL", self.workflow)
        self.assertIn("KWABOR_PLAY_SERVICE_ACCOUNT_JSON_BASE64", self.workflow)
        self.assertIn("/environments/staging", self.workflow)
        self.assertIn("/environments/play-internal", self.workflow)
        self.assertEqual(self.workflow.count(".can_admins_bypass == false"), 2)
        self.assertEqual(self.workflow.count(".prevent_self_review == true"), 2)
        self.assertEqual(self.workflow.count(".deployment_branch_policy.protected_branches == true"), 2)
        self.assertEqual(self.workflow.count(".deployment_branch_policy.custom_branch_policies == false"), 2)

    def test_publication_cannot_roll_out_beyond_internal_track(self) -> None:
        self.assertIn("packageName: com.kwabor.android", self.workflow)
        self.assertRegex(self.workflow, r"(?m)^\s+tracks: internal\s*$")
        self.assertRegex(self.workflow, r"(?m)^\s+status: completed\s*$")
        self.assertNotRegex(
            self.workflow,
            r"(?m)^\s+(userFraction|tracks?: production|tracks?: beta|tracks?: alpha):",
        )
        self.assertEqual(self.workflow.count("r0adkll/upload-google-play@"), 1)
        self.assertIn(
            "r0adkll/upload-google-play@7f5b759879c088a86faf85d18779f7f14e79a086",
            self.workflow,
        )

    def test_provenance_signature_and_staging_identity_are_required(self) -> None:
        required_tokens = (
            "KWABOR-SHA256SUMS.txt",
            "KWABOR-ANDROID-PROVENANCE.json",
            "mapping.txt",
            "jarsigner",
            "prepare_android_release_bundle.py strip",
            "prepare_android_release_bundle.py verify",
            "KWABOR_ANDROID_UPLOAD_CERT_SHA256",
            "KWABOR_STAGING_PROJECT_REF_SHA256",
            "KWABOR_STAGING_FIREBASE_PROJECT_ID_SHA256",
            "qualified_ci_run_id",
            "artifact-digest",
            "artifact-url",
            "/actions/workflows/ci.yml/runs",
            '.event == "push"',
            '.head_branch == "main"',
        )
        for token in required_tokens:
            with self.subTest(token=token):
                self.assertIn(token, self.workflow)

    def test_every_external_action_is_pinned_to_a_commit(self) -> None:
        uses = re.findall(r"(?m)^\s*-?\s*uses:\s*([^\s#]+)", self.workflow)
        self.assertGreaterEqual(len(uses), 8)
        for action in uses:
            with self.subTest(action=action):
                self.assertRegex(action, r"^[^@]+@[0-9a-f]{40}$")


if __name__ == "__main__":
    unittest.main()
