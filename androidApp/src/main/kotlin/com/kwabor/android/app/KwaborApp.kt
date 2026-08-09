package com.kwabor.android.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kwabor.android.auth.LegalDocumentLauncher
import com.kwabor.android.design.KwaborTheme
import com.kwabor.android.detail.DetailExternalActionLauncher
import com.kwabor.android.media.ListingMediaUrlPolicy
import com.kwabor.android.observability.AndroidObservabilityController
import com.kwabor.android.presentation.auth.AuthAccessUiState
import com.kwabor.android.presentation.auth.AuthEffect
import com.kwabor.android.presentation.auth.AuthIntent
import com.kwabor.android.presentation.auth.AuthPlatformUiState
import com.kwabor.android.presentation.auth.AuthProtectedAction
import com.kwabor.android.presentation.auth.AuthSoftWallContext
import com.kwabor.android.presentation.auth.AuthSurface
import com.kwabor.android.presentation.auth.AuthViewModel
import com.kwabor.android.presentation.auth.PromoterActivationUiState
import com.kwabor.android.presentation.detail.CatalogDetailViewModel
import com.kwabor.android.presentation.explore.ExploreIntent
import com.kwabor.android.presentation.explore.ExploreViewModel
import com.kwabor.android.presentation.favorites.FavoritesViewModel
import com.kwabor.android.presentation.guide.GuideDiscoveryViewModel
import com.kwabor.android.presentation.onboarding.OnboardingEffect
import com.kwabor.android.presentation.onboarding.OnboardingIntent
import com.kwabor.android.presentation.onboarding.OnboardingUiState
import com.kwabor.android.presentation.onboarding.OnboardingViewModel
import com.kwabor.android.presentation.search.SearchViewModel
import com.kwabor.android.ui.components.KwaborStateMessage
import com.kwabor.android.ui.screens.auth.AuthSheet
import com.kwabor.android.ui.screens.auth.AuthSheetState
import com.kwabor.android.ui.screens.auth.RegistrationScreenState
import com.kwabor.android.ui.screens.detail.CatalogDetailPlatformDependencies
import com.kwabor.android.ui.screens.detail.CatalogDetailSheet
import com.kwabor.shared.domain.auth.AuthSessionPurpose
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthUiState
import com.kwabor.shared.presentation.auth.PasswordRecoveryUiState
import com.kwabor.shared.presentation.auth.RegistrationUiState
import com.kwabor.shared.presentation.detail.CatalogDetailIntent
import com.kwabor.shared.presentation.navigation.RootNavigationDestination
import com.kwabor.shared.presentation.navigation.label
import com.kwabor.shared.presentation.onboarding.OnboardingEntry
import com.kwabor.shared.presentation.onboarding.OnboardingEntryResolver
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun KwaborApp(dependencies: KwaborAppDependencies, runtimeState: KwaborAppRuntimeState) {
    val state = collectKwaborAppState(dependencies = dependencies, runtimeState = runtimeState)
    val strings = stringsFor(AppLocale.French)
    ObservabilitySessionBindingEffect(
        userId = state.auth.currentSession
            ?.takeIf { session -> session.purpose == AuthSessionPurpose.Standard }
            ?.userId,
        controller = dependencies.observabilityController,
    )
    OnboardingEffectHandler(dependencies = dependencies)
    AuthPlatformEffectHandler(dependencies = dependencies)
    SensitiveAuthDeepLinkResetHandler(
        signOutInProgress = state.authAccess.signOutInProgress,
        accountDeletionInProgress = state.authAccess.accountDeletionInProgress,
        onReset = runtimeState.onDeepLinksReset,
    )
    KwaborTheme {
        KwaborThemedContent(state, strings, dependencies, runtimeState)
    }
}

