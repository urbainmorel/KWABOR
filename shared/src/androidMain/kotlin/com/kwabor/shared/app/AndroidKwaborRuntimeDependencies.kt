package com.kwabor.shared.app

import android.content.Context
import com.kwabor.shared.data.auth.createAndroidSecureAuthSessionManager

fun createAndroidKwaborRuntimeDependenciesOrNull(
    context: Context,
    supabaseUrl: String?,
    supabasePublishableKey: String?,
): KwaborRuntimeDependencies? = KwaborRuntimeDependencies.createOrNull(
    supabaseUrl = supabaseUrl,
    supabasePublishableKey = supabasePublishableKey,
    authSessionManager = createAndroidSecureAuthSessionManager(context.applicationContext),
)
