package com.kwabor.android.observability

import com.kwabor.shared.domain.observability.ObservabilityConsent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedPreferencesObservabilityConsentStoreTest {
    @Test
    fun freshInstallHasNoConsentAndNoPendingMaintenance() {
        val stored = SharedPreferencesObservabilityConsentStore(FakeObservabilityPreferences()).read()

        assertEquals(StoredObservabilityConsent(null, ObservabilityConsent()), stored)
    }

    @Test
    fun missingForceDisabledMarkerFailsClosedButPreservesHistoricalConsent() {
        val preferences = FakeObservabilityPreferences()
        preferences.seedConsent(
            ownerUserId = USER_ID,
            consent = ALL_GRANTED,
            forceDisabled = null,
        )

        val stored = SharedPreferencesObservabilityConsentStore(preferences).read()

        assertNull(stored.ownerUserId)
        assertEquals(ObservabilityConsent(), stored.consent)
        assertEquals(USER_ID, stored.persistedOwnerUserId)
        assertEquals(ALL_GRANTED, stored.persistedConsent)
    }

    @Test
    fun retryingRevocationFromForceDisabledHistoryCreatesEveryMaintenanceRequest() {
        val preferences = FakeObservabilityPreferences()
        preferences.seedConsent(
            ownerUserId = USER_ID,
            consent = ALL_GRANTED,
            forceDisabled = true,
        )
        val store = SharedPreferencesObservabilityConsentStore(
            preferences = preferences,
            requestIdProvider = requestIds(FID_RETRY_REQUEST_ID, DIAGNOSTICS_RETRY_REQUEST_ID),
        )

        assertTrue(store.revoke())

        val stored = store.read()
        assertNull(stored.ownerUserId)
        assertEquals(ObservabilityConsent(), stored.consent)
        assertNull(stored.persistedOwnerUserId)
        assertEquals(ObservabilityConsent(), stored.persistedConsent)
        assertTrue(stored.analyticsPurgePending)
        assertEquals(DIAGNOSTICS_RETRY_REQUEST_ID, stored.diagnosticsReportPurgeRequestId)
        assertEquals(FID_RETRY_REQUEST_ID, stored.installationDeletionRequestId)
    }

    @Test
    fun newerDiagnosticsPurgeRequestRejectsAStaleCompletion() {
        val preferences = FakeObservabilityPreferences()
        val store = SharedPreferencesObservabilityConsentStore(
            preferences = preferences,
            requestIdProvider = requestIds(
                DIAGNOSTICS_OLD_REQUEST_ID,
                DIAGNOSTICS_NEW_REQUEST_ID,
                FID_RETRY_REQUEST_ID,
            ),
        )
        assertTrue(
            store.write(
                USER_ID,
                ObservabilityConsent(diagnosticsAllowed = true),
            ),
        )

        assertTrue(store.write(USER_ID, ObservabilityConsent()))

        assertEquals(
            InstallationDeletionCompletion.Superseded,
            store.completeDiagnosticsReportPurge(DIAGNOSTICS_OLD_REQUEST_ID),
        )
        assertEquals(DIAGNOSTICS_NEW_REQUEST_ID, store.read().diagnosticsReportPurgeRequestId)
        assertEquals(
            InstallationDeletionCompletion.Completed,
            store.completeDiagnosticsReportPurge(DIAGNOSTICS_NEW_REQUEST_ID),
        )
        assertNull(store.read().diagnosticsReportPurgeRequestId)
    }

    @Test
    fun newerInstallationDeletionRequestRejectsAStaleCompletion() {
        val preferences = FakeObservabilityPreferences()
        val store = SharedPreferencesObservabilityConsentStore(
            preferences = preferences,
            requestIdProvider = requestIds(
                FID_OLD_REQUEST_ID,
                DIAGNOSTICS_RETRY_REQUEST_ID,
                FID_NEW_REQUEST_ID,
            ),
        )
        assertTrue(
            store.write(
                USER_ID,
                ObservabilityConsent(remoteConfigurationAllowed = true),
            ),
        )
        assertTrue(
            store.write(
                USER_ID,
                ObservabilityConsent(analyticsAllowed = true),
            ),
        )

        assertTrue(
            store.write(
                OTHER_USER_ID,
                ObservabilityConsent(analyticsAllowed = true),
            ),
        )

        assertEquals(
            InstallationDeletionCompletion.Superseded,
            store.completeInstallationDeletion(FID_OLD_REQUEST_ID),
        )
        assertEquals(FID_NEW_REQUEST_ID, store.read().installationDeletionRequestId)
        assertEquals(
            InstallationDeletionCompletion.Completed,
            store.completeInstallationDeletion(FID_NEW_REQUEST_ID),
        )
        assertNull(store.read().installationDeletionRequestId)
    }

    @Test
    fun failedRequestCompletionKeepsTheDurableMarkerForRetry() {
        val preferences = FakeObservabilityPreferences()
        preferences.seedDurably(
            ObservabilityPreferencesMutation(
                strings = mapOf("installation_deletion_request_id" to FID_RETRY_REQUEST_ID),
            ),
        )
        preferences.commitResults += listOf(false, false, false)
        val store = SharedPreferencesObservabilityConsentStore(preferences)

        assertEquals(
            InstallationDeletionCompletion.Failure,
            store.completeInstallationDeletion(FID_RETRY_REQUEST_ID),
        )
        assertEquals(FID_RETRY_REQUEST_ID, store.read().installationDeletionRequestId)
    }

    @Test
    fun failedFinalCommitKeepsTheDurableFailClosedStateAfterRestart() {
        val preferences = FakeObservabilityPreferences()
        val initialStore = SharedPreferencesObservabilityConsentStore(
            preferences = preferences,
            requestIdProvider = requestIds(DIAGNOSTICS_OLD_REQUEST_ID, FID_NEW_REQUEST_ID),
        )
        assertTrue(initialStore.write(USER_ID, ALL_GRANTED))
        preferences.commitResults += listOf(true, false, false, false, true)
        val diagnosticsOnly = ObservabilityConsent(diagnosticsAllowed = true)

        assertFalse(initialStore.write(USER_ID, diagnosticsOnly))

        val processState = initialStore.read()
        assertNull(processState.ownerUserId)
        assertEquals(ObservabilityConsent(), processState.consent)
        assertEquals(USER_ID, processState.persistedOwnerUserId)
        assertEquals(ALL_GRANTED, processState.persistedConsent)

        val restartedStore = SharedPreferencesObservabilityConsentStore(preferences.restart())
        val stored = restartedStore.read()
        assertNull(stored.ownerUserId)
        assertEquals(ObservabilityConsent(), stored.consent)
        assertEquals(USER_ID, stored.persistedOwnerUserId)
        assertEquals(ALL_GRANTED, stored.persistedConsent)
        assertTrue(stored.analyticsPurgePending)
        assertEquals(DIAGNOSTICS_OLD_REQUEST_ID, stored.diagnosticsReportPurgeRequestId)
        assertEquals(FID_NEW_REQUEST_ID, stored.installationDeletionRequestId)
    }

    @Test
    fun retryAfterFailedDiagnosticsGrantRequiresANewPurge() {
        val preferences = FakeObservabilityPreferences()
        val store = SharedPreferencesObservabilityConsentStore(
            preferences = preferences,
            requestIdProvider = requestIds(DIAGNOSTICS_NEW_REQUEST_ID, DIAGNOSTICS_RETRY_REQUEST_ID),
        )
        assertTrue(store.write(USER_ID, ANALYTICS_ONLY))
        preferences.commitResults += listOf(true, false, false, false, true)

        assertFalse(store.write(USER_ID, ANALYTICS_AND_DIAGNOSTICS))

        val rolledBack = SharedPreferencesObservabilityConsentStore(preferences.restart()).read()
        assertNull(rolledBack.ownerUserId)
        assertEquals(ObservabilityConsent(), rolledBack.consent)
        assertEquals(ANALYTICS_ONLY, rolledBack.persistedConsent)
        assertEquals(DIAGNOSTICS_NEW_REQUEST_ID, rolledBack.diagnosticsReportPurgeRequestId)
        assertEquals(
            InstallationDeletionCompletion.Completed,
            store.completeDiagnosticsReportPurge(DIAGNOSTICS_NEW_REQUEST_ID),
        )

        assertTrue(store.write(USER_ID, ANALYTICS_AND_DIAGNOSTICS))

        val retried = store.read()
        assertEquals(ANALYTICS_AND_DIAGNOSTICS, retried.consent)
        assertTrue(retried.hasPendingMaintenance)
        assertEquals(DIAGNOSTICS_RETRY_REQUEST_ID, retried.diagnosticsReportPurgeRequestId)
        assertEquals(
            InstallationDeletionCompletion.Completed,
            store.completeDiagnosticsReportPurge(DIAGNOSTICS_RETRY_REQUEST_ID),
        )
        assertFalse(store.read().hasPendingMaintenance)
    }

    @Test
    fun firstFailedCommitIsRetriedAndRevocationSurvivesRestart() {
        val preferences = FakeObservabilityPreferences()
        val store = SharedPreferencesObservabilityConsentStore(
            preferences = preferences,
            requestIdProvider = requestIds(
                DIAGNOSTICS_OLD_REQUEST_ID,
                FID_RETRY_REQUEST_ID,
                DIAGNOSTICS_RETRY_REQUEST_ID,
            ),
        )
        assertTrue(store.write(USER_ID, ALL_GRANTED))
        val attemptsBeforeRevocation = preferences.commitAttempts
        preferences.commitResults += listOf(false, true, true)

        assertTrue(store.revoke())

        assertEquals(3, preferences.commitAttempts - attemptsBeforeRevocation)
        val stored = SharedPreferencesObservabilityConsentStore(preferences.restart()).read()
        assertNull(stored.ownerUserId)
        assertEquals(ObservabilityConsent(), stored.consent)
        assertNull(stored.persistedOwnerUserId)
        assertEquals(ObservabilityConsent(), stored.persistedConsent)
        assertTrue(stored.analyticsPurgePending)
        assertEquals(DIAGNOSTICS_RETRY_REQUEST_ID, stored.diagnosticsReportPurgeRequestId)
        assertEquals(FID_RETRY_REQUEST_ID, stored.installationDeletionRequestId)
    }

    @Test
    fun exhaustedFailClosedRetriesReturnFailureWithoutClaimingDurability() {
        val preferences = FakeObservabilityPreferences()
        val store = SharedPreferencesObservabilityConsentStore(
            preferences = preferences,
            requestIdProvider = requestIds(
                DIAGNOSTICS_OLD_REQUEST_ID,
                FID_RETRY_REQUEST_ID,
                DIAGNOSTICS_RETRY_REQUEST_ID,
            ),
        )
        assertTrue(store.write(USER_ID, ALL_GRANTED))
        val attemptsBeforeRevocation = preferences.commitAttempts
        preferences.commitResults += listOf(false, false, false)

        assertFalse(store.revoke())

        assertEquals(3, preferences.commitAttempts - attemptsBeforeRevocation)
        val processState = store.read()
        assertNull(processState.ownerUserId)
        assertEquals(ObservabilityConsent(), processState.consent)
        assertTrue(processState.analyticsPurgePending)
        assertEquals(DIAGNOSTICS_RETRY_REQUEST_ID, processState.diagnosticsReportPurgeRequestId)
        assertEquals(FID_RETRY_REQUEST_ID, processState.installationDeletionRequestId)

        val restartedState = SharedPreferencesObservabilityConsentStore(preferences.restart()).read()
        assertEquals(USER_ID, restartedState.ownerUserId)
        assertEquals(ALL_GRANTED, restartedState.consent)
        assertFalse(restartedState.analyticsPurgePending)
        assertEquals(DIAGNOSTICS_OLD_REQUEST_ID, restartedState.diagnosticsReportPurgeRequestId)
        assertNull(restartedState.installationDeletionRequestId)
    }

    @Test
    fun failedRevokedConsentCommitReportsFailureAndKeepsDurableFailClosedState() {
        val preferences = FakeObservabilityPreferences()
        val store = SharedPreferencesObservabilityConsentStore(
            preferences = preferences,
            requestIdProvider = requestIds(
                DIAGNOSTICS_OLD_REQUEST_ID,
                FID_RETRY_REQUEST_ID,
                DIAGNOSTICS_RETRY_REQUEST_ID,
            ),
        )
        assertTrue(store.write(USER_ID, ALL_GRANTED))
        preferences.commitResults += listOf(true, false, false, false)

        assertFalse(store.revoke())

        val stored = store.read()
        assertNull(stored.ownerUserId)
        assertEquals(ObservabilityConsent(), stored.consent)
        assertNull(stored.persistedOwnerUserId)
        assertEquals(ObservabilityConsent(), stored.persistedConsent)
        assertTrue(stored.analyticsPurgePending)
        assertEquals(DIAGNOSTICS_RETRY_REQUEST_ID, stored.diagnosticsReportPurgeRequestId)
        assertEquals(FID_RETRY_REQUEST_ID, stored.installationDeletionRequestId)

        val restartedState = SharedPreferencesObservabilityConsentStore(preferences.restart()).read()
        assertNull(restartedState.ownerUserId)
        assertEquals(ObservabilityConsent(), restartedState.consent)
        assertEquals(USER_ID, restartedState.persistedOwnerUserId)
        assertEquals(ALL_GRANTED, restartedState.persistedConsent)
    }
}