@Composable
private fun KwaborThemedContent(
    state: KwaborCollectedState,
    strings: KwaborStrings,
    dependencies: KwaborAppDependencies,
    runtimeState: KwaborAppRuntimeState,
) {
    when (state.authPlatform.surface) {
        AuthSurface.Registration -> RegistrationSurface(
            state = state.registrationScreenState,
            strings = strings,
            authViewModel = dependencies.authViewModel,
        )
        AuthSurface.SignIn -> SignInSurface(
            state = state.authAccess,
            federatedSignInInProgress = state.authPlatform.federatedSignInInProgress,
            strings = strings,
            authViewModel = dependencies.authViewModel,
        )
        AuthSurface.PasswordRecovery -> PasswordRecoverySurface(
            state = state.passwordRecovery,
            resendSecondsRemaining = state.authAccess.recoveryResendSecondsRemaining,
            strings = strings,
            authViewModel = dependencies.authViewModel,
        )
        AuthSurface.PromoterActivation -> PromoterActivationSurface(
            state = state.promoterActivation,
            strings = strings,
            authViewModel = dependencies.authViewModel,
        )
        AuthSurface.SessionRestoreFailure ->
            SessionRestoreFailureSurface(strings, dependencies.authViewModel)
        AuthSurface.Hidden,
        AuthSurface.SoftWall,
        -> KwaborEntryContent(
            entry = state.onboardingEntry,
            state = state,
            strings = strings,
            dependencies = dependencies,
            runtimeState = runtimeState,
        )
    }
    SoftWallOverlay(state.authPlatform, strings, dependencies.authViewModel)
}

@Composable
private fun SoftWallOverlay(platformState: AuthPlatformUiState, strings: KwaborStrings, authViewModel: AuthViewModel) {
    if (platformState.surface != AuthSurface.SoftWall) return
    AuthSheet(
        strings = strings,
        state = AuthSheetState(
            context = platformState.softWallContext,
            errorMessage = platformState.softWallErrorMessage,
            federatedSignInInProgress = platformState.federatedSignInInProgress,
        ),
        actions = remember(authViewModel) { authViewModel.sheetActions() },
    )
}

internal data class KwaborAppDependencies(
    val exploreViewModel: ExploreViewModel,
    val favoritesViewModel: FavoritesViewModel,
    val viewerSessionScopeTracker: ViewerSessionScopeTracker,
    val searchViewModel: SearchViewModel,
    val catalogDetailViewModel: CatalogDetailViewModel,
    val guideDiscoveryViewModel: GuideDiscoveryViewModel,
    val authViewModel: AuthViewModel,
    val onboardingViewModel: OnboardingViewModel,
    val legalDocumentLauncher: LegalDocumentLauncher,
    val observabilityController: AndroidObservabilityController,
    val listingMediaUrlPolicy: ListingMediaUrlPolicy,
    val detailExternalActionLauncher: DetailExternalActionLauncher,
)

internal data class KwaborAppRuntimeState(
    val pendingDeepLink: StateFlow<AndroidDeepLinkDelivery?>,
    val launchSplashExited: StateFlow<Boolean>,
    val onDeepLinkAcknowledged: (Long) -> Unit,
    val onDeepLinksReset: () -> Unit,
)

private class KwaborCollectedState(
    val authentication: CollectedAuthenticationState,
    val onboarding: OnboardingUiState,
    val observabilityConsent: ObservabilityConsent,
    val observabilityPrivacyOperationFailed: Boolean,
    val deepLink: AndroidDeepLinkDelivery?,
    val launchSplashExited: Boolean,
) {
    val auth: AuthUiState get() = authentication.auth
    val authAccess: AuthAccessUiState get() = authentication.access
    val registration: RegistrationUiState get() = authentication.registration
    val passwordRecovery: PasswordRecoveryUiState get() = authentication.passwordRecovery
    val promoterActivation: PromoterActivationUiState get() = authentication.promoterActivation
    val authPlatform: AuthPlatformUiState get() = authentication.platform
    val isSessionRestoreComplete: Boolean get() = authentication.isSessionRestoreComplete

    val registrationScreenState: RegistrationScreenState
        get() = RegistrationScreenState(
            registration = registration,
            surface = authPlatform.surface,
            softWallContext = authPlatform.softWallContext,
            otpResendSecondsRemaining = authPlatform.otpResendSecondsRemaining,
            legalDocumentOpenFailed = authPlatform.legalDocumentOpenFailed,
            federatedSignInInProgress = authPlatform.federatedSignInInProgress,
        )

    val onboardingEntry: OnboardingEntry
        get() = OnboardingEntryResolver.resolve(
            firstLaunchCompleted = !onboarding.isIntroRequired,
            sessionRestoreCompleted = isSessionRestoreComplete,
            isAuthenticated = auth.isAuthenticated,
            guestAccessGranted = onboarding.isGuestSession,
        )
}

