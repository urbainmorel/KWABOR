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
import com.kwabor.android.presentation.auth.AuthAccessUiState
import com.kwabor.android.presentation.auth.AuthEffect
import com.kwabor.android.presentation.auth.AuthIntent
import com.kwabor.android.presentation.auth.AuthPlatformUiState
import com.kwabor.android.presentation.auth.AuthSurface
import com.kwabor.android.presentation.auth.AuthViewModel
import com.kwabor.android.presentation.explore.ExploreEffect
import com.kwabor.android.presentation.explore.ExploreIntent
import com.kwabor.android.presentation.explore.ExploreViewModel
import com.kwabor.android.presentation.onboarding.OnboardingEffect
import com.kwabor.android.presentation.onboarding.OnboardingIntent
import com.kwabor.android.presentation.onboarding.OnboardingUiState
import com.kwabor.android.presentation.onboarding.OnboardingViewModel
import com.kwabor.android.ui.components.KwaborStateMessage
import com.kwabor.android.ui.screens.auth.AuthSheet
import com.kwabor.android.ui.screens.auth.PasswordRecoveryScreen
import com.kwabor.android.ui.screens.auth.RegistrationScreen
import com.kwabor.android.ui.screens.auth.RegistrationScreenState
import com.kwabor.android.ui.screens.auth.SignInScreen
import com.kwabor.android.ui.screens.explore.ExploreScreen
import com.kwabor.android.ui.screens.explore.ExploreScreenActions
import com.kwabor.android.ui.screens.profile.ProfileSessionScreen
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthUiState
import com.kwabor.shared.presentation.auth.PasswordRecoveryUiState
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
    when (state.authPlatform.surface) {
        AuthSurface.Registration -> RegistrationScreen(
            state = state.registrationScreenState,
            strings = strings,
            actions = remember(dependencies.authViewModel) { dependencies.authViewModel.registrationActions() },
        )
        AuthSurface.SignIn -> SignInScreen(
            state = state.authAccess,
            strings = strings,
            actions = remember(dependencies.authViewModel) { dependencies.authViewModel.signInActions() },
        )
        AuthSurface.PasswordRecovery -> PasswordRecoveryScreen(
            state = state.passwordRecovery,
            resendSecondsRemaining = state.authAccess.recoveryResendSecondsRemaining,
            strings = strings,
            actions = remember(dependencies.authViewModel) {
                dependencies.authViewModel.passwordRecoveryActions()
            },
        )
        AuthSurface.Hidden,
        AuthSurface.SoftWall,
        -> KwaborEntryContent(
            entry = state.onboardingEntry,
            state = state,
            strings = strings,
            dependencies = dependencies,
            onDeepLinkConsumed = onDeepLinkConsumed,
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
    val authViewModel: AuthViewModel,
    val onboardingViewModel: OnboardingViewModel,
    val legalDocumentLauncher: LegalDocumentLauncher,
)

internal data class KwaborAppRuntimeState(
    val pendingDeepLink: StateFlow<String?>,
    val onDeepLinkConsumed: () -> Unit,
)

private class KwaborCollectedState(
    val authentication: CollectedAuthenticationState,
    val onboarding: OnboardingUiState,
    val deepLink: String?,
) {
    val auth: AuthUiState get() = authentication.auth
    val authAccess: AuthAccessUiState get() = authentication.access
    val registration: RegistrationUiState get() = authentication.registration
    val passwordRecovery: PasswordRecoveryUiState get() = authentication.passwordRecovery
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
        )

    val onboardingEntry: OnboardingEntry
        get() = if (onboarding.isLaunchDecisionComplete) {
            OnboardingEntryResolver.resolve(
                firstLaunchCompleted = !onboarding.isIntroRequired,
                sessionRestoreCompleted = isSessionRestoreComplete,
                isAuthenticated = auth.isAuthenticated,
                guestAccessGranted = onboarding.isGuestSession,
            )
        } else {
            OnboardingEntry.RestoringSession
        }
}

private data class CollectedAuthenticationState(
    val auth: AuthUiState,
    val access: AuthAccessUiState,
    val registration: RegistrationUiState,
    val passwordRecovery: PasswordRecoveryUiState,
    val platform: AuthPlatformUiState,
    val isSessionRestoreComplete: Boolean,
)

private data class HomeShellDependencies(
    val exploreViewModel: ExploreViewModel,
    val authViewModel: AuthViewModel,
    val onboardingViewModel: OnboardingViewModel,
)

