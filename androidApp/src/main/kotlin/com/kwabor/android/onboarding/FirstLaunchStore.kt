package com.kwabor.android.onboarding

import android.content.Context

internal interface FirstLaunchStore {
    fun isBundledIntroRequired(): Boolean

    fun markBundledIntroSeen()
}
internal class SharedPreferencesFirstLaunchStore(context: Context) : FirstLaunchStore {
    private val preferences = context.getSharedPreferences(FIRST_LAUNCH_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val stateLock = Any()

    init {
        synchronized(stateLock) {
            migrateLegacyIntroSeenIfNeeded()
        }
    }

    override fun isBundledIntroRequired(): Boolean = synchronized(stateLock) {
        isBundledIntroRevisionRequired(
            presentedRevision = presentedBundledIntroRevision(),
            bundledRevision = BUNDLED_INTRO_REVISION,
        )
    }

    override fun markBundledIntroSeen() {
        synchronized(stateLock) {
            val presentedRevision = maxOf(presentedBundledIntroRevision(), BUNDLED_INTRO_REVISION)
            preferences.edit()
                .putLong(PRESENTED_BUNDLED_INTRO_REVISION_KEY, presentedRevision)
                .remove(LEGACY_INTRO_SEEN_KEY)
                .commit()
        }
    }

    private fun migrateLegacyIntroSeenIfNeeded() {
        if (preferences.contains(PRESENTED_BUNDLED_INTRO_REVISION_KEY)) return
        val migratedRevision = migratedBundledIntroRevision(
            storedRevision = null,
            legacyIntroSeen = preferences.getBoolean(LEGACY_INTRO_SEEN_KEY, false),
        )
        preferences.edit()
            .putLong(PRESENTED_BUNDLED_INTRO_REVISION_KEY, migratedRevision)
            .remove(LEGACY_INTRO_SEEN_KEY)
            .commit()
    }

    private fun presentedBundledIntroRevision(): Long = migratedBundledIntroRevision(
        storedRevision = preferences.getLongOrNull(PRESENTED_BUNDLED_INTRO_REVISION_KEY),
        legacyIntroSeen = preferences.getBoolean(LEGACY_INTRO_SEEN_KEY, false),
    )
}

internal fun migratedBundledIntroRevision(storedRevision: Long?, legacyIntroSeen: Boolean): Long =
    storedRevision?.coerceAtLeast(NO_BUNDLED_INTRO_REVISION)
        ?: LEGACY_BUNDLED_INTRO_REVISION.takeIf { legacyIntroSeen }
        ?: NO_BUNDLED_INTRO_REVISION

internal fun isBundledIntroRevisionRequired(presentedRevision: Long, bundledRevision: Long): Boolean {
    require(bundledRevision > NO_BUNDLED_INTRO_REVISION) {
        "The bundled intro revision must be positive."
    }
    return presentedRevision.coerceAtLeast(NO_BUNDLED_INTRO_REVISION) < bundledRevision
}

private fun android.content.SharedPreferences.getLongOrNull(key: String): Long? =
    if (contains(key)) getLong(key, NO_BUNDLED_INTRO_REVISION) else null

internal const val BUNDLED_INTRO_REVISION = 1L
internal const val FIRST_LAUNCH_PREFERENCES_NAME = "kwabor_first_launch"
internal const val LEGACY_INTRO_SEEN_KEY = "intro_seen_v1"
internal const val PRESENTED_BUNDLED_INTRO_REVISION_KEY = "presented_bundled_intro_revision"
private const val LEGACY_BUNDLED_INTRO_REVISION = 1L
private const val NO_BUNDLED_INTRO_REVISION = 0L