private class FakeObservabilityPreferences private constructor(
    private val durableValues: MutableMap<String, Any>,
) : ObservabilityPreferences {
    constructor() : this(mutableMapOf())

    private val processValues = durableValues.toMutableMap()
    val commitResults = ArrayDeque<Boolean>()
    var commitAttempts: Int = 0
        private set

    override fun getString(key: String, defaultValue: String?): String? = processValues[key] as? String ?: defaultValue

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        processValues[key] as? Boolean ?: defaultValue

    override fun commit(mutation: ObservabilityPreferencesMutation): Boolean {
        commitAttempts += 1
        processValues.applyMutation(mutation)
        val succeeds = commitResults.removeFirstOrNull() ?: true
        if (succeeds) {
            durableValues.clear()
            durableValues.putAll(processValues)
        }
        return succeeds
    }

    fun restart(): FakeObservabilityPreferences = FakeObservabilityPreferences(durableValues.toMutableMap())

    fun seedDurably(mutation: ObservabilityPreferencesMutation) {
        processValues.applyMutation(mutation)
        durableValues.applyMutation(mutation)
    }

    fun seedConsent(ownerUserId: String, consent: ObservabilityConsent, forceDisabled: Boolean?) {
        seedDurably(
            ObservabilityPreferencesMutation(
                strings = mapOf("owner_user_id" to ownerUserId),
                booleans = buildMap {
                    put("analytics_allowed", consent.analyticsAllowed)
                    put("diagnostics_allowed", consent.diagnosticsAllowed)
                    put("remote_configuration_allowed", consent.remoteConfigurationAllowed)
                    forceDisabled?.let { put("force_disabled", it) }
                },
            ),
        )
    }
}

