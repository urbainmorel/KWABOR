import FirebaseAnalytics
import FirebaseCore
import FirebaseCrashlytics
import FirebaseInstallations
import FirebasePerformance
import FirebaseRemoteConfig
import CryptoKit
import Foundation
import Security
import Shared

enum ObservabilityConsentCategory {
    case analytics
    case diagnostics
    case remoteConfiguration
}

@MainActor
final class FirebaseObservability {
    private let options: FirebaseOptions?
    private let consentStore: FirebaseConsentStore
    private let sessionTracker: ConsentedAppSessionTracker?
    private var remoteConfig: RemoteConfig?
    private var remoteConfigUpdateRegistration: ConfigUpdateListenerRegistration?
    private var remoteConfigurationGeneration = 0
    private var performance: Performance?
    private var diagnosticsReportsArmed = false
    private var diagnosticsReportPurgeState: FirebaseDiagnosticsReportPurgeState
    private var diagnosticsReportPurgeProcessState: FirebaseDiagnosticsReportPurgeProcessState = .notChecked
    private var overrideSanitizationState: FirebaseOverrideSanitizationState
    private var installationDeletionState: FirebaseInstallationDeletionState
    private var privacyTransactionState: FirebasePrivacyTransactionState
    private var installationDeletionInFlight = false
    private var authenticatedSessionBound = false
    private var runtimeCollectionSuspended = true
    private var pendingConsentMutation: PendingObservabilityConsentMutation?
    private var publishedPerformanceCollectionAllowed = false

    private(set) var consent: ObservabilityConsent
    private(set) var isConfigured = false
    var onPerformanceCollectionEligibilityChanged: ((Bool) -> Void)? {
        didSet {
            let allowed = isPerformanceCollectionAllowed
            publishedPerformanceCollectionAllowed = allowed
            onPerformanceCollectionEligibilityChanged?(allowed)
        }
    }

    init(
        sessionTracker: ConsentedAppSessionTracker? = nil,
        bundle: Bundle = .main,
        legacyUserDefaults: UserDefaults = .standard
    ) {
        let consentStore = FirebaseConsentStore(
            service: (bundle.bundleIdentifier ?? fallbackConsentService) + consentServiceSuffix,
            legacyUserDefaults: legacyUserDefaults,
            processToken: firebaseObservabilityProcessToken
        )
        self.consentStore = consentStore
        self.sessionTracker = sessionTracker
        diagnosticsReportPurgeState = consentStore.diagnosticsReportPurgeState
        overrideSanitizationState = consentStore.overrideSanitizationState
        installationDeletionState = consentStore.installationDeletionState
        privacyTransactionState = consentStore.privacyTransactionState
        consent = disabledObservabilityConsent()

        guard
            let configurationPath = bundle.path(forResource: "GoogleService-Info", ofType: "plist"),
            let configurationOptions = FirebaseOptions(contentsOfFile: configurationPath),
            configurationOptions.bundleID == bundle.bundleIdentifier
        else {
            options = nil
            return
        }
        options = configurationOptions
    }

    @discardableResult
    func bindToAuthenticatedUser(_ userId: String?) -> Bool {
        let requestedUserId = normalizedUserId(userId)
        suspendEffectiveConsent(configureForMaintenance: false)
        authenticatedSessionBound = false
        refreshPersistedMaintenanceState()
        guard durablySupersedePendingUpdateIfNeeded(requestedUserId: requestedUserId) else {
            return false
        }
        guard consentStore.reconcileInstallationDeletionIntent() else {
            return false
        }
        installationDeletionState = consentStore.refreshInstallationDeletionState()
        privacyTransactionState = consentStore.refreshPrivacyTransactionState()
        if case let .pending(transaction) = privacyTransactionState {
            let requestedOwnerFingerprint = requestedUserId.map(ownerFingerprint)
            if let requestedOwnerFingerprint,
               let pendingOwnerFingerprint = transaction.replacementConsent?.ownerFingerprint,
               pendingOwnerFingerprint != requestedOwnerFingerprint {
                guard consentStore.stagePrivacyTransaction(
                    replacementConsent: nil,
                    ownerUserId: nil,
                    diagnosticsPurgePending: true,
                    installationDeletionPending: true,
                    analyticsOverrideSanitizationPending: true,
                    diagnosticsOverrideSanitizationPending: true
                ) else {
                    return false
                }
                privacyTransactionState = consentStore.privacyTransactionState
            }
            guard resumeDurablePrivacyTransaction() else { return false }
            refreshPersistedMaintenanceState()
        }
        guard pendingConsentMutation == nil else { return false }
        guard overrideSanitizationState != .failure,
              diagnosticsReportPurgeState != .failure,
              installationDeletionState != .failure,
              privacyTransactionState != .failure else {
            return false
        }
        guard let userId = requestedUserId else {
            switch consentStore.stageDisabledSessionCleanupPreservingConsent() {
            case .failure:
                return false
            case .knownState:
                break
            case .unknownState:
                guard prepareUnknownFirebaseStateForRevocation() else { return false }
            }
            privacyTransactionState = consentStore.privacyTransactionState
            guard resumeDurablePrivacyTransaction() else { return false }
            resumePendingFirebaseInstallationDeletion()
            return true
        }
        switch consentStore.read() {
        case .missing:
            guard consentStore.stagePrivacyTransaction(
                replacementConsent: nil,
                ownerUserId: nil,
                diagnosticsPurgePending: false,
                installationDeletionPending: false
            ) else {
                return false
            }
            privacyTransactionState = consentStore.privacyTransactionState
            guard resumeDurablePrivacyTransaction() else { return false }
            replaceEffectiveConsent(
                disabledObservabilityConsent(),
                purgeDisabledData: true,
                diagnosticsReportAction: .revoked
            )
            resumePendingFirebaseInstallationDeletion()
            return true
        case .failure:
            return false
        case .corrupted:
            guard consentStore.stagePrivacyTransaction(
                replacementConsent: nil,
                ownerUserId: nil,
                diagnosticsPurgePending: true,
                installationDeletionPending: true,
                analyticsOverrideSanitizationPending: true,
                diagnosticsOverrideSanitizationPending: true
            ) else {
                return false
            }
            privacyTransactionState = consentStore.privacyTransactionState
            guard prepareUnknownFirebaseStateForRevocation(),
                  resumeDurablePrivacyTransaction() else {
                return false
            }
            replaceEffectiveConsent(
                disabledObservabilityConsent(),
                purgeDisabledData: true,
                diagnosticsReportAction: .revoked
            )
            resumePendingFirebaseInstallationDeletion()
            return true
        case let .stored(storedConsent):
            guard storedConsent.ownerFingerprint == ownerFingerprint(userId) else {
                guard consentStore.stagePrivacyTransaction(
                    replacementConsent: nil,
                    ownerUserId: nil,
                    diagnosticsPurgePending: true,
                    installationDeletionPending: true,
                    analyticsOverrideSanitizationPending: true,
                    diagnosticsOverrideSanitizationPending: true
                ) else {
                    return false
                }
                privacyTransactionState = consentStore.privacyTransactionState
                guard prepareStoredFirebaseStateForRevocation(storedConsent),
                      resumeDurablePrivacyTransaction() else {
                    return false
                }
                replaceEffectiveConsent(
                    disabledObservabilityConsent(),
                    purgeDisabledData: true,
                    diagnosticsReportAction: .revoked
                )
                resumePendingFirebaseInstallationDeletion()
                return true
            }
            let restoredConsent = storedConsent.consent
            if !restoredConsent.allowsObservedSessionMeasurement {
                guard consentStore.stagePrivacyTransaction(
                    replacementConsent: restoredConsent,
                    ownerUserId: userId,
                    diagnosticsPurgePending: false,
                    installationDeletionPending: false
                ) else {
                    return false
                }
                privacyTransactionState = consentStore.privacyTransactionState
                guard resumeDurablePrivacyTransaction() else { return false }
            }
            authenticatedSessionBound = true
            replaceEffectiveConsent(
                restoredConsent,
                purgeDisabledData: true,
                diagnosticsReportAction: restoredConsent.diagnosticsAllowed
                    ? (diagnosticsReportsArmed ? .none : .restored)
                    : .revoked
            )
            resumePendingFirebaseInstallationDeletion()
            return true
        }
    }

    private func durablySupersedePendingUpdateIfNeeded(requestedUserId: String?) -> Bool {
        guard case let .update(_, ownerUserId)? = pendingConsentMutation,
              let requestedUserId,
              ownerUserId != requestedUserId else {
            return true
        }
        pendingConsentMutation = .revoke
        guard consentStore.stagePrivacyTransaction(
            replacementConsent: nil,
            ownerUserId: nil,
            diagnosticsPurgePending: true,
            installationDeletionPending: true,
            analyticsOverrideSanitizationPending: true,
            diagnosticsOverrideSanitizationPending: true
        ) else {
            return false
        }
        privacyTransactionState = consentStore.privacyTransactionState
        return true
    }

    @discardableResult
    func updateConsent(_ updatedConsent: ObservabilityConsent, ownerUserId: String) -> Bool {
        guard let ownerUserId = normalizedUserId(ownerUserId) else {
            suspendEffectiveConsent(configureForMaintenance: false)
            return false
        }
        refreshPersistedMaintenanceState()
        guard privacyTransactionState == .notRequired,
              overrideSanitizationState == .notRequired,
              diagnosticsReportPurgeState == .notRequired,
              installationDeletionState == .notRequired else {
            suspendEffectiveConsent(configureForMaintenance: false)
            return false
        }
        if case .revoke? = pendingConsentMutation {
            suspendEffectiveConsent(configureForMaintenance: false)
            return false
        }
        let mutation = PendingObservabilityConsentMutation.update(
            updatedConsent,
            ownerUserId: ownerUserId
        )
        pendingConsentMutation = mutation
        return attemptConsentUpdate(mutation)
    }

