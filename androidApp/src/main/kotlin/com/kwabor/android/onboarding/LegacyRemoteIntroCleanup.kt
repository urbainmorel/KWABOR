package com.kwabor.android.onboarding

import android.content.Context
import java.io.File

internal interface LegacyRemoteIntroCleanupState {
    fun isComplete(): Boolean

    fun removeLegacyPreferences(): Boolean

    fun markComplete(): Boolean
}

internal class SharedPreferencesLegacyRemoteIntroCleanupState(context: Context) : LegacyRemoteIntroCleanupState {
    private val firstLaunchPreferences =
        context.getSharedPreferences(FIRST_LAUNCH_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val observabilityPreferences =
        context.getSharedPreferences(OBSERVABILITY_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val migrationPreferences =
        context.getSharedPreferences(MIGRATION_PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun isComplete(): Boolean = migrationPreferences.getBoolean(CLEANUP_COMPLETE_KEY, false)

    override fun removeLegacyPreferences(): Boolean {
        val firstLaunchCleaned = firstLaunchPreferences.edit()
            .remove(PENDING_REMOTE_REVISION_KEY)
            .remove(PENDING_REMOTE_SHA256_KEY)
            .remove(PENDING_REMOTE_FILE_NAME_KEY)
            .remove(LAST_PRESENTED_REMOTE_REVISION_KEY)
            .commit()
        val observabilityCleaned = observabilityPreferences.edit()
            .remove(REQUIRED_CONSENT_PURGE_EPOCH_KEY)
            .remove(REQUIRED_DISABLE_PURGE_EPOCH_KEY)
            .remove(ACKNOWLEDGED_CONSENT_PURGE_EPOCH_KEY)
            .remove(ACKNOWLEDGED_DISABLE_PURGE_EPOCH_KEY)
            .commit()
        return firstLaunchCleaned && observabilityCleaned
    }

    override fun markComplete(): Boolean = migrationPreferences.edit()
        .putBoolean(CLEANUP_COMPLETE_KEY, true)
        .commit()
}

internal class LegacyRemoteIntroCleanup(
    private val state: LegacyRemoteIntroCleanupState,
    private val cacheDirectory: File,
    private val cacheCleanup: (File) -> Boolean = ::deleteLegacyRemoteIntroCache,
) {
    fun run(): Boolean {
        if (state.isComplete()) return true
        val preferencesCleaned = state.removeLegacyPreferences()
        val cacheCleaned = cacheCleanup(cacheDirectory)
        return preferencesCleaned && cacheCleaned && state.markComplete()
    }
}

internal fun deleteLegacyRemoteIntroCache(directory: File): Boolean =
    !directory.exists() || directory.deleteRecursively() || !directory.exists()

internal fun createLegacyRemoteIntroCleanup(context: Context): LegacyRemoteIntroCleanup = LegacyRemoteIntroCleanup(
    state = SharedPreferencesLegacyRemoteIntroCleanupState(context),
    cacheDirectory = File(context.filesDir, LEGACY_REMOTE_INTRO_CACHE_DIRECTORY),
)

private const val OBSERVABILITY_PREFERENCES_NAME = "kwabor_observability_consent"
private const val MIGRATION_PREFERENCES_NAME = "kwabor_local_migrations"
private const val CLEANUP_COMPLETE_KEY = "remote_intro_store_release_cleanup_v1"
private const val LEGACY_REMOTE_INTRO_CACHE_DIRECTORY = "intro-media"
private const val PENDING_REMOTE_REVISION_KEY = "pending_remote_intro_revision"
private const val PENDING_REMOTE_SHA256_KEY = "pending_remote_intro_sha256"
private const val PENDING_REMOTE_FILE_NAME_KEY = "pending_remote_intro_file_name"
private const val LAST_PRESENTED_REMOTE_REVISION_KEY = "last_presented_remote_intro_revision"
private const val REQUIRED_CONSENT_PURGE_EPOCH_KEY = "required_remote_media_consent_purge_epoch"
private const val REQUIRED_DISABLE_PURGE_EPOCH_KEY = "required_remote_media_disable_purge_epoch"
private const val ACKNOWLEDGED_CONSENT_PURGE_EPOCH_KEY = "acknowledged_remote_media_consent_purge_epoch"
private const val ACKNOWLEDGED_DISABLE_PURGE_EPOCH_KEY = "acknowledged_remote_media_disable_purge_epoch"
