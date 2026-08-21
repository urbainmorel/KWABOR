package com.kwabor.android.observability

import android.content.Context
import android.content.SharedPreferences
import com.kwabor.shared.domain.observability.ObservabilityConsent
import java.util.UUID

internal class SharedPreferencesObservabilityConsentStore(
    private val preferences: ObservabilityPreferences,
    private val requestIdProvider: () -> String = { UUID.randomUUID().toString() },
) : ObservabilityConsentStore {
    constructor(context: Context) : this(
        AndroidObservabilityPreferences(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        ),
    )

    override fun read(): StoredObservabilityConsent {
        val forceDisabled = preferences.getBoolean(FORCE_DISABLED_KEY, true)
        val ownerUserId = preferences.getString(OWNER_USER_ID_KEY, null)?.normalizedOrNull()
        val installationDeletionRequestId =
            preferences.getString(INSTALLATION_DELETION_REQUEST_ID_KEY, null)?.normalizedOrNull()
        val staged = preferences.readStagedConsent()
        val persistedConsent = if (ownerUserId == null) {
            ObservabilityConsent()
        } else {
            ObservabilityConsent(
                analyticsAllowed = preferences.getBoolean(ANALYTICS_ALLOWED_KEY, false),
                diagnosticsAllowed = preferences.getBoolean(DIAGNOSTICS_ALLOWED_KEY, false),
                remoteConfigurationAllowed = preferences.getBoolean(REMOTE_CONFIGURATION_ALLOWED_KEY, false),
            )
        }
        return StoredObservabilityConsent(
            ownerUserId = ownerUserId.takeUnless { forceDisabled },
            consent = persistedConsent.takeUnless { forceDisabled } ?: ObservabilityConsent(),
            analyticsPurgePending = preferences.getBoolean(ANALYTICS_PURGE_PENDING_KEY, false),
            diagnosticsReportPurgeRequestId =
            preferences.getString(DIAGNOSTICS_PURGE_REQUEST_ID_KEY, null)?.normalizedOrNull(),
            installationDeletionRequestId = installationDeletionRequestId,
            persistedOwnerUserId = ownerUserId,
            persistedConsent = persistedConsent,
            sessionCheckpointPurgePending = preferences.getBoolean(SESSION_CHECKPOINT_PURGE_PENDING_KEY, false),
            hasStagedConsentActivation = staged.isPending,
            stagedOwnerUserId = staged.ownerUserId,
            stagedConsent = staged.consent,
        )
    }

    fun write(ownerUserId: String, consent: ObservabilityConsent): Boolean {
        val normalizedOwnerUserId = ownerUserId.normalizedOrNull() ?: return false
        val plan = createWritePlan(normalizedOwnerUserId, consent) ?: return false
        return persistWritePlan(plan)
    }

    override fun stageWrite(ownerUserId: String, consent: ObservabilityConsent): Boolean {
        val normalizedOwnerUserId = ownerUserId.normalizedOrNull() ?: return false
        val plan = createWritePlan(normalizedOwnerUserId, consent) ?: return false
        return preferences.commitDurably(stagedConsentMutation(plan))
    }

    fun revoke(): Boolean {
        val current = read()
        val hadAccountState = current.persistedOwnerUserId != null || current.persistedConsent.allowsAnyCollection
        val installationDeletionRequestId = if (hadAccountState) {
            requestIdProvider().normalizedOrNull() ?: return false
        } else {
            current.installationDeletionRequestId
        }
        val maintenance = ObservabilityMaintenanceState(
            analyticsPurgePending = current.analyticsPurgePending || hadAccountState,
            diagnosticsReportPurgeRequestId = if (hadAccountState) {
                requestIdProvider().normalizedOrNull() ?: return false
            } else {
                current.diagnosticsReportPurgeRequestId
            },
            installationDeletionRequestId = installationDeletionRequestId,
        )
        if (!preferences.commitDurably(forceDisabledMutation(maintenance))) return false
        return preferences.commitDurably(
            revokedConsentMutation(maintenance).withoutStagedActivation(),
        )
    }

    override fun stageRevocation(): Boolean {
        val current = read()
        val hadAccountState =
            current.persistedOwnerUserId != null ||
                current.persistedConsent.allowsAnyCollection ||
                current.hasStagedConsentActivation
        val diagnosticsRequestId = if (hadAccountState) {
            requestIdProvider().normalizedOrNull() ?: return false
        } else {
            current.diagnosticsReportPurgeRequestId
        }
        val installationRequestId = if (hadAccountState) {
            requestIdProvider().normalizedOrNull() ?: return false
        } else {
            current.installationDeletionRequestId
        }
        val maintenance = ObservabilityMaintenanceState(
            analyticsPurgePending = current.analyticsPurgePending || hadAccountState,
            diagnosticsReportPurgeRequestId = diagnosticsRequestId,
            installationDeletionRequestId = installationRequestId,
        )
        return preferences.commitDurably(
            revokedConsentMutation(maintenance)
                .withSessionCheckpointPurgePending()
                .withoutStagedActivation(),
        )
    }

    override fun ensureSessionCheckpointPurgePending(): Boolean {
        if (read().sessionCheckpointPurgePending) return true
        return preferences.commitDurably(
            ObservabilityPreferencesMutation(
                booleans = mapOf(SESSION_CHECKPOINT_PURGE_PENDING_KEY to true),
            ),
        )
    }

    override fun completeSessionCheckpointPurge(): Boolean = removeDurably(
        key = SESSION_CHECKPOINT_PURGE_PENDING_KEY,
        restoreMutation = ObservabilityPreferencesMutation(
            booleans = mapOf(SESSION_CHECKPOINT_PURGE_PENDING_KEY to true),
        ),
    )

    override fun activateStagedConsent(): Boolean {
        val current = read()
        val ownerUserId = current.stagedOwnerUserId
        val consent = current.stagedConsent
        return when {
            !current.hasStagedConsentActivation -> true
            current.hasPendingMaintenance || ownerUserId == null || consent == null -> false
            else -> preferences.commitDurably(
                activatedConsentMutation(ownerUserId, consent).withoutStagedActivation(),
            )
        }
    }

    override fun clearAnalyticsPurgePending(): Boolean = removeDurably(
        key = ANALYTICS_PURGE_PENDING_KEY,
        restoreMutation = ObservabilityPreferencesMutation(
            booleans = mapOf(ANALYTICS_PURGE_PENDING_KEY to true),
        ),
    )

    override fun completeDiagnosticsReportPurge(expectedRequestId: String): InstallationDeletionCompletion =
        completeRequest(
            accountKey = DIAGNOSTICS_PURGE_REQUEST_ID_KEY,
            expectedRequestId = expectedRequestId,
        )

    override fun completeInstallationDeletion(expectedRequestId: String): InstallationDeletionCompletion =
        completeRequest(
            accountKey = INSTALLATION_DELETION_REQUEST_ID_KEY,
            expectedRequestId = expectedRequestId,
        )

    private fun completeRequest(accountKey: String, expectedRequestId: String): InstallationDeletionCompletion {
        val currentRequestId = preferences.getString(accountKey, null)?.normalizedOrNull()
            ?: return InstallationDeletionCompletion.Completed
        if (currentRequestId != expectedRequestId) return InstallationDeletionCompletion.Superseded
        return if (removeDurably(accountKey, requestIdMutation(accountKey, currentRequestId))) {
            InstallationDeletionCompletion.Completed
        } else {
            InstallationDeletionCompletion.Failure
        }
    }

    private fun removeDurably(key: String, restoreMutation: ObservabilityPreferencesMutation): Boolean {
        val removed = preferences.commitDurably(ObservabilityPreferencesMutation(removals = setOf(key)))
        if (removed) return true
        preferences.commitDurably(restoreMutation)
        return false
    }

    private fun createWritePlan(ownerUserId: String, consent: ObservabilityConsent): ObservabilityConsentWritePlan? {
        val current = read()
        val transition = ObservabilityConsentTransition(current, ownerUserId, consent)
        val diagnosticsRequestId = transition.diagnosticsRequestId(requestIdProvider)
        val installationRequestId = transition.installationRequestId(requestIdProvider)
        if (!diagnosticsRequestId.isValid || !installationRequestId.isValid) return null
        val maintenance = transition.maintenance(diagnosticsRequestId.value, installationRequestId.value)
        return ObservabilityConsentWritePlan(
            ownerUserId = ownerUserId,
            consent = consent,
            maintenance = maintenance,
            rollbackMutation = historicalConsentMutation(current, maintenance),
        )
    }

    private val persistWritePlan: (ObservabilityConsentWritePlan) -> Boolean = persist@{ plan ->
        val failClosedMutation = forceDisabledMutation(plan.maintenance)
        if (!preferences.commitDurably(failClosedMutation)) return@persist false
        val finalMutation = plan.finalMutation().withoutStagedActivation()
        if (preferences.commitDurably(finalMutation)) return@persist true
        preferences.commitDurably(plan.rollbackMutation)
        false
    }
}