private object AuthEffectDispatcher {
    val dispatch: (AuthEffect, String?, HomeShellDependencies, RootEffectActions) -> Unit =
        { effect, pendingDestinationKey, dependencies, actions ->
            when (effect) {
                AuthEffect.AuthenticationCompleted -> {
                    val destination = pendingDestinationKey?.let(RootNavigationDestination::fromRouteKey)
                    if (destination == null) {
                        dependencies.exploreViewModel.onIntent(ExploreIntent.ReplayPendingInteraction)
                    } else {
                        actions.onAuthenticatedDestinationRequested(destination)
                    }
                    actions.onDestinationResolved()
                }
                AuthEffect.GuestContinuationSelected -> {
                    actions.onDestinationResolved()
                    dependencies.exploreViewModel.onIntent(ExploreIntent.ContinueAsGuest)
                }
                AuthEffect.SignedOut -> {
                    dependencies.onboardingViewModel.onIntent(OnboardingIntent.GuestConfirmed)
                    actions.onAuthenticatedDestinationRequested(RootNavigationDestination.Home)
                    actions.onDestinationResolved()
                    actions.onDeepLinkConsumed()
                    dependencies.exploreViewModel.onIntent(ExploreIntent.ContinueAsGuest)
                    dependencies.authViewModel.onIntent(AuthIntent.SignOutNavigationHandled)
                }
            }
        }
}

private data class HomeShellState(
    val auth: AuthUiState,
    val authAccess: AuthAccessUiState,
    val deepLink: String?,
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
    val authPlatformState by dependencies.authViewModel.platformState.collectAsStateWithLifecycle()
    val deepLink by runtimeState.pendingDeepLink.collectAsStateWithLifecycle()
    return KwaborCollectedState(
        authentication = CollectedAuthenticationState(
            auth = authState,
            access = authAccessState,
            registration = registrationState,
            passwordRecovery = passwordRecoveryState,
            platform = authPlatformState,
            isSessionRestoreComplete = restoreComplete,
        ),
        onboarding = onboardingState,
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
            dependencies = HomeShellDependencies(
                exploreViewModel = dependencies.exploreViewModel,
                authViewModel = dependencies.authViewModel,
                onboardingViewModel = dependencies.onboardingViewModel,
            ),
            state = HomeShellState(
                auth = state.auth,
                authAccess = state.authAccess,
                deepLink = state.deepLink.takeIf { state.isSessionRestoreComplete },
            ),
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
    dependencies: HomeShellDependencies,
    state: HomeShellState,
    onDeepLinkConsumed: () -> Unit,
) {
    val strings = stringsFor(AppLocale.French)
    val navController = rememberNavController()
    var pendingDestinationKey by rememberSaveable { mutableStateOf<String?>(null) }
    val requestDestination = rootDestinationRequester(
        navController,
        state.auth.isAuthenticated,
        dependencies.authViewModel,
        { destination -> pendingDestinationKey = destination.routeKey },
    )

    val effectActions = RootEffectActions(
        onDestinationRequested = requestDestination,
        onAuthenticatedDestinationRequested = navController::navigateToRoot,
        onDestinationResolved = { pendingDestinationKey = null },
        onDeepLinkConsumed = onDeepLinkConsumed,
    )
    DeepLinkEffectHandler(deepLink = state.deepLink, actions = effectActions)
    ExploreEffectHandler(dependencies = dependencies)
    AuthEffectHandler(
        pendingDestinationKey = pendingDestinationKey,
        dependencies = dependencies,
        actions = effectActions,
    )
    KwaborNavigationShell(
        navController = navController,
        strings = strings,
        dependencies = dependencies,
        state = state,
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
    dependencies: HomeShellDependencies,
    state: HomeShellState,
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
            strings = strings,
            dependencies = dependencies,
            state = state,
        )
    }
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
        composable<HomeRoute> {
            ExploreRoute(
                exploreViewModel = dependencies.exploreViewModel,
                strings = strings,
                isGuestSession = !state.auth.isAuthenticated,
                modifier = Modifier.padding(paddingValues),
            )
        }
        rootAnchorRoutes(paddingValues = paddingValues, strings = strings)
        composable<ProfileRoute> {
            ProfileSessionScreen(
                email = state.auth.currentSession?.email ?: strings.authConnectedSession,
                authAccessState = state.authAccess,
                strings = strings,
                actions = remember(dependencies.authViewModel) {
                    dependencies.authViewModel.profileSessionActions()
                },
                modifier = Modifier.padding(paddingValues),
            )
        }
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
private fun DeepLinkEffectHandler(deepLink: String?, actions: RootEffectActions) {
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(deepLink) {
        val currentDeepLink = deepLink ?: return@LaunchedEffect
        when (val result = RootDeepLinkParser.parse(currentDeepLink)) {
            is RootDeepLinkResult.Accepted -> currentActions.onDestinationRequested(result.destination)
            is RootDeepLinkResult.Rejected -> Unit
        }
        currentActions.onDeepLinkConsumed()
    }
}

@Composable
private fun ExploreEffectHandler(dependencies: HomeShellDependencies) {
    LaunchedEffect(dependencies.exploreViewModel, dependencies.authViewModel) {
        dependencies.exploreViewModel.effects.collect { effect ->
            when (effect) {
                ExploreEffect.AuthenticationRequired -> {
                    dependencies.authViewModel.onIntent(AuthIntent.OpenSoftWall)
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
    val onDestinationResolved: () -> Unit,
    val onDeepLinkConsumed: () -> Unit,
)

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
        actions = remember(exploreViewModel) { exploreViewModel.screenActions },
    )
}

private val ExploreViewModel.screenActions: ExploreScreenActions
    get() = ExploreScreenActions(
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
