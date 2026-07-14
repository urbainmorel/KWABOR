package com.kwabor.shared.app

import com.kwabor.shared.bridge.KwaborSharedBridge
import com.kwabor.shared.data.auth.createIosSecureAuthSessionManager

class IosKwaborCompositionRoot(
    supabaseUrl: String?,
    supabasePublishableKey: String?,
) {
    private val sharedRoot = createKwaborCompositionRootOrNull(
        supabaseUrl = supabaseUrl,
        supabasePublishableKey = supabasePublishableKey,
        authSessionManager = createIosSecureAuthSessionManager(),
    )

    val bridge = KwaborSharedBridge(hasCatalogConfiguration = sharedRoot != null)

    fun close() {
        sharedRoot?.close()
    }
}
