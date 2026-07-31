package com.kwabor.android.onboarding

import android.content.Context

internal interface FirstLaunchStore {
    fun isBundledIntroRequired(): Boolean

    fun markBundledIntroSeen()

    fun pendingRemoteIntro(): PendingRemoteIntro?

    fun lastPresentedRemoteRevision(): Long

    fun markRemoteIntroPending(intro: PendingRemoteIntro): Boolean

    fun markRemoteIntroPresented(revision: Long): Boolean

    fun clearPendingRemoteIntro(): Boolean
}

internal data class PendingRemoteIntro(
    val revision: Long,
    val sha256: String,
    val fileName: String,
)

internal class SharedPreferencesFirstLaunchStore(context: Context) : FirstLaunchStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val stateLock = Any()

    override fun isBundledIntroRequired(): Boolean = synchronized(stateLock) {
        !preferences.getBoolean(INTRO_SEEN_KEY, false)
    }

    override fun markBundledIntroSeen() {
        synchronized(stateLock) {
            preferences.edit().putBoolean(INTRO_SEEN_KEY, true).commit()
        }
    }

    override fun pendingRemoteIntro(): PendingRemoteIntro? = synchronized(stateLock) {
        pendingRemoteIntroLocked()
    }

    override fun lastPresentedRemoteRevision(): Long = synchronized(stateLock) {
        lastPresentedRemoteRevisionLocked()
    }

    override fun markRemoteIntroPending(intro: PendingRemoteIntro): Boolean = synchronized(stateLock) {
        val currentPendingRevision = pendingRemoteIntroLocked()?.revision ?: NO_REMOTE_REVISION
        val latestKnownRevision = maxOf(lastPresentedRemoteRevisionLocked(), currentPendingRevision)
        if (
            intro.revision <= latestKnownRevision ||
            !intro.sha256.isSha256() ||
            !intro.fileName.isSafeCacheFileName()
        ) {
            return@synchronized false
        }
        preferences.edit()
            .putLong(PENDING_REMOTE_REVISION_KEY, intro.revision)
            .putString(PENDING_REMOTE_SHA256_KEY, intro.sha256)
            .putString(PENDING_REMOTE_FILE_NAME_KEY, intro.fileName)
            .commit()
    }

    override fun markRemoteIntroPresented(revision: Long): Boolean = synchronized(stateLock) {
        if (revision <= NO_REMOTE_REVISION) return@synchronized false
        val latestPresentedRevision = maxOf(lastPresentedRemoteRevisionLocked(), revision)
        val editor = preferences.edit().putLong(LAST_PRESENTED_REMOTE_REVISION_KEY, latestPresentedRevision)
        if (preferences.getLong(PENDING_REMOTE_REVISION_KEY, NO_REMOTE_REVISION) <= revision) {
            editor.remove(PENDING_REMOTE_REVISION_KEY)
                .remove(PENDING_REMOTE_SHA256_KEY)
                .remove(PENDING_REMOTE_FILE_NAME_KEY)
        }
        editor.commit()
    }

    override fun clearPendingRemoteIntro(): Boolean = synchronized(stateLock) {
        preferences.edit()
            .remove(PENDING_REMOTE_REVISION_KEY)
            .remove(PENDING_REMOTE_SHA256_KEY)
            .remove(PENDING_REMOTE_FILE_NAME_KEY)
            .commit()
    }

    private fun pendingRemoteIntroLocked(): PendingRemoteIntro? {
        val revision = preferences.getLong(PENDING_REMOTE_REVISION_KEY, NO_REMOTE_REVISION)
        val sha256 = preferences.getString(PENDING_REMOTE_SHA256_KEY, null)
        val fileName = preferences.getString(PENDING_REMOTE_FILE_NAME_KEY, null)
        val isNewerThanPresented = revision > lastPresentedRemoteRevisionLocked()
        if (revision <= NO_REMOTE_REVISION || !isNewerThanPresented) return null
        if (!sha256.isSha256() || !fileName.isSafeCacheFileName()) return null
        return PendingRemoteIntro(
            revision = revision,
            sha256 = requireNotNull(sha256),
            fileName = requireNotNull(fileName),
        )
    }

    private fun lastPresentedRemoteRevisionLocked(): Long =
        preferences.getLong(LAST_PRESENTED_REMOTE_REVISION_KEY, NO_REMOTE_REVISION)
}

private fun String?.isSafeCacheFileName(): Boolean = this != null &&
    isNotBlank() &&
    this == substringAfterLast('/') &&
    this == substringAfterLast('\\') &&
    endsWith(MP4_FILE_SUFFIX, ignoreCase = true)

private fun String?.isSha256(): Boolean = this != null && SHA256_PATTERN.matches(this)

private const val PREFERENCES_NAME = "kwabor_first_launch"
private const val INTRO_SEEN_KEY = "intro_seen_v1"
private const val PENDING_REMOTE_REVISION_KEY = "pending_remote_intro_revision"
private const val PENDING_REMOTE_SHA256_KEY = "pending_remote_intro_sha256"
private const val PENDING_REMOTE_FILE_NAME_KEY = "pending_remote_intro_file_name"
private const val LAST_PRESENTED_REMOTE_REVISION_KEY = "last_presented_remote_intro_revision"
private const val NO_REMOTE_REVISION = 0L
private const val MP4_FILE_SUFFIX = ".mp4"
private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
