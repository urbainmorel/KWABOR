package com.kwabor.shared.app

import com.kwabor.shared.bridge.KwaborSharedBridge
import com.kwabor.shared.data.auth.createIosSecureAuthSessionManager
import com.kwabor.shared.data.local.createIosKwaborDatabaseBuilder
import com.kwabor.shared.data.preferences.createIosAppPreferencesStorage
import com.kwabor.shared.presentation.session.ViewerSessionScope
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker

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
    private val viewerSessionScopeTracker = sharedRoot?.viewerSessionScopeTracker ?: ViewerSessionScopeTracker()

    val bridge = KwaborSharedBridge(
        hasCatalogConfiguration = sharedRoot != null,
        rootNavigationProfile = rootNavigationProfileForEnvironmentName(environmentName),
    )
    val exploreController = IosExploreController(
        presenter = sharedRoot?.explorePresenter,
        dispatcherProvider = dispatcherProvider,
        viewerSessionScopeTracker = viewerSessionScopeTracker,
        interactionCoordinator = sharedRoot?.interactionCoordinator,
    )
    val favoritesController = IosFavoritesController(
        presenter = sharedRoot?.favoritesPresenter,
        dispatcherProvider = dispatcherProvider,
        viewerSessionScopeTracker = viewerSessionScopeTracker,
        interactionCoordinator = sharedRoot?.interactionCoordinator,
    )
    val searchController = IosSearchController(
        presenter = sharedRoot?.searchPresenter,
        dispatcherProvider = dispatcherProvider,
    )
    val guideDiscoveryController = IosGuideDiscoveryController(
        presenter = sharedRoot?.guideDiscoveryPresenter,
        dispatcherProvider = dispatcherProvider,
    )
    val catalogDetailController = IosCatalogDetailController(
        presenter = sharedRoot?.catalogDetailPresenter,
        dispatcherProvider = dispatcherProvider,
    )
    val authController = IosAuthController(
        presenter = sharedRoot?.authPresenter,
        dispatcherProvider = dispatcherProvider,
        interactionCoordinator = sharedRoot?.interactionCoordinator,
    )
    val registrationController = IosRegistrationController(
        presenter = sharedRoot?.registrationPresenter,
        dispatcherProvider = dispatcherProvider,
    )
    val passwordRecoveryController = IosPasswordRecoveryController(
        presenter = sharedRoot?.passwordRecoveryPresenter,
        dispatcherProvider = dispatcherProvider,
    )

    fun updateViewerSessionScope(accountId: String?, accountSetupComplete: Boolean): ViewerSessionScope {
        val scope = viewerSessionScopeTracker.update(accountId, accountSetupComplete)
        exploreController.interactionActions.updateViewerContext(scope)
        favoritesController.actions.updateViewerContext(scope)
        return scope
    }

    fun applicationBecameActive() {
        if (authController.hasPendingAccountDeletionCleanup) {
            authController.restoreSession { }
        }
        sharedRoot?.interactionCoordinator?.onForeground()
    }

    fun close() {
        exploreController.close()
        favoritesController.close()
        searchController.close()
        guideDiscoveryController.close()
        catalogDetailController.close()
        authController.close()
        registrationController.close()
        passwordRecoveryController.close()
        sharedRoot?.close()
    }
}
