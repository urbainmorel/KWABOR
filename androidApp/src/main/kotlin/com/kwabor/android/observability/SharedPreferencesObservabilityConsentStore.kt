package com.kwabor.android.observability

import android.content.Context
import com.kwabor.shared.domain.observability.ObservabilityConsent

internal class SharedPreferencesObservabilityConsentStore(context: Context) : ObservabilityConsentStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): ObservabilityConsent = ObservabilityConsent(
        analyticsAllowed = preferences.getBoolean(ANALYTICS_ALLOWED_KEY, false),
        diagnosticsAllowed = preferences.getBoolean(DIAGNOSTICS_ALLOWED_KEY, false),
        remoteConfigurationAllowed = preferences.getBoolean(REMOTE_CONFIGURATION_ALLOWED_KEY, false),
    )

    override fun write(consent: ObservabilityConsent) {
        preferences.edit()
            .putBoolean(ANALYTICS_ALLOWED_KEY, consent.analyticsAllowed)
            .putBoolean(DIAGNOSTICS_ALLOWED_KEY, consent.diagnosticsAllowed)
            .putBoolean(REMOTE_CONFIGURATION_ALLOWED_KEY, consent.remoteConfigurationAllowed)
            .apply()
    }
}

private const val PREFERENCES_NAME = "kwabor_observability_consent"
private const val ANALYTICS_ALLOWED_KEY = "analytics_allowed"
private const val DIAGNOSTICS_ALLOWED_KEY = "diagnostics_allowed"
private const val REMOTE_CONFIGURATION_ALLOWED_KEY = "remote_configuration_allowed"