private data class StagedConsentRead(
    val isPending: Boolean,
    val ownerUserId: String?,
    val consent: ObservabilityConsent?,
)

private fun ObservabilityPreferences.readStagedConsent(): StagedConsentRead {
    val isPending = getBoolean(STAGED_ACTIVATION_PENDING_KEY, false)
    val ownerUserId = getString(STAGED_OWNER_USER_ID_KEY, null)?.normalizedOrNull()
    val consent = if (isPending && ownerUserId != null) {
        ObservabilityConsent(
            analyticsAllowed = getBoolean(STAGED_ANALYTICS_ALLOWED_KEY, false),
            diagnosticsAllowed = getBoolean(STAGED_DIAGNOSTICS_ALLOWED_KEY, false),
            remoteConfigurationAllowed = getBoolean(STAGED_REMOTE_CONFIGURATION_ALLOWED_KEY, false),
        )
    } else {
        null
    }
    return StagedConsentRead(isPending, ownerUserId, consent)
}

private fun activatedConsentMutation(
    ownerUserId: String,
    consent: ObservabilityConsent,
): ObservabilityPreferencesMutation {
    val maintenance = ObservabilityMaintenanceState(
        analyticsPurgePending = false,
        diagnosticsReportPurgeRequestId = null,
        installationDeletionRequestId = null,
    )
    return if (consent.allowsAnyCollection) {
        activeConsentMutation(ownerUserId, consent, maintenance)
    } else {
        revokedConsentMutation(maintenance)
    }
}

