package com.kwabor.android.ui.screens.detail

import com.kwabor.android.detail.DetailExternalAction
import com.kwabor.android.detail.DetailExternalActionLauncher
import com.kwabor.android.media.ListingMediaUrlPolicy

data class CatalogDetailSheetActions(
    val onDismiss: () -> Unit,
    val onRetry: () -> Unit,
    val onMediaSelected: (Int) -> Unit,
    val onDescriptionToggle: () -> Unit,
)

internal data class CatalogDetailPlatformDependencies(
    val mediaUrlPolicy: ListingMediaUrlPolicy,
    val externalActionLauncher: DetailExternalActionLauncher,
)

internal data class CatalogDetailExternalActionCallbacks(
    val onLaunch: (DetailExternalAction) -> Unit,
    val onContactRequested: () -> Unit,
)

internal data class CatalogDetailExternalActionPresentation(
    val model: CatalogDetailExternalActionUiModel,
    val callbacks: CatalogDetailExternalActionCallbacks,
)
