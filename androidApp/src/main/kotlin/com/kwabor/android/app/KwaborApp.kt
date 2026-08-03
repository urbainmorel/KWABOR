package com.kwabor.android.app

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
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
import com.kwabor.android.presentation.auth.AuthAccessUiState
import com.kwabor.android.presentation.auth.AuthEffect
import com.kwabor.android.presentation.auth.AuthIntent
import com.kwabor.android.presentation.auth.AuthPlatformUiState
import com.kwabor.android.presentation.auth.AuthSurface
import com.kwabor.android.presentation.auth.AuthViewModel
import com.kwabor.android.presentation.auth.PromoterActivationUiState
import com.kwabor.android.presentation.detail.CatalogDetailViewModel
import com.kwabor.android.presentation.explore.ExploreEffect
import com.kwabor.android.presentation.explore.ExploreIntent
import com.kwabor.android.presentation.explore.ExploreViewModel
import com.kwabor.android.presentation.guide.GuideDiscoveryViewModel
import com.kwabor.android.presentation.onboarding.OnboardingEffect
import com.kwabor.android.presentation.onboarding.OnboardingIntent
import com.kwabor.android.presentation.onboarding.OnboardingUiState
import com.kwabor.android.presentation.onboarding.OnboardingViewModel
import com.kwabor.android.ui.components.KwaborStateMessage
import com.kwabor.android.ui.screens.auth.AuthSheet
import com.kwabor.android.ui.screens.auth.RegistrationScreenState
import com.kwabor.android.ui.screens.detail.CatalogDetailPlatformDependencies
import com.kwabor.android.ui.screens.detail.CatalogDetailSheet
import com.kwabor.android.ui.screens.explore.ExploreScreen
import com.kwabor.android.ui.screens.explore.ExploreScreenUiModel
import com.kwabor.android.ui.screens.profile.ProfileScreen
import com.kwabor.android.ui.screens.profile.ProfileScreenUiModel
import com.kwabor.android.ui.screens.settings.SettingsScreen
import com.kwabor.android.ui.screens.settings.SettingsScreenUiModel
import com.kwabor.shared.domain.i18n.AppLocale
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
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun KwaborApp(dependencies: KwaborAppDependencies, runtimeState: KwaborAppRuntimeState) {
    val state = collectKwaborAppState(dependencies = dependencies, runtimeState = runtimeState)
    val strings = stringsFor(AppLocale.French)
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
    SoftWallOverlay(state.authPlatform.surface, strings, dependencies.authViewModel)
}

@Composable
private fun SoftWallOverlay(surface: AuthSurface, strings: KwaborStrings, authViewModel: AuthViewModel) {
    if (surface != AuthSurface.SoftWall) return
    AuthSheet(
        strings = strings,
        actions = remember(authViewModel) { authViewModel.sheetActions() },
    )
}

internal data class KwaborAppDependencies(
    val exploreViewModel: ExploreViewModel,
    val catalogDetailViewModel: CatalogDetailViewModel,
    val guideDiscoveryViewModel: GuideDiscoveryViewModel,
    val authViewModel: AuthViewModel,
    val onboardingViewModel: OnboardingViewModel,
    val legalDocumentLauncher: LegalDocumentLauncher,
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
            locationStatus = authPlatform.locationStatus,
            locationPermissionRequestInFlight = authPlatform.locationPermissionRequestInFlight,
            otpResendSecondsRemaining = authPlatform.otpResendSecondsRemaining,
            legalDocumentOpenFailed = authPlatform.legalDocumentOpenFailed,
            observabilityConsentPersistenceFailed = authPlatform.observabilityConsentPersistenceFailed,
            notificationPermissionRequestInFlight = authPlatform.notificationPermissionRequestInFlight,
            notificationPrimingPersistenceFailed = authPlatform.notificationPrimingPersistenceFailed,
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

private data class HomeShellDependencies(
    val exploreViewModel: ExploreViewModel,
    val catalogDetailViewModel: CatalogDetailViewModel,
    val guideDiscoveryViewModel: GuideDiscoveryViewModel,
    val authViewModel: AuthViewModel,
    val onboardingViewModel: OnboardingViewModel,
    val listingMediaUrlPolicy: ListingMediaUrlPolicy,
    val detailExternalActionLauncher: DetailExternalActionLauncher,
) {
    constructor(dependencies: KwaborAppDependencies) : this(
        exploreViewModel = dependencies.exploreViewModel,
        catalogDetailViewModel = dependencies.catalogDetailViewModel,
        guideDiscoveryViewModel = dependencies.guideDiscoveryViewModel,
        authViewModel = dependencies.authViewModel,
        onboardingViewModel = dependencies.onboardingViewModel,
        listingMediaUrlPolicy = dependencies.listingMediaUrlPolicy,
        detailExternalActionLauncher = dependencies.detailExternalActionLauncher,
    )
}

private object AuthEffectDispatcher {
    val dispatch: (AuthEffect, String?, HomeShellDependencies, RootEffectActions) -> Unit =
        { effect, pendingDestinationKey, dependencies, actions ->
            when (effect) {
                AuthEffect.AuthenticationCompleted -> {
                    dependencies.syncExploreViewerContext()
                    val destination = pendingDestinationKey?.let(RootNavigationDestination::fromRouteKey)
                    if (destination != null) {
                        actions.onAuthenticatedDestinationRequested(destination)
                    }
                    actions.onDestinationResolved()
                }
                AuthEffect.GuestContinuationSelected -> {
                    actions.onDestinationResolved()
                    dependencies.exploreViewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerId = null))
                }
                AuthEffect.SignedOut -> {
                    dependencies.onboardingViewModel.onIntent(OnboardingIntent.GuestConfirmed)
                    actions.onAuthenticationEnded()
                    actions.onDestinationResolved()
                    actions.onDeepLinksReset()
                    dependencies.exploreViewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerId = null))
                    dependencies.authViewModel.onIntent(AuthIntent.SignOutNavigationHandled)
                }
                AuthEffect.AccountDeleted -> {
                    dependencies.onboardingViewModel.onIntent(OnboardingIntent.GuestConfirmed)
                    actions.onAuthenticationEnded()
                    actions.onDestinationResolved()
                    actions.onDeepLinksReset()
                    dependencies.exploreViewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerId = null))
                    dependencies.authViewModel.onIntent(AuthIntent.AccountDeletionNavigationHandled)
                }
                is AuthEffect.PromoterActivationCompleted -> {
                    dependencies.syncExploreViewerContext()
                    actions.onAuthenticatedDestinationRequested(RootNavigationDestination.Home)
                    actions.onDestinationResolved()
                    actions.onDeepLinksReset()
                }
            }
        }
}