private data class CollectedAuthenticationState(
    val auth: AuthUiState,
    val access: AuthAccessUiState,
    val registration: RegistrationUiState,
    val passwordRecovery: PasswordRecoveryUiState,
    val promoterActivation: PromoterActivationUiState,
    val platform: AuthPlatformUiState,
    val isSessionRestoreComplete: Boolean,
)

internal data class HomeShellDependencies(
    val exploreViewModel: ExploreViewModel,
    val favoritesViewModel: FavoritesViewModel,
    val viewerSessionScopeTracker: ViewerSessionScopeTracker,
    val searchViewModel: SearchViewModel,
    val catalogDetailViewModel: CatalogDetailViewModel,
    val guideDiscoveryViewModel: GuideDiscoveryViewModel,
    val authViewModel: AuthViewModel,
    val onboardingViewModel: OnboardingViewModel,
    val observabilityController: AndroidObservabilityController,
    val listingMediaUrlPolicy: ListingMediaUrlPolicy,
    val detailExternalActionLauncher: DetailExternalActionLauncher,
) {
    constructor(dependencies: KwaborAppDependencies) : this(
        exploreViewModel = dependencies.exploreViewModel,
        favoritesViewModel = dependencies.favoritesViewModel,
        viewerSessionScopeTracker = dependencies.viewerSessionScopeTracker,
        searchViewModel = dependencies.searchViewModel,
        catalogDetailViewModel = dependencies.catalogDetailViewModel,
        guideDiscoveryViewModel = dependencies.guideDiscoveryViewModel,
        authViewModel = dependencies.authViewModel,
        onboardingViewModel = dependencies.onboardingViewModel,
        observabilityController = dependencies.observabilityController,
        listingMediaUrlPolicy = dependencies.listingMediaUrlPolicy,
        detailExternalActionLauncher = dependencies.detailExternalActionLauncher,
    )
}

private object AuthEffectDispatcher {
    val dispatch: (AuthEffect, String?, HomeShellDependencies, RootEffectActions) -> Unit =
        { effect, pendingDestinationKey, dependencies, actions ->
            when (effect) {
                AuthEffect.AuthenticationCompleted -> {
                    dependencies.syncViewerSessionScope()
                    val destination = pendingDestinationKey?.let(RootNavigationDestination::fromRouteKey)
                    if (destination != null) {
                        actions.onAuthenticatedDestinationRequested(destination)
                    }
                    actions.onDestinationResolved()
                }
                AuthEffect.GuestContinuationSelected -> {
                    dependencies.clearViewerSessionScope()
                    actions.onDestinationResolved()
                }
                AuthEffect.SignedOut -> {
                    dependencies.clearViewerSessionScope()
                    dependencies.onboardingViewModel.onIntent(OnboardingIntent.GuestConfirmed)
                    actions.onAuthenticationEnded()
                    actions.onDestinationResolved()
                    actions.onDeepLinksReset()
                    dependencies.authViewModel.onIntent(AuthIntent.SignOutNavigationHandled)
                }
                AuthEffect.AccountDeleted -> {
                    dependencies.clearViewerSessionScope()
                    dependencies.onboardingViewModel.onIntent(OnboardingIntent.GuestConfirmed)
                    actions.onAuthenticationEnded()
                    actions.onDestinationResolved()
                    actions.onDeepLinksReset()
                    dependencies.authViewModel.onIntent(AuthIntent.AccountDeletionNavigationHandled)
                }
                is AuthEffect.PromoterActivationCompleted -> {
                    dependencies.syncViewerSessionScope()
                    actions.onAuthenticatedDestinationRequested(RootNavigationDestination.Home)
                    actions.onDestinationResolved()
                    actions.onDeepLinksReset()
                }
            }
        }
}