    private func attemptConsentUpdate(_ mutation: PendingObservabilityConsentMutation) -> Bool {
        guard case let .update(updatedConsent, ownerUserId) = mutation else { return false }
        let diagnosticsReportAction: DiagnosticsReportAction
        switch (consent.diagnosticsAllowed, updatedConsent.diagnosticsAllowed) {
        case (false, true):
            diagnosticsReportAction = .newlyGranted
        case (true, false):
            diagnosticsReportAction = .revoked
        case (false, false), (true, true):
            diagnosticsReportAction = .none
        }
        let remoteConfigurationRevoked = consent.remoteConfigurationAllowed &&
            !updatedConsent.remoteConfigurationAllowed
        let allCollectionRevoked = consent.allowsAnyCollection &&
            !updatedConsent.allowsAnyCollection
        suspendEffectiveConsent(configureForMaintenance: false)
        refreshPersistedMaintenanceState()
        guard overrideSanitizationState != .failure,
              diagnosticsReportPurgeState != .failure,
              installationDeletionState != .failure,
              privacyTransactionState != .failure else {
            return false
        }
        let requiresDiagnosticsReportPurge = diagnosticsReportAction.requiresDurablePurge ||
            allCollectionRevoked
        let requiresInstallationDeletion = remoteConfigurationRevoked ||
            allCollectionRevoked || installationDeletionState.isPending
        guard consentStore.stagePrivacyTransaction(
            replacementConsent: updatedConsent,
            ownerUserId: ownerUserId,
            diagnosticsPurgePending: requiresDiagnosticsReportPurge,
            installationDeletionPending: requiresInstallationDeletion,
            analyticsOverrideSanitizationPending: consent.analyticsAllowed &&
                !updatedConsent.analyticsAllowed,
            diagnosticsOverrideSanitizationPending: consent.diagnosticsAllowed &&
                !updatedConsent.diagnosticsAllowed
        ) else {
            return false
        }
        privacyTransactionState = consentStore.privacyTransactionState
        let completed = resumeDurablePrivacyTransaction()
        return completed || privacyTransactionState.isPending
    }

    @discardableResult
    func revokeAllConsent() -> Bool {
        pendingConsentMutation = .revoke
        return attemptConsentRevocation()
    }

    private func attemptConsentRevocation() -> Bool {
        suspendEffectiveConsent(configureForMaintenance: false)
        authenticatedSessionBound = false
        refreshPersistedMaintenanceState()
        guard overrideSanitizationState != .failure,
              diagnosticsReportPurgeState != .failure,
              installationDeletionState != .failure,
              privacyTransactionState != .failure else {
            return false
        }
        guard consentStore.stagePrivacyTransaction(
            replacementConsent: nil,
            ownerUserId: nil,
            diagnosticsPurgePending: true,
            installationDeletionPending: true,
            analyticsOverrideSanitizationPending: true,
            diagnosticsOverrideSanitizationPending: true
        ) else {
            return false
        }
        privacyTransactionState = consentStore.privacyTransactionState
        let completed = resumeDurablePrivacyTransaction()
        return completed || privacyTransactionState.isPending
    }

    @discardableResult
    func resetConsentForFreshInstallation() -> Bool {
        guard diagnosticsReportPurgeProcessState == .notChecked,
              !installationDeletionInFlight else {
            return false
        }
        suspendEffectiveConsent(configureForMaintenance: false)
        guard consentStore.stagePrivacyTransaction(
            replacementConsent: nil,
            ownerUserId: nil,
            diagnosticsPurgePending: false,
            installationDeletionPending: false
        ) else {
            return false
        }
        privacyTransactionState = consentStore.privacyTransactionState
        guard revokeObservedSession(),
              consentStore.completePrivacySessionCheckpointPurge() else {
            return false
        }
        authenticatedSessionBound = false
        guard consentStore.resetForFreshInstallation() else { return false }
        refreshPersistedMaintenanceState()
        replaceEffectiveConsent(
            disabledObservabilityConsent(),
            purgeDisabledData: true,
            diagnosticsReportAction: .revoked
        )
        return true
    }

    @discardableResult
    func retryPendingMaintenance() -> Bool {
        let desiredConsent = consent
        suspendEffectiveConsent(configureForMaintenance: false)
        refreshPersistedMaintenanceState()
        guard consentStore.reconcileInstallationDeletionIntent() else { return false }
        installationDeletionState = consentStore.refreshInstallationDeletionState()
        privacyTransactionState = consentStore.refreshPrivacyTransactionState()
        if privacyTransactionState.isPending {
            return resumeDurablePrivacyTransaction()
        }
        guard privacyTransactionState != .failure else { return false }
        if pendingConsentMutation != nil {
            return attemptPendingConsentMutation()
        }
        guard overrideSanitizationState != .failure,
              diagnosticsReportPurgeState != .failure,
              installationDeletionState != .failure else {
            return false
        }
        replaceEffectiveConsent(
            desiredConsent,
            purgeDisabledData: false,
            diagnosticsReportAction: .none
        )
        resumePendingFirebaseInstallationDeletion()
        return true
    }

    private func resumeDurablePrivacyTransaction() -> Bool {
        suspendEffectiveConsent(configureForMaintenance: false)
        refreshPersistedMaintenanceState()
        guard case var .pending(transaction) = privacyTransactionState else {
            return privacyTransactionState == .notRequired
        }
        if let pendingConsentMutation,
           !transaction.matches(pendingConsentMutation) {
            guard stagePendingConsentMutation(pendingConsentMutation) else { return false }
            privacyTransactionState = consentStore.refreshPrivacyTransactionState()
            guard case let .pending(refreshedTransaction) = privacyTransactionState else {
                return false
            }
            transaction = refreshedTransaction
        }
        if transaction.sessionCheckpointPurgePending {
            guard revokeObservedSession(),
                  consentStore.completePrivacySessionCheckpointPurge() else {
                return false
            }
            privacyTransactionState = consentStore.refreshPrivacyTransactionState()
            guard case let .pending(refreshedTransaction) = privacyTransactionState else {
                return false
            }
            transaction = refreshedTransaction
        }
        guard resumePendingOverrideSanitization(transaction: &transaction) else {
            return false
        }
        if transaction.diagnosticsPurgePending,
           diagnosticsReportPurgeState == .notRequired,
           !requestDiagnosticsReportPurge() {
            return false
        }
        if transaction.installationDeletionPending,
           installationDeletionState == .notRequired,
           !requestFirebaseInstallationDeletion(.preserveConsent) {
            return false
        }
        replaceEffectiveConsent(
            disabledObservabilityConsent(),
            purgeDisabledData: true,
            diagnosticsReportAction: .revoked
        )
        resumePendingFirebaseInstallationDeletion()
        privacyTransactionState = consentStore.refreshPrivacyTransactionState()
        guard case let .pending(currentTransaction) = privacyTransactionState else {
            return false
        }
        guard !currentTransaction.hasPendingCleanup,
              diagnosticsReportPurgeState == .notRequired,
              installationDeletionState == .notRequired,
              consentStore.activatePrivacyTransactionIfReady() else {
            return false
        }
        privacyTransactionState = consentStore.refreshPrivacyTransactionState()
        guard privacyTransactionState == .notRequired else { return false }
        return completePendingConsentMutation(
            activatedTransaction: currentTransaction,
            storedConsent: consentStore.read()
        )
    }

    private func stagePendingConsentMutation(
        _ mutation: PendingObservabilityConsentMutation
    ) -> Bool {
        switch mutation {
        case let .update(updatedConsent, ownerUserId):
            return consentStore.stagePrivacyTransaction(
                replacementConsent: updatedConsent,
                ownerUserId: ownerUserId,
                diagnosticsPurgePending: true,
                installationDeletionPending: true,
                analyticsOverrideSanitizationPending: true,
                diagnosticsOverrideSanitizationPending: true
            )
        case .revoke:
            return consentStore.stagePrivacyTransaction(
                replacementConsent: nil,
                ownerUserId: nil,
                diagnosticsPurgePending: true,
                installationDeletionPending: true,
                analyticsOverrideSanitizationPending: true,
                diagnosticsOverrideSanitizationPending: true
            )
        }
    }

    private func resumePendingOverrideSanitization(
        transaction: inout StoredFirebasePrivacyTransactionRecord
    ) -> Bool {
        let analytics = transaction.analyticsOverrideSanitizationPending
        let diagnostics = transaction.diagnosticsOverrideSanitizationPending
        guard analytics || diagnostics else { return true }
        switch overrideSanitizationState {
        case .notRequired:
            guard requireOverrideSanitization(
                analytics: analytics,
                diagnostics: diagnostics
            ) else {
                return false
            }
            return false
        case .requiresSafeConfiguration, .awaitingRestart, .failure:
            return false
        case .readyAfterRestart:
            replaceEffectiveConsent(
                disabledObservabilityConsent(),
                purgeDisabledData: true,
                diagnosticsReportAction: .revoked
            )
            guard overrideSanitizationState == .notRequired,
                  consentStore.completePrivacyOverrideSanitization() else {
                return false
            }
            privacyTransactionState = consentStore.refreshPrivacyTransactionState()
            guard case let .pending(refreshedTransaction) = privacyTransactionState else {
                return false
            }
            transaction = refreshedTransaction
            return true
        }
    }

