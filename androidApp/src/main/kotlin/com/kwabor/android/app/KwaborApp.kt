package com.kwabor.android.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import com.kwabor.android.presentation.auth.AuthEffect
import com.kwabor.android.presentation.auth.AuthIntent
import com.kwabor.android.presentation.auth.AuthPlatformUiState
import com.kwabor.android.presentation.auth.AuthSurface
import com.kwabor.android.presentation.auth.AuthViewModel
import com.kwabor.android.presentation.explore.ExploreEffect
import com.kwabor.android.presentation.explore.ExploreIntent
import com.kwabor.android.presentation.explore.ExploreViewModel
import com.kwabor.android.presentation.onboarding.OnboardingEffect
import com.kwabor.android.presentation.onboarding.OnboardingUiState
import com.kwabor.android.presentation.onboarding.OnboardingViewModel
import com.kwabor.android.ui.components.KwaborStateMessage
import com.kwabor.android.ui.screens.auth.AuthSheet
import com.kwabor.android.ui.screens.auth.RegistrationScreen
import com.kwabor.android.ui.screens.auth.RegistrationScreenState
import com.kwabor.android.ui.screens.explore.ExploreScreen
import com.kwabor.android.ui.screens.explore.ExploreScreenActions
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthUiState
import com.kwabor.shared.presentation.auth.RegistrationUiState
import com.kwabor.shared.presentation.navigation.RootDeepLinkParser
import com.kwabor.shared.presentation.navigation.RootDeepLinkResult
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
    KwaborTheme {
        KwaborThemedContent(state, strings, dependencies, runtimeState.onDeepLinkConsumed)
    }
}

@Composable
private fun KwaborThemedContent(
    state: KwaborCollectedState,
    strings: KwaborStrings,
    dependencies: KwaborAppDependencies,
    onDeepLinkConsumed: () -> Unit,
) {
    val isAuthenticationSurface = state.authPlatform.surface == AuthSurface.Registration ||
        state.authPlatform.surface == AuthSurface.SignIn
    if (isAuthenticationSurface) {
        RegistrationScreen(
            state = RegistrationScreenState(
                registration = state.registration,
                surface = state.authPlatform.surface,
                locationStatus = state.authPlatform.locationStatus,
                locationPermissionRequestInFlight = state.authPlatform.locationPermissionRequestInFlight,
                otpResendSecondsRemaining = state.authPlatform.otpResendSecondsRemaining,
                legalDocumentOpenFailed = state.authPlatform.legalDocumentOpenFailed,
                observabilityConsentPersistenceFailed =
                state.authPlatform.observabilityConsentPersistenceFailed,
                notificationPermissionRequestInFlight =
                state.authPlatform.notificationPermissionRequestInFlight,
                notificationPrimingPersistenceFailed =
                state.authPlatform.notificationPrimingPersistenceFailed,
            ),
            strings = strings,
            actions = remember(dependencies.authViewModel) { dependencies.authViewModel.registrationActions() },
        )
    } else {
        KwaborEntryContent(
            entry = resolveOnboardingEntry(state),
            state = state,
            strings = strings,
            dependencies = dependencies,
            onDeepLinkConsumed = onDeepLinkConsumed,
        )
    }
    if (state.authPlatform.surface == AuthSurface.SoftWall) {
        AuthSheet(
            strings = strings,
            actions = remember(dependencies.authViewModel) { dependencies.authViewModel.sheetActions() },
        )
    }
}

private fun resolveOnboardingEntry(state: KwaborCollectedState): OnboardingEntry =
    if (state.onboarding.isLaunchDecisionComplete) {
        OnboardingEntryResolver.resolve(
            firstLaunchCompleted = !state.onboarding.isIntroRequired,
            sessionRestoreCompleted = state.isSessionRestoreComplete,
            isAuthenticated = state.auth.isAuthenticated,
            guestAccessGranted = state.onboarding.isGuestSession,
        )
    } else {
        OnboardingEntry.RestoringSession
    }

internal data class KwaborAppDependencies(
    val exploreViewModel: ExploreViewModel,
    val authViewModel: AuthViewModel,
    val onboardingViewModel: OnboardingViewModel,
    val legalDocumentLauncher: LegalDocumentLauncher,
)

internal data class KwaborAppRuntimeState(
    val pendingDeepLink: StateFlow<String?>,
    val onDeepLinkConsumed: () -> Unit,
)

private data class KwaborCollectedState(
    val auth: AuthUiState,
    val onboarding: OnboardingUiState,
    val registration: RegistrationUiState,
    val authPlatform: AuthPlatformUiState,
    val isSessionRestoreComplete: Boolean,
    val deepLink: String?,
)

