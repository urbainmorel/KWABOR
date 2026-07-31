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

    override fun write(consent: ObservabilityConsent): Boolean = preferences.edit()
        .putBoolean(ANALYTICS_ALLOWED_KEY, consent.analyticsAllowed)
        .putBoolean(DIAGNOSTICS_ALLOWED_KEY, consent.diagnosticsAllowed)
        .putBoolean(REMOTE_CONFIGURATION_ALLOWED_KEY, consent.remoteConfigurationAllowed)
        .commit()

    override fun readRemoteMediaPurgeState(): RemoteMediaPurgeState {
        val required = RemoteMediaPurgeEpoch(
            consentRevocations = preferences.getLong(REQUIRED_CONSENT_PURGE_EPOCH_KEY, 0L).coerceAtLeast(0L),
            explicitDisables = preferences.getLong(REQUIRED_DISABLE_PURGE_EPOCH_KEY, 0L).coerceAtLeast(0L),
        )
        val acknowledged = RemoteMediaPurgeEpoch(
            consentRevocations = preferences.getLong(ACKNOWLEDGED_CONSENT_PURGE_EPOCH_KEY, 0L)
                .coerceIn(0L, required.consentRevocations),
            explicitDisables = preferences.getLong(ACKNOWLEDGED_DISABLE_PURGE_EPOCH_KEY, 0L)
                .coerceIn(0L, required.explicitDisables),
        )
        return RemoteMediaPurgeState(required = required, acknowledged = acknowledged)
    }

    override fun writeRequiredRemoteMediaPurgeEpoch(epoch: RemoteMediaPurgeEpoch): Boolean = preferences.edit()
        .putLong(REQUIRED_CONSENT_PURGE_EPOCH_KEY, epoch.consentRevocations)
        .putLong(REQUIRED_DISABLE_PURGE_EPOCH_KEY, epoch.explicitDisables)
        .commit()

    override fun writeAcknowledgedRemoteMediaPurgeEpoch(epoch: RemoteMediaPurgeEpoch): Boolean = preferences.edit()
        .putLong(ACKNOWLEDGED_CONSENT_PURGE_EPOCH_KEY, epoch.consentRevocations)
        .putLong(ACKNOWLEDGED_DISABLE_PURGE_EPOCH_KEY, epoch.explicitDisables)
        .commit()
}

private const val PREFERENCES_NAME = "kwabor_observability_consent"
private const val ANALYTICS_ALLOWED_KEY = "analytics_allowed"
private const val DIAGNOSTICS_ALLOWED_KEY = "diagnostics_allowed"
private const val REMOTE_CONFIGURATION_ALLOWED_KEY = "remote_configuration_allowed"
private const val REQUIRED_CONSENT_PURGE_EPOCH_KEY = "required_remote_media_consent_purge_epoch"
private const val REQUIRED_DISABLE_PURGE_EPOCH_KEY = "required_remote_media_disable_purge_epoch"
private const val ACKNOWLEDGED_CONSENT_PURGE_EPOCH_KEY = "acknowledged_remote_media_consent_purge_epoch"
private const val ACKNOWLEDGED_DISABLE_PURGE_EPOCH_KEY = "acknowledged_remote_media_disable_purge_epoch"