    private func completePendingConsentMutation(
        activatedTransaction: StoredFirebasePrivacyTransactionRecord,
        storedConsent: FirebaseConsentReadResult
    ) -> Bool {
        guard let pendingConsentMutation else {
            authenticatedSessionBound = false
            replaceEffectiveConsent(
                disabledObservabilityConsent(),
                purgeDisabledData: true,
                diagnosticsReportAction: .revoked
            )
            return true
        }
        guard activatedTransaction.matches(pendingConsentMutation) else { return false }
        switch (pendingConsentMutation, storedConsent) {
        case let (.update(updatedConsent, ownerUserId), .stored(storedConsent))
            where storedConsent.matches(updatedConsent, ownerUserId: ownerUserId):
            self.pendingConsentMutation = nil
            if authenticatedSessionBound {
                replaceEffectiveConsent(
                    storedConsent.consent,
                    purgeDisabledData: true,
                    diagnosticsReportAction: storedConsent.consent.diagnosticsAllowed
                        ? .newlyGranted
                        : .revoked
                )
            }
            return true
        case (.revoke, .missing):
            self.pendingConsentMutation = nil
            authenticatedSessionBound = false
            replaceEffectiveConsent(
                disabledObservabilityConsent(),
                purgeDisabledData: true,
                diagnosticsReportAction: .revoked
            )
            return true
        case (.update, .stored), (.update, .missing), (.update, .corrupted), (.update, .failure),
             (.revoke, .stored), (.revoke, .corrupted), (.revoke, .failure):
            return false
        }
    }

    private func attemptPendingConsentMutation() -> Bool {
        guard let pendingConsentMutation else { return true }
        switch pendingConsentMutation {
        case .update:
            return attemptConsentUpdate(pendingConsentMutation)
        case .revoke:
            return attemptConsentRevocation()
        }
    }

    func applicationEnteredForeground() {
        guard let session = sessionTracker?.onForeground() else { return }
        trackObservedSession(session)
    }

    func applicationEnteredBackground() {
        sessionTracker?.onBackground()
    }

    private func suspendEffectiveConsent(configureForMaintenance: Bool = true) {
        guard configureForMaintenance else {
            runtimeCollectionSuspended = true
            applyConsent(
                consent,
                purgeDisabledData: false,
                diagnosticsReportAction: .none
            )
            remoteConfigurationGeneration += 1
            stopRemoteConfigurationUpdates()
            updateObservedSessionEligibility()
            publishPerformanceCollectionEligibility()
            return
        }
        replaceEffectiveConsent(
            disabledObservabilityConsent(),
            purgeDisabledData: false,
            diagnosticsReportAction: .none
        )
    }

    private func replaceEffectiveConsent(
        _ updatedConsent: ObservabilityConsent,
        purgeDisabledData: Bool,
        diagnosticsReportAction: DiagnosticsReportAction
    ) {
        let remoteConfigurationWasEffectivelyAllowed = effectiveRemoteConfigurationAllowed
        let wasConfigured = isConfigured
        consent = updatedConsent
        runtimeCollectionSuspended = false
        configureIfNeeded(for: updatedConsent)
        applyConsent(
            updatedConsent,
            purgeDisabledData: purgeDisabledData,
            diagnosticsReportAction: diagnosticsReportAction
        )

        switch diagnosticsReportAction {
        case .none:
            break
        case .restored, .newlyGranted:
            if isConfigured && effectiveDiagnosticsAllowed {
                diagnosticsReportsArmed = true
            }
        case .revoked:
            diagnosticsReportsArmed = false
        }

        if !effectiveRemoteConfigurationAllowed {
            remoteConfigurationGeneration += 1
            stopRemoteConfigurationUpdates()
        } else if !remoteConfigurationWasEffectivelyAllowed || !wasConfigured {
            startRemoteConfigurationSession()
        }
        resumePendingDiagnosticsReportPurge()
        updateObservedSessionEligibility()
        publishPerformanceCollectionEligibility()
    }

    private func configureIfNeeded(for consent: ObservabilityConsent) {
        if isConfigured {
            advanceOverrideSanitizationAfterConfiguration()
            return
        }
        guard consent.allowsAnyCollection ||
              diagnosticsReportPurgeState == .pending ||
              installationDeletionState.isPending,
              overrideSanitizationState.allowsConfiguration(for: consent),
              let options else {
            return
        }
        let performance = Performance.sharedInstance()
        performance.isDataCollectionEnabled = false
        performance.isInstrumentationEnabled = false
        if FirebaseApp.app() == nil {
            FirebaseApp.configure(options: options)
        }
        guard FirebaseApp.app() != nil else {
            performance.isDataCollectionEnabled = false
            performance.isInstrumentationEnabled = false
            return
        }
        Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(false)
        self.performance = performance
        isConfigured = true
        advanceOverrideSanitizationAfterConfiguration()
    }

    func track(_ event: AnalyticsEvent) {
        guard isConfigured, effectiveAnalyticsAllowed else { return }
        var parameters: [String: Any] = [
            "ville": event.context.cityId ?? notApplicable,
            "type_entite": event.context.entityType.wireName,
            "entite_id": event.context.entityId ?? notApplicable,
            "source_session": event.context.sessionSource.wireName,
            "langue": event.context.locale.tag,
            "devise_affichage": event.context.displayCurrency.name.uppercased(),
        ]
        if let authMethod = event.authMethod {
            parameters["auth_method"] = authMethod.wireName
        }
        if let postType = event.socialPostType {
            parameters["post_type"] = postType.wireName
        }
        Analytics.logEvent(event.name.wireName, parameters: parameters)
    }

    private func trackObservedSession(_ session: ObservedAppSession) {
        guard isConfigured, effectiveAnalyticsAllowed, effectiveDiagnosticsAllowed else { return }
        Analytics.logEvent(session.eventName, parameters: nil)
    }

    private func updateObservedSessionEligibility() {
        let allowed = isConfigured && effectiveAnalyticsAllowed && effectiveDiagnosticsAllowed
        guard let session = sessionTracker?.updateMeasurementEligibility(allowed: allowed) else { return }
        trackObservedSession(session)
    }

    private func revokeObservedSession() -> Bool {
        sessionTracker?.revoke() ?? true
    }

    func recordDiagnostic(_ code: DiagnosticCode) {
        recordDiagnostic(wireName: code.wireName)
    }

    private func recordDiagnostic(wireName: String) {
        guard isConfigured, effectiveDiagnosticsAllowed else { return }
        let error = NSError(
            domain: diagnosticDomain,
            code: diagnosticErrorCode,
            userInfo: [NSLocalizedDescriptionKey: wireName]
        )
        Crashlytics.crashlytics().record(error: error)
    }

    func startTrace(_ name: PerformanceTraceName) -> FirebasePerformanceTrace? {
        guard isConfigured, effectiveDiagnosticsAllowed else {
            return nil
        }
        guard let trace = Performance.startTrace(name: name.wireName) else {
            return nil
        }
        return FirebasePerformanceTrace(trace: trace)
    }

    var isPerformanceCollectionAllowed: Bool {
        isConfigured && effectiveDiagnosticsAllowed
    }

    private func publishPerformanceCollectionEligibility() {
        let allowed = isPerformanceCollectionAllowed
        guard publishedPerformanceCollectionAllowed != allowed else { return }
        publishedPerformanceCollectionAllowed = allowed
        onPerformanceCollectionEligibilityChanged?(allowed)
    }

    func recordPerformanceMeasurement(_ measurement: PerformanceMeasurement) {
        guard isPerformanceCollectionAllowed,
              let trace = Performance.startTrace(name: measurement.traceName.wireName) else {
            return
        }
        trace.setValue(measurement.metricValue, forMetric: measurement.metricName.wireName)
        trace.setValue(
            measurement.processExploreKind.wireName,
            forAttribute: performanceProcessExploreKindAttribute
        )
        trace.setValue(measurement.viewportState.wireName, forAttribute: performanceViewportStateAttribute)
        trace.stop()
    }

    private func startRemoteConfigurationSession() {
        guard isConfigured, effectiveRemoteConfigurationAllowed else { return }
        if remoteConfig == nil {
            remoteConfig = configureRemoteConfig()
        }
        remoteConfigurationGeneration += 1
        let generation = remoteConfigurationGeneration
        startRemoteConfigurationUpdates(generation: generation)
        refreshRemoteConfiguration(generation: generation)
    }

    private func refreshRemoteConfiguration(generation: Int) {
        guard isRemoteConfigurationGenerationActive(generation), let remoteConfig else { return }
        remoteConfig.fetchAndActivate { [weak self] _, error in
            Task { @MainActor [weak self] in
                guard let self else { return }
                guard self.isRemoteConfigurationGenerationActive(generation) else { return }
                guard error == nil else {
                    self.recordDiagnostic(wireName: remoteConfigFetchFailureCode)
                    return
                }
            }
        }
    }

    private func startRemoteConfigurationUpdates(generation: Int) {
        guard isConfigured,
              isRemoteConfigurationGenerationActive(generation),
              remoteConfigUpdateRegistration == nil,
              let remoteConfig else {
            return
        }
        remoteConfigUpdateRegistration = remoteConfig.addOnConfigUpdateListener {
            [weak self] update, error in
            let updatedKeys = update?.updatedKeys
            let updateFailed = error != nil || updatedKeys == nil
            Task { @MainActor [weak self] in
                self?.handleRemoteConfigurationUpdate(
                    updatedKeys: updatedKeys,
                    failed: updateFailed,
                    generation: generation
                )
            }
        }
    }

