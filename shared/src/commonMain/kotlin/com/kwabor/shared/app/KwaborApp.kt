package com.kwabor.shared.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kwabor.shared.design.KwaborTheme
import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthPresenter
import com.kwabor.shared.presentation.auth.initialAuthUiState
import com.kwabor.shared.presentation.explore.ExploreInteractionKind
import com.kwabor.shared.presentation.explore.ExploreLoadRequest
import com.kwabor.shared.presentation.explore.ExplorePresenter
import com.kwabor.shared.presentation.explore.initialExploreUiState
import com.kwabor.shared.presentation.explore.loadingExploreUiState
import com.kwabor.shared.ui.screens.auth.AuthSheet
import com.kwabor.shared.ui.screens.explore.ExploreScreen
import kotlinx.coroutines.launch

enum class RootDestination {
    Home,
    Social,
    Add,
    Notifications,
    Profile,
}

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
                NavigationBar {
                    RootDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = selectedDestination == destination,
                            onClick = { selectedDestination = destination },
                            icon = {
                                Icon(
                                    imageVector = destination.icon(),
                                    contentDescription = destination.label(strings),
                                )
                            },
                            label = { Text(text = destination.label(strings)) },
                        )
                    }
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
private fun ExploreRoute(
    catalogRepository: CatalogRepository?,
    clockProvider: ClockProvider?,
    authRepository: AuthRepository?,
    strings: com.kwabor.shared.i18n.KwaborStrings,
    modifier: Modifier = Modifier,
) {
    var request by remember { mutableStateOf(ExploreLoadRequest()) }
    var reloadTrigger by remember { mutableStateOf(0) }
    var state by remember(strings) { mutableStateOf(initialExploreUiState(strings = strings, request = request)) }
    var authState by remember(strings) { mutableStateOf(initialAuthUiState()) }
    var showAuthSheet by remember { mutableStateOf(false) }
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

    LaunchedEffect(presenter, request, reloadTrigger, strings) {
        state = if (presenter == null) {
            initialExploreUiState(strings = strings, request = request)
        } else {
            state = loadingExploreUiState(strings = strings, request = request)
            presenter.load(request = request, strings = strings)
        }
    }

    LaunchedEffect(authPresenter, strings) {
        authPresenter?.let { activePresenter ->
            authState = activePresenter.loadCurrentSession(authState, strings)
        }
    }

    fun runExploreInteraction(listingId: String, kind: ExploreInteractionKind) {
        val activePresenter = presenter ?: return
        coroutineScope.launch {
            val updatedState = when (kind) {
                ExploreInteractionKind.Like -> activePresenter.toggleLike(
                    state = state,
                    listingId = listingId,
                    strings = strings,
                )
                ExploreInteractionKind.Favorite -> activePresenter.toggleFavorite(
                    state = state,
                    listingId = listingId,
                    strings = strings,
                )
            }
            state = updatedState
            if (updatedState.pendingAuthInteraction != null && authPresenter != null) {
                showAuthSheet = true
            }
        }
    }

    fun replayPendingInteractionAfterAuth() {
        val pendingInteraction = state.pendingAuthInteraction ?: return
        val activePresenter = presenter ?: return
        coroutineScope.launch {
            state = when (pendingInteraction.kind) {
                ExploreInteractionKind.Like -> activePresenter.toggleLike(
                    state = state,
                    listingId = pendingInteraction.listingId,
                    strings = strings,
                )
                ExploreInteractionKind.Favorite -> activePresenter.toggleFavorite(
                    state = state,
                    listingId = pendingInteraction.listingId,
                    strings = strings,
                )
            }
        }
    }

    ExploreScreen(
        state = state,
        strings = strings,
        isGuestSession = !authState.isAuthenticated,
        modifier = modifier,
        onTabSelected = { selectedTab ->
            request = ExploreLoadRequest(selectedTab = selectedTab)
        },
        onChipSelected = { selectedChip ->
            request = request.copy(selectedChipId = selectedChip.id)
        },
        onRetry = {
            reloadTrigger += 1
        },
        onLikeClick = { listingId ->
            runExploreInteraction(listingId = listingId, kind = ExploreInteractionKind.Like)
        },
        onFavoriteClick = { listingId ->
            runExploreInteraction(listingId = listingId, kind = ExploreInteractionKind.Favorite)
        },
    )

    if (showAuthSheet && authPresenter != null) {
        AuthSheet(
            state = authState,
            strings = strings,
            onDismiss = { showAuthSheet = false },
            onEmailChange = { email -> authState = authPresenter.updateEmail(authState, email) },
            onFirstNameChange = { firstName -> authState = authPresenter.updateFirstName(authState, firstName) },
            onLastNameChange = { lastName -> authState = authPresenter.updateLastName(authState, lastName) },
            onOtpCodeChange = { otpCode -> authState = authPresenter.updateOtpCode(authState, otpCode) },
            onLegalAcceptedChange = { accepted ->
                authState = authPresenter.updateLegalAccepted(authState, accepted)
            },
            onRequestOtp = {
                coroutineScope.launch {
                    authState = authPresenter.requestEmailOtp(authState, strings)
                }
            },
            onVerifyOtp = {
                coroutineScope.launch {
                    val updatedAuthState = authPresenter.verifyEmailOtpWithProfile(authState, strings)
                    authState = updatedAuthState
                    if (updatedAuthState.isAuthenticated) {
                        showAuthSheet = false
                        replayPendingInteractionAfterAuth()
                    }
                }
            },
            onContinueAsGuest = {
                showAuthSheet = false
                state = state.copy(pendingAuthInteraction = null, interactionMessage = null)
            },
        )
    }
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

private fun RootDestination.label(strings: com.kwabor.shared.i18n.KwaborStrings): String = when (this) {
    RootDestination.Home -> strings.home
    RootDestination.Social -> strings.social
    RootDestination.Add -> strings.add
    RootDestination.Notifications -> strings.notifications
    RootDestination.Profile -> strings.profile
}

private fun RootDestination.icon(): ImageVector = when (this) {
    RootDestination.Home -> Icons.Filled.Explore
    RootDestination.Social -> Icons.Filled.PlayCircle
    RootDestination.Add -> Icons.Filled.AddCircle
    RootDestination.Notifications -> Icons.Filled.Notifications
    RootDestination.Profile -> Icons.Filled.Person
}
