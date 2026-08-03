package com.kwabor.shared.presentation.guide

import com.kwabor.shared.domain.guide.GuideDiscoveryFilters
import com.kwabor.shared.domain.money.MoneyXof

sealed interface GuideDiscoveryIntent {
    data object Start : GuideDiscoveryIntent

    data object Retry : GuideDiscoveryIntent

    data object Refresh : GuideDiscoveryIntent

    data object LoadNext : GuideDiscoveryIntent

    data class SelectCity(val cityId: String?) : GuideDiscoveryIntent

    data class SelectLanguage(val languageId: String?) : GuideDiscoveryIntent

    data class SelectSpecialty(val specialtyId: String?) : GuideDiscoveryIntent

    data object ClearFilters : GuideDiscoveryIntent

    data class OpenGuide(val guideId: String) : GuideDiscoveryIntent
}

sealed interface GuideDiscoveryEffect {
    data class OpenCatalogDetail(val listingId: String) : GuideDiscoveryEffect
}

data class GuideFilterOptionUiModel(
    val id: String,
    val label: String,
)

data class GuideSummaryUiModel(
    val id: String,
    val title: String,
    val baseCityLabel: String,
    val coverImageUrl: String,
    val coverImageAlt: String,
    val languages: List<String>,
    val coverageCities: List<String>,
    val specialties: List<String>,
    val indicativePrice: MoneyXof,
    val ratingLabel: String?,
    val ratingCount: Int,
    val verified: Boolean,
)

data class GuideDiscoveryUiState(
    val filters: GuideDiscoveryFilters = GuideDiscoveryFilters(),
    val cityOptions: List<GuideFilterOptionUiModel> = emptyList(),
    val languageOptions: List<GuideFilterOptionUiModel> = emptyList(),
    val specialtyOptions: List<GuideFilterOptionUiModel> = emptyList(),
    val guides: List<GuideSummaryUiModel> = emptyList(),
    val nextCursor: String? = null,
    val resultCountLabel: String = "",
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isAppending: Boolean = false,
    val isOffline: Boolean = false,
    val errorMessage: String? = null,
    val refreshMessage: String? = null,
    val appendErrorMessage: String? = null,
) {
    val isEmpty: Boolean
        get() = !isLoading && errorMessage == null && guides.isEmpty()

    val hasActiveFilters: Boolean
        get() = filters.cityId != null || filters.languageId != null || filters.specialtyId != null

    val canLoadMore: Boolean
        get() = nextCursor != null && !isLoading && !isRefreshing && !isAppending
}