    private func handleRemoteConfigurationUpdate(
        updatedKeys: Set<String>?,
        failed: Bool,
        generation: Int
    ) {
        guard isRemoteConfigurationGenerationActive(generation) else { return }
        guard !failed, let updatedKeys else {
            recordDiagnostic(wireName: remoteConfigFetchFailureCode)
            return
        }
        guard !updatedKeys.isEmpty, let remoteConfig else { return }
        remoteConfig.activate { [weak self] _, error in
            let activationFailed = error != nil
            Task { @MainActor [weak self] in
                self?.completeRemoteConfigurationActivation(
                    failed: activationFailed,
                    generation: generation
                )
            }
        }
    }

    private func completeRemoteConfigurationActivation(failed: Bool, generation: Int) {
        guard isRemoteConfigurationGenerationActive(generation) else { return }
        guard failed else { return }
        recordDiagnostic(wireName: remoteConfigFetchFailureCode)
    }

    private func isRemoteConfigurationGenerationActive(_ generation: Int) -> Bool {
        effectiveRemoteConfigurationAllowed && generation == remoteConfigurationGeneration
    }

    private func stopRemoteConfigurationUpdates() {
        remoteConfigUpdateRegistration?.remove()
        remoteConfigUpdateRegistration = nil
    }

    private func applyConsent(
        _ consent: ObservabilityConsent,
        purgeDisabledData: Bool,
        diagnosticsReportAction: DiagnosticsReportAction
    ) {
        guard isConfigured else { return }
        Analytics.setUserProperty("false", forName: AnalyticsUserPropertyAllowAdPersonalizationSignals)
        Analytics.setAnalyticsCollectionEnabled(effectiveAnalyticsAllowed)
        let crashlytics = Crashlytics.crashlytics()
        crashlytics.setCrashlyticsCollectionEnabled(false)
        performance?.isDataCollectionEnabled = effectiveDiagnosticsAllowed
        performance?.isInstrumentationEnabled = false

        if purgeDisabledData && !consent.analyticsAllowed {
            Analytics.resetAnalyticsData()
        }
        switch diagnosticsReportAction {
        case .none:
            break
        case .restored:
            if effectiveDiagnosticsAllowed {
                crashlytics.sendUnsentReports()
            }
        case .newlyGranted, .revoked:
            if purgeDisabledData && diagnosticsReportPurgeState == .notRequired {
                crashlytics.deleteUnsentReports()
            }
        }
    }

    private var effectiveAnalyticsAllowed: Bool {
        consent.analyticsAllowed && maintenanceAllowsCollection
    }

    private var effectiveDiagnosticsAllowed: Bool {
        consent.diagnosticsAllowed && diagnosticsMaintenanceAllowsCollection
    }

    private var effectiveRemoteConfigurationAllowed: Bool {
        consent.remoteConfigurationAllowed && maintenanceAllowsCollection
    }

    private var maintenanceAllowsCollection: Bool {
        authenticatedSessionBound &&
            !runtimeCollectionSuspended &&
            pendingConsentMutation == nil &&
            privacyTransactionState == .notRequired &&
            overrideSanitizationState.allowsCollection &&
            installationDeletionState == .notRequired
    }

    private var diagnosticsMaintenanceAllowsCollection: Bool {
        maintenanceAllowsCollection && diagnosticsReportPurgeState == .notRequired
    }

    private func refreshPersistedMaintenanceState() {
        overrideSanitizationState = consentStore.refreshOverrideSanitizationState(
            processToken: firebaseObservabilityProcessToken
        )
        diagnosticsReportPurgeState = consentStore.refreshDiagnosticsReportPurgeState()
        installationDeletionState = consentStore.refreshInstallationDeletionState()
        privacyTransactionState = consentStore.refreshPrivacyTransactionState()
    }

    private func prepareUnknownFirebaseStateForRevocation() -> Bool {
        guard requestDiagnosticsReportPurge() else { return false }
        guard requireOverrideSanitization(
            analytics: true,
            diagnostics: true
        ) else {
            return false
        }
        return requestFirebaseInstallationDeletion(.revokeConsent)
    }

    private func prepareStoredFirebaseStateForRevocation(
        _ storedConsent: StoredFirebaseConsent
    ) -> Bool {
        guard requestDiagnosticsReportPurge() else { return false }
        if storedConsent.consent.analyticsAllowed || storedConsent.consent.diagnosticsAllowed {
            guard requireOverrideSanitization(
                analytics: storedConsent.consent.analyticsAllowed,
                diagnostics: storedConsent.consent.diagnosticsAllowed
            ) else {
                return false
            }
        }
        return true
    }

    private func requireOverrideSanitization(analytics: Bool, diagnostics: Bool) -> Bool {
        guard consentStore.requireOverrideSanitization(
            analytics: analytics,
            diagnostics: diagnostics,
            configuredProcessToken: isConfigured ? firebaseObservabilityProcessToken : nil
        ) else {
            overrideSanitizationState = .failure
            return false
        }
        overrideSanitizationState = consentStore.overrideSanitizationState
        return true
    }

    private func requestDiagnosticsReportPurge() -> Bool {
        guard consentStore.markDiagnosticsReportPurgePending() else {
            diagnosticsReportPurgeState = .failure
            return false
        }
        diagnosticsReportPurgeState = consentStore.diagnosticsReportPurgeState
        return true
    }

    private func resumePendingDiagnosticsReportPurge() {
        guard diagnosticsReportPurgeState == .pending,
              isConfigured else {
            return
        }
        switch diagnosticsReportPurgeProcessState {
        case .notChecked:
            diagnosticsReportPurgeProcessState = .checking
        case .confirmedNoReportsPendingClear:
            completeDiagnosticsReportPurge()
            return
        case .checking, .checkConsumed, .deletionRequested:
            return
        }
        let crashlytics = Crashlytics.crashlytics()
        Task { [weak self, crashlytics] in
            let hasUnsentReports = await crashlytics.checkForUnsentReports()
            guard let self else { return }
            guard diagnosticsReportPurgeState == .pending else { return }
            crashlytics.deleteUnsentReports()
            if hasUnsentReports {
                diagnosticsReportPurgeProcessState = .deletionRequested
                return
            }
            diagnosticsReportPurgeProcessState = .confirmedNoReportsPendingClear
            completeDiagnosticsReportPurge()
        }
    }

    private func completeDiagnosticsReportPurge() {
        guard consentStore.clearDiagnosticsReportPurgePending() else {
            diagnosticsReportPurgeState = .failure
            suspendEffectiveConsent(configureForMaintenance: false)
            return
        }
        diagnosticsReportPurgeState = .notRequired
        guard consentStore.completePrivacyDiagnosticsPurge() else {
            privacyTransactionState = .failure
            suspendEffectiveConsent(configureForMaintenance: false)
            return
        }
        privacyTransactionState = consentStore.refreshPrivacyTransactionState()
        diagnosticsReportPurgeProcessState = .checkConsumed
        if privacyTransactionState.isPending {
            _ = resumeDurablePrivacyTransaction()
            return
        }
        let postPurgeAction: DiagnosticsReportAction = consent.diagnosticsAllowed
            ? .newlyGranted
            : .revoked
        replaceEffectiveConsent(
            consent,
            purgeDisabledData: false,
            diagnosticsReportAction: postPurgeAction
        )
    }

    private func requestFirebaseInstallationDeletion(
        _ intent: FirebaseInstallationDeletionIntent
    ) -> Bool {
        guard consentStore.markInstallationDeletionPending(intent: intent) else {
            installationDeletionState = .failure
            return false
        }
        installationDeletionState = consentStore.installationDeletionState
        return consentStore.reconcileInstallationDeletionIntent()
    }

    private func resumePendingFirebaseInstallationDeletion() {
        guard case let .pending(deletionRequest) = installationDeletionState,
              !installationDeletionInFlight else {
            return
        }
        guard consentStore.reconcileInstallationDeletionIntent() else { return }
        configureIfNeeded(for: consent)
        guard isConfigured else { return }
        installationDeletionInFlight = true
        Task { [weak self] in
            do {
                try await Installations.installations().delete()
                guard let self else { return }
                installationDeletionInFlight = false
                switch consentStore.completeInstallationDeletion(
                    expectedRequestID: deletionRequest.requestID
                ) {
                case .completed:
                    installationDeletionState = .notRequired
                    guard consentStore.completePrivacyInstallationDeletion() else {
                        privacyTransactionState = .failure
                        suspendEffectiveConsent(configureForMaintenance: false)
                        return
                    }
                    privacyTransactionState = consentStore.refreshPrivacyTransactionState()
                case .superseded:
                    installationDeletionState = consentStore.refreshInstallationDeletionState()
                    resumePendingFirebaseInstallationDeletion()
                    return
                case .failure:
                    installationDeletionState = .failure
                    return
                }
                if privacyTransactionState.isPending {
                    _ = resumeDurablePrivacyTransaction()
                    return
                }
                let diagnosticsReportAction: DiagnosticsReportAction = consent.diagnosticsAllowed
                    ? (diagnosticsReportsArmed ? .none : .restored)
                    : .revoked
                replaceEffectiveConsent(
                    consent,
                    purgeDisabledData: true,
                    diagnosticsReportAction: diagnosticsReportAction
                )
            } catch {
                self?.installationDeletionInFlight = false
            }
        }
    }

