package com.kwabor.shared.data.observability

import android.content.Context
import android.content.SharedPreferences
import com.kwabor.shared.domain.observability.ObservedAppSessionCheckpointRead
import com.kwabor.shared.domain.observability.ObservedAppSessionStore
import com.kwabor.shared.domain.observability.ObservedAppSessionTimeMark

internal fun createAndroidObservedAppSessionStore(context: Context): ObservedAppSessionStore =
    AndroidObservedAppSessionStore(
        preferences = context.applicationContext.getSharedPreferences(
            OBSERVED_APP_SESSION_PREFERENCES,
            Context.MODE_PRIVATE,
        ),
    )

private class AndroidObservedAppSessionStore(
    private val preferences: SharedPreferences,
) : ObservedAppSessionStore {
    override fun read(): ObservedAppSessionCheckpointRead = when (
        val checkpoint = preferences.all[CHECKPOINT_KEY]
    ) {
        null -> ObservedAppSessionCheckpointRead.Missing
        is String -> ObservedAppSessionCheckpointCodec.decode(checkpoint)
        else -> ObservedAppSessionCheckpointRead.Failure
    }

    override fun writeForeground(): Boolean = preferences.edit()
        .putString(CHECKPOINT_KEY, ObservedAppSessionCheckpointCodec.encodeForeground())
        .commit()

    override fun writeBackgroundedAt(timeMark: ObservedAppSessionTimeMark): Boolean = preferences.edit()
        .putString(CHECKPOINT_KEY, ObservedAppSessionCheckpointCodec.encodeBackgroundedAt(timeMark))
        .commit()

    override fun clear(): Boolean = preferences.edit()
        .remove(CHECKPOINT_KEY)
        .commit()
}

private const val OBSERVED_APP_SESSION_PREFERENCES = "kwabor_observed_app_session"
private const val CHECKPOINT_KEY = "checkpoint"
