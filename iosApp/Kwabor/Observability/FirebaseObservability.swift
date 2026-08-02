import FirebaseAnalytics
import FirebaseCore
import FirebaseCrashlytics
import FirebasePerformance
import FirebaseRemoteConfig
import Foundation
import Shared

@MainActor
final class FirebaseObservability {
    private let consentStore: FirebaseConsentStore
    private var remoteConfig: RemoteConfig?
    private var remoteConfigUpdateRegistration: ConfigUpdateListenerRegistration?
    private var remoteConfigurationGeneration = 0
    private var performance: Performance?

    private(set) var consent: ObservabilityConsent
    private(set) var isConfigured = false

    init(bundle: Bundle = .main, userDefaults: UserDefaults = .standard) {
        consentStore = FirebaseConsentStore(userDefaults: userDefaults)
        consent = consentStore.read()

        guard
            let configurationPath = bundle.path(forResource: "GoogleService-Info", ofType: "plist"),
            let options = FirebaseOptions(contentsOfFile: configurationPath),
            options.bundleID == bundle.bundleIdentifier
        else {
            return
        }

        if FirebaseApp.app() == nil {
            FirebaseApp.configure(options: options)
        }
        remoteConfig = configureRemoteConfig()
        performance = Performance.sharedInstance()
        isConfigured = true
        applyConsent(consent)
        if consent.remoteConfigurationAllowed {
            startRemoteConfigurationSession()
        }
    }

    func updateConsent(_ updatedConsent: ObservabilityConsent) {
        let remoteConfigurationWasAllowed = consent.remoteConfigurationAllowed
        consent = updatedConsent
        consentStore.write(updatedConsent)
        applyConsent(updatedConsent)

        if !updatedConsent.remoteConfigurationAllowed {
            remoteConfigurationGeneration += 1
            stopRemoteConfigurationUpdates()
        } else if !remoteConfigurationWasAllowed {
            startRemoteConfigurationSession()
        }
    }

    func track(_ event: AnalyticsEvent) {
        guard isConfigured, consent.analyticsAllowed else { return }
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

    func recordDiagnostic(_ code: DiagnosticCode) {
        recordDiagnostic(wireName: code.wireName)
    }

    private func recordDiagnostic(wireName: String) {
        guard isConfigured, consent.diagnosticsAllowed else { return }
        let error = NSError(
            domain: diagnosticDomain,
            code: diagnosticErrorCode,
            userInfo: [NSLocalizedDescriptionKey: wireName]
        )
        Crashlytics.crashlytics().record(error: error)
    }

    func startTrace(_ name: PerformanceTraceName) -> FirebasePerformanceTrace? {
        guard isConfigured, consent.diagnosticsAllowed else {
            return nil
        }
        guard let trace = Performance.startTrace(name: name.wireName) else {
            return nil
        }
        return FirebasePerformanceTrace(trace: trace)
    }

    private func startRemoteConfigurationSession() {
        guard isConfigured, consent.remoteConfigurationAllowed else { return }
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
        consent.remoteConfigurationAllowed && generation == remoteConfigurationGeneration
    }

    private func stopRemoteConfigurationUpdates() {
        remoteConfigUpdateRegistration?.remove()
        remoteConfigUpdateRegistration = nil
    }

    private func applyConsent(_ consent: ObservabilityConsent) {
        guard isConfigured else { return }
        Analytics.setUserProperty("false", forName: AnalyticsUserPropertyAllowAdPersonalizationSignals)
        Analytics.setAnalyticsCollectionEnabled(consent.analyticsAllowed)
        Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(consent.diagnosticsAllowed)
        performance?.isDataCollectionEnabled = consent.diagnosticsAllowed
        performance?.isInstrumentationEnabled = consent.diagnosticsAllowed

        if !consent.analyticsAllowed {
            Analytics.resetAnalyticsData()
        }
        if !consent.diagnosticsAllowed {
            Crashlytics.crashlytics().deleteUnsentReports()
        }
    }

    private func configureRemoteConfig() -> RemoteConfig {
        let config = RemoteConfig.remoteConfig()
        let settings = RemoteConfigSettings()
        settings.minimumFetchInterval = remoteConfigFetchInterval
        config.configSettings = settings
        return config
    }
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

private struct FirebaseConsentStore {
    let userDefaults: UserDefaults

    func read() -> ObservabilityConsent {
        ObservabilityConsent(
            analyticsAllowed: userDefaults.bool(forKey: analyticsAllowedKey),
            diagnosticsAllowed: userDefaults.bool(forKey: diagnosticsAllowedKey),
            remoteConfigurationAllowed: userDefaults.bool(forKey: remoteConfigurationAllowedKey)
        )
    }

    func write(_ consent: ObservabilityConsent) {
        userDefaults.set(consent.analyticsAllowed, forKey: analyticsAllowedKey)
        userDefaults.set(consent.diagnosticsAllowed, forKey: diagnosticsAllowedKey)
        userDefaults.set(consent.remoteConfigurationAllowed, forKey: remoteConfigurationAllowedKey)
    }
}

private let remoteConfigFetchInterval: TimeInterval = 43_200
private let notApplicable = "not_applicable"
private let diagnosticDomain = "com.kwabor.observability"
private let diagnosticErrorCode = 1
private let remoteConfigFetchFailureCode = "remote_config_fetch_failed"
private let analyticsAllowedKey = "kwabor.observability.analytics_allowed"
private let diagnosticsAllowedKey = "kwabor.observability.diagnostics_allowed"
private let remoteConfigurationAllowedKey = "kwabor.observability.remote_configuration_allowed"