    @discardableResult
    private func advanceOverrideSanitizationAfterConfiguration() -> Bool {
        switch overrideSanitizationState {
        case .requiresSafeConfiguration:
            guard consentStore.markCrashlyticsDisableScheduled(
                processToken: firebaseObservabilityProcessToken
            ) else {
                overrideSanitizationState = .failure
                return false
            }
            overrideSanitizationState = consentStore.overrideSanitizationState
        case .readyAfterRestart:
            guard consentStore.clearOverrideSanitizationMarker() else {
                overrideSanitizationState = .failure
                return false
            }
            overrideSanitizationState = .notRequired
        case .notRequired, .awaitingRestart, .failure:
            break
        }
        return overrideSanitizationState != .failure
    }

    private func configureRemoteConfig() -> RemoteConfig {
        let config = RemoteConfig.remoteConfig()
        let settings = RemoteConfigSettings()
        settings.minimumFetchInterval = remoteConfigFetchInterval
        config.configSettings = settings
        return config
    }
}

private enum DiagnosticsReportAction: Equatable {
    case none
    case restored
    case newlyGranted
    case revoked

    var requiresDurablePurge: Bool {
        switch self {
        case .newlyGranted, .revoked:
            return true
        case .none, .restored:
            return false
        }
    }
}

private enum FirebaseDiagnosticsReportPurgeProcessState: Equatable {
    case notChecked
    case checking
    case confirmedNoReportsPendingClear
    case checkConsumed
    case deletionRequested
}

final class FirebasePerformanceTrace {
    private var trace: Trace?

    fileprivate init(trace: Trace) {
        self.trace = trace
    }

    func stop() {
        trace?.stop()
        trace = nil
    }

    deinit {
        trace?.stop()
    }
}

private final class FirebaseConsentStore {
    let service: String
    private(set) var overrideSanitizationState: FirebaseOverrideSanitizationState = .notRequired
    private(set) var diagnosticsReportPurgeState: FirebaseDiagnosticsReportPurgeState = .notRequired
    private(set) var installationDeletionState: FirebaseInstallationDeletionState = .notRequired
    private(set) var privacyTransactionState: FirebasePrivacyTransactionState = .notRequired
    private let legacyUserDefaults: UserDefaults

    init(service: String, legacyUserDefaults: UserDefaults, processToken: String) {
        self.service = service
        self.legacyUserDefaults = legacyUserDefaults
        privacyTransactionState = preparePrivacyTransactionState()
        installationDeletionState = prepareInstallationDeletionState()
        diagnosticsReportPurgeState = prepareDiagnosticsReportPurgeState()
        overrideSanitizationState = prepareOverrideSanitization(processToken: processToken)
    }

    func read() -> FirebaseConsentReadResult {
        switch refreshPrivacyTransactionState() {
        case .pending:
            return .missing
        case .failure:
            return .failure
        case .notRequired:
            break
        }
        switch readData(account: consentKeychainAccount) {
        case .missing:
            return .missing
        case .failure:
            return .failure
        case let .data(data):
            guard let record = try? JSONDecoder().decode(StoredFirebaseConsentRecord.self, from: data),
              record.schemaVersion == consentSchemaVersion else {
                return .corrupted
            }
            return .stored(
                StoredFirebaseConsent(
                    ownerFingerprint: record.ownerFingerprint,
                    consent: ObservabilityConsent(
                        analyticsAllowed: record.analyticsAllowed,
                        diagnosticsAllowed: record.diagnosticsAllowed,
                        remoteConfigurationAllowed: record.remoteConfigurationAllowed
                    )
                )
            )
        }
    }

    func write(_ consent: ObservabilityConsent, ownerUserId: String) -> Bool {
        let record = StoredFirebaseConsentRecord(
            schemaVersion: consentSchemaVersion,
            ownerFingerprint: ownerFingerprint(ownerUserId),
            analyticsAllowed: consent.analyticsAllowed,
            diagnosticsAllowed: consent.diagnosticsAllowed,
            remoteConfigurationAllowed: consent.remoteConfigurationAllowed
        )
        guard let data = try? JSONEncoder().encode(record) else { return false }
        return writeData(data, account: consentKeychainAccount)
    }

    func revoke() -> Bool {
        remove(account: consentKeychainAccount)
    }

    func stageDisabledSessionCleanupPreservingConsent() -> FirebaseDisabledSessionCleanupStagingResult {
        let replacementConsent: StoredFirebaseConsentRecord?
        let requiresUnknownStateCleanup: Bool
        switch readData(account: consentKeychainAccount) {
        case .missing:
            replacementConsent = nil
            requiresUnknownStateCleanup = false
        case .failure:
            return .failure
        case let .data(data):
            guard let record = try? JSONDecoder().decode(StoredFirebaseConsentRecord.self, from: data),
                  isValidReplacementConsent(record) else {
                replacementConsent = nil
                requiresUnknownStateCleanup = true
                break
            }
            replacementConsent = record
            requiresUnknownStateCleanup = false
        }
        let transaction = StoredFirebasePrivacyTransactionRecord(
            schemaVersion: privacyTransactionSchemaVersion,
            sessionCheckpointPurgePending: true,
            diagnosticsPurgePending: requiresUnknownStateCleanup,
            installationDeletionPending: requiresUnknownStateCleanup,
            replacementConsent: replacementConsent,
            analyticsOverrideSanitizationPending: requiresUnknownStateCleanup,
            diagnosticsOverrideSanitizationPending: requiresUnknownStateCleanup
        )
        guard persistPrivacyTransactionRecord(transaction), revoke() else { return .failure }
        return requiresUnknownStateCleanup ? .unknownState : .knownState
    }

    func stagePrivacyTransaction(
        replacementConsent: ObservabilityConsent?,
        ownerUserId: String?,
        diagnosticsPurgePending: Bool,
        installationDeletionPending: Bool,
        analyticsOverrideSanitizationPending: Bool = false,
        diagnosticsOverrideSanitizationPending: Bool = false
    ) -> Bool {
        let existingTransaction: StoredFirebasePrivacyTransactionRecord?
        switch refreshPrivacyTransactionState() {
        case let .pending(record):
            existingTransaction = record
        case .notRequired:
            existingTransaction = nil
        case .failure:
            return false
        }
        let replacementRecord: StoredFirebaseConsentRecord?
        switch (replacementConsent, ownerUserId) {
        case let (.some(consent), .some(ownerUserId)):
            replacementRecord = storedConsentRecord(consent, ownerUserId: ownerUserId)
        case (nil, nil):
            replacementRecord = nil
        case (.some, nil), (nil, .some):
            return false
        }
        let record = StoredFirebasePrivacyTransactionRecord(
            schemaVersion: privacyTransactionSchemaVersion,
            sessionCheckpointPurgePending: true,
            diagnosticsPurgePending: diagnosticsPurgePending ||
                (existingTransaction?.diagnosticsPurgePending ?? false),
            installationDeletionPending: installationDeletionPending ||
                (existingTransaction?.installationDeletionPending ?? false),
            replacementConsent: replacementRecord,
            analyticsOverrideSanitizationPending: analyticsOverrideSanitizationPending ||
                (existingTransaction?.analyticsOverrideSanitizationPending ?? false),
            diagnosticsOverrideSanitizationPending: diagnosticsOverrideSanitizationPending ||
                (existingTransaction?.diagnosticsOverrideSanitizationPending ?? false)
        )
        guard persistPrivacyTransactionRecord(record) else { return false }
        return revoke()
    }

    func refreshPrivacyTransactionState() -> FirebasePrivacyTransactionState {
        let state = preparePrivacyTransactionState()
        privacyTransactionState = state
        return state
    }

    func completePrivacySessionCheckpointPurge() -> Bool {
        updatePrivacyTransaction { record in
            StoredFirebasePrivacyTransactionRecord(
                schemaVersion: record.schemaVersion,
                sessionCheckpointPurgePending: false,
                diagnosticsPurgePending: record.diagnosticsPurgePending,
                installationDeletionPending: record.installationDeletionPending,
                replacementConsent: record.replacementConsent,
                analyticsOverrideSanitizationPending: record.analyticsOverrideSanitizationPending,
                diagnosticsOverrideSanitizationPending: record.diagnosticsOverrideSanitizationPending
            )
        }
    }

    func completePrivacyDiagnosticsPurge() -> Bool {
        updatePrivacyTransaction { record in
            StoredFirebasePrivacyTransactionRecord(
                schemaVersion: record.schemaVersion,
                sessionCheckpointPurgePending: record.sessionCheckpointPurgePending,
                diagnosticsPurgePending: false,
                installationDeletionPending: record.installationDeletionPending,
                replacementConsent: record.replacementConsent,
                analyticsOverrideSanitizationPending: record.analyticsOverrideSanitizationPending,
                diagnosticsOverrideSanitizationPending: record.diagnosticsOverrideSanitizationPending
            )
        }
    }

    func completePrivacyInstallationDeletion() -> Bool {
        updatePrivacyTransaction { record in
            StoredFirebasePrivacyTransactionRecord(
                schemaVersion: record.schemaVersion,
                sessionCheckpointPurgePending: record.sessionCheckpointPurgePending,
                diagnosticsPurgePending: record.diagnosticsPurgePending,
                installationDeletionPending: false,
                replacementConsent: record.replacementConsent,
                analyticsOverrideSanitizationPending: record.analyticsOverrideSanitizationPending,
                diagnosticsOverrideSanitizationPending: record.diagnosticsOverrideSanitizationPending
            )
        }
    }

