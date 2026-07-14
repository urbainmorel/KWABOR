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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kwabor.android.design.KwaborTheme
import com.kwabor.android.presentation.auth.AuthEffect
import com.kwabor.android.presentation.auth.AuthIntent
import com.kwabor.android.presentation.auth.AuthViewModel
import com.kwabor.android.presentation.explore.ExploreEffect
import com.kwabor.android.presentation.explore.ExploreIntent
import com.kwabor.android.presentation.explore.ExploreViewModel
import com.kwabor.android.ui.components.KwaborStateMessage
import com.kwabor.android.ui.screens.auth.AuthSheet
import com.kwabor.android.ui.screens.auth.AuthSheetActions
import com.kwabor.android.ui.screens.explore.ExploreScreen
import com.kwabor.android.ui.screens.explore.ExploreScreenActions
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.i18n.stringsFor

@Composable
internal fun KwaborApp(exploreViewModel: ExploreViewModel, authViewModel: AuthViewModel) {
    val strings = stringsFor(AppLocale.French)

    KwaborTheme {
        var selectedDestination by remember { mutableStateOf(RootDestination.Home) }

        Scaffold(
            bottomBar = {
                KwaborBottomNavigation(selectedDestination, strings) { destination ->
                    selectedDestination = destination
                }
            },
        ) { paddingValues ->
            when (selectedDestination) {
                RootDestination.Home -> ExploreRoute(
                    exploreViewModel = exploreViewModel,
                    authViewModel = authViewModel,
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
    exploreViewModel: ExploreViewModel,
    authViewModel: AuthViewModel,
    strings: KwaborStrings,
    modifier: Modifier = Modifier,
) {
    val exploreState by exploreViewModel.state.collectAsStateWithLifecycle()
    val authState by authViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(exploreViewModel, authViewModel) {
        exploreViewModel.effects.collect { effect ->
            when (effect) {
                ExploreEffect.AuthenticationRequired -> authViewModel.onIntent(AuthIntent.Open)
            }
        }
    }
    LaunchedEffect(exploreViewModel, authViewModel) {
        authViewModel.effects.collect { effect ->
            when (effect) {
                AuthEffect.AuthenticationCompleted -> {
                    exploreViewModel.onIntent(ExploreIntent.ReplayPendingInteraction)
                }
                AuthEffect.GuestContinuationSelected -> exploreViewModel.onIntent(ExploreIntent.ContinueAsGuest)
            }
        }
    }

    ExploreScreen(
        state = exploreState,
        strings = strings,
        isGuestSession = !authState.isAuthenticated,
        modifier = modifier,
        actions = remember(exploreViewModel) { exploreViewModel.screenActions() },
    )
    if (authState.isVisible) {
        AuthSheet(
            state = authState,
            strings = strings,
            actions = remember(authViewModel) { authViewModel.sheetActions() },
        )
    }
}

private fun ExploreViewModel.screenActions(): ExploreScreenActions = ExploreScreenActions(
    onTabSelected = { tab -> onIntent(ExploreIntent.SelectTab(tab)) },
    onChipSelected = { chip -> onIntent(ExploreIntent.SelectChip(chip)) },
    onRetry = { onIntent(ExploreIntent.Retry) },
    onLikeClick = { listingId -> onIntent(ExploreIntent.ToggleLike(listingId)) },
    onFavoriteClick = { listingId -> onIntent(ExploreIntent.ToggleFavorite(listingId)) },
)

private fun AuthViewModel.sheetActions(): AuthSheetActions = AuthSheetActions(
    onDismiss = { onIntent(AuthIntent.Dismiss) },
    onEmailChange = { email -> onIntent(AuthIntent.ChangeEmail(email)) },
    onFirstNameChange = { firstName -> onIntent(AuthIntent.ChangeFirstName(firstName)) },
    onLastNameChange = { lastName -> onIntent(AuthIntent.ChangeLastName(lastName)) },
    onOtpCodeChange = { code -> onIntent(AuthIntent.ChangeOtpCode(code)) },
    onLegalAcceptedChange = { accepted -> onIntent(AuthIntent.ChangeLegalAccepted(accepted)) },
    onRequestOtp = { onIntent(AuthIntent.RequestOtp) },
    onVerifyOtp = { onIntent(AuthIntent.VerifyOtp) },
    onContinueAsGuest = { onIntent(AuthIntent.ContinueAsGuest) },
)

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