internal data class HomeShellState(
    val auth: AuthUiState,
    val authAccess: AuthAccessUiState,
    val observabilityConsent: ObservabilityConsent,
    val observabilityPrivacyOperationFailed: Boolean,
    val deepLink: AndroidDeepLinkDelivery?,
)

@Composable
private fun collectKwaborAppState(
    dependencies: KwaborAppDependencies,
    runtimeState: KwaborAppRuntimeState,
): KwaborCollectedState {
    val authState by dependencies.authViewModel.state.collectAsStateWithLifecycle()
    val authAccessState by dependencies.authViewModel.accessState.collectAsStateWithLifecycle()
    val restoreComplete by dependencies.authViewModel.isSessionRestoreComplete.collectAsStateWithLifecycle()
    val onboardingState by dependencies.onboardingViewModel.state.collectAsStateWithLifecycle()
    val registrationState by dependencies.authViewModel.registrationState.collectAsStateWithLifecycle()
    val passwordRecoveryState by dependencies.authViewModel.passwordRecoveryState.collectAsStateWithLifecycle()
    val promoterActivationState by dependencies.authViewModel.promoterActivationState.collectAsStateWithLifecycle()
    val authPlatformState by dependencies.authViewModel.platformState.collectAsStateWithLifecycle()
    val observabilityConsent by dependencies.observabilityController.consent.collectAsStateWithLifecycle()
    val observabilityPrivacyOperationFailed by
        dependencies.observabilityController.privacyOperationFailed.collectAsStateWithLifecycle()
    val deepLink by runtimeState.pendingDeepLink.collectAsStateWithLifecycle()
    val launchSplashExited by runtimeState.launchSplashExited.collectAsStateWithLifecycle()
    return KwaborCollectedState(
        authentication = CollectedAuthenticationState(
            auth = authState,
            access = authAccessState,
            registration = registrationState,
            passwordRecovery = passwordRecoveryState,
            promoterActivation = promoterActivationState,
            platform = authPlatformState,
            isSessionRestoreComplete = restoreComplete,
        ),
        onboarding = onboardingState,
        observabilityConsent = observabilityConsent,
        observabilityPrivacyOperationFailed = observabilityPrivacyOperationFailed,
        deepLink = deepLink,
        launchSplashExited = launchSplashExited,
    )
}

@Composable
private fun OnboardingEffectHandler(dependencies: KwaborAppDependencies) {
    LaunchedEffect(dependencies.onboardingViewModel, dependencies.authViewModel) {
        dependencies.onboardingViewModel.effects.collect { effect ->
            when (effect) {
                OnboardingEffect.OpenRegistration -> dependencies.authViewModel.onIntent(AuthIntent.OpenRegistration())
                OnboardingEffect.OpenSignIn -> dependencies.authViewModel.onIntent(AuthIntent.OpenSignIn())
            }
        }
    }
}