    func completePrivacyOverrideSanitization() -> Bool {
        updatePrivacyTransaction { record in
            StoredFirebasePrivacyTransactionRecord(
                schemaVersion: record.schemaVersion,
                sessionCheckpointPurgePending: record.sessionCheckpointPurgePending,
                diagnosticsPurgePending: record.diagnosticsPurgePending,
                installationDeletionPending: record.installationDeletionPending,
                replacementConsent: record.replacementConsent,
                analyticsOverrideSanitizationPending: false,
                diagnosticsOverrideSanitizationPending: false
            )
        }
    }

    func activatePrivacyTransactionIfReady() -> Bool {
        guard case let .pending(record) = refreshPrivacyTransactionState() else {
            return privacyTransactionState == .notRequired
        }
        guard !record.hasPendingCleanup else { return false }
        if let replacementConsent = record.replacementConsent {
            guard isValidReplacementConsent(replacementConsent),
                  let data = try? JSONEncoder().encode(replacementConsent),
                  writeData(data, account: consentKeychainAccount) else {
                return false
            }
        } else if !revoke() {
            return false
        }
        guard remove(account: privacyTransactionKeychainAccount) else { return false }
        privacyTransactionState = .notRequired
        return true
    }

    func resetForFreshInstallation() -> Bool {
        guard remove(account: privacyTransactionKeychainAccount) else { return false }
        guard remove(account: consentKeychainAccount) else { return false }
        guard remove(account: diagnosticsReportPurgeKeychainAccount) else { return false }
        guard remove(account: installationDeletionKeychainAccount) else { return false }
        guard writeSanitizedOverrideState() else { return false }
        purgeLegacyPreferences()
        overrideSanitizationState = .notRequired
        diagnosticsReportPurgeState = .notRequired
        installationDeletionState = .notRequired
        privacyTransactionState = .notRequired
        return true
    }

    func refreshOverrideSanitizationState(
        processToken: String
    ) -> FirebaseOverrideSanitizationState {
        let state = prepareOverrideSanitization(processToken: processToken)
        overrideSanitizationState = state
        return state
    }

    func requireOverrideSanitization(
        analytics: Bool,
        diagnostics: Bool,
        configuredProcessToken: String?
    ) -> Bool {
        guard analytics || diagnostics else { return true }
        switch overrideSanitizationState {
        case .awaitingRestart, .readyAfterRestart:
            return true
        case .failure:
            return false
        case .notRequired, .requiresSafeConfiguration:
            break
        }
        let currentRequirements = overrideSanitizationState.requirements
        let phase: FirebaseOverrideSanitizationPhase = configuredProcessToken == nil
            ? .requiresSafeConfiguration
            : .awaitingRestart
        let record = StoredFirebaseOverrideSanitizationRecord(
            schemaVersion: overrideSanitizationSchemaVersion,
            phase: phase,
            processToken: configuredProcessToken,
            analyticsNeedsSanitization: analytics || currentRequirements.analytics,
            diagnosticsNeedsSanitization: diagnostics || currentRequirements.diagnostics
        )
        guard let data = try? JSONEncoder().encode(record),
              writeData(data, account: overrideSanitizationKeychainAccount) else {
            overrideSanitizationState = .failure
            return false
        }
        let mergedAnalytics = record.analyticsNeedsSanitization ?? true
        let mergedDiagnostics = record.diagnosticsNeedsSanitization ?? true
        overrideSanitizationState = configuredProcessToken == nil
            ? .requiresSafeConfiguration(
                analytics: mergedAnalytics,
                diagnostics: mergedDiagnostics
            )
            : .awaitingRestart(
                analytics: mergedAnalytics,
                diagnostics: mergedDiagnostics
            )
        return true
    }

    func markCrashlyticsDisableScheduled(processToken: String) -> Bool {
        let requirements = overrideSanitizationState.requirements
        let record = StoredFirebaseOverrideSanitizationRecord(
            schemaVersion: overrideSanitizationSchemaVersion,
            phase: .awaitingRestart,
            processToken: processToken,
            analyticsNeedsSanitization: requirements.analytics,
            diagnosticsNeedsSanitization: requirements.diagnostics
        )
        guard let data = try? JSONEncoder().encode(record),
              writeData(data, account: overrideSanitizationKeychainAccount) else {
            return false
        }
        overrideSanitizationState = .awaitingRestart(
            analytics: requirements.analytics,
            diagnostics: requirements.diagnostics
        )
        return true
    }

    func clearOverrideSanitizationMarker() -> Bool {
        guard writeSanitizedOverrideState() else { return false }
        overrideSanitizationState = .notRequired
        return true
    }

    func refreshInstallationDeletionState() -> FirebaseInstallationDeletionState {
        let state = prepareInstallationDeletionState()
        installationDeletionState = state
        return state
    }

    func refreshDiagnosticsReportPurgeState() -> FirebaseDiagnosticsReportPurgeState {
        let state = prepareDiagnosticsReportPurgeState()
        diagnosticsReportPurgeState = state
        return state
    }

    func markDiagnosticsReportPurgePending(forceRewrite: Bool = false) -> Bool {
        if diagnosticsReportPurgeState == .pending, !forceRewrite { return true }
        let record = StoredFirebaseDiagnosticsReportPurgeRecord(
            schemaVersion: diagnosticsReportPurgeSchemaVersion,
            pending: true
        )
        guard let data = try? JSONEncoder().encode(record),
              writeData(data, account: diagnosticsReportPurgeKeychainAccount) else {
            return false
        }
        diagnosticsReportPurgeState = .pending
        return true
    }

    func clearDiagnosticsReportPurgePending() -> Bool {
        guard remove(account: diagnosticsReportPurgeKeychainAccount) else { return false }
        diagnosticsReportPurgeState = .notRequired
        return true
    }

    func markInstallationDeletionPending(
        intent: FirebaseInstallationDeletionIntent = .preserveConsent
    ) -> Bool {
        if case .preserveConsent = intent,
           case .pending = installationDeletionState {
            return true
        }
        let consentMutation: FirebaseInstallationDeletionConsentMutation
        let replacementConsent: StoredFirebaseConsentRecord?
        switch intent {
        case .preserveConsent:
            consentMutation = .preserve
            replacementConsent = nil
        case let .replaceConsent(consent, ownerUserId):
            consentMutation = .replace
            replacementConsent = storedConsentRecord(
                consent,
                ownerUserId: ownerUserId
            )
        case .revokeConsent:
            consentMutation = .revoke
            replacementConsent = nil
        }
        let record = StoredFirebaseInstallationDeletionRecord(
            schemaVersion: installationDeletionSchemaVersion,
            requestID: UUID().uuidString,
            consentMutation: consentMutation,
            replacementConsent: replacementConsent
        )
        return persistInstallationDeletionRecord(record)
    }

    func reconcileInstallationDeletionIntent() -> Bool {
        guard case let .pending(record) = installationDeletionState else {
            return installationDeletionState == .notRequired
        }
        switch record.consentMutation {
        case .preserve:
            return true
        case .replace:
            guard let legacyReplacement = record.replacementConsent,
                  isValidReplacementConsent(legacyReplacement) else {
                installationDeletionState = .failure
                return false
            }
            switch refreshPrivacyTransactionState() {
            case .failure:
                return false
            case .notRequired:
                let migratedTransaction = StoredFirebasePrivacyTransactionRecord(
                    schemaVersion: privacyTransactionSchemaVersion,
                    sessionCheckpointPurgePending: true,
                    diagnosticsPurgePending: true,
                    installationDeletionPending: true,
                    replacementConsent: legacyReplacement,
                    analyticsOverrideSanitizationPending: true,
                    diagnosticsOverrideSanitizationPending: true
                )
                guard persistPrivacyTransactionRecord(migratedTransaction) else { return false }
            case let .pending(transaction):
                guard reconcileReplacementDeletionRecord(record, with: transaction) else {
                    return false
                }
            }
            return revoke()
        case .revoke:
            return revoke()
        }
    }

    private func reconcileReplacementDeletionRecord(
        _ deletionRecord: StoredFirebaseInstallationDeletionRecord,
        with transaction: StoredFirebasePrivacyTransactionRecord
    ) -> Bool {
        guard deletionRecord.replacementConsent != transaction.replacementConsent else { return true }
        let updatedRecord = StoredFirebaseInstallationDeletionRecord(
            schemaVersion: installationDeletionSchemaVersion,
            requestID: deletionRecord.requestID,
            consentMutation: transaction.replacementConsent == nil ? .revoke : .replace,
            replacementConsent: transaction.replacementConsent
        )
        return persistInstallationDeletionRecord(updatedRecord)
    }

    func completeInstallationDeletion(
        expectedRequestID: String
    ) -> FirebaseInstallationDeletionCompletion {
        switch readData(account: installationDeletionKeychainAccount) {
        case .missing:
            installationDeletionState = .notRequired
            return .completed
        case .failure:
            return .failure
        case let .data(data):
            guard let record = decodeInstallationDeletionRecord(data) else {
                installationDeletionState = .failure
                return .failure
            }
            guard record.requestID == expectedRequestID else {
                installationDeletionState = .pending(record)
                return .superseded
            }
            guard remove(account: installationDeletionKeychainAccount) else {
                return .failure
            }
            installationDeletionState = .notRequired
            return .completed
        }
    }

