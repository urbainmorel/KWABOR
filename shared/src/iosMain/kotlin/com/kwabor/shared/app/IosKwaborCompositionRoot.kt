package com.kwabor.shared.app

import com.kwabor.shared.bridge.KwaborSharedBridge
import com.kwabor.shared.data.auth.createIosSecureAuthSessionManager
import com.kwabor.shared.data.local.createIosKwaborDatabaseBuilder
import com.kwabor.shared.data.preferences.createIosAppPreferencesStorage

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
        persistenceConfigurationProvider = {
            KwaborPersistenceConfiguration(
                databaseBuilderFactory = ::createIosKwaborDatabaseBuilder,
                preferencesStorageFactory = ::createIosAppPreferencesStorage,
            )
        },
    )
    private val dispatcherProvider = sharedRoot?.dispatcherProvider ?: DefaultDispatcherProvider()

    val bridge = KwaborSharedBridge(hasCatalogConfiguration = sharedRoot != null)
    val authController = IosAuthController(
        presenter = sharedRoot?.authPresenter,
        dispatcherProvider = dispatcherProvider,
    )
    val registrationController = IosRegistrationController(
        presenter = sharedRoot?.registrationPresenter,
        dispatcherProvider = dispatcherProvider,
    )
    val passwordRecoveryController = IosPasswordRecoveryController(
        presenter = sharedRoot?.passwordRecoveryPresenter,
        dispatcherProvider = dispatcherProvider,
    )

    fun close() {
        authController.close()
        registrationController.close()
        passwordRecoveryController.close()
        sharedRoot?.close()
    }
}