@Composable
private fun KwaborEntryContent(
    entry: OnboardingEntry,
    state: KwaborCollectedState,
    strings: KwaborStrings,
    dependencies: KwaborAppDependencies,
    runtimeState: KwaborAppRuntimeState,
) {
    when (entry) {
        OnboardingEntry.RestoringSession -> SessionRestoreScreen(strings = strings)
        OnboardingEntry.Intro -> KwaborIntroRoute(
            strings = strings,
            state = state.onboarding,
            viewModel = dependencies.onboardingViewModel,
            launchSplashExited = state.launchSplashExited,
        )
        OnboardingEntry.Authentication -> KwaborLandingRoute(
            strings = strings,
            state = state.onboarding,
            viewModel = dependencies.onboardingViewModel,
        )
        OnboardingEntry.Home -> KwaborAppContent(
            dependencies = HomeShellDependencies(dependencies),
            state = HomeShellState(
                auth = state.auth,
                authAccess = state.authAccess,
                observabilityConsent = state.observabilityConsent,
                observabilityPrivacyOperationFailed = state.observabilityPrivacyOperationFailed,
                deepLink = AndroidDeepLinkDispatchPolicy.readyDelivery(
                    delivery = state.deepLink,
                    eligibility = AndroidDeepLinkHomeEligibility(
                        isSessionRestoreComplete = state.isSessionRestoreComplete,
                        isIntroRequired = state.onboarding.isIntroRequired,
                        isAuthenticated = state.auth.isAuthenticated,
                        isGuestSession = state.onboarding.isGuestSession,
                    ),
                ),
            ),
            onDeepLinkAcknowledged = runtimeState.onDeepLinkAcknowledged,
            onDeepLinksReset = runtimeState.onDeepLinksReset,
        )
    }
}

