package com.kwabor.android.auth

import android.content.Context

internal enum class InterruptedAuthJourney {
    None,
    Registration,
    SocialRegistration,
}

internal interface AuthJourneyStore {
    fun read(): InterruptedAuthJourney

    fun write(journey: InterruptedAuthJourney): Boolean

    fun clear(): Boolean = write(InterruptedAuthJourney.None)
}

internal class SharedPreferencesAuthJourneyStore(context: Context) : AuthJourneyStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): InterruptedAuthJourney = when (preferences.getString(KEY_ACTIVE_JOURNEY, null)) {
        VALUE_REGISTRATION -> InterruptedAuthJourney.Registration
        VALUE_SOCIAL_REGISTRATION -> InterruptedAuthJourney.SocialRegistration
        else -> InterruptedAuthJourney.None
    }

    override fun write(journey: InterruptedAuthJourney): Boolean {
        val editor = preferences.edit()
        when (journey) {
            InterruptedAuthJourney.None -> editor.remove(KEY_ACTIVE_JOURNEY)
            InterruptedAuthJourney.Registration -> editor.putString(KEY_ACTIVE_JOURNEY, VALUE_REGISTRATION)
            InterruptedAuthJourney.SocialRegistration -> editor.putString(
                KEY_ACTIVE_JOURNEY,
                VALUE_SOCIAL_REGISTRATION,
            )
        }
        return editor.commit()
    }
}

private const val PREFERENCES_NAME = "kwabor_auth_journey"
private const val KEY_ACTIVE_JOURNEY = "active_journey"
private const val VALUE_REGISTRATION = "registration"
private const val VALUE_SOCIAL_REGISTRATION = "social_registration"