private fun MutableMap<String, Any>.applyMutation(mutation: ObservabilityPreferencesMutation) {
    mutation.removals.forEach(::remove)
    putAll(mutation.strings)
    putAll(mutation.booleans)
}

private fun requestIds(vararg requestIds: String): () -> String {
    val pendingRequestIds = ArrayDeque(requestIds.toList())
    return {
        pendingRequestIds.removeFirstOrNull() ?: error("No deterministic request ID remains.")
    }
}

private const val USER_ID = "user-one"
private const val OTHER_USER_ID = "user-two"
private const val DIAGNOSTICS_OLD_REQUEST_ID = "diagnostics-old"
private const val DIAGNOSTICS_NEW_REQUEST_ID = "diagnostics-new"
private const val DIAGNOSTICS_RETRY_REQUEST_ID = "diagnostics-retry"
private const val FID_OLD_REQUEST_ID = "fid-old"
private const val FID_NEW_REQUEST_ID = "fid-new"
private const val FID_RETRY_REQUEST_ID = "fid-retry"
private val ALL_GRANTED = ObservabilityConsent(
    analyticsAllowed = true,
    diagnosticsAllowed = true,
    remoteConfigurationAllowed = true,
)
private val ANALYTICS_ONLY = ObservabilityConsent(analyticsAllowed = true)
private val ANALYTICS_AND_DIAGNOSTICS = ObservabilityConsent(
    analyticsAllowed = true,
    diagnosticsAllowed = true,
)