    private func prepareOverrideSanitization(processToken: String) -> FirebaseOverrideSanitizationState {
        switch readData(account: overrideSanitizationKeychainAccount) {
        case .failure:
            return .failure
        case let .data(data):
            guard let record = try? JSONDecoder().decode(
                StoredFirebaseOverrideSanitizationRecord.self,
                from: data
            ), record.schemaVersion == overrideSanitizationSchemaVersion else {
                return repairCorruptedOverrideSanitizationMarker()
            }
            purgeLegacyPreferences()
            let analytics = record.analyticsNeedsSanitization ?? true
            let diagnostics = record.diagnosticsNeedsSanitization ?? true
            switch record.phase {
            case .sanitized:
                return .notRequired
            case .requiresSafeConfiguration:
                return .requiresSafeConfiguration(
                    analytics: analytics,
                    diagnostics: diagnostics
                )
            case .awaitingRestart:
                guard let scheduledProcessToken = record.processToken else {
                    return repairCorruptedOverrideSanitizationMarker()
                }
                return scheduledProcessToken == processToken
                    ? .awaitingRestart(analytics: analytics, diagnostics: diagnostics)
                    : .readyAfterRestart
            }
        case .missing:
            guard markDiagnosticsReportPurgePending(),
                  markInstallationDeletionPending() else {
                return .failure
            }
            let record = StoredFirebaseOverrideSanitizationRecord(
                schemaVersion: overrideSanitizationSchemaVersion,
                phase: .requiresSafeConfiguration,
                processToken: nil,
                analyticsNeedsSanitization: true,
                diagnosticsNeedsSanitization: true
            )
            guard let data = try? JSONEncoder().encode(record),
                  writeData(data, account: overrideSanitizationKeychainAccount) else {
                return .requiresSafeConfiguration(analytics: true, diagnostics: true)
            }
            purgeLegacyPreferences()
            return .requiresSafeConfiguration(analytics: true, diagnostics: true)
        }
    }

    private func writeSanitizedOverrideState() -> Bool {
        let record = StoredFirebaseOverrideSanitizationRecord(
            schemaVersion: overrideSanitizationSchemaVersion,
            phase: .sanitized,
            processToken: nil,
            analyticsNeedsSanitization: false,
            diagnosticsNeedsSanitization: false
        )
        guard let data = try? JSONEncoder().encode(record) else { return false }
        return writeData(data, account: overrideSanitizationKeychainAccount)
    }

    private func repairCorruptedOverrideSanitizationMarker() -> FirebaseOverrideSanitizationState {
        guard markDiagnosticsReportPurgePending(),
              markInstallationDeletionPending() else {
            return .failure
        }
        let record = StoredFirebaseOverrideSanitizationRecord(
            schemaVersion: overrideSanitizationSchemaVersion,
            phase: .requiresSafeConfiguration,
            processToken: nil,
            analyticsNeedsSanitization: true,
            diagnosticsNeedsSanitization: true
        )
        guard let data = try? JSONEncoder().encode(record),
              writeData(data, account: overrideSanitizationKeychainAccount) else {
            return .failure
        }
        purgeLegacyPreferences()
        return .requiresSafeConfiguration(analytics: true, diagnostics: true)
    }

    private func prepareDiagnosticsReportPurgeState() -> FirebaseDiagnosticsReportPurgeState {
        switch readData(account: diagnosticsReportPurgeKeychainAccount) {
        case .missing:
            return .notRequired
        case .failure:
            return .failure
        case let .data(data):
            guard let record = try? JSONDecoder().decode(
                StoredFirebaseDiagnosticsReportPurgeRecord.self,
                from: data
            ), record.schemaVersion == diagnosticsReportPurgeSchemaVersion,
               record.pending else {
                return markDiagnosticsReportPurgePending(forceRewrite: true) ? .pending : .failure
            }
            return .pending
        }
    }

    private func prepareInstallationDeletionState() -> FirebaseInstallationDeletionState {
        switch readData(account: installationDeletionKeychainAccount) {
        case .missing:
            return .notRequired
        case .failure:
            return .failure
        case let .data(data):
            if let record = decodeInstallationDeletionRecord(data) {
                return .pending(record)
            }
            let recoveryIntent: FirebaseInstallationDeletionIntent = .revokeConsent
            return markInstallationDeletionPending(intent: recoveryIntent)
                ? installationDeletionState
                : .failure
        }
    }

    private func preparePrivacyTransactionState() -> FirebasePrivacyTransactionState {
        switch readData(account: privacyTransactionKeychainAccount) {
        case .missing:
            return .notRequired
        case .failure:
            return .failure
        case let .data(data):
            guard let record = try? JSONDecoder().decode(
                StoredFirebasePrivacyTransactionRecord.self,
                from: data
            ), record.schemaVersion == privacyTransactionSchemaVersion,
               record.replacementConsent.map(isValidReplacementConsent) ?? true else {
                return .failure
            }
            return .pending(record)
        }
    }

    private func persistPrivacyTransactionRecord(
        _ record: StoredFirebasePrivacyTransactionRecord
    ) -> Bool {
        guard let data = try? JSONEncoder().encode(record),
              writeData(data, account: privacyTransactionKeychainAccount) else {
            privacyTransactionState = .failure
            return false
        }
        privacyTransactionState = .pending(record)
        return true
    }

    private func updatePrivacyTransaction(
        _ update: (StoredFirebasePrivacyTransactionRecord) -> StoredFirebasePrivacyTransactionRecord
    ) -> Bool {
        guard case let .pending(record) = refreshPrivacyTransactionState() else {
            return privacyTransactionState == .notRequired
        }
        return persistPrivacyTransactionRecord(update(record))
    }

    private func storedConsentRecord(
        _ consent: ObservabilityConsent,
        ownerUserId: String
    ) -> StoredFirebaseConsentRecord {
        StoredFirebaseConsentRecord(
            schemaVersion: consentSchemaVersion,
            ownerFingerprint: ownerFingerprint(ownerUserId),
            analyticsAllowed: consent.analyticsAllowed,
            diagnosticsAllowed: consent.diagnosticsAllowed,
            remoteConfigurationAllowed: consent.remoteConfigurationAllowed
        )
    }

    private func persistInstallationDeletionRecord(
        _ record: StoredFirebaseInstallationDeletionRecord
    ) -> Bool {
        guard let data = try? JSONEncoder().encode(record),
              writeData(data, account: installationDeletionKeychainAccount) else {
            return false
        }
        installationDeletionState = .pending(record)
        return true
    }

    private func decodeInstallationDeletionRecord(
        _ data: Data
    ) -> StoredFirebaseInstallationDeletionRecord? {
        guard let record = try? JSONDecoder().decode(
            StoredFirebaseInstallationDeletionRecord.self,
            from: data
        ), record.schemaVersion == installationDeletionSchemaVersion,
           !record.requestID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        switch record.consentMutation {
        case .preserve, .revoke:
            return record.replacementConsent == nil ? record : nil
        case .replace:
            guard let replacementConsent = record.replacementConsent,
                  isValidReplacementConsent(replacementConsent) else {
                return nil
            }
            return record
        }
    }

    private func isValidReplacementConsent(_ record: StoredFirebaseConsentRecord) -> Bool {
        record.schemaVersion == consentSchemaVersion &&
            !record.ownerFingerprint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func purgeLegacyPreferences() {
        legacyConsentKeys.forEach { key in
            legacyUserDefaults.removeObject(forKey: key)
        }
    }

    private func readData(account: String) -> FirebaseKeychainReadResult {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return .missing }
        guard status == errSecSuccess, let data = item as? Data else { return .failure }
        return .data(data)
    }

