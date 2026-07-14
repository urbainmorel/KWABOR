package com.kwabor.shared.app

import android.content.Context
import com.kwabor.shared.data.auth.createAndroidSecureAuthSessionManager

fun createAndroidKwaborCompositionRootOrNull(
    context: Context,
    environmentName: String?,
    supabaseUrl: String?,
    supabasePublishableKey: String?,
): KwaborCompositionRoot? = createKwaborCompositionRootOrNull(
    supabaseUrl = supabaseUrl,
    supabasePublishableKey = supabasePublishableKey,
    environmentName = environmentName,
    authSessionManager = createAndroidSecureAuthSessionManager(context.applicationContext),
)