private data class ObservabilityConsentWritePlan(
    val ownerUserId: String,
    val consent: ObservabilityConsent,
    val maintenance: ObservabilityMaintenanceState,
    val rollbackMutation: ObservabilityPreferencesMutation,
) {
    fun finalMutation(): ObservabilityPreferencesMutation = if (consent.allowsAnyCollection) {
        activeConsentMutation(ownerUserId, consent, maintenance)
    } else {
        revokedConsentMutation(maintenance)
    }
}

private fun stagedConsentMutation(plan: ObservabilityConsentWritePlan): ObservabilityPreferencesMutation =
    forceDisabledMutation(plan.maintenance)
        .withSessionCheckpointPurgePending()
        .copy(
            strings = forceDisabledMutation(plan.maintenance).strings +
                (STAGED_OWNER_USER_ID_KEY to plan.ownerUserId),
            booleans = forceDisabledMutation(plan.maintenance).booleans + mapOf(
                STAGED_ACTIVATION_PENDING_KEY to true,
                STAGED_ANALYTICS_ALLOWED_KEY to plan.consent.analyticsAllowed,
                STAGED_DIAGNOSTICS_ALLOWED_KEY to plan.consent.diagnosticsAllowed,
                STAGED_REMOTE_CONFIGURATION_ALLOWED_KEY to plan.consent.remoteConfigurationAllowed,
                SESSION_CHECKPOINT_PURGE_PENDING_KEY to true,
            ),
            removals = forceDisabledMutation(plan.maintenance).removals - STAGED_OWNER_USER_ID_KEY,
        )