    private func writeData(_ data: Data, account: String) -> Bool {
        let query = baseQuery(account: account)
        let updateStatus = SecItemUpdate(
            query as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        if updateStatus == errSecSuccess { return true }
        guard updateStatus == errSecItemNotFound else { return false }
        var insert = query
        insert[kSecValueData as String] = data
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        return SecItemAdd(insert as CFDictionary, nil) == errSecSuccess
    }

    private func remove(account: String) -> Bool {
        let status = SecItemDelete(baseQuery(account: account) as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}

private enum FirebaseKeychainReadResult {
    case missing
    case data(Data)
    case failure
}

private enum FirebaseConsentReadResult {
    case missing
    case stored(StoredFirebaseConsent)
    case corrupted
    case failure
}

private struct StoredFirebaseConsent {
    let ownerFingerprint: String
    let consent: ObservabilityConsent
}

private struct StoredFirebaseConsentRecord: Codable, Equatable {
    let schemaVersion: Int
    let ownerFingerprint: String
    let analyticsAllowed: Bool
    let diagnosticsAllowed: Bool
    let remoteConfigurationAllowed: Bool
}

private enum FirebasePrivacyTransactionState: Equatable {
    case notRequired
    case pending(StoredFirebasePrivacyTransactionRecord)
    case failure

    var isPending: Bool {
        if case .pending = self {
            return true
        }
        return false
    }
}

private enum FirebaseDisabledSessionCleanupStagingResult {
    case knownState
    case unknownState
    case failure
}

private struct StoredFirebasePrivacyTransactionRecord: Codable, Equatable {
    let schemaVersion: Int
    let sessionCheckpointPurgePending: Bool
    let diagnosticsPurgePending: Bool
    let installationDeletionPending: Bool
    let replacementConsent: StoredFirebaseConsentRecord?
    let analyticsOverrideSanitizationPending: Bool
    let diagnosticsOverrideSanitizationPending: Bool

    init(
        schemaVersion: Int,
        sessionCheckpointPurgePending: Bool,
        diagnosticsPurgePending: Bool,
        installationDeletionPending: Bool,
        replacementConsent: StoredFirebaseConsentRecord?,
        analyticsOverrideSanitizationPending: Bool = false,
        diagnosticsOverrideSanitizationPending: Bool = false
    ) {
        self.schemaVersion = schemaVersion
        self.sessionCheckpointPurgePending = sessionCheckpointPurgePending
        self.diagnosticsPurgePending = diagnosticsPurgePending
        self.installationDeletionPending = installationDeletionPending
        self.replacementConsent = replacementConsent
        self.analyticsOverrideSanitizationPending = analyticsOverrideSanitizationPending
        self.diagnosticsOverrideSanitizationPending = diagnosticsOverrideSanitizationPending
    }

    private enum CodingKeys: String, CodingKey {
        case schemaVersion
        case sessionCheckpointPurgePending
        case diagnosticsPurgePending
        case installationDeletionPending
        case replacementConsent
        case analyticsOverrideSanitizationPending
        case diagnosticsOverrideSanitizationPending
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try container.decode(Int.self, forKey: .schemaVersion)
        sessionCheckpointPurgePending = try container.decode(
            Bool.self,
            forKey: .sessionCheckpointPurgePending
        )
        diagnosticsPurgePending = try container.decode(Bool.self, forKey: .diagnosticsPurgePending)
        installationDeletionPending = try container.decode(
            Bool.self,
            forKey: .installationDeletionPending
        )
        replacementConsent = try container.decodeIfPresent(
            StoredFirebaseConsentRecord.self,
            forKey: .replacementConsent
        )
        analyticsOverrideSanitizationPending = try container.decodeIfPresent(
            Bool.self,
            forKey: .analyticsOverrideSanitizationPending
        ) ?? false
        diagnosticsOverrideSanitizationPending = try container.decodeIfPresent(
            Bool.self,
            forKey: .diagnosticsOverrideSanitizationPending
        ) ?? false
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(schemaVersion, forKey: .schemaVersion)
        try container.encode(sessionCheckpointPurgePending, forKey: .sessionCheckpointPurgePending)
        try container.encode(diagnosticsPurgePending, forKey: .diagnosticsPurgePending)
        try container.encode(installationDeletionPending, forKey: .installationDeletionPending)
        try container.encodeIfPresent(replacementConsent, forKey: .replacementConsent)
        try container.encode(
            analyticsOverrideSanitizationPending,
            forKey: .analyticsOverrideSanitizationPending
        )
        try container.encode(
            diagnosticsOverrideSanitizationPending,
            forKey: .diagnosticsOverrideSanitizationPending
        )
    }

    var hasPendingCleanup: Bool {
        sessionCheckpointPurgePending ||
            diagnosticsPurgePending ||
            installationDeletionPending ||
            analyticsOverrideSanitizationPending ||
            diagnosticsOverrideSanitizationPending
    }

    func matches(_ mutation: PendingObservabilityConsentMutation) -> Bool {
        switch mutation {
        case let .update(consent, ownerUserId):
            return replacementConsent?.matches(consent, ownerUserId: ownerUserId) == true
        case .revoke:
            return replacementConsent == nil
        }
    }
}

private extension StoredFirebaseConsentRecord {
    func matches(_ consent: ObservabilityConsent, ownerUserId: String) -> Bool {
        matchesOwnerFingerprint(ownerFingerprint, ownerUserId: ownerUserId) &&
            analyticsAllowed == consent.analyticsAllowed &&
            diagnosticsAllowed == consent.diagnosticsAllowed &&
            remoteConfigurationAllowed == consent.remoteConfigurationAllowed
    }
}

private extension StoredFirebaseConsent {
    func matches(_ expectedConsent: ObservabilityConsent, ownerUserId: String) -> Bool {
        matchesOwnerFingerprint(ownerFingerprint, ownerUserId: ownerUserId) &&
            consent.analyticsAllowed == expectedConsent.analyticsAllowed &&
            consent.diagnosticsAllowed == expectedConsent.diagnosticsAllowed &&
            consent.remoteConfigurationAllowed == expectedConsent.remoteConfigurationAllowed
    }
}

private enum FirebaseOverrideSanitizationState: Equatable {
    case notRequired
    case requiresSafeConfiguration(analytics: Bool, diagnostics: Bool)
    case awaitingRestart(analytics: Bool, diagnostics: Bool)
    case readyAfterRestart
    case failure

    func allowsConfiguration(for consent: ObservabilityConsent) -> Bool {
        switch self {
        case .notRequired, .readyAfterRestart:
            return true
        case let .requiresSafeConfiguration(analytics, diagnostics):
            return (!analytics || consent.analyticsAllowed) &&
                (!diagnostics || consent.diagnosticsAllowed)
        case .awaitingRestart:
            return false
        case .failure:
            return false
        }
    }

    var allowsCollection: Bool {
        switch self {
        case .notRequired, .readyAfterRestart:
            return true
        case .requiresSafeConfiguration, .awaitingRestart, .failure:
            return false
        }
    }

    var requirements: (analytics: Bool, diagnostics: Bool) {
        switch self {
        case let .requiresSafeConfiguration(analytics, diagnostics),
             let .awaitingRestart(analytics, diagnostics):
            return (analytics, diagnostics)
        case .notRequired, .readyAfterRestart, .failure:
            return (false, false)
        }
    }
}

private enum FirebaseInstallationDeletionState: Equatable {
    case notRequired
    case pending(StoredFirebaseInstallationDeletionRecord)
    case failure

    var isPending: Bool {
        if case .pending = self {
            return true
        }
        return false
    }
}

private enum FirebaseDiagnosticsReportPurgeState: Equatable {
    case notRequired
    case pending
    case failure
}

private struct StoredFirebaseOverrideSanitizationRecord: Codable {
    let schemaVersion: Int
    let phase: FirebaseOverrideSanitizationPhase
    let processToken: String?
    let analyticsNeedsSanitization: Bool?
    let diagnosticsNeedsSanitization: Bool?
}

private enum FirebaseInstallationDeletionIntent {
    case preserveConsent
    case replaceConsent(ObservabilityConsent, ownerUserId: String)
    case revokeConsent
}

private enum PendingObservabilityConsentMutation {
    case update(ObservabilityConsent, ownerUserId: String)
    case revoke
}

private enum FirebaseInstallationDeletionConsentMutation: String, Codable {
    case preserve
    case replace
    case revoke
}

private struct StoredFirebaseInstallationDeletionRecord: Codable, Equatable {
    let schemaVersion: Int
    let requestID: String
    let consentMutation: FirebaseInstallationDeletionConsentMutation
    let replacementConsent: StoredFirebaseConsentRecord?
}

private struct StoredFirebaseDiagnosticsReportPurgeRecord: Codable {
    let schemaVersion: Int
    let pending: Bool
}

private enum FirebaseInstallationDeletionCompletion {
    case completed
    case superseded
    case failure
}

private enum FirebaseOverrideSanitizationPhase: String, Codable {
    case sanitized
    case requiresSafeConfiguration
    case awaitingRestart
}

private func normalizedUserId(_ userId: String?) -> String? {
    guard let normalized = userId?.trimmingCharacters(in: .whitespacesAndNewlines),
          !normalized.isEmpty else {
        return nil
    }
    return normalized
}

private func ownerFingerprint(_ userId: String) -> String {
    SHA256.hash(data: Data(userId.utf8))
        .map { String(format: "%02x", $0) }
        .joined()
}

private func matchesOwnerFingerprint(_ fingerprint: String, ownerUserId: String) -> Bool {
    fingerprint == ownerFingerprint(ownerUserId)
}

private func disabledObservabilityConsent() -> ObservabilityConsent {
    ObservabilityConsent(
        analyticsAllowed: false,
        diagnosticsAllowed: false,
        remoteConfigurationAllowed: false
    )
}

private extension ObservabilityConsent {
    var allowsAnyCollection: Bool {
        analyticsAllowed || diagnosticsAllowed || remoteConfigurationAllowed
    }
}

private let remoteConfigFetchInterval: TimeInterval = 43_200
private let notApplicable = "not_applicable"
private let diagnosticDomain = "com.kwabor.observability"
private let diagnosticErrorCode = 1
private let performanceProcessExploreKindAttribute = "process_explore_kind"
private let performanceViewportStateAttribute = "viewport_state"
private let remoteConfigFetchFailureCode = "remote_config_fetch_failed"
private let legacyConsentKeys = [
    "kwabor.observability.analytics_allowed",
    "kwabor.observability.diagnostics_allowed",
    "kwabor.observability.remote_configuration_allowed",
    "kwabor.observability.owner_user_id",
    "kwabor.observability.force_disabled",
]
private let fallbackConsentService = "com.kwabor.ios"
private let consentServiceSuffix = ".observability-consent"
private let consentKeychainAccount = "account-bound-consent"
private let overrideSanitizationKeychainAccount = "firebase-override-sanitization"
private let diagnosticsReportPurgeKeychainAccount = "firebase-diagnostics-report-purge"
private let installationDeletionKeychainAccount = "firebase-installation-deletion"
private let privacyTransactionKeychainAccount = "firebase-privacy-transaction"
private let consentSchemaVersion = 1
private let overrideSanitizationSchemaVersion = 1
private let diagnosticsReportPurgeSchemaVersion = 1
private let installationDeletionSchemaVersion = 2
private let privacyTransactionSchemaVersion = 1
private let firebaseObservabilityProcessToken = UUID().uuidString
