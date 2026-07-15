package com.kwabor.android.onboarding

import android.content.Context
import androidx.core.content.edit

internal interface FirstLaunchStore {
    fun isIntroRequired(): Boolean

    fun markIntroSeen()
}

internal class SharedPreferencesFirstLaunchStore(context: Context) : FirstLaunchStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun isIntroRequired(): Boolean = !preferences.getBoolean(INTRO_SEEN_KEY, false)

    override fun markIntroSeen() {
        preferences.edit { putBoolean(INTRO_SEEN_KEY, true) }
    }
}

private const val PREFERENCES_NAME = "kwabor_first_launch"
private const val INTRO_SEEN_KEY = "intro_seen_v1"
