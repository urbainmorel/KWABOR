#!/usr/bin/env python3
"""Regression tests for the audited iOS privacy and observability contracts."""

from __future__ import annotations

import copy
import importlib.util
import plistlib
import re
import tempfile
import unittest
from pathlib import Path
from types import ModuleType


TOOLS_DIRECTORY = Path(__file__).resolve().parent
REPOSITORY_ROOT = TOOLS_DIRECTORY.parent
VERIFIER_PATH = TOOLS_DIRECTORY / "verify-repository-integrity.py"


def load_verifier() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "kwabor_verify_repository_integrity",
        VERIFIER_PATH,
    )
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load repository verifier: {VERIFIER_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


VERIFIER = load_verifier()
EXPECTED_FUV_CRITICAL_SOURCES = {
    "shared/src/commonMain/kotlin/com/kwabor/shared/presentation/explore/ExploreModels.kt",
    "shared/src/commonMain/kotlin/com/kwabor/shared/presentation/explore/ExploreFirstUsableViewportProbe.kt",
    "shared/src/commonMain/kotlin/com/kwabor/shared/presentation/explore/ExploreSurfacePresentationGate.kt",
    "shared/src/commonMain/kotlin/com/kwabor/shared/presentation/explore/ExploreSurfacePresentationRegistry.kt",
    "androidApp/src/main/kotlin/com/kwabor/android/observability/AndroidExploreFirstUsableViewportReporter.kt",
    "androidApp/src/main/kotlin/com/kwabor/android/ui/screens/explore/ExploreViewportPerformanceBinding.kt",
    "androidApp/src/main/kotlin/com/kwabor/android/app/ExploreAppRoute.kt",
    "iosApp/Kwabor/Explore/ExploreStore.swift",
    "iosApp/Kwabor/Explore/ExploreView.swift",
    "iosApp/Kwabor/App/ContentView.swift",
}


def read_observed_app_session_sources() -> dict[str, str]:
    return {
        source_path: (REPOSITORY_ROOT / source_path).read_text(encoding="utf-8")
        for source_path in VERIFIER.OBSERVED_APP_SESSION_CRITICAL_SOURCE_SHA256
    }


def read_fuv_critical_sources() -> dict[str, str]:
    return {
        source_path: (REPOSITORY_ROOT / source_path).read_text(encoding="utf-8")
        for source_path in VERIFIER.FUV_CRITICAL_SOURCE_SHA256
    }


class FuvCriticalSourceValidationTest(unittest.TestCase):
    def test_inventory_and_every_audited_source_are_locked(self) -> None:
        sources = read_fuv_critical_sources()
        VERIFIER.validate_fuv_critical_source_contract(sources)
        self.assertEqual(EXPECTED_FUV_CRITICAL_SOURCES, set(sources))
        self.assertEqual(EXPECTED_FUV_CRITICAL_SOURCES, set(VERIFIER.FUV_CRITICAL_SOURCE_SHA256))

        missing_source = dict(sources)
        missing_source.pop(next(iter(VERIFIER.FUV_CRITICAL_SOURCE_SHA256)))
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "FUV critical-source inventory changed",
        ):
            VERIFIER.validate_fuv_critical_source_contract(missing_source)

        for source_path in sorted(sources):
            with self.subTest(fuv_source_drift=source_path):
                changed_sources = dict(sources)
                changed_sources[source_path] += "\n// audited drift"
                with self.assertRaisesRegex(
                    VERIFIER.RepositoryIntegrityError,
                    "changed outside its audited FUV snapshot",
                ):
                    VERIFIER.validate_fuv_critical_source_contract(changed_sources)

    def test_newline_representation_does_not_create_false_drift(self) -> None:
        sources = read_fuv_critical_sources()
        crlf_sources = {
            source_path: source.replace("\n", "\r\n")
            for source_path, source in sources.items()
        }
        VERIFIER.validate_fuv_critical_source_contract(crlf_sources)


class IosPrivacyManifestValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        manifest_path = REPOSITORY_ROOT / VERIFIER.IOS_PRIVACY_MANIFEST_PATH
        with manifest_path.open("rb") as manifest_file:
            self.manifest = plistlib.load(manifest_file)

    def assert_manifest_rejected(self, manifest: dict[str, object]) -> None:
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            VERIFIER.validate_ios_privacy_manifest(manifest)

    def test_current_manifest_is_accepted(self) -> None:
        VERIFIER.validate_ios_privacy_manifest(self.manifest)

    def test_extra_root_key_is_rejected(self) -> None:
        changed = copy.deepcopy(self.manifest)
        changed["UnexpectedPrivacyDeclaration"] = False

        self.assert_manifest_rejected(changed)

    def test_changed_accessed_api_type_is_rejected(self) -> None:
        changed = copy.deepcopy(self.manifest)
        changed["NSPrivacyAccessedAPITypes"][0]["NSPrivacyAccessedAPIType"] = (
            "NSPrivacyAccessedAPICategoryFileTimestamp"
        )

        self.assert_manifest_rejected(changed)

    def test_changed_user_defaults_reason_is_rejected(self) -> None:
        changed = copy.deepcopy(self.manifest)
        changed["NSPrivacyAccessedAPITypes"][0]["NSPrivacyAccessedAPITypeReasons"] = [
            "1C8F.1"
        ]

        self.assert_manifest_rejected(changed)

    def test_extra_accessed_api_type_is_rejected(self) -> None:
        changed = copy.deepcopy(self.manifest)
        changed["NSPrivacyAccessedAPITypes"].append(
            {
                "NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryFileTimestamp",
                "NSPrivacyAccessedAPITypeReasons": ["C617.1"],
            }
        )

        self.assert_manifest_rejected(changed)

    def test_changed_collected_data_type_is_rejected(self) -> None:
        changed = copy.deepcopy(self.manifest)
        changed["NSPrivacyCollectedDataTypes"][0]["NSPrivacyCollectedDataType"] = (
            "NSPrivacyCollectedDataTypePhoneNumber"
        )

        self.assert_manifest_rejected(changed)

    def test_changed_purpose_is_rejected(self) -> None:
        changed = copy.deepcopy(self.manifest)
        changed["NSPrivacyCollectedDataTypes"][0][
            "NSPrivacyCollectedDataTypePurposes"
        ] = [VERIFIER.IOS_ANALYTICS_PURPOSE]

        self.assert_manifest_rejected(changed)

    def test_unlinked_collected_data_is_rejected(self) -> None:
        changed = copy.deepcopy(self.manifest)
        changed["NSPrivacyCollectedDataTypes"][0][
            "NSPrivacyCollectedDataTypeLinked"
        ] = False

        self.assert_manifest_rejected(changed)

    def test_collected_data_tracking_is_rejected(self) -> None:
        changed = copy.deepcopy(self.manifest)
        changed["NSPrivacyCollectedDataTypes"][0][
            "NSPrivacyCollectedDataTypeTracking"
        ] = True

        self.assert_manifest_rejected(changed)

    def test_host_tracking_is_rejected(self) -> None:
        changed = copy.deepcopy(self.manifest)
        changed["NSPrivacyTracking"] = True

        self.assert_manifest_rejected(changed)


class IosObservabilityPrivacyValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        with (REPOSITORY_ROOT / VERIFIER.IOS_INFO_PLIST_PATH).open("rb") as info_file:
            self.info_plist = plistlib.load(info_file)
        self.observability_source = (
            REPOSITORY_ROOT / VERIFIER.IOS_OBSERVABILITY_SOURCE_PATH
        ).read_text(encoding="utf-8")
        self.coordinator_source = (
            REPOSITORY_ROOT / VERIFIER.IOS_ONBOARDING_COORDINATOR_PATH
        ).read_text(encoding="utf-8")
        self.content_view_source = (
            REPOSITORY_ROOT / VERIFIER.IOS_CONTENT_VIEW_PATH
        ).read_text(encoding="utf-8")
        self.app_source = (
            REPOSITORY_ROOT / VERIFIER.IOS_APP_SOURCE_PATH
        ).read_text(encoding="utf-8")
        self.xcode_project_source = (
            REPOSITORY_ROOT / VERIFIER.IOS_XCODE_PROJECT_PATH
        ).read_text(encoding="utf-8")

    def validate(
        self,
        *,
        info_plist: dict[str, object] | None = None,
        observability_source: str | None = None,
        coordinator_source: str | None = None,
        content_view_source: str | None = None,
        app_source: str | None = None,
        xcode_project_source: str | None = None,
    ) -> None:
        VERIFIER.validate_ios_observability_privacy_contract(
            info_plist=self.info_plist if info_plist is None else info_plist,
            observability_source=(
                self.observability_source if observability_source is None else observability_source
            ),
            coordinator_source=(
                self.coordinator_source if coordinator_source is None else coordinator_source
            ),
            content_view_source=(
                self.content_view_source if content_view_source is None else content_view_source
            ),
            app_source=self.app_source if app_source is None else app_source,
            xcode_project_source=(
                self.xcode_project_source
                if xcode_project_source is None
                else xcode_project_source
            ),
        )

    def replace_after(self, marker: str, old: str, new: str) -> str:
        marker_offset = self.observability_source.index(marker)
        old_offset = self.observability_source.index(old, marker_offset)
        return (
            self.observability_source[:old_offset]
            + new
            + self.observability_source[old_offset + len(old) :]
        )

    def test_current_observability_contract_is_accepted(self) -> None:
        self.validate()

    def test_fuv_measurement_adapter_remains_diagnostics_gated(self) -> None:
        changed = self.observability_source.replace(
            "func recordPerformanceMeasurement(_ measurement: PerformanceMeasurement)",
            "func recordUncheckedPerformanceMeasurement(_ measurement: PerformanceMeasurement)",
            1,
        )
        self.assertNotEqual(changed, self.observability_source)
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_fuv_surface_request_cannot_bypass_diagnostics_consent(self) -> None:
        changed = self.content_view_source.replace(
            "performanceCollectionRequested: observabilityConsent.diagnosticsAllowed",
            "performanceCollectionRequested: true",
            1,
        )
        self.assertNotEqual(changed, self.content_view_source)
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(content_view_source=changed)

    def test_dynamic_crashlytics_collection_is_rejected(self) -> None:
        changed = self.observability_source.replace(
            "setCrashlyticsCollectionEnabled(false)",
            "setCrashlyticsCollectionEnabled(true)",
            1,
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_missing_manual_report_send_is_rejected(self) -> None:
        changed = self.observability_source.replace("sendUnsentReports()", "sendReports()")
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_dynamic_performance_instrumentation_is_rejected(self) -> None:
        changed = self.observability_source.replace(
            "isInstrumentationEnabled = false",
            "isInstrumentationEnabled = consent.diagnosticsAllowed",
            1,
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_missing_installation_deletion_is_rejected(self) -> None:
        changed = self.observability_source.replace(
            "try await Installations.installations().delete()",
            "try await Task.yield()",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_untyped_installation_deletion_intent_is_rejected(self) -> None:
        changed = self.observability_source.replace(
            "replaceConsent(ObservabilityConsent, ownerUserId: String)",
            "replaceConsent(ObservabilityConsent)",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_missing_installation_deletion_reconciliation_is_rejected(self) -> None:
        changed = self.observability_source.replace(
            "reconcileInstallationDeletionIntent",
            "skipInstallationDeletionIntent",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_missing_durable_diagnostics_purge_is_rejected(self) -> None:
        changed = self.observability_source.replace(
            "checkForUnsentReports()",
            "didCrashDuringPreviousExecution()",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_runtime_refresh_before_suspension_is_rejected(self) -> None:
        for function_name, next_function_name in (
            ("bindToAuthenticatedUser", "updateConsent"),
            ("updateConsent", "revokeAllConsent"),
            ("revokeAllConsent", "resetConsentForFreshInstallation"),
            ("retryPendingMaintenance", "suspendEffectiveConsent"),
        ):
            with self.subTest(function_name=function_name):
                function_match = re.search(
                    rf"\bfunc\s+{function_name}\b.*?(?=\bfunc\s+{next_function_name}\b)",
                    self.observability_source,
                    re.DOTALL,
                )
                self.assertIsNotNone(function_match)
                function_source = function_match.group(0)
                suspension = re.search(
                    r"suspendEffectiveConsent\(configureForMaintenance: false\)",
                    function_source,
                )
                refresh = re.search(r"refreshPersistedMaintenanceState\(\)", function_source)
                self.assertIsNotNone(suspension)
                self.assertIsNotNone(refresh)
                changed_function = (
                    function_source[: suspension.start()]
                    + refresh.group(0)
                    + function_source[suspension.end() : refresh.start()]
                    + suspension.group(0)
                    + function_source[refresh.end() :]
                )
                changed = (
                    self.observability_source[: function_match.start()]
                    + changed_function
                    + self.observability_source[function_match.end() :]
                )
                with self.assertRaises(VERIFIER.RepositoryIntegrityError):
                    self.validate(observability_source=changed)

    def test_multiline_runtime_suspension_requires_source_reaudit(self) -> None:
        changed = self.observability_source.replace(
            "suspendEffectiveConsent(configureForMaintenance: false)",
            "suspendEffectiveConsent(\n            configureForMaintenance: false\n        )",
            1,
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_commented_crashlytics_overrides_are_rejected(self) -> None:
        changed = re.sub(
            r"(?m)^(\s*)(.*setCrashlyticsCollectionEnabled\(false\).*)$",
            r"\1// \2",
            self.observability_source,
        )
        self.assertNotEqual(changed, self.observability_source)
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_commented_installation_delete_is_rejected(self) -> None:
        changed = self.observability_source.replace(
            "try await Installations.installations().delete()",
            "// try await Installations.installations().delete()",
            1,
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_ungated_report_send_is_rejected(self) -> None:
        changed = self.observability_source.replace(
            "case .restored:\n"
            "            if effectiveDiagnosticsAllowed {\n"
            "                crashlytics.sendUnsentReports()\n"
            "            }",
            "case .restored:\n"
            "            crashlytics.sendUnsentReports()",
            1,
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_neutralized_pre_persistence_purge_is_rejected(self) -> None:
        changed = self.observability_source.replace(
            "diagnosticsPurgePending: requiresDiagnosticsReportPurge,",
            "diagnosticsPurgePending: false,",
            1,
        )
        self.assertNotEqual(changed, self.observability_source)
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_neutralized_post_persistence_purge_is_rejected(self) -> None:
        changed = self.replace_after(
            "private func attemptConsentRevocation() -> Bool",
            "diagnosticsPurgePending: true,",
            "diagnosticsPurgePending: false,",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_full_revocation_without_installation_deletion_is_rejected(self) -> None:
        changed = self.observability_source.replace(
            "let requiresInstallationDeletion = remoteConfigurationRevoked ||\n"
            "            allCollectionRevoked || installationDeletionState.isPending",
            "let requiresInstallationDeletion = remoteConfigurationRevoked ||\n"
            "            installationDeletionState.isPending",
            1,
        )
        self.assertNotEqual(changed, self.observability_source)
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_unguarded_installation_marker_is_rejected(self) -> None:
        changed = self.replace_after(
            "private func requestFirebaseInstallationDeletion",
            "guard consentStore.markInstallationDeletionPending(intent: intent) else {",
            "guard true else {",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_unguarded_installation_reconciliation_is_rejected(self) -> None:
        changed = self.replace_after(
            "private func resumePendingFirebaseInstallationDeletion",
            "guard consentStore.reconcileInstallationDeletionIntent() else { return }",
            "guard true else { return }",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_stale_installation_completion_guard_is_required(self) -> None:
        changed = self.replace_after(
            "func completeInstallationDeletion",
            "guard record.requestID == expectedRequestID else {",
            "guard true else {",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_report_delete_action_after_check_is_required(self) -> None:
        changed = self.replace_after(
            "private func resumePendingDiagnosticsReportPurge",
            "crashlytics.deleteUnsentReports()",
            "// crashlytics.deleteUnsentReports()",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_unknown_override_requires_report_purge(self) -> None:
        changed = self.replace_after(
            "private func prepareOverrideSanitization",
            "guard markDiagnosticsReportPurgePending(),",
            "guard true,",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_later_same_process_purge_cannot_be_cleared(self) -> None:
        changed = self.observability_source.replace(
            "case .confirmedNoReportsPendingClear:\n"
            "            completeDiagnosticsReportPurge()",
            "case .confirmedNoReportsPendingClear, .checkConsumed:\n"
            "            completeDiagnosticsReportPurge()",
            1,
        ).replace(
            "case .checking, .checkConsumed, .deletionRequested:",
            "case .checking, .deletionRequested:",
            1,
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_configured_override_must_advance_restart_marker(self) -> None:
        changed = self.replace_after(
            "private func requireOverrideSanitization",
            "configuredProcessToken: isConfigured ? firebaseObservabilityProcessToken : nil",
            "configuredProcessToken: nil",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_override_marker_clear_failure_must_fail_closed(self) -> None:
        changed = self.replace_after(
            "case .readyAfterRestart:",
            "overrideSanitizationState = .failure",
            "overrideSanitizationState = .readyAfterRestart",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_scheduled_override_restart_cannot_regress(self) -> None:
        changed = self.replace_after(
            "final class FirebaseConsentStore",
            "case .awaitingRestart, .readyAfterRestart:\n"
            "            return true",
            "case .awaitingRestart, .readyAfterRestart:\n"
            "            break",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_second_ungated_report_send_is_rejected(self) -> None:
        changed = self.replace_after(
            "private func applyConsent",
            "switch diagnosticsReportAction {",
            "crashlytics.sendUnsentReports()\n\n        switch diagnosticsReportAction {",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_allows_any_collection_cannot_be_neutralized(self) -> None:
        changed = self.observability_source.replace(
            "analyticsAllowed || diagnosticsAllowed || remoteConfigurationAllowed",
            "false",
            1,
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_allows_any_collection_cannot_be_suffixed_true(self) -> None:
        changed = self.observability_source.replace(
            "analyticsAllowed || diagnosticsAllowed || remoteConfigurationAllowed",
            "analyticsAllowed || diagnosticsAllowed || remoteConfigurationAllowed || true",
            1,
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_diagnostics_purge_policy_cannot_be_neutralized(self) -> None:
        changed = self.observability_source.replace(
            "case .newlyGranted, .revoked:\n"
            "            return true",
            "case .newlyGranted, .revoked:\n"
            "            return false",
            1,
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_diagnostics_purge_policy_cannot_be_suffixed_true(self) -> None:
        changed = self.replace_after(
            "var requiresDurablePurge",
            "return false",
            "return false || true",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_authenticated_runtime_and_pending_mutation_gates_are_required(self) -> None:
        guarded_expressions = (
            "authenticatedSessionBound &&\n"
            "            !runtimeCollectionSuspended &&\n",
            "            pendingConsentMutation == nil &&\n",
        )
        for guarded_expression in guarded_expressions:
            with self.subTest(guarded_expression=guarded_expression.strip()):
                changed = self.observability_source.replace(guarded_expression, "", 1)
                with self.assertRaises(VERIFIER.RepositoryIntegrityError):
                    self.validate(observability_source=changed)

    def test_effective_analytics_getter_cannot_return_true(self) -> None:
        changed = self.replace_after(
            "private var effectiveAnalyticsAllowed",
            "consent.analyticsAllowed && maintenanceAllowsCollection",
            "true",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_effective_diagnostics_getter_cannot_return_true(self) -> None:
        changed = self.replace_after(
            "private var effectiveDiagnosticsAllowed",
            "consent.diagnosticsAllowed && diagnosticsMaintenanceAllowsCollection",
            "true",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_effective_remote_configuration_getter_cannot_return_true(self) -> None:
        changed = self.replace_after(
            "private var effectiveRemoteConfigurationAllowed",
            "consent.remoteConfigurationAllowed && maintenanceAllowsCollection",
            "true",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_maintenance_gate_cannot_be_suffixed_true(self) -> None:
        changed = self.replace_after(
            "private var maintenanceAllowsCollection",
            "installationDeletionState == .notRequired",
            "installationDeletionState == .notRequired || true",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_diagnostics_maintenance_gate_cannot_be_suffixed_true(self) -> None:
        changed = self.replace_after(
            "private var diagnosticsMaintenanceAllowsCollection",
            "diagnosticsReportPurgeState == .notRequired",
            "diagnosticsReportPurgeState == .notRequired || true",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_premature_report_purge_clear_is_rejected(self) -> None:
        changed = self.replace_after(
            "private func requestDiagnosticsReportPurge",
            "guard consentStore.markDiagnosticsReportPurgePending() else {",
            "_ = consentStore.clearDiagnosticsReportPurgePending()\n"
            "        guard consentStore.markDiagnosticsReportPurgePending() else {",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_diagnostics_marker_cannot_return_success_before_write(self) -> None:
        changed = self.replace_after(
            "func markDiagnosticsReportPurgePending",
            ") -> Bool {\n",
            ") -> Bool {\n        return true\n",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_installation_marker_cannot_return_success_before_write(self) -> None:
        changed = self.replace_after(
            "func markInstallationDeletionPending",
            ") -> Bool {\n",
            ") -> Bool {\n        return true\n",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_installation_record_helper_cannot_return_success_before_write(self) -> None:
        changed = self.replace_after(
            "private func persistInstallationDeletionRecord",
            ") -> Bool {\n",
            ") -> Bool {\n        return true\n",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_installation_record_helper_must_publish_pending_state(self) -> None:
        changed = self.replace_after(
            "private func persistInstallationDeletionRecord",
            "installationDeletionState = .pending(record)",
            "installationDeletionState = .notRequired",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_installation_reconciliation_cannot_return_success_early(self) -> None:
        changed = self.replace_after(
            "func reconcileInstallationDeletionIntent",
            ") -> Bool {\n",
            ") -> Bool {\n        return true\n",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_installation_preparation_cannot_ignore_a_durable_intent(self) -> None:
        changed = self.replace_after(
            "private func prepareInstallationDeletionState",
            ") -> FirebaseInstallationDeletionState {\n",
            ") -> FirebaseInstallationDeletionState {\n        return .notRequired\n",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_installation_completion_cannot_report_completed_early(self) -> None:
        changed = self.replace_after(
            "func completeInstallationDeletion",
            ") -> FirebaseInstallationDeletionCompletion {\n",
            ") -> FirebaseInstallationDeletionCompletion {\n        return .completed\n",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_installation_decoder_cannot_return_nil_early(self) -> None:
        changed = self.replace_after(
            "private func decodeInstallationDeletionRecord",
            ") -> StoredFirebaseInstallationDeletionRecord? {\n",
            ") -> StoredFirebaseInstallationDeletionRecord? {\n        return nil\n",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_keychain_write_cannot_return_success_before_secitem_update(self) -> None:
        changed = self.replace_after(
            "private func writeData",
            ") -> Bool {\n",
            ") -> Bool {\n        return true\n",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_keychain_write_requires_successful_fallback_insert(self) -> None:
        changed = self.replace_after(
            "private func writeData",
            "return SecItemAdd(insert as CFDictionary, nil) == errSecSuccess",
            "return SecItemAdd(insert as CFDictionary, nil) == errSecItemNotFound",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_keychain_write_cannot_suffix_fallback_success_with_true(self) -> None:
        changed = self.replace_after(
            "private func writeData",
            "return SecItemAdd(insert as CFDictionary, nil) == errSecSuccess",
            "return SecItemAdd(insert as CFDictionary, nil) == errSecSuccess || true",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_keychain_read_cannot_report_missing_before_secitem_lookup(self) -> None:
        changed = self.replace_after(
            "private func readData",
            ") -> FirebaseKeychainReadResult {\n",
            ") -> FirebaseKeychainReadResult {\n        return .missing\n",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_keychain_remove_cannot_return_success_before_secitem_delete(self) -> None:
        changed = self.replace_after(
            "private func remove",
            ") -> Bool {\n",
            ") -> Bool {\n        return true\n",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_keychain_remove_cannot_suffix_delete_status_with_true(self) -> None:
        changed = self.replace_after(
            "private func remove",
            "return status == errSecSuccess || status == errSecItemNotFound",
            "return status == errSecSuccess || status == errSecItemNotFound || true",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_keychain_query_must_keep_the_requested_account(self) -> None:
        changed = self.replace_after(
            "private func baseQuery",
            "kSecAttrAccount as String: account",
            "kSecAttrAccount as String: service",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_installation_cannot_be_acknowledged_before_network_delete(self) -> None:
        changed = self.replace_after(
            "private func resumePendingFirebaseInstallationDeletion",
            "try await Installations.installations().delete()",
            "_ = consentStore.completeInstallationDeletion(\n"
            "                    expectedRequestID: deletionRequest.requestID\n"
            "                )\n"
            "                try await Installations.installations().delete()",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_parenthesized_full_revocation_expression_requires_source_reaudit(self) -> None:
        changed = self.observability_source.replace(
            "let allCollectionRevoked = consent.allowsAnyCollection &&\n"
            "            !updatedConsent.allowsAnyCollection",
            "let allCollectionRevoked = (consent.allowsAnyCollection &&\n"
            "            !updatedConsent.allowsAnyCollection)",
            1,
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_audited_source_hash_tolerates_crlf_only(self) -> None:
        changed = self.observability_source.replace("\n", "\r\n")

        self.validate(observability_source=changed)

    def test_coordinator_fresh_install_guard_cannot_be_suffixed_true(self) -> None:
        changed = self.coordinator_source.replace(
            "guard observability.resetConsentForFreshInstallation() else {",
            "guard observability.resetConsentForFreshInstallation() || true else {",
            1,
        )
        self.assertNotEqual(changed, self.coordinator_source)
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(coordinator_source=changed)

    def test_content_view_cannot_emit_a_second_forced_analytics_grant(self) -> None:
        changed = self.content_view_source.replace(
            "onConsentChanged(.analytics, allowed)",
            "onConsentChanged(.analytics, allowed)\n"
            "                        onConsentChanged(.analytics, true)",
            1,
        )
        self.assertNotEqual(changed, self.content_view_source)
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(content_view_source=changed)

    def test_app_foreground_retry_cannot_be_neutralized(self) -> None:
        changed = self.app_source.replace(
            "coordinator.applicationBecameActive()",
            "if false { coordinator.applicationBecameActive() }",
            1,
        )
        self.assertNotEqual(changed, self.app_source)
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(app_source=changed)

    def test_firebase_import_outside_the_audited_adapter_is_rejected(self) -> None:
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            VERIFIER.validate_ios_firebase_source_boundary(
                {
                    VERIFIER.IOS_OBSERVABILITY_SOURCE_PATH: self.observability_source,
                    "iosApp/Kwabor/UnexpectedFirebaseClient.swift": (
                        "import FirebaseAnalytics\nAnalytics.setAnalyticsCollectionEnabled(true)\n"
                    ),
                }
            )

    def test_firebase_usage_outside_the_audited_adapter_is_rejected_without_import(self) -> None:
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            VERIFIER.validate_ios_firebase_source_boundary(
                {
                    VERIFIER.IOS_OBSERVABILITY_SOURCE_PATH: self.observability_source,
                    "iosApp/Kwabor/UnexpectedFirebaseClient.swift": "FirebaseApp.configure()\n",
                }
            )

    def test_objective_c_firebase_usage_outside_the_audited_adapter_is_rejected(self) -> None:
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            VERIFIER.validate_ios_firebase_source_boundary(
                {
                    VERIFIER.IOS_OBSERVABILITY_SOURCE_PATH: self.observability_source,
                    "iosApp/Kwabor/UnexpectedFirebaseClient.m": (
                        "@import FirebaseAnalytics;\n"
                        "[FIRAnalytics setAnalyticsCollectionEnabled:YES];\n"
                    ),
                }
            )

    def test_extra_analytics_collection_enable_is_rejected(self) -> None:
        changed = self.replace_after(
            "private func applyConsent",
            "Analytics.setAnalyticsCollectionEnabled(effectiveAnalyticsAllowed)",
            "Analytics.setAnalyticsCollectionEnabled(effectiveAnalyticsAllowed)\n"
            "        Analytics.setAnalyticsCollectionEnabled(true)",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_extra_performance_collection_enable_is_rejected(self) -> None:
        changed = self.replace_after(
            "private func applyConsent",
            "performance?.isDataCollectionEnabled = effectiveDiagnosticsAllowed",
            "performance?.isDataCollectionEnabled = effectiveDiagnosticsAllowed\n"
            "        performance?.isDataCollectionEnabled = true",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_performance_collection_gate_cannot_be_suffixed_true(self) -> None:
        changed = self.replace_after(
            "private func applyConsent",
            "performance?.isDataCollectionEnabled = effectiveDiagnosticsAllowed",
            "performance?.isDataCollectionEnabled = effectiveDiagnosticsAllowed || true",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_performance_instrumentation_disable_cannot_be_suffixed_true(self) -> None:
        changed = self.observability_source.replace(
            "performance.isInstrumentationEnabled = false",
            "performance.isInstrumentationEnabled = false || true",
            1,
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_report_purge_process_must_start_unchecked(self) -> None:
        changed = self.observability_source.replace(
            "FirebaseDiagnosticsReportPurgeProcessState = .notChecked",
            "FirebaseDiagnosticsReportPurgeProcessState = .confirmedNoReportsPendingClear",
            1,
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_extra_report_purge_completion_is_rejected(self) -> None:
        changed = self.replace_after(
            "private func resumePendingDiagnosticsReportPurge",
            "switch diagnosticsReportPurgeProcessState {",
            "completeDiagnosticsReportPurge()\n"
            "        switch diagnosticsReportPurgeProcessState {",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_configured_override_retry_cannot_skip_transition(self) -> None:
        changed = self.replace_after(
            "private func configureIfNeeded",
            "if isConfigured {\n"
            "            advanceOverrideSanitizationAfterConfiguration()\n"
            "            return\n"
            "        }",
            "if isConfigured {\n"
            "            return\n"
            "        }",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_awaiting_restart_without_token_must_be_repaired(self) -> None:
        changed = self.replace_after(
            "private func prepareOverrideSanitization",
            "guard let scheduledProcessToken = record.processToken else {\n"
            "                    return repairCorruptedOverrideSanitizationMarker()\n"
            "                }",
            "guard let scheduledProcessToken = record.processToken else {\n"
            "                    return .failure\n"
            "                }",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(observability_source=changed)

    def test_missing_installations_product_is_rejected(self) -> None:
        changed = self.xcode_project_source.replace(
            "productName = FirebaseInstallations;",
            "productName = FirebaseCore;",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(xcode_project_source=changed)

    def test_missing_foreground_retry_is_rejected(self) -> None:
        changed = self.app_source.replace(
            "coordinator.applicationBecameActive()",
            "coordinator.introDisplayed()",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(app_source=changed)

    def test_foreground_retry_before_fresh_install_cleanup_is_rejected(self) -> None:
        marker = "guard freshInstallSessionCleanupCompleted else { return }"
        changed = self.coordinator_source.replace(marker, "guard true else { return }", 1)
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(coordinator_source=changed)

    def test_missing_fresh_install_cleanup_is_rejected(self) -> None:
        changed = self.coordinator_source.replace(
            "observability.resetConsentForFreshInstallation()",
            "observability.revokeAllConsent()",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(coordinator_source=changed)

    def test_firebase_bind_before_fresh_install_cleanup_is_rejected(self) -> None:
        changed = self.coordinator_source.replace(
            "guard freshInstallSessionCleanupCompleted else {",
            "guard true else {",
            1,
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(coordinator_source=changed)

    def test_stale_toggle_snapshot_regression_is_rejected(self) -> None:
        changed = self.content_view_source.replace(
            "onConsentChanged(.analytics, allowed)",
            "onConsentChanged(.analytics, consent.analyticsAllowed)",
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(content_view_source=changed)


class AndroidLocalBackupValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manifest_sources = VERIFIER.discover_android_source_manifests(REPOSITORY_ROOT)
        self.backup_rule_sources = VERIFIER.discover_android_backup_rule_sources(REPOSITORY_ROOT)

    def validate(
        self,
        *,
        manifest_sources: dict[str, str] | None = None,
        backup_rule_sources: dict[str, str] | None = None,
    ) -> None:
        VERIFIER.validate_android_local_backup_contract(
            manifest_sources=manifest_sources or self.manifest_sources,
            backup_rule_sources=backup_rule_sources or self.backup_rule_sources,
        )

    def test_current_android_backup_contract_is_accepted(self) -> None:
        self.validate()

    def test_allow_backup_true_is_rejected(self) -> None:
        manifests = dict(self.manifest_sources)
        manifests[VERIFIER.ANDROID_MANIFEST_PATH] = manifests[
            VERIFIER.ANDROID_MANIFEST_PATH
        ].replace('android:allowBackup="false"', 'android:allowBackup="true"', 1)
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "allowBackup"):
            self.validate(manifest_sources=manifests)

    def test_backup_rule_reference_change_is_rejected(self) -> None:
        manifests = dict(self.manifest_sources)
        manifests[VERIFIER.ANDROID_MANIFEST_PATH] = manifests[
            VERIFIER.ANDROID_MANIFEST_PATH
        ].replace("@xml/backup_rules", "@xml/permissive_backup_rules", 1)
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "fullBackupContent"):
            self.validate(manifest_sources=manifests)

    def test_missing_backup_domain_is_rejected(self) -> None:
        rules = dict(self.backup_rule_sources)
        rules[VERIFIER.ANDROID_BACKUP_RULES_PATH] = rules[
            VERIFIER.ANDROID_BACKUP_RULES_PATH
        ].replace('    <exclude domain="database" path="." />\n', "", 1)
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "every audited"):
            self.validate(backup_rule_sources=rules)

    def test_include_element_is_rejected(self) -> None:
        rules = dict(self.backup_rule_sources)
        rules[VERIFIER.ANDROID_BACKUP_RULES_PATH] = rules[
            VERIFIER.ANDROID_BACKUP_RULES_PATH
        ].replace("<exclude ", "<include ", 1)
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "only exclude"):
            self.validate(backup_rule_sources=rules)

    def test_missing_device_transfer_section_is_rejected(self) -> None:
        rules = dict(self.backup_rule_sources)
        source = rules[VERIFIER.ANDROID_DATA_EXTRACTION_RULES_PATH]
        section_start = source.index("    <device-transfer>")
        section_end = source.index("    </device-transfer>") + len("    </device-transfer>\n")
        rules[VERIFIER.ANDROID_DATA_EXTRACTION_RULES_PATH] = (
            source[:section_start] + source[section_end:]
        )
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "device-transfer"):
            self.validate(backup_rule_sources=rules)

    def test_variant_cannot_enable_backup(self) -> None:
        manifests = dict(self.manifest_sources)
        manifests["androidApp/src/debug/AndroidManifest.xml"] = """\
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:allowBackup="true" />
</manifest>
"""
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "allowBackup"):
            self.validate(manifest_sources=manifests)

    def test_variant_backup_rules_are_rejected(self) -> None:
        rules = dict(self.backup_rule_sources)
        rules["androidApp/src/debug/res/xml/backup_rules.xml"] = rules[
            VERIFIER.ANDROID_BACKUP_RULES_PATH
        ]
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "audited main"):
            self.validate(backup_rule_sources=rules)

    def test_qualified_backup_rules_are_rejected(self) -> None:
        rules = dict(self.backup_rule_sources)
        rules["androidApp/src/main/res/xml-v36/data_extraction_rules.xml"] = rules[
            VERIFIER.ANDROID_DATA_EXTRACTION_RULES_PATH
        ]
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "audited main"):
            self.validate(backup_rule_sources=rules)

    def test_cross_module_qualified_backup_rules_are_rejected(self) -> None:
        rules = dict(self.backup_rule_sources)
        rules["shared/src/androidMain/res/xml-v36/data_extraction_rules.xml"] = rules[
            VERIFIER.ANDROID_DATA_EXTRACTION_RULES_PATH
        ]
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "audited main"):
            self.validate(backup_rule_sources=rules)


class AndroidRoomStorageContractValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.source = (
            REPOSITORY_ROOT / VERIFIER.ANDROID_ROOM_DATABASE_BUILDER_PATH
        ).read_text(encoding="utf-8")

    def test_current_android_room_storage_contract_is_accepted(self) -> None:
        VERIFIER.validate_android_room_storage_contract(self.source)

    def test_no_backup_directory_cannot_be_removed(self) -> None:
        changed = self.source.replace("context.noBackupFilesDir", "context.filesDir")
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "local-storage control"):
            VERIFIER.validate_android_room_storage_contract(changed)

    def test_memory_only_fallback_cannot_be_removed(self) -> None:
        changed = self.source.replace(
            "Room.inMemoryDatabaseBuilder<KwaborDatabase>(",
            "Room.databaseBuilder<KwaborDatabase>(",
        )
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "local-storage control"):
            VERIFIER.validate_android_room_storage_contract(changed)

    def test_broad_exception_catch_is_rejected(self) -> None:
        changed = self.source.replace("catch (_: IOException)", "catch (_: Exception)")
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "local-storage control"):
            VERIFIER.validate_android_room_storage_contract(changed)

    def test_broad_legacy_deletion_catch_is_rejected(self) -> None:
        changed = self.source.replace(
            "catch (_: SecurityException)",
            "catch (_: Exception)",
            1,
        )
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "filesystem failures"):
            VERIFIER.validate_android_room_storage_contract(changed)

    def test_short_circuiting_legacy_cleanup_is_rejected(self) -> None:
        changed = self.source.replace(
            "ANDROID_DATABASE_FILE_SUFFIXES.forEach",
            "ANDROID_DATABASE_FILE_SUFFIXES.all",
        )
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "local-storage control"):
            VERIFIER.validate_android_room_storage_contract(changed)

    def test_late_legacy_cleanup_is_rejected(self) -> None:
        early_cleanup = "val legacyFilesRemoved = removeLegacyAndroidDatabaseFiles(context)"
        no_backup_root = "val noBackupRoot = context.noBackupFilesDir.canonicalFile"
        changed = self.source.replace(
            f"    {early_cleanup}\n    {no_backup_root}",
            f"    {no_backup_root}\n    {early_cleanup}",
        )
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "before preparing"):
            VERIFIER.validate_android_room_storage_contract(changed)

    def test_crlf_is_accepted(self) -> None:
        VERIFIER.validate_android_room_storage_contract(self.source.replace("\n", "\r\n"))


class IosRoomStorageContractValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.source = (
            REPOSITORY_ROOT / VERIFIER.IOS_ROOM_DATABASE_BUILDER_PATH
        ).read_text(encoding="utf-8")

    def test_current_ios_room_storage_contract_is_accepted(self) -> None:
        VERIFIER.validate_ios_room_storage_contract(self.source)

    def test_backup_exclusion_cannot_be_removed(self) -> None:
        changed = self.source.replace("NSURLIsExcludedFromBackupKey", "NSURLNameKey")
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "local-storage control"):
            VERIFIER.validate_ios_room_storage_contract(changed)

    def test_file_protection_cannot_be_weakened(self) -> None:
        changed = self.source.replace(
            "NSFileProtectionCompleteUntilFirstUserAuthentication",
            "NSFileProtectionNone",
        )
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "local-storage control"):
            VERIFIER.validate_ios_room_storage_contract(changed)

    def test_database_family_cleanup_cannot_be_narrowed(self) -> None:
        changed = self.source.replace(
            'listOf("", "-wal", "-shm", "-journal")',
            'listOf("")',
        )
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "local-storage control"):
            VERIFIER.validate_ios_room_storage_contract(changed)

    def test_legacy_cleanup_cannot_move_after_directory_preparation(self) -> None:
        cleanup = """\
    removeLegacyIosDatabaseFiles(
        applicationSupportPath = applicationSupportPath,
        fileManager = fileManager,
    )
"""
        changed = self.source.replace(cleanup, "", 1).replace(
            "    val roomDirectoryUrl = resolveIosRoomDirectoryUrl(applicationSupportUrl)\n",
            "    val roomDirectoryUrl = resolveIosRoomDirectoryUrl(applicationSupportUrl)\n" + cleanup,
            1,
        )
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "before preparing"):
            VERIFIER.validate_ios_room_storage_contract(changed)

    def test_policy_failures_cannot_be_swallowed(self) -> None:
        changed = self.source + "\nprivate fun unsafe() = runCatching { Unit }\n"
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "runCatching"):
            VERIFIER.validate_ios_room_storage_contract(changed)

    def test_memory_only_fallback_cannot_be_removed(self) -> None:
        changed = self.source.replace(
            "Room.inMemoryDatabaseBuilder<KwaborDatabase>(",
            "Room.databaseBuilder<KwaborDatabase>(",
        )
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "local-storage control"):
            VERIFIER.validate_ios_room_storage_contract(changed)

    def test_broad_exception_catch_is_rejected(self) -> None:
        changed = self.source.replace(
            "catch (_: IosRoomStoragePolicyException)",
            "catch (_: Exception)",
        )
        with self.assertRaisesRegex(VERIFIER.RepositoryIntegrityError, "local-storage control"):
            VERIFIER.validate_ios_room_storage_contract(changed)

    def test_production_file_protection_adapter_cannot_be_stubbed(self) -> None:
        delegation = """\
        setAttributes(
            attributes = attributes,
            ofItemAtPath = path,
            error = null,
        )
"""
        changed = self.source.replace(delegation, "        true\n", 1)
        self.assertNotEqual(changed, self.source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "production file-protection adapter",
        ):
            VERIFIER.validate_ios_room_storage_contract(changed)

    def test_both_storage_scopes_must_use_the_protection_applicator(self) -> None:
        changed = self.source.replace(
            "protectionApplicator.apply(",
            "bypassedProtectionApplicator.apply(",
            1,
        )
        self.assertNotEqual(changed, self.source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "Room directory and existing database family",
        ):
            VERIFIER.validate_ios_room_storage_contract(changed)

    def test_both_entry_points_must_default_to_the_production_adapter(self) -> None:
        changed = self.source.replace(
            "fileManager.iosRoomFileProtectionApplicator()",
            "testOnlyFileProtectionApplicator()",
            1,
        )
        self.assertNotEqual(changed, self.source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "default both entry points",
        ):
            VERIFIER.validate_ios_room_storage_contract(changed)

    def test_crlf_is_accepted(self) -> None:
        VERIFIER.validate_ios_room_storage_contract(self.source.replace("\n", "\r\n"))


class AndroidFirebasePrivacyValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manifest_source = (
            REPOSITORY_ROOT / VERIFIER.ANDROID_MANIFEST_PATH
        ).read_text(encoding="utf-8")
        self.gradle_properties_source = (
            REPOSITORY_ROOT / VERIFIER.ANDROID_GRADLE_PROPERTIES_PATH
        ).read_text(encoding="utf-8")
        self.build_gradle_source = (
            REPOSITORY_ROOT / VERIFIER.ANDROID_BUILD_GRADLE_PATH
        ).read_text(encoding="utf-8")
        self.root_build_gradle_source = (
            REPOSITORY_ROOT / VERIFIER.ROOT_BUILD_GRADLE_PATH
        ).read_text(encoding="utf-8")
        self.backend_source = (
            REPOSITORY_ROOT / VERIFIER.ANDROID_OBSERVABILITY_BACKEND_PATH
        ).read_text(encoding="utf-8")
        self.controller_source = (
            REPOSITORY_ROOT / VERIFIER.ANDROID_OBSERVABILITY_CONTROLLER_PATH
        ).read_text(encoding="utf-8")
        self.store_source = (
            REPOSITORY_ROOT / VERIFIER.ANDROID_OBSERVABILITY_STORE_PATH
        ).read_text(encoding="utf-8")
        self.runtime_source = (
            REPOSITORY_ROOT / VERIFIER.ANDROID_OBSERVABILITY_RUNTIME_PATH
        ).read_text(encoding="utf-8")
        self.maintenance_source = (
            REPOSITORY_ROOT / VERIFIER.ANDROID_OBSERVABILITY_MAINTENANCE_PATH
        ).read_text(encoding="utf-8")
        self.remote_configuration_source = (
            REPOSITORY_ROOT / VERIFIER.ANDROID_REMOTE_CONFIGURATION_COORDINATOR_PATH
        ).read_text(encoding="utf-8")
        self.main_activity_source = (
            REPOSITORY_ROOT / VERIFIER.ANDROID_MAIN_ACTIVITY_PATH
        ).read_text(encoding="utf-8")

    def validate(self, **overrides: str) -> None:
        sources = {
            "manifest_source": self.manifest_source,
            "gradle_properties_source": self.gradle_properties_source,
            "build_gradle_source": self.build_gradle_source,
            "root_build_gradle_source": self.root_build_gradle_source,
            "backend_source": self.backend_source,
            "controller_source": self.controller_source,
            "store_source": self.store_source,
            "runtime_source": self.runtime_source,
            "maintenance_source": self.maintenance_source,
            "remote_configuration_source": self.remote_configuration_source,
            "main_activity_source": self.main_activity_source,
        }
        sources.update(overrides)
        VERIFIER.validate_android_firebase_privacy_contract(**sources)

    def test_current_android_contract_is_accepted(self) -> None:
        self.validate()
        observed_session_sources = read_observed_app_session_sources()
        VERIFIER.validate_observed_app_session_source_contract(observed_session_sources)
        self.assertEqual(
            set(observed_session_sources),
            set(VERIFIER.OBSERVED_APP_SESSION_CRITICAL_SOURCE_SHA256),
        )
        missing_source = dict(observed_session_sources)
        missing_source.pop(VERIFIER.OBSERVED_APP_SESSION_TRACKER_PATH)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "critical-source inventory changed",
        ):
            VERIFIER.validate_observed_app_session_source_contract(missing_source)
        for source_path in sorted(observed_session_sources):
            with self.subTest(observed_session_source_drift=source_path):
                changed_sources = dict(observed_session_sources)
                changed_sources[source_path] += "\n"
                with self.assertRaisesRegex(
                    VERIFIER.RepositoryIntegrityError,
                    "changed outside its audited observed-session snapshot",
                ):
                    VERIFIER.validate_observed_app_session_source_contract(changed_sources)

    def test_fuv_measurement_cannot_bypass_effective_diagnostics_consent(self) -> None:
        changed = self.controller_source.replace(
            "if (isCollectionAllowed()) backend.recordPerformanceMeasurement(measurement)",
            "backend.recordPerformanceMeasurement(measurement)",
            1,
        )
        self.assertNotEqual(changed, self.controller_source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "missing durable privacy controls",
        ):
            self.validate(controller_source=changed)

    def test_ios_checkpoint_backup_exclusion_cannot_be_bypassed(self) -> None:
        observed_session_sources = read_observed_app_session_sources()
        ios_store_source = observed_session_sources[
            VERIFIER.IOS_OBSERVED_APP_SESSION_STORE_PATH
        ]
        changed = ios_store_source.replace(
            "!directoryCreated || "
            "!backupExclusionApplicator.exclude(directoryUrl, backupKey)",
            "!directoryCreated",
            1,
        )
        self.assertNotEqual(changed, ios_store_source)
        observed_session_sources[
            VERIFIER.IOS_OBSERVED_APP_SESSION_STORE_PATH
        ] = changed
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "backup-excluded before persistence",
        ):
            VERIFIER.validate_observed_app_session_source_contract(
                observed_session_sources
            )

    def test_firebase_init_provider_removal_is_required(self) -> None:
        changed = self.manifest_source.replace(
            'android:name="com.google.firebase.provider.FirebaseInitProvider"\n'
            '            tools:node="remove"',
            'android:name="com.google.firebase.provider.FirebaseInitProvider"\n'
            '            tools:node="merge"',
            1,
        )
        self.assertNotEqual(changed, self.manifest_source)
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(manifest_source=changed)

    def test_every_attribution_permission_must_be_explicitly_removed(self) -> None:
        for permission_name in VERIFIER.ANDROID_FORBIDDEN_ATTRIBUTION_PERMISSIONS:
            with self.subTest(permission_name=permission_name):
                removal = (
                    f'android:name="{permission_name}"\n'
                    '        tools:node="remove"'
                )
                changed = self.manifest_source.replace(
                    removal,
                    f'android:name="{permission_name}"',
                    1,
                )
                self.assertNotEqual(changed, self.manifest_source)
                with self.assertRaises(VERIFIER.RepositoryIntegrityError):
                    self.validate(manifest_source=changed)

    def test_adservices_library_must_be_explicitly_removed(self) -> None:
        changed = self.manifest_source.replace(
            'android:name="android.ext.adservices"\n            tools:node="remove"',
            'android:name="android.ext.adservices"',
            1,
        )
        self.assertNotEqual(changed, self.manifest_source)
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(manifest_source=changed)

    def test_privacy_removal_cannot_be_scoped_with_tools_selector(self) -> None:
        changed = self.manifest_source.replace(
            'tools:node="remove" />',
            'tools:node="remove"\n        tools:selector="com.google.firebase" />',
            1,
        )
        self.assertNotEqual(changed, self.manifest_source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "without tools:selector",
        ):
            self.validate(manifest_source=changed)

    def test_every_manifest_collection_default_must_remain_false(self) -> None:
        changed = self.manifest_source.replace(
            'android:name="firebase_data_collection_default_enabled"\n            android:value="false"',
            'android:name="firebase_data_collection_default_enabled"\n            android:value="true"',
            1,
        )
        self.assertNotEqual(changed, self.manifest_source)
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(manifest_source=changed)

    def test_performance_instrumentation_property_must_remain_false(self) -> None:
        changed = self.gradle_properties_source.replace(
            "firebasePerformanceInstrumentationEnabled=false",
            "firebasePerformanceInstrumentationEnabled=true",
            1,
        )
        with self.assertRaises(VERIFIER.RepositoryIntegrityError):
            self.validate(gradle_properties_source=changed)

    def test_firebase_installations_dependency_is_required(self) -> None:
        changed = self.build_gradle_source.replace(
            'implementation("com.google.firebase:firebase-installations")',
            'implementation("com.google.firebase:firebase-config")',
            1,
        )
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "must declare exactly the audited Firebase dependency set",
        ):
            self.validate(build_gradle_source=changed)

    def test_unexpected_firebase_dependency_is_rejected(self) -> None:
        changed = self.build_gradle_source.replace(
            'implementation("com.google.firebase:firebase-perf")',
            'implementation("com.google.firebase:firebase-perf")\n'
            '    implementation("com.google.firebase:firebase-messaging")',
            1,
        )
        self.assertNotEqual(changed, self.build_gradle_source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "must declare exactly the audited Firebase dependency set",
        ):
            self.validate(build_gradle_source=changed)

    def test_firebase_dependency_outside_android_app_is_rejected(self) -> None:
        configuration_files = VERIFIER.discover_gradle_configuration_files(REPOSITORY_ROOT)
        unexpected_references = {
            "shared/build.gradle.kts": (
                'androidMain.dependencies { implementation("com.google.firebase:'
                'firebase-messaging") }'
            ),
            "feature/build.gradle.kts": (
                'implementation(group = "com.google.firebase", '
                'name = "firebase-messaging", version = "25.0.0")'
            ),
            "legacy/build.gradle": (
                'implementation group: "com.google.firebase", '
                'name: "firebase-messaging", version: "25.0.0"'
            ),
            "gradle/libs.versions.toml": (
                'firebase-messaging = { group = "com.google.firebase", '
                'name = "firebase-messaging", version = "25.0.0" }'
            ),
            "dynamic/build.gradle.kts": (
                'implementation("com.google.firebase" + ":firebase-messaging:25.0.0")'
            ),
            "split/build.gradle.kts": (
                'implementation("com.google." + "fire" + "base:firebase-messaging:25.0.0")'
            ),
        }
        for source_path, source in unexpected_references.items():
            with self.subTest(source_path=source_path):
                changed = dict(configuration_files)
                changed[source_path] = source
                with self.assertRaisesRegex(
                    VERIFIER.RepositoryIntegrityError,
                    "Firebase Gradle references must only appear in the audited",
                ):
                    VERIFIER.validate_android_firebase_dependency_boundary(changed)

    def test_gradle_snapshot_rejects_expression_that_defeats_lexical_compaction(self) -> None:
        configuration_files = VERIFIER.discover_gradle_configuration_files(REPOSITORY_ROOT)
        configuration_files["shared/build.gradle.kts"] += (
            '\nandroidMain.dependencies { implementation("com.google." + '
            '("fire" + "base") + ":firebase-messaging:25.0.0") }\n'
        )
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "shared/build.gradle.kts changed outside its audited Gradle configuration snapshot",
        ):
            VERIFIER.validate_android_firebase_dependency_boundary(configuration_files)

    def test_gradle_snapshot_rejects_a_new_build_script_without_firebase_text(self) -> None:
        configuration_files = VERIFIER.discover_gradle_configuration_files(REPOSITORY_ROOT)
        configuration_files["feature/build.gradle.kts"] = "plugins {}\n"
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "Gradle configuration inventory changed outside its audited snapshot",
        ):
            VERIFIER.validate_android_firebase_dependency_boundary(configuration_files)

    def test_firebase_bom_version_is_exact(self) -> None:
        changed = self.build_gradle_source.replace(
            "com.google.firebase:firebase-bom:34.15.0",
            "com.google.firebase:firebase-bom:34.16.0",
            1,
        )
        self.assertNotEqual(changed, self.build_gradle_source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "must declare exactly the audited Firebase dependency set",
        ):
            self.validate(build_gradle_source=changed)

    def test_evaluated_firebase_dependency_boundary_task_is_required(self) -> None:
        changed = self.root_build_gradle_source.replace(
            "val verifyFirebaseDependencyBoundary by tasks.registering",
            "val disabledFirebaseDependencyBoundary by tasks.registering",
            1,
        )
        self.assertNotEqual(changed, self.root_build_gradle_source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "must register the evaluated Firebase dependency boundary",
        ):
            self.validate(root_build_gradle_source=changed)

    def test_merged_manifest_verification_task_is_required(self) -> None:
        changed = self.build_gradle_source.replace(
            "val verifyFirebaseMergedManifests by tasks.registering",
            "val disabledFirebaseManifestVerification by tasks.registering",
            1,
        )
        self.assertNotEqual(changed, self.build_gradle_source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "must register the audited verifyFirebaseMergedManifests task",
        ):
            self.validate(build_gradle_source=changed)

    def test_crashlytics_cannot_be_enabled_automatically(self) -> None:
        changed = self.backend_source.replace(
            "setCrashlyticsCollectionEnabled(false)",
            "setCrashlyticsCollectionEnabled(true)",
            1,
        )
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "must keep automatic Crashlytics disabled",
        ):
            self.validate(backend_source=changed)

    def test_concrete_firebase_backend_must_remain_private(self) -> None:
        changed = self.backend_source.replace(
            "private class FirebaseAndroidObservabilityBackend(",
            "internal class FirebaseAndroidObservabilityBackend(",
            1,
        )
        self.assertNotEqual(changed, self.backend_source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "missing lazy-safe Firebase controls",
        ):
            self.validate(backend_source=changed)

    def test_controller_must_replay_a_pending_consent_update(self) -> None:
        changed = self.controller_source.replace(
            "is PendingConsentMutation.Update -> attemptConsentUpdate(pending)",
            "is PendingConsentMutation.Update -> Unit",
            1,
        )
        self.assertNotEqual(changed, self.controller_source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "missing durable privacy controls",
        ):
            self.validate(controller_source=changed)

    def test_store_cannot_drop_request_id_comparison(self) -> None:
        changed = self.store_source.replace(
            "currentRequestId != expectedRequestId",
            "false",
            1,
        )
        self.assertNotEqual(changed, self.store_source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "missing durable privacy storage controls",
        ):
            self.validate(store_source=changed)

    def test_store_cannot_fallback_to_asynchronous_apply(self) -> None:
        changed = self.store_source.replace(
            "preferences.edit().applyMutation(mutation).commit()",
            "preferences.edit().applyMutation(mutation).apply().let { true }",
            1,
        )
        self.assertNotEqual(changed, self.store_source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "must not use asynchronous SharedPreferences.apply",
        ):
            self.validate(store_source=changed)

    def test_runtime_cannot_skip_installation_deletion_maintenance(self) -> None:
        changed = self.runtime_source.replace(
            "installationDeletion.resume(installationDeletionRequestId)",
            "return",
            1,
        )
        self.assertNotEqual(changed, self.runtime_source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "missing fail-closed runtime controls",
        ):
            self.validate(runtime_source=changed)

    def test_maintenance_cannot_accept_a_stale_installation_callback(self) -> None:
        changed = self.maintenance_source.replace(
            "if (inFlightRequestId != requestId) return",
            "if (false) return",
            1,
        )
        self.assertNotEqual(changed, self.maintenance_source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "missing stale-safe maintenance controls",
        ):
            self.validate(maintenance_source=changed)

    def test_remote_configuration_cannot_accept_a_stale_generation(self) -> None:
        changed = self.remote_configuration_source.replace(
            "if (!isActive(activeGeneration)) return",
            "if (false) return",
            1,
        )
        self.assertNotEqual(changed, self.remote_configuration_source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "missing generation-safe remote configuration controls",
        ):
            self.validate(remote_configuration_source=changed)

    def test_foreground_maintenance_retry_is_required(self) -> None:
        changed = self.main_activity_source.replace(
            "observability.retryPendingMaintenance()",
            "observability.close()",
            1,
        )
        self.assertNotEqual(changed, self.main_activity_source)
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "must retry maintenance and forward observed-session lifecycle",
        ):
            self.validate(main_activity_source=changed)

    def test_android_source_hash_tolerates_crlf_only(self) -> None:
        self.validate(backend_source=self.backend_source.replace("\n", "\r\n"))

    def test_firebase_import_outside_the_audited_backend_is_rejected(self) -> None:
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "must not access Firebase directly",
        ):
            VERIFIER.validate_android_firebase_source_boundary(
                {
                    VERIFIER.ANDROID_OBSERVABILITY_BACKEND_PATH: self.backend_source,
                    "androidApp/src/main/kotlin/com/kwabor/android/UnexpectedFirebaseClient.kt": (
                        "import com.google.firebase.crashlytics.FirebaseCrashlytics\n"
                        "FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)\n"
                    ),
                }
            )

    def test_split_firebase_reflection_outside_the_backend_is_rejected(self) -> None:
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "must not load classes dynamically outside",
        ):
            VERIFIER.validate_android_firebase_source_boundary(
                {
                    "shared/src/androidMain/kotlin/UnexpectedFirebaseClient.kt": (
                        'val firebaseClass = Class.forName("com.google." + '
                        '"fire" + "base.FirebaseApp")\n'
                        'firebaseClass.getDeclaredMethod("initializeApp")\n'
                    )
                }
            )

    def test_dynamic_class_loading_is_rejected_even_without_firebase_text(self) -> None:
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "must not load classes dynamically outside",
        ):
            VERIFIER.validate_android_firebase_source_boundary(
                {
                    "androidApp/src/main/kotlin/UnexpectedLoader.kt": (
                        'PathClassLoader("payload.dex", parent).loadClass("example.Payload")\n'
                    )
                }
            )

    def test_dynamic_class_function_reference_is_rejected(self) -> None:
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "must not load classes dynamically outside",
        ):
            VERIFIER.validate_android_firebase_source_boundary(
                {
                    "androidApp/src/main/kotlin/UnexpectedLoader.kt": (
                        "val loader: (String) -> Class<*> = Class::forName\n"
                        'loader(listOf("com.google.", "fire", "base.", "Fire", '
                        '"baseApp").joinToString(""))\n'
                    )
                }
            )

    def test_unicode_escaped_firebase_import_is_rejected(self) -> None:
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "must not access Firebase directly",
        ):
            VERIFIER.validate_android_firebase_source_boundary(
                {
                    "shared/src/androidMain/kotlin/UnicodeImport.kt": (
                        r"\u0069mport com.google.firebase.FirebaseApp" "\n"
                    )
                }
            )

    def test_private_firebase_backend_cannot_be_referenced_outside_its_file(self) -> None:
        with self.assertRaisesRegex(
            VERIFIER.RepositoryIntegrityError,
            "must not reference the private Firebase backend",
        ):
            VERIFIER.validate_android_firebase_source_boundary(
                {
                    "androidApp/src/main/kotlin/UnexpectedBackendFactory.kt": (
                        "FirebaseAndroidObservabilityBackend(context)\n"
                    )
                }
            )

    def test_current_android_source_roots_and_manifests_are_accepted(self) -> None:
        source_files = VERIFIER.discover_android_firebase_source_files(REPOSITORY_ROOT)
        gradle_files = VERIFIER.discover_gradle_configuration_files(REPOSITORY_ROOT)
        self.assertTrue(any(path.startswith("androidApp/src/") for path in source_files))
        self.assertTrue(any(path.startswith("shared/src/androidMain/") for path in source_files))
        self.assertIn(VERIFIER.ANDROID_BUILD_GRADLE_PATH, gradle_files)
        self.assertIn("shared/build.gradle.kts", gradle_files)
        VERIFIER.validate_android_firebase_source_boundary(source_files)
        VERIFIER.validate_android_firebase_dependency_boundary(gradle_files)
        VERIFIER.validate_android_source_manifests(
            VERIFIER.discover_android_source_manifests(REPOSITORY_ROOT)
        )

    def test_source_discovery_rejects_reflection_from_shared_android_main(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            android_source = repository_root / "androidApp/src/main/kotlin/Safe.kt"
            shared_source = repository_root / "shared/src/androidMain/kotlin/Unsafe.kt"
            android_source.parent.mkdir(parents=True)
            shared_source.parent.mkdir(parents=True)
            android_source.write_text("class Safe", encoding="utf-8")
            shared_source.write_text(
                'Class.forName("com.google." + "fire" + "base.FirebaseApp")',
                encoding="utf-8",
            )

            discovered = VERIFIER.discover_android_firebase_source_files(repository_root)

            self.assertEqual(
                set(discovered),
                {
                    "androidApp/src/main/kotlin/Safe.kt",
                    "shared/src/androidMain/kotlin/Unsafe.kt",
                },
            )
            with self.assertRaises(VERIFIER.RepositoryIntegrityError):
                VERIFIER.validate_android_firebase_source_boundary(discovered)

    def test_source_manifest_discovery_rejects_unsafe_variant(self) -> None:
        unsafe_variant = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="com.google.android.gms.permission.AD_ID" />
    <application>
        <meta-data
            android:name="firebase_data_collection_default_enabled"
            android:value="true" />
    </application>
</manifest>
"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            repository_root = Path(temporary_directory)
            main_manifest = repository_root / VERIFIER.ANDROID_MANIFEST_PATH
            debug_manifest = repository_root / "androidApp/src/debug/AndroidManifest.xml"
            main_manifest.parent.mkdir(parents=True)
            debug_manifest.parent.mkdir(parents=True)
            main_manifest.write_text(self.manifest_source, encoding="utf-8")
            debug_manifest.write_text(unsafe_variant, encoding="utf-8")

            discovered = VERIFIER.discover_android_source_manifests(repository_root)

            self.assertEqual(
                set(discovered),
                {
                    VERIFIER.ANDROID_MANIFEST_PATH,
                    "androidApp/src/debug/AndroidManifest.xml",
                },
            )
            with self.assertRaises(VERIFIER.RepositoryIntegrityError):
                VERIFIER.validate_android_source_manifests(discovered)


if __name__ == "__main__":
    unittest.main()
