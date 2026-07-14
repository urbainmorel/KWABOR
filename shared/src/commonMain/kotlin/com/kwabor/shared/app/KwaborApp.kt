package com.kwabor.shared.app

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kwabor.shared.design.KwaborTheme
import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthPresenter
import com.kwabor.shared.presentation.auth.AuthUiState
import com.kwabor.shared.presentation.auth.initialAuthUiState
import com.kwabor.shared.presentation.explore.ExploreInteractionKind
import com.kwabor.shared.presentation.explore.ExploreLoadRequest
import com.kwabor.shared.presentation.explore.ExplorePresenter
import com.kwabor.shared.presentation.explore.ExploreUiState
import com.kwabor.shared.presentation.explore.initialExploreUiState
import com.kwabor.shared.presentation.explore.loadingExploreUiState
import com.kwabor.shared.ui.screens.auth.AuthSheet
import com.kwabor.shared.ui.screens.auth.AuthSheetActions
import com.kwabor.shared.ui.screens.explore.ExploreScreen
import com.kwabor.shared.ui.screens.explore.ExploreScreenActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun KwaborApp(
    catalogRepository: CatalogRepository? = null,
    clockProvider: ClockProvider? = null,
    authRepository: AuthRepository? = null,
) {
    KwaborTheme {
        var selectedDestination by remember { mutableStateOf(RootDestination.Home) }
        val strings = stringsFor(AppLocale.French)

        Scaffold(
            bottomBar = {
                KwaborBottomNavigation(selectedDestination, strings) { destination ->
                    selectedDestination = destination
                }
            },
        ) { paddingValues ->
            when (selectedDestination) {
                RootDestination.Home -> ExploreRoute(
                    catalogRepository = catalogRepository,
                    clockProvider = clockProvider,
                    authRepository = authRepository,
                    strings = strings,
                    modifier = Modifier.padding(paddingValues),
                )
                else -> KwaborRootContent(
                    paddingValues = paddingValues,
                    title = selectedDestination.label(strings),
                    status = strings.foundationStatus,
                )
            }
        }
    }
}

