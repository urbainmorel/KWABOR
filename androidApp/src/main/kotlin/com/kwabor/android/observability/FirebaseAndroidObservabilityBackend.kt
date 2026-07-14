package com.kwabor.android.observability

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.domain.observability.PerformanceTraceName
import com.kwabor.shared.domain.observability.RemoteFeatureConfiguration
import com.kwabor.shared.domain.observability.createRemoteFeatureConfiguration

internal class FirebaseAndroidObservabilityBackend private constructor(
    private val analytics: FirebaseAnalytics?,
    private val crashlytics: FirebaseCrashlytics?,
    private val performance: FirebasePerformance?,
    private val remoteConfig: FirebaseRemoteConfig?,
) : AndroidObservabilityBackend {
    override val isConfigured: Boolean = analytics != null

    override fun applyConsent(consent: ObservabilityConsent) {
        analytics?.setUserProperty(FirebaseAnalytics.UserProperty.ALLOW_AD_PERSONALIZATION_SIGNALS, "false")
        analytics?.setAnalyticsCollectionEnabled(consent.analyticsAllowed)
        crashlytics?.setCrashlyticsCollectionEnabled(consent.diagnosticsAllowed)
        performance?.isPerformanceCollectionEnabled = consent.diagnosticsAllowed
        if (!consent.analyticsAllowed) {
            analytics?.resetAnalyticsData()
        }
        if (!consent.diagnosticsAllowed) {
            crashlytics?.deleteUnsentReports()
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

    override fun fetchRemoteConfiguration(onResult: (RemoteFeatureConfiguration?) -> Unit) {
        val config = remoteConfig ?: run {
            onResult(null)
            return
        }
        config.fetchAndActivate().addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                onResult(null)
                return@addOnCompleteListener
            }
            onResult(config.toDomainConfiguration())
        }
    }

    companion object {
        fun create(context: Context): FirebaseAndroidObservabilityBackend {
            val firebaseApp = FirebaseApp.initializeApp(context) ?: return unconfigured()
            val remoteConfig = FirebaseRemoteConfig.getInstance(firebaseApp).apply {
                setConfigSettingsAsync(
                    FirebaseRemoteConfigSettings.Builder()
                        .setMinimumFetchIntervalInSeconds(REMOTE_CONFIG_FETCH_INTERVAL_SECONDS)
                        .build(),
                )
                setDefaultsAsync(REMOTE_CONFIG_DEFAULTS)
            }
            return FirebaseAndroidObservabilityBackend(
                analytics = FirebaseAnalytics.getInstance(context),
                crashlytics = FirebaseCrashlytics.getInstance(),
                performance = FirebasePerformance.getInstance(),
                remoteConfig = remoteConfig,
            )
        }

        private fun unconfigured() = FirebaseAndroidObservabilityBackend(
            analytics = null,
            crashlytics = null,
            performance = null,
            remoteConfig = null,
        )
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

private fun FirebaseRemoteConfig.toDomainConfiguration(): RemoteFeatureConfiguration = createRemoteFeatureConfiguration(
    introVideoEnabled = getBoolean(INTRO_VIDEO_ENABLED_KEY),
    introVideoUrl = getString(INTRO_VIDEO_URL_KEY),
    introVideoSha256 = getString(INTRO_VIDEO_SHA256_KEY),
    introVideoRevision = getLong(INTRO_VIDEO_REVISION_KEY),
)

private const val REMOTE_CONFIG_FETCH_INTERVAL_SECONDS = 43_200L
private const val INTRO_VIDEO_ENABLED_KEY = "intro_video_enabled"
private const val INTRO_VIDEO_URL_KEY = "intro_video_url"
private const val INTRO_VIDEO_SHA256_KEY = "intro_video_sha256"
private const val INTRO_VIDEO_REVISION_KEY = "intro_video_revision"
private const val NOT_APPLICABLE = "not_applicable"
private val REMOTE_CONFIG_DEFAULTS: Map<String, Any> = mapOf(
    INTRO_VIDEO_ENABLED_KEY to false,
    INTRO_VIDEO_URL_KEY to "",
    INTRO_VIDEO_SHA256_KEY to "",
    INTRO_VIDEO_REVISION_KEY to 0L,
)
