package com.kwabor.shared.app

import com.kwabor.shared.bridge.KwaborSharedBridge
import com.kwabor.shared.data.auth.createIosSecureAuthSessionManager
import com.kwabor.shared.domain.core.DefaultDispatcherProvider

class IosKwaborCompositionRoot(
    environmentName: String?,
    supabaseUrl: String?,
    supabasePublishableKey: String?,
) {
    private val sharedRoot = createKwaborCompositionRootOrNull(
        supabaseUrl = supabaseUrl,
        supabasePublishableKey = supabasePublishableKey,
        environmentName = environmentName,
        authSessionManager = createIosSecureAuthSessionManager(),
    )
    private val dispatcherProvider = sharedRoot?.dispatcherProvider ?: DefaultDispatcherProvider()

    val bridge = KwaborSharedBridge(hasCatalogConfiguration = sharedRoot != null)
    val authController = IosAuthController(
        presenter = sharedRoot?.authPresenter,
        dispatcherProvider = dispatcherProvider,
    )

    fun close() {
        authController.close()
        sharedRoot?.close()
    }
}
