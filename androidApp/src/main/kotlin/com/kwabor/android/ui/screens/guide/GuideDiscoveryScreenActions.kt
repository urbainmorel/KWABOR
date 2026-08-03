package com.kwabor.android.ui.screens.guide

internal data class GuideDiscoveryScreenActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onRefresh: () -> Unit,
    val onLoadNext: () -> Unit,
    val onCitySelected: (String?) -> Unit,
    val onLanguageSelected: (String?) -> Unit,
    val onSpecialtySelected: (String?) -> Unit,
    val onResetFilters: () -> Unit,
    val onGuideClick: (String) -> Unit,
)