private data class ObservabilityConsentTransition(
    private val current: StoredObservabilityConsent,
    private val nextOwnerUserId: String,
    private val nextConsent: ObservabilityConsent,
) {
    private val ownerChanged: Boolean
        get() = current.persistedOwnerUserId?.let { it != nextOwnerUserId } == true

    private val allCollectionRevoked: Boolean
        get() = current.persistedConsent.allowsAnyCollection && !nextConsent.allowsAnyCollection

    fun diagnosticsRequestId(requestIdProvider: () -> String): ResolvedRequestId = requestId(
        required = ownerChanged ||
            current.persistedConsent.diagnosticsAllowed != nextConsent.diagnosticsAllowed ||
            allCollectionRevoked,
        current = current.diagnosticsReportPurgeRequestId,
        requestIdProvider = requestIdProvider,
    )

    fun installationRequestId(requestIdProvider: () -> String): ResolvedRequestId = requestId(
        required = ownerChanged ||
            current.persistedConsent.remoteConfigurationAllowed && !nextConsent.remoteConfigurationAllowed ||
            allCollectionRevoked,
        current = current.installationDeletionRequestId,
        requestIdProvider = requestIdProvider,
    )

    fun maintenance(diagnosticsRequestId: String?, installationRequestId: String?): ObservabilityMaintenanceState =
        ObservabilityMaintenanceState(
            analyticsPurgePending = current.analyticsPurgePending ||
                ownerChanged ||
                current.persistedConsent.analyticsAllowed && !nextConsent.analyticsAllowed ||
                allCollectionRevoked,
            diagnosticsReportPurgeRequestId = diagnosticsRequestId,
            installationDeletionRequestId = installationRequestId,
        )

    private fun requestId(required: Boolean, current: String?, requestIdProvider: () -> String): ResolvedRequestId =
        if (required) {
            requestIdProvider().normalizedOrNull()?.let(ResolvedRequestId::valid) ?: ResolvedRequestId.invalid()
        } else {
            ResolvedRequestId.valid(current)
        }
}

private data class ResolvedRequestId(
    val value: String?,
    val isValid: Boolean,
) {
    companion object {
        fun valid(value: String?): ResolvedRequestId = ResolvedRequestId(value, isValid = true)

        fun invalid(): ResolvedRequestId = ResolvedRequestId(value = null, isValid = false)
    }
}

private data class ObservabilityMaintenanceState(
    val analyticsPurgePending: Boolean,
    val diagnosticsReportPurgeRequestId: String?,
    val installationDeletionRequestId: String?,
)

private fun forceDisabledMutation(maintenance: ObservabilityMaintenanceState): ObservabilityPreferencesMutation {
    val maintenanceMutation = maintenanceMutation(maintenance)
    return maintenanceMutation.copy(
        booleans = maintenanceMutation.booleans + (FORCE_DISABLED_KEY to true),
    )
}

private fun revokedConsentMutation(maintenance: ObservabilityMaintenanceState): ObservabilityPreferencesMutation {
    val forceDisabledMutation = forceDisabledMutation(maintenance)
    return forceDisabledMutation.copy(
        removals = forceDisabledMutation.removals + setOf(
            OWNER_USER_ID_KEY,
            ANALYTICS_ALLOWED_KEY,
            DIAGNOSTICS_ALLOWED_KEY,
            REMOTE_CONFIGURATION_ALLOWED_KEY,
        ),
    )
}

