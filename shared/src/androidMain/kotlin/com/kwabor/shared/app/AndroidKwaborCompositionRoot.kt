package com.kwabor.shared.app

import android.content.Context
import com.kwabor.shared.data.auth.createAndroidSecureAuthSessionManager

fun createAndroidKwaborCompositionRootOrNull(
    context: Context,
    supabaseUrl: String?,
    supabasePublishableKey: String?,
): KwaborCompositionRoot? = createKwaborCompositionRootOrNull(
    supabaseUrl = supabaseUrl,
    supabasePublishableKey = supabasePublishableKey,
    authSessionManager = createAndroidSecureAuthSessionManager(context.applicationContext),
)