private data class HomeShellState(
    val auth: AuthUiState,
    val authAccess: AuthAccessUiState,
    val deepLink: AndroidDeepLinkDelivery?,
) {
    constructor(state: KwaborCollectedState) : this(
        auth = state.auth,
        authAccess = state.authAccess,
        deepLink = AndroidDeepLinkDispatchPolicy.readyDelivery(
            delivery = state.deepLink,
            eligibility = AndroidDeepLinkHomeEligibility(
                isSessionRestoreComplete = state.isSessionRestoreComplete,
                isIntroRequired = state.onboarding.isIntroRequired,
                isAuthenticated = state.auth.isAuthenticated,
                isGuestSession = state.onboarding.isGuestSession,
            ),
        ),
    )
}

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
            staticFallbackRequired = state.onboarding.isStaticIntroFallbackRequired,
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
            state = HomeShellState(state),
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
    DeepLinkEffectHandler(deepLink = state.deepLink, actions = actions.deepLink)
    ExploreViewerContextHandler(
        viewerId = state.auth.exploreViewerId,
        exploreViewModel = dependencies.exploreViewModel,
    )
    ExploreEffectHandler(dependencies = dependencies)
    GuideDiscoveryEffectHandler(
        guideDiscoveryViewModel = dependencies.guideDiscoveryViewModel,
        catalogDetailViewModel = dependencies.catalogDetailViewModel,
    )
    AuthEffectHandler(
        pendingDestinationKey = pendingDestinationKey,
        dependencies = dependencies,
        actions = actions.root,
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

    Scaffold(
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
        composable<ProfileRoute> {
            ProfileScreen(
                model = ProfileScreenUiModel(email = state.auth.currentSession?.email),
                strings = strings,
                onSettingsRequested = {
                    navController.navigate(SettingsRoute) { launchSingleTop = true }
                },
                modifier = Modifier.padding(paddingValues),
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                model = SettingsScreenUiModel(
                    email = state.auth.currentSession?.email,
                    authenticationMethod = state.auth.currentSession?.authenticationMethod,
                    authAccessState = state.authAccess,
                ),
                strings = strings,
                actions = remember(dependencies.authViewModel) {
                    dependencies.authViewModel.settingsScreenActions()
                },
                onBack = { navController.popBackStack() },
                modifier = Modifier.padding(bottom = paddingValues.calculateBottomPadding()),
            )
        }
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
private fun ExploreEffectHandler(dependencies: HomeShellDependencies) {
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        dependencies.exploreViewModel.onIntent(ExploreIntent.LocationPermissionResult(granted))
    }
    LaunchedEffect(dependencies.exploreViewModel, dependencies.authViewModel) {
        dependencies.exploreViewModel.effects.collect { effect ->
            when (effect) {
                ExploreEffect.AuthenticationRequired -> {
                    dependencies.authViewModel.onIntent(AuthIntent.OpenSoftWall)
                }
                ExploreEffect.RequestLocationPermission -> {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            }
        }
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
    LaunchedEffect(dependencies.authViewModel, dependencies.exploreViewModel) {
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

private val AuthUiState.exploreViewerId: String?
    get() = currentSession?.userId?.takeIf { isAuthenticated }

private fun HomeShellDependencies.syncExploreViewerContext() {
    exploreViewModel.onIntent(ExploreIntent.ViewerContextChanged(authViewModel.state.value.exploreViewerId))
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
                authViewModel.onIntent(AuthIntent.OpenSoftWall)
            }
        }
    }

@Composable
private fun ExploreRoute(
    dependencies: HomeShellDependencies,
    strings: KwaborStrings,
    isGuestSession: Boolean,
    modifier: Modifier = Modifier,
    onGuideDiscoveryRequested: () -> Unit,
) {
    val exploreState by dependencies.exploreViewModel.state.collectAsStateWithLifecycle()

    ExploreScreen(
        model = ExploreScreenUiModel(
            state = exploreState,
            isGuestSession = isGuestSession,
        ),
        strings = strings,
        mediaUrlPolicy = dependencies.listingMediaUrlPolicy,
        modifier = modifier,
        actions = remember(
            dependencies.exploreViewModel,
            dependencies.catalogDetailViewModel,
            onGuideDiscoveryRequested,
        ) {
            dependencies.exploreViewModel.detailEnabledScreenActions(
                onListingClick = { listingId ->
                    dependencies.catalogDetailViewModel.onIntent(
                        CatalogDetailIntent.Open(listingId),
                    )
                },
                onGuideDiscoveryClick = onGuideDiscoveryRequested,
            )
        },
    )
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