private fun activeConsentMutation(
    ownerUserId: String,
    consent: ObservabilityConsent,
    maintenance: ObservabilityMaintenanceState,
): ObservabilityPreferencesMutation {
    val maintenanceMutation = maintenanceMutation(maintenance)
    return maintenanceMutation.copy(
        strings = maintenanceMutation.strings + (OWNER_USER_ID_KEY to ownerUserId),
        booleans = maintenanceMutation.booleans + mapOf(
            ANALYTICS_ALLOWED_KEY to consent.analyticsAllowed,
            DIAGNOSTICS_ALLOWED_KEY to consent.diagnosticsAllowed,
            REMOTE_CONFIGURATION_ALLOWED_KEY to consent.remoteConfigurationAllowed,
            FORCE_DISABLED_KEY to false,
        ),
    )
}

private fun historicalConsentMutation(
    current: StoredObservabilityConsent,
    maintenance: ObservabilityMaintenanceState,
): ObservabilityPreferencesMutation {
    val failClosedMutation = forceDisabledMutation(maintenance)
    val ownerUserId = current.persistedOwnerUserId
    if (ownerUserId == null) {
        return failClosedMutation.copy(
            removals = failClosedMutation.removals + CONSENT_HISTORY_KEYS,
        )
    }
    return failClosedMutation.copy(
        strings = failClosedMutation.strings + (OWNER_USER_ID_KEY to ownerUserId),
        booleans = failClosedMutation.booleans + mapOf(
            ANALYTICS_ALLOWED_KEY to current.persistedConsent.analyticsAllowed,
            DIAGNOSTICS_ALLOWED_KEY to current.persistedConsent.diagnosticsAllowed,
            REMOTE_CONFIGURATION_ALLOWED_KEY to current.persistedConsent.remoteConfigurationAllowed,
        ),
    )
}

private fun maintenanceMutation(maintenance: ObservabilityMaintenanceState): ObservabilityPreferencesMutation {
    val strings = buildMap {
        maintenance.diagnosticsReportPurgeRequestId?.let { requestId ->
            put(DIAGNOSTICS_PURGE_REQUEST_ID_KEY, requestId)
        }
        maintenance.installationDeletionRequestId?.let { requestId ->
            put(INSTALLATION_DELETION_REQUEST_ID_KEY, requestId)
        }
    }
    val booleans = buildMap {
        if (maintenance.analyticsPurgePending) put(ANALYTICS_PURGE_PENDING_KEY, true)
    }
    val removals = buildSet {
        if (!maintenance.analyticsPurgePending) add(ANALYTICS_PURGE_PENDING_KEY)
        if (maintenance.diagnosticsReportPurgeRequestId == null) add(DIAGNOSTICS_PURGE_REQUEST_ID_KEY)
        if (maintenance.installationDeletionRequestId == null) add(INSTALLATION_DELETION_REQUEST_ID_KEY)
    }
    return ObservabilityPreferencesMutation(
        strings = strings,
        booleans = booleans,
        removals = removals,
    )
}

private val ObservabilityConsent.allowsAnyCollection: Boolean
    get() = analyticsAllowed || diagnosticsAllowed || remoteConfigurationAllowed

private fun String.normalizedOrNull(): String? = trim().takeIf(String::isNotEmpty)

internal interface ObservabilityPreferences {
    fun getString(key: String, defaultValue: String?): String?

    fun getBoolean(key: String, defaultValue: Boolean): Boolean

    fun commit(mutation: ObservabilityPreferencesMutation): Boolean
}

internal data class ObservabilityPreferencesMutation(
    val strings: Map<String, String> = emptyMap(),
    val booleans: Map<String, Boolean> = emptyMap(),
    val removals: Set<String> = emptySet(),
)

