package com.kwabor.android.ui.screens.detail

data class CatalogDetailSheetActions(
    val onDismiss: () -> Unit,
    val onRetry: () -> Unit,
    val onMediaSelected: (Int) -> Unit,
    val onDescriptionToggle: () -> Unit,
)
