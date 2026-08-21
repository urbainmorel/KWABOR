package com.kwabor.android.observability

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.ConfigUpdateListenerRegistration
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.domain.observability.PerformanceMeasurement
import com.kwabor.shared.domain.observability.PerformanceTraceName

internal fun createAndroidObservabilityController(context: Context): AndroidObservabilityController =
    AndroidObservabilityController(
        backend = FirebaseAndroidObservabilityBackend(context.applicationContext),
        consentStore = SharedPreferencesObservabilityConsentStore(context.applicationContext),
    )

private class FirebaseAndroidObservabilityBackend(
    private val context: Context,
) : AndroidObservabilityBackend {
    private var firebaseApp: FirebaseApp? = null
    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null
    private var performance: FirebasePerformance? = null
    private var remoteConfig: FirebaseRemoteConfig? = null
    private var installations: FirebaseInstallations? = null
    private var configUpdateRegistration: ConfigUpdateListenerRegistration? = null

    override val isConfigured: Boolean
        get() = firebaseApp != null

    override fun ensureConfigured(): Boolean {
        if (isConfigured) return true
        val app = FirebaseApp.initializeApp(context) ?: return false
        val initializedAnalytics = FirebaseAnalytics.getInstance(context)
        val initializedCrashlytics = FirebaseCrashlytics.getInstance()
        val initializedPerformance = FirebasePerformance.getInstance()
        initializedAnalytics.setAnalyticsCollectionEnabled(false)
        initializedCrashlytics.setCrashlyticsCollectionEnabled(false)
        initializedPerformance.isPerformanceCollectionEnabled = false
        val initializedRemoteConfig = FirebaseRemoteConfig.getInstance(app).apply {
            setConfigSettingsAsync(
                FirebaseRemoteConfigSettings.Builder()
                    .setMinimumFetchIntervalInSeconds(REMOTE_CONFIG_FETCH_INTERVAL_SECONDS)
                    .build(),
            )
        }
        firebaseApp = app
        analytics = initializedAnalytics
        crashlytics = initializedCrashlytics
        performance = initializedPerformance
        remoteConfig = initializedRemoteConfig
        installations = FirebaseInstallations.getInstance(app)
        return true
    }

    override fun applyConsent(consent: ObservabilityConsent) {
        analytics?.setUserProperty(FirebaseAnalytics.UserProperty.ALLOW_AD_PERSONALIZATION_SIGNALS, "false")
        analytics?.setAnalyticsCollectionEnabled(consent.analyticsAllowed)
        crashlytics?.setCrashlyticsCollectionEnabled(false)
        performance?.isPerformanceCollectionEnabled = consent.diagnosticsAllowed
    }

    override fun resetAnalyticsData() {
        analytics?.setAnalyticsCollectionEnabled(false)
        analytics?.resetAnalyticsData()
    }

    override fun checkForUnsentReports(onResult: (DiagnosticsReportCheckResult) -> Unit) {
        val crashReporter = crashlytics ?: run {
            onResult(DiagnosticsReportCheckResult.Failure)
            return
        }
        crashReporter.checkForUnsentReports().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                onResult(DiagnosticsReportCheckResult.Success(hasUnsentReports = task.result == true))
            } else {
                onResult(DiagnosticsReportCheckResult.Failure)
            }
        }
    }

    override fun deleteUnsentReports() {
        crashlytics?.deleteUnsentReports()
    }

    override fun sendUnsentReports() {
        crashlytics?.sendUnsentReports()
    }

    override fun deleteInstallation(onResult: (Boolean) -> Unit) {
        val firebaseInstallations = installations ?: run {
            onResult(false)
            return
        }
        firebaseInstallations.delete().addOnCompleteListener { task ->
            onResult(task.isSuccessful)
        }
    }

    override fun track(event: AnalyticsEvent) {
        analytics?.logEvent(event.name.wireName, event.toBundle())
    }

    override fun recordDiagnostic(code: DiagnosticCode) {
        crashlytics?.recordException(KwaborDiagnosticException(code))
    }

    override fun startTrace(name: PerformanceTraceName): PerformanceTrace {
        val trace = performance?.newTrace(name.wireName) ?: return PerformanceTrace.None
        trace.start()
        return PerformanceTrace(trace::stop)
    }

    override fun recordPerformanceMeasurement(measurement: PerformanceMeasurement) {
        val trace = performance?.newTrace(measurement.traceName.wireName) ?: return
        trace.start()
        trace.putMetric(measurement.metricName.wireName, measurement.metricValue)
        trace.putAttribute(PERFORMANCE_SAMPLE_KIND_ATTRIBUTE, measurement.sampleKind.wireName)
        trace.putAttribute(PERFORMANCE_VIEWPORT_STATE_ATTRIBUTE, measurement.viewportState.wireName)
        trace.stop()
    }

    override fun fetchAndActivateRemoteConfiguration(onResult: (Boolean) -> Unit) {
        val config = remoteConfig ?: run {
            onResult(false)
            return
        }
        config.fetchAndActivate().addOnCompleteListener { task ->
            onResult(task.isSuccessful)
        }
    }

    override fun startRemoteConfigurationUpdates(onResult: (Boolean) -> Unit) {
        val config = remoteConfig ?: return
        if (configUpdateRegistration != null) return
        configUpdateRegistration = config.addOnConfigUpdateListener(
            object : ConfigUpdateListener {
                override fun onUpdate(configUpdate: ConfigUpdate) {
                    if (configUpdate.updatedKeys.isEmpty()) return
                    config.activate().addOnCompleteListener { task ->
                        onResult(task.isSuccessful)
                    }
                }

                override fun onError(error: FirebaseRemoteConfigException) {
                    onResult(false)
                }
            },
        )
    }

    override fun stopRemoteConfigurationUpdates() {
        configUpdateRegistration?.remove()
        configUpdateRegistration = null
    }
}

private class KwaborDiagnosticException(code: DiagnosticCode) : IllegalStateException(code.wireName)

private fun AnalyticsEvent.toBundle(): Bundle = Bundle().apply {
    putString("ville", context.cityId ?: NOT_APPLICABLE)
    putString("type_entite", context.entityType.wireName)
    putString("entite_id", context.entityId ?: NOT_APPLICABLE)
    putString("source_session", context.sessionSource.wireName)
    putString("langue", context.locale.tag)
    putString("devise_affichage", context.displayCurrency.name.uppercase())
    authMethod?.let { method -> putString("auth_method", method.wireName) }
    socialPostType?.let { postType -> putString("post_type", postType.wireName) }
}

private const val REMOTE_CONFIG_FETCH_INTERVAL_SECONDS = 43_200L
private const val NOT_APPLICABLE = "not_applicable"
private const val PERFORMANCE_SAMPLE_KIND_ATTRIBUTE = "sample_kind"
private const val PERFORMANCE_VIEWPORT_STATE_ATTRIBUTE = "viewport_state"