@Composable
private fun KwaborBottomNavigation(
    selectedDestination: RootDestination,
    strings: KwaborStrings,
    onDestinationSelected: (RootDestination) -> Unit,
) {
    NavigationBar {
        RootDestination.entries.forEach { destination ->
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
    catalogRepository: CatalogRepository?,
    clockProvider: ClockProvider?,
    authRepository: AuthRepository?,
    strings: KwaborStrings,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val presenter = remember(catalogRepository, clockProvider) {
        if (catalogRepository != null && clockProvider != null) {
            ExplorePresenter(
                catalogRepository = catalogRepository,
                clockProvider = clockProvider,
            )
        } else {
            null
        }
    }
    val authPresenter = remember(authRepository) {
        authRepository?.let(::AuthPresenter)
    }
    val controller = remember(presenter, authPresenter, strings, coroutineScope) {
        ExploreRouteController(presenter, authPresenter, strings, coroutineScope)
    }

    LaunchedEffect(presenter, controller.request, controller.reloadTrigger, strings) {
        controller.loadExplore()
    }
    LaunchedEffect(authPresenter, strings) {
        controller.loadAuthSession()
    }

    ExploreScreen(
        state = controller.exploreState,
        strings = strings,
        isGuestSession = !controller.authState.isAuthenticated,
        modifier = modifier,
        actions = controller.exploreActions(),
    )
    if (controller.showAuthSheet && authPresenter != null) {
        AuthSheet(
            state = controller.authState,
            strings = strings,
            actions = controller.authActions(),
        )
    }
}

private class ExploreRouteController(
    private val explorePresenter: ExplorePresenter?,
    private val authPresenter: AuthPresenter?,
    private val strings: KwaborStrings,
    private val coroutineScope: CoroutineScope,
) {
    var request by mutableStateOf(ExploreLoadRequest())
    var reloadTrigger by mutableStateOf(0)
    var exploreState by mutableStateOf(initialExploreUiState(strings, request))
    var authState by mutableStateOf(initialAuthUiState())
    var showAuthSheet by mutableStateOf(false)

    suspend fun loadExplore() {
        val presenter = explorePresenter
        exploreState = if (presenter == null) {
            initialExploreUiState(strings, request)
        } else {
            exploreState = loadingExploreUiState(strings, request)
            presenter.load(request, strings)
        }
    }

    suspend fun loadAuthSession() {
        authPresenter?.let { presenter -> authState = presenter.loadCurrentSession(authState, strings) }
    }

    fun exploreActions(): ExploreScreenActions = ExploreScreenActions(
        onTabSelected = { selectedTab -> request = ExploreLoadRequest(selectedTab = selectedTab) },
        onChipSelected = { selectedChip -> request = request.copy(selectedChipId = selectedChip.id) },
        onRetry = { reloadTrigger += 1 },
        onLikeClick = { listingId -> toggleInteraction(listingId, ExploreInteractionKind.Like) },
        onFavoriteClick = { listingId -> toggleInteraction(listingId, ExploreInteractionKind.Favorite) },
    )

    fun authActions(): AuthSheetActions = AuthSheetActions(
        onDismiss = { showAuthSheet = false },
        onEmailChange = { email -> updateAuthState { presenter, state -> presenter.updateEmail(state, email) } },
        onFirstNameChange = { name -> updateAuthState { presenter, state -> presenter.updateFirstName(state, name) } },
        onLastNameChange = { name -> updateAuthState { presenter, state -> presenter.updateLastName(state, name) } },
        onOtpCodeChange = { code -> updateAuthState { presenter, state -> presenter.updateOtpCode(state, code) } },
        onLegalAcceptedChange = { accepted ->
            updateAuthState { presenter, state -> presenter.updateLegalAccepted(state, accepted) }
        },
        onRequestOtp = ::requestOtp,
        onVerifyOtp = ::verifyOtp,
        onContinueAsGuest = ::continueAsGuest,
    )

    private fun toggleInteraction(listingId: String, kind: ExploreInteractionKind) {
        val presenter = explorePresenter ?: return
        coroutineScope.launch {
            exploreState = presenter.toggle(exploreState, listingId, strings, kind)
            showAuthSheet = exploreState.pendingAuthInteraction != null && authPresenter != null
        }
    }

    private fun requestOtp() {
        val presenter = authPresenter ?: return
        coroutineScope.launch { authState = presenter.requestEmailOtp(authState, strings) }
    }

    private fun verifyOtp() {
        val presenter = authPresenter ?: return
        coroutineScope.launch {
            authState = presenter.verifyEmailOtpWithProfile(authState, strings)
            if (authState.isAuthenticated) {
                showAuthSheet = false
                replayPendingInteraction()
            }
        }
    }

    private fun replayPendingInteraction() {
        val pending = exploreState.pendingAuthInteraction ?: return
        toggleInteraction(pending.listingId, pending.kind)
    }

    private fun continueAsGuest() {
        showAuthSheet = false
        exploreState = exploreState.copy(pendingAuthInteraction = null, interactionMessage = null)
    }

    private fun updateAuthState(transform: (AuthPresenter, AuthUiState) -> AuthUiState) {
        val presenter = authPresenter ?: return
        authState = transform(presenter, authState)
    }
}

private suspend fun ExplorePresenter.toggle(
    state: ExploreUiState,
    listingId: String,
    strings: KwaborStrings,
    kind: ExploreInteractionKind,
): ExploreUiState = when (kind) {
    ExploreInteractionKind.Like -> toggleLike(state, listingId, strings)
    ExploreInteractionKind.Favorite -> toggleFavorite(state, listingId, strings)
}

@Composable
private fun KwaborRootContent(paddingValues: PaddingValues, title: String, status: String) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(all = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = title)
            Text(text = status)
        }
    }
}