@Composable
private fun collectKwaborAppState(
    dependencies: KwaborAppDependencies,
    runtimeState: KwaborAppRuntimeState,
): KwaborCollectedState {
    val authState by dependencies.authViewModel.state.collectAsStateWithLifecycle()
    val restoreComplete by dependencies.authViewModel.isSessionRestoreComplete.collectAsStateWithLifecycle()
    val onboardingState by dependencies.onboardingViewModel.state.collectAsStateWithLifecycle()
    val registrationState by dependencies.authViewModel.registrationState.collectAsStateWithLifecycle()
    val authPlatformState by dependencies.authViewModel.platformState.collectAsStateWithLifecycle()
    val deepLink by runtimeState.pendingDeepLink.collectAsStateWithLifecycle()
    return KwaborCollectedState(
        auth = authState,
        onboarding = onboardingState,
        registration = registrationState,
        authPlatform = authPlatformState,
        isSessionRestoreComplete = restoreComplete,
        deepLink = deepLink,
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
    onDeepLinkConsumed: () -> Unit,
) {
    when (entry) {
        OnboardingEntry.RestoringSession -> SessionRestoreScreen(strings = strings)
        OnboardingEntry.Intro -> KwaborIntroRoute(
            strings = strings,
            mediaSource = state.onboarding.introMediaSource,
            viewModel = dependencies.onboardingViewModel,
        )
        OnboardingEntry.Authentication -> KwaborLandingRoute(
            strings = strings,
            state = state.onboarding,
            viewModel = dependencies.onboardingViewModel,
        )
        OnboardingEntry.Home -> KwaborAppContent(
            exploreViewModel = dependencies.exploreViewModel,
            authViewModel = dependencies.authViewModel,
            authState = state.auth,
            deepLink = state.deepLink.takeIf { state.isSessionRestoreComplete },
            onDeepLinkConsumed = onDeepLinkConsumed,
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
    exploreViewModel: ExploreViewModel,
    authViewModel: AuthViewModel,
    authState: AuthUiState,
    deepLink: String?,
    onDeepLinkConsumed: () -> Unit,
) {
    val strings = stringsFor(AppLocale.French)
    val navController = rememberNavController()
    var pendingDestinationKey by rememberSaveable { mutableStateOf<String?>(null) }
    val requestDestination = rootDestinationRequester(
        navController = navController,
        isAuthenticated = authState.isAuthenticated,
        authViewModel = authViewModel,
        onAuthenticationRequired = { destination -> pendingDestinationKey = destination.routeKey },
    )

    RootEffectHandlers(
        deepLink = deepLink,
        pendingDestinationKey = pendingDestinationKey,
        authViewModel = authViewModel,
        exploreViewModel = exploreViewModel,
        actions = RootEffectActions(
            onDestinationRequested = requestDestination,
            onAuthenticatedDestinationRequested = navController::navigateToRoot,
            onDestinationResolved = { pendingDestinationKey = null },
            onDeepLinkConsumed = onDeepLinkConsumed,
        ),
    )
    KwaborNavigationShell(
        navController = navController,
        strings = strings,
        exploreViewModel = exploreViewModel,
        isGuestSession = !authState.isAuthenticated,
        onDestinationSelected = requestDestination,
    )
}

@Composable
internal fun KwaborUnavailableApp() {
    val strings = stringsFor(AppLocale.French)
    KwaborTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            KwaborStateMessage(
                title = strings.errorStateTitle,
                supportingText = strings.configurationUnavailable,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun KwaborNavigationShell(
    navController: NavHostController,
    strings: KwaborStrings,
    exploreViewModel: ExploreViewModel,
    isGuestSession: Boolean,
    onDestinationSelected: (RootNavigationDestination) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
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
            exploreViewModel = exploreViewModel,
            strings = strings,
            isGuestSession = isGuestSession,
        )
    }
}

@Composable
private fun KwaborRootNavHost(
    navController: NavHostController,
    paddingValues: PaddingValues,
    exploreViewModel: ExploreViewModel,
    strings: KwaborStrings,
    isGuestSession: Boolean,
) {
    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            ExploreRoute(
                exploreViewModel = exploreViewModel,
                strings = strings,
                isGuestSession = isGuestSession,
                modifier = Modifier.padding(paddingValues),
            )
        }
        rootAnchorRoutes(paddingValues = paddingValues, strings = strings)
    }
}

private fun NavGraphBuilder.rootAnchorRoutes(paddingValues: PaddingValues, strings: KwaborStrings) {
    rootAnchor<SocialRoute>(RootNavigationDestination.Social, paddingValues, strings)
    rootAnchor<AddRoute>(RootNavigationDestination.Add, paddingValues, strings)
    rootAnchor<NotificationsRoute>(RootNavigationDestination.Notifications, paddingValues, strings)
    rootAnchor<ProfileRoute>(RootNavigationDestination.Profile, paddingValues, strings)
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
private fun RootEffectHandlers(
    deepLink: String?,
    pendingDestinationKey: String?,
    authViewModel: AuthViewModel,
    exploreViewModel: ExploreViewModel,
    actions: RootEffectActions,
) {
    val currentPendingDestinationKey by rememberUpdatedState(pendingDestinationKey)
    val currentActions by rememberUpdatedState(actions)

    LaunchedEffect(deepLink) {
        val currentDeepLink = deepLink ?: return@LaunchedEffect
        when (val result = RootDeepLinkParser.parse(currentDeepLink)) {
            is RootDeepLinkResult.Accepted -> currentActions.onDestinationRequested(result.destination)
            is RootDeepLinkResult.Rejected -> Unit
        }
        currentActions.onDeepLinkConsumed()
    }
    LaunchedEffect(exploreViewModel, authViewModel) {
        exploreViewModel.effects.collect { effect ->
            when (effect) {
                ExploreEffect.AuthenticationRequired -> authViewModel.onIntent(AuthIntent.OpenSoftWall)
            }
        }
    }
    LaunchedEffect(authViewModel, exploreViewModel) {
        authViewModel.effects.collect { effect ->
            when (effect) {
                AuthEffect.AuthenticationCompleted -> {
                    val destination = currentPendingDestinationKey?.let(RootNavigationDestination::fromRouteKey)
                    if (destination == null) {
                        exploreViewModel.onIntent(ExploreIntent.ReplayPendingInteraction)
                    } else {
                        currentActions.onAuthenticatedDestinationRequested(destination)
                    }
                    currentActions.onDestinationResolved()
                }
                AuthEffect.GuestContinuationSelected -> {
                    currentActions.onDestinationResolved()
                    exploreViewModel.onIntent(ExploreIntent.ContinueAsGuest)
                }
            }
        }
    }
}

private data class RootEffectActions(
    val onDestinationRequested: (RootNavigationDestination) -> Unit,
    val onAuthenticatedDestinationRequested: (RootNavigationDestination) -> Unit,
    val onDestinationResolved: () -> Unit,
    val onDeepLinkConsumed: () -> Unit,
)

private fun rootDestinationRequester(
    navController: NavHostController,
    isAuthenticated: Boolean,
    authViewModel: AuthViewModel,
    onAuthenticationRequired: (RootNavigationDestination) -> Unit,
): (RootNavigationDestination) -> Unit = { destination ->
    if (destination == RootNavigationDestination.Home || isAuthenticated) {
        navController.navigateToRoot(destination)
    } else {
        onAuthenticationRequired(destination)
        authViewModel.onIntent(AuthIntent.OpenSoftWall)
    }
}

@Composable
private fun KwaborBottomNavigation(
    selectedDestination: RootNavigationDestination,
    strings: KwaborStrings,
    onDestinationSelected: (RootNavigationDestination) -> Unit,
) {
    NavigationBar {
        RootNavigationDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = selectedDestination == destination,
                onClick = { onDestinationSelected(destination) },
                icon = { Icon(destination.icon(), contentDescription = destination.label(strings)) },
                label = { Text(text = destination.label(strings)) },
            )
        }
    }
}

@Composable
private fun ExploreRoute(
    exploreViewModel: ExploreViewModel,
    strings: KwaborStrings,
    isGuestSession: Boolean,
    modifier: Modifier = Modifier,
) {
    val exploreState by exploreViewModel.state.collectAsStateWithLifecycle()

    ExploreScreen(
        state = exploreState,
        strings = strings,
        isGuestSession = isGuestSession,
        modifier = modifier,
        actions = remember(exploreViewModel) { exploreViewModel.screenActions() },
    )
}

private fun ExploreViewModel.screenActions(): ExploreScreenActions = ExploreScreenActions(
    onTabSelected = { tab -> onIntent(ExploreIntent.SelectTab(tab)) },
    onChipSelected = { chip -> onIntent(ExploreIntent.SelectChip(chip)) },
    onRetry = { onIntent(ExploreIntent.Retry) },
    onLikeClick = { listingId -> onIntent(ExploreIntent.ToggleLike(listingId)) },
    onFavoriteClick = { listingId -> onIntent(ExploreIntent.ToggleFavorite(listingId)) },
)

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