@Composable
private fun SessionRestoreScreen(strings: KwaborStrings) {
    Surface(modifier = Modifier.fillMaxSize()) {
        KwaborStateMessage(
            title = strings.appName,
            supportingText = strings.loading,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun KwaborAppContent(
    dependencies: HomeShellDependencies,
    state: HomeShellState,
    onDeepLinkAcknowledged: (Long) -> Unit,
    onDeepLinksReset: () -> Unit,
) {
    val navController = rememberNavController()
    FavoritesNavigationPrivacyEffect(
        accountId = state.auth.viewerAccountId,
        navController = navController,
    )
    var pendingDestinationKey by rememberSaveable { mutableStateOf<String?>(null) }
    val requestDestination = rootDestinationRequester(
        navController,
        state.auth.isAuthenticated,
        dependencies.authViewModel,
        { destination -> pendingDestinationKey = destination.routeKey },
    )

    val actions = kwaborAppEffectActions(
        navController,
        dependencies.catalogDetailViewModel,
        requestDestination,
        { pendingDestinationKey = null },
        KwaborDeepLinkCallbacks(onDeepLinkAcknowledged, onDeepLinksReset),
    )
    KwaborHomeEffectHandlers(
        state = state,
        pendingDestinationKey = pendingDestinationKey,
        dependencies = dependencies,
        actions = actions,
    )
    KwaborNavigationShell(
        navController = navController,
        strings = stringsFor(AppLocale.French),
        dependencies = dependencies,
        state = state,
        onDestinationSelected = requestDestination,
    )
}

@Composable
private fun KwaborHomeEffectHandlers(
    state: HomeShellState,
    pendingDestinationKey: String?,
    dependencies: HomeShellDependencies,
    actions: KwaborAppEffectActions,
) {
    DeepLinkEffectHandler(deepLink = state.deepLink, actions = actions.deepLink)
    ViewerSessionScopeHandler(
        accountId = state.auth.viewerAccountId,
        accountSetupComplete = state.auth.isAuthenticated,
        dependencies = dependencies,
    )
    ExploreEffectHandler(dependencies = dependencies)
    FavoritesEffectHandler(
        favoritesViewModel = dependencies.favoritesViewModel,
        exploreViewModel = dependencies.exploreViewModel,
        catalogDetailViewModel = dependencies.catalogDetailViewModel,
        viewerSessionScopeTracker = dependencies.viewerSessionScopeTracker,
    )
    SearchEffectHandler(dependencies = dependencies)
    GuideDiscoveryEffectHandler(
        guideDiscoveryViewModel = dependencies.guideDiscoveryViewModel,
        catalogDetailViewModel = dependencies.catalogDetailViewModel,
    )
    AuthEffectHandler(
        pendingDestinationKey = pendingDestinationKey,
        dependencies = dependencies,
        actions = actions.root,
    )
}

@Composable
private fun KwaborNavigationShell(
    navController: NavHostController,
    strings: KwaborStrings,
    dependencies: HomeShellDependencies,
    state: HomeShellState,
    onDestinationSelected: (RootNavigationDestination) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val detailState by dependencies.catalogDetailViewModel.state.collectAsStateWithLifecycle()
    val selectedDestination = backStackEntry?.destination?.toRootDestination() ?: RootNavigationDestination.Home
    val privacySnackbarHostState = rememberPrivacySnackbarHostState(state, strings, dependencies)

    Scaffold(
        snackbarHost = { SnackbarHost(privacySnackbarHostState) },
        bottomBar = {
            KwaborBottomNavigation(
                selectedDestination = selectedDestination,
                strings = strings,
                onDestinationSelected = onDestinationSelected,
            )
        },
    ) { paddingValues ->
        KwaborRootNavHost(
            navController = navController,
            paddingValues = paddingValues,
            strings = strings,
            dependencies = dependencies,
            state = state,
        )
    }
    CatalogDetailSheet(
        state = detailState,
        strings = strings,
        platformDependencies = CatalogDetailPlatformDependencies(
            mediaUrlPolicy = dependencies.listingMediaUrlPolicy,
            externalActionLauncher = dependencies.detailExternalActionLauncher,
        ),
        actions = remember(dependencies.catalogDetailViewModel) {
            dependencies.catalogDetailViewModel.sheetActions
        },
    )
}

@Composable
private fun KwaborRootNavHost(
    navController: NavHostController,
    paddingValues: PaddingValues,
    strings: KwaborStrings,
    dependencies: HomeShellDependencies,
    state: HomeShellState,
) {
    NavHost(navController = navController, startDestination = HomeRoute) {
        homeExploreRoute(navController, paddingValues, strings, dependencies, state)
        guideDiscoveryChildRoute(
            navController = navController,
            viewModel = dependencies.guideDiscoveryViewModel,
            strings = strings,
            mediaUrlPolicy = dependencies.listingMediaUrlPolicy,
            paddingValues = paddingValues,
        )
        rootAnchorRoutes(paddingValues = paddingValues, strings = strings)
        profileRoute(navController, paddingValues, strings, state)
        favoritesChildRoute(
            navController = navController,
            viewModel = dependencies.favoritesViewModel,
            strings = strings,
            mediaUrlPolicy = dependencies.listingMediaUrlPolicy,
            paddingValues = paddingValues,
        )
        settingsRoute(navController, paddingValues, strings, dependencies, state)
    }
}

private fun NavGraphBuilder.homeExploreRoute(
    navController: NavHostController,
    paddingValues: PaddingValues,
    strings: KwaborStrings,
    dependencies: HomeShellDependencies,
    state: HomeShellState,
) {
    composable<HomeRoute> {
        ExploreRoute(
            dependencies = dependencies,
            strings = strings,
            isGuestSession = !state.auth.isAuthenticated,
            modifier = Modifier.padding(paddingValues),
            onGuideDiscoveryRequested = {
                navController.navigate(GuideDiscoveryRoute) { launchSingleTop = true }
            },
        )
    }
}

private fun NavGraphBuilder.rootAnchorRoutes(paddingValues: PaddingValues, strings: KwaborStrings) {
    rootAnchor<SocialRoute>(RootNavigationDestination.Social, paddingValues, strings)
    rootAnchor<AddRoute>(RootNavigationDestination.Add, paddingValues, strings)
    rootAnchor<NotificationsRoute>(RootNavigationDestination.Notifications, paddingValues, strings)
}

private inline fun <reified Route : Any> NavGraphBuilder.rootAnchor(
    destination: RootNavigationDestination,
    paddingValues: PaddingValues,
    strings: KwaborStrings,
) {
    composable<Route> {
        KwaborRootContent(
            modifier = Modifier.padding(paddingValues),
            destination = destination,
            strings = strings,
        )
    }
}

@Composable
private fun AuthEffectHandler(
    pendingDestinationKey: String?,
    dependencies: HomeShellDependencies,
    actions: RootEffectActions,
) {
    val currentPendingDestinationKey by rememberUpdatedState(pendingDestinationKey)
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(
        dependencies.authViewModel,
        dependencies.exploreViewModel,
        dependencies.favoritesViewModel,
    ) {
        dependencies.authViewModel.effects.collect { effect ->
            AuthEffectDispatcher.dispatch(effect, currentPendingDestinationKey, dependencies, currentActions)
        }
    }
}

private data class RootEffectActions(
    val onDestinationRequested: (RootNavigationDestination) -> Unit,
    val onAuthenticatedDestinationRequested: (RootNavigationDestination) -> Unit,
    val onAuthenticationEnded: () -> Unit,
    val onDestinationResolved: () -> Unit,
    val onDeepLinksReset: () -> Unit,
)

private data class KwaborAppEffectActions(
    val root: RootEffectActions,
    val deepLink: AndroidNavigationDeepLinkDispatchActions,
)

private data class KwaborDeepLinkCallbacks(
    val onAcknowledged: (Long) -> Unit,
    val onReset: () -> Unit,
)

private fun kwaborAppEffectActions(
    navController: NavHostController,
    catalogDetailViewModel: CatalogDetailViewModel,
    requestDestination: (RootNavigationDestination) -> Unit,
    onDestinationResolved: () -> Unit,
    deepLinkCallbacks: KwaborDeepLinkCallbacks,
): KwaborAppEffectActions = KwaborAppEffectActions(
    root = RootEffectActions(
        onDestinationRequested = requestDestination,
        onAuthenticatedDestinationRequested = navController::navigateToRoot,
        onAuthenticationEnded = navController::resetToHomeAfterAuthenticationEnd,
        onDestinationResolved = onDestinationResolved,
        onDeepLinksReset = deepLinkCallbacks.onReset,
    ),
    deepLink = AndroidNavigationDeepLinkDispatchActions(
        onRootDestination = requestDestination,
        onHomeDestination = { navController.navigateToRoot(RootNavigationDestination.Home) },
        onCatalogDetailOpen = { listingId ->
            catalogDetailViewModel.onIntent(CatalogDetailIntent.Open(listingId))
        },
        onAcknowledged = deepLinkCallbacks.onAcknowledged,
    ),
)

private val AuthUiState.viewerAccountId: String?
    get() = currentSession?.userId?.takeIf { isAuthenticated }

private fun HomeShellDependencies.syncViewerSessionScope() {
    val auth = authViewModel.state.value
    publishViewerSessionScope(
        accountId = auth.viewerAccountId,
        accountSetupComplete = auth.isAuthenticated,
    )
}

private fun HomeShellDependencies.clearViewerSessionScope() {
    publishViewerSessionScope(accountId = null, accountSetupComplete = false)
    exploreViewModel.onIntent(ExploreIntent.ClearPendingAuthentication)
}

private val rootDestinationRequester =
    {
            navController: NavHostController,
            isAuthenticated: Boolean,
            authViewModel: AuthViewModel,
            onAuthenticationRequired: (RootNavigationDestination) -> Unit,
        ->
        { destination: RootNavigationDestination ->
            if (destination == RootNavigationDestination.Home || isAuthenticated) {
                navController.navigateToRoot(destination)
            } else {
                onAuthenticationRequired(destination)
                authViewModel.onIntent(
                    AuthIntent.OpenSoftWall(
                        AuthSoftWallContext(
                            action = AuthProtectedAction.Other,
                            suggestedCityId = null,
                        ),
                    ),
                )
            }
        }
    }

@Composable
private fun KwaborRootContent(
    destination: RootNavigationDestination,
    strings: KwaborStrings,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(all = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = destination.label(strings))
            Text(text = strings.foundationStatus)
        }
    }
}
