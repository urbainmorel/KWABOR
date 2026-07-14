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
    private var performance: Performance?

    private(set) var consent: ObservabilityConsent
    private(set) var isConfigured = false
    private(set) var remoteConfiguration = FirebaseRemoteFeatureConfiguration.safeDefaults

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
            refreshRemoteConfiguration()
        }
    }

    func updateConsent(_ updatedConsent: ObservabilityConsent) {
        let remoteConfigurationWasAllowed = consent.remoteConfigurationAllowed
        consent = updatedConsent
        consentStore.write(updatedConsent)
        applyConsent(updatedConsent)

        if !updatedConsent.remoteConfigurationAllowed {
            remoteConfiguration = .safeDefaults
        } else if !remoteConfigurationWasAllowed {
            refreshRemoteConfiguration()
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

    func refreshRemoteConfiguration() {
        guard isConfigured, consent.remoteConfigurationAllowed, let remoteConfig else { return }
        remoteConfig.fetchAndActivate { [weak self] _, error in
            Task { @MainActor in
                guard let self else { return }
                guard error == nil else {
                    self.recordDiagnostic(wireName: remoteConfigFetchFailureCode)
                    return
                }
                self.remoteConfiguration = FirebaseRemoteFeatureConfiguration(remoteConfig: remoteConfig)
            }
        }
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
        config.setDefaults([
            introVideoEnabledKey: false as NSNumber,
            introVideoURLKey: "" as NSString,
            introVideoSHA256Key: "" as NSString,
            introVideoRevisionKey: 0 as NSNumber,
        ])
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

struct FirebaseRemoteFeatureConfiguration: Equatable {
    let introVideo: FirebaseRemoteIntroVideo?

    static let safeDefaults = FirebaseRemoteFeatureConfiguration(introVideo: nil)

    fileprivate init(remoteConfig: RemoteConfig) {
        let enabled = remoteConfig[introVideoEnabledKey].boolValue
        let urlValue = remoteConfig[introVideoURLKey].stringValue
        let hashValue = remoteConfig[introVideoSHA256Key].stringValue.lowercased()
        let revision = remoteConfig[introVideoRevisionKey].numberValue.int64Value
        introVideo = FirebaseRemoteIntroVideo.create(
            enabled: enabled,
            urlValue: urlValue,
            sha256: hashValue,
            revision: revision
        )
    }

    private init(introVideo: FirebaseRemoteIntroVideo?) {
        self.introVideo = introVideo
    }
}

struct FirebaseRemoteIntroVideo: Equatable {
    let url: URL
    let sha256: String
    let revision: Int64

    fileprivate static func create(
        enabled: Bool,
        urlValue: String,
        sha256: String,
        revision: Int64
    ) -> FirebaseRemoteIntroVideo? {
        guard
            enabled,
            !urlValue.isEmpty,
            urlValue.count <= maximumRemoteURLLength,
            urlValue.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
            let components = URLComponents(string: urlValue),
            components.scheme?.lowercased() == "https",
            components.host?.isEmpty == false,
            components.host?.contains(".") == true,
            components.user == nil,
            components.password == nil,
            sha256.range(of: sha256Pattern, options: .regularExpression) != nil,
            revision > 0,
            let url = components.url
        else {
            return nil
        }
        return FirebaseRemoteIntroVideo(url: url, sha256: sha256, revision: revision)
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
private let maximumRemoteURLLength = 2_048
private let introVideoEnabledKey = "intro_video_enabled"
private let introVideoURLKey = "intro_video_url"
private let introVideoSHA256Key = "intro_video_sha256"
private let introVideoRevisionKey = "intro_video_revision"
private let sha256Pattern = "^[a-f0-9]{64}$"
private let notApplicable = "not_applicable"
private let diagnosticDomain = "com.kwabor.observability"
private let diagnosticErrorCode = 1
private let remoteConfigFetchFailureCode = "remote_config_fetch_failed"
private let analyticsAllowedKey = "kwabor.observability.analytics_allowed"
private let diagnosticsAllowedKey = "kwabor.observability.diagnostics_allowed"
private let remoteConfigurationAllowedKey = "kwabor.observability.remote_configuration_allowed"
