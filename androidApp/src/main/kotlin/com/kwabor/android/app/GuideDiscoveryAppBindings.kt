package com.kwabor.android.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.kwabor.android.media.ListingMediaUrlPolicy
import com.kwabor.android.presentation.detail.CatalogDetailViewModel
import com.kwabor.android.presentation.guide.GuideDiscoveryViewModel
import com.kwabor.android.ui.screens.guide.GuideDiscoveryScreen
import com.kwabor.android.ui.screens.guide.GuideDiscoveryScreenActions
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.detail.CatalogDetailIntent
import com.kwabor.shared.presentation.guide.GuideDiscoveryEffect
import com.kwabor.shared.presentation.guide.GuideDiscoveryIntent

internal fun GuideDiscoveryViewModel.screenActions(onBack: () -> Unit): GuideDiscoveryScreenActions =
    GuideDiscoveryScreenActions(
        onBack = onBack,
        onRetry = { onIntent(GuideDiscoveryIntent.Retry) },
        onRefresh = { onIntent(GuideDiscoveryIntent.Refresh) },
        onLoadNext = { onIntent(GuideDiscoveryIntent.LoadNext) },
        onCitySelected = { cityId -> onIntent(GuideDiscoveryIntent.SelectCity(cityId)) },
        onLanguageSelected = { languageId -> onIntent(GuideDiscoveryIntent.SelectLanguage(languageId)) },
        onSpecialtySelected = { specialtyId -> onIntent(GuideDiscoveryIntent.SelectSpecialty(specialtyId)) },
        onResetFilters = { onIntent(GuideDiscoveryIntent.ClearFilters) },
        onGuideClick = { guideId -> onIntent(GuideDiscoveryIntent.OpenGuide(guideId)) },
    )

@Composable
internal fun GuideDiscoveryEffectHandler(
    guideDiscoveryViewModel: GuideDiscoveryViewModel,
    catalogDetailViewModel: CatalogDetailViewModel,
) {
    LaunchedEffect(guideDiscoveryViewModel, catalogDetailViewModel) {
        guideDiscoveryViewModel.effects.collect { effect ->
            when (effect) {
                is GuideDiscoveryEffect.OpenCatalogDetail -> {
                    catalogDetailViewModel.onIntent(CatalogDetailIntent.Open(effect.listingId))
                }
            }
        }
    }
}

internal fun NavGraphBuilder.guideDiscoveryChildRoute(
    navController: NavHostController,
    viewModel: GuideDiscoveryViewModel,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    paddingValues: PaddingValues,
) {
    composable<GuideDiscoveryRoute> {
        GuideDiscoveryScreenRoute(
            navController = navController,
            viewModel = viewModel,
            strings = strings,
            mediaUrlPolicy = mediaUrlPolicy,
            modifier = Modifier.padding(bottom = paddingValues.calculateBottomPadding()),
        )
    }
}

@Composable
private fun GuideDiscoveryScreenRoute(
    navController: NavHostController,
    viewModel: GuideDiscoveryViewModel,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    modifier: Modifier = Modifier,
) {
    val guideState by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.onIntent(GuideDiscoveryIntent.Start)
    }
    GuideDiscoveryScreen(
        state = guideState,
        strings = strings,
        mediaUrlPolicy = mediaUrlPolicy,
        actions = remember(viewModel, navController) {
            viewModel.screenActions(onBack = { navController.popBackStack() })
        },
        modifier = modifier,
    )
}