private class AndroidObservabilityPreferences(
    private val preferences: SharedPreferences,
) : ObservabilityPreferences {
    override fun getString(key: String, defaultValue: String?): String? = preferences.getString(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = preferences.getBoolean(key, defaultValue)

    override fun commit(mutation: ObservabilityPreferencesMutation): Boolean =
        preferences.edit().applyMutation(mutation).commit()

    private fun SharedPreferences.Editor.applyMutation(
        mutation: ObservabilityPreferencesMutation,
    ): SharedPreferences.Editor {
        mutation.removals.forEach(::remove)
        mutation.strings.forEach(::putString)
        mutation.booleans.forEach(::putBoolean)
        return this
    }
}

private fun ObservabilityPreferences.commitDurably(mutation: ObservabilityPreferencesMutation): Boolean {
    repeat(DURABLE_COMMIT_ATTEMPTS) {
        if (commit(mutation)) return true
    }
    return false
}

private fun ObservabilityPreferencesMutation.withSessionCheckpointPurgePending(): ObservabilityPreferencesMutation =
    copy(
        booleans = booleans + (SESSION_CHECKPOINT_PURGE_PENDING_KEY to true),
        removals = removals - SESSION_CHECKPOINT_PURGE_PENDING_KEY,
    )

private fun ObservabilityPreferencesMutation.withoutStagedActivation(): ObservabilityPreferencesMutation = copy(
    removals = removals + STAGED_ACTIVATION_KEYS,
)

private fun requestIdMutation(key: String, requestId: String): ObservabilityPreferencesMutation =
    ObservabilityPreferencesMutation(strings = mapOf(key to requestId))

private const val PREFERENCES_NAME = "kwabor_observability_consent"
private const val DURABLE_COMMIT_ATTEMPTS = 3
private const val OWNER_USER_ID_KEY = "owner_user_id"
private const val FORCE_DISABLED_KEY = "force_disabled"
private const val ANALYTICS_ALLOWED_KEY = "analytics_allowed"
private const val DIAGNOSTICS_ALLOWED_KEY = "diagnostics_allowed"
private const val REMOTE_CONFIGURATION_ALLOWED_KEY = "remote_configuration_allowed"
private const val ANALYTICS_PURGE_PENDING_KEY = "analytics_purge_pending"
private const val DIAGNOSTICS_PURGE_REQUEST_ID_KEY = "diagnostics_report_purge_request_id"
private const val INSTALLATION_DELETION_REQUEST_ID_KEY = "installation_deletion_request_id"
private const val SESSION_CHECKPOINT_PURGE_PENDING_KEY = "session_checkpoint_purge_pending"
private const val STAGED_ACTIVATION_PENDING_KEY = "staged_activation_pending"
private const val STAGED_OWNER_USER_ID_KEY = "staged_owner_user_id"
private const val STAGED_ANALYTICS_ALLOWED_KEY = "staged_analytics_allowed"
private const val STAGED_DIAGNOSTICS_ALLOWED_KEY = "staged_diagnostics_allowed"
private const val STAGED_REMOTE_CONFIGURATION_ALLOWED_KEY = "staged_remote_configuration_allowed"
private val CONSENT_HISTORY_KEYS = setOf(
    OWNER_USER_ID_KEY,
    ANALYTICS_ALLOWED_KEY,
    DIAGNOSTICS_ALLOWED_KEY,
    REMOTE_CONFIGURATION_ALLOWED_KEY,
)
private val STAGED_ACTIVATION_KEYS = setOf(
    STAGED_ACTIVATION_PENDING_KEY,
    STAGED_OWNER_USER_ID_KEY,
    STAGED_ANALYTICS_ALLOWED_KEY,
    STAGED_DIAGNOSTICS_ALLOWED_KEY,
    STAGED_REMOTE_CONFIGURATION_ALLOWED_KEY,
)
