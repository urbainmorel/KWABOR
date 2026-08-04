package com.kwabor.shared.presentation.guide

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.guide.GuideDiscoveryFilters
import com.kwabor.shared.domain.guide.GuideDiscoveryRepository
import com.kwabor.shared.domain.guide.GuideFacet
import com.kwabor.shared.domain.guide.GuideFacetType
import com.kwabor.shared.domain.guide.GuidePageRequest
import com.kwabor.shared.domain.guide.GuideSummary
import com.kwabor.shared.i18n.GuideDiscoveryStrings
import kotlin.math.roundToInt

private const val RATING_DECIMAL_SCALE = 10
private const val RATING_DECIMAL_DIVISOR = 10.0

class GuideDiscoveryPresenter(
    private val repository: GuideDiscoveryRepository,
) {
    suspend fun load(filters: GuideDiscoveryFilters, strings: GuideDiscoveryStrings): GuideDiscoveryUiState {
        val facets = when (val result = repository.listFacets()) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return initialFailure(filters, strings, result.error)
        }
        val normalizedFilters = filters.retainAvailable(facets)
        return when (
            val result = repository.listServices(
                filters = normalizedFilters,
                page = GuidePageRequest(),
            )
        ) {
            is DomainResult.Success -> GuideDiscoveryUiState(
                filters = normalizedFilters,
                cityOptions = facets.options(GuideFacetType.City),
                languageOptions = facets.options(GuideFacetType.Language),
                specialtyOptions = facets.options(GuideFacetType.Specialty),
                guides = result.value.items.map(GuideSummary::toUiModel),
                nextCursor = result.value.nextCursor,
                resultCountLabel = result.value.items.size.toResultCountLabel(strings),
                isLoading = false,
            )
            is DomainResult.Failure -> initialFailure(
                filters = normalizedFilters,
                strings = strings,
                error = result.error,
                facets = facets,
            )
        }
    }

    suspend fun refresh(state: GuideDiscoveryUiState, strings: GuideDiscoveryStrings): GuideDiscoveryUiState {
        val refreshed = load(filters = state.filters, strings = strings)
        return if (refreshed.errorMessage == null) {
            refreshed
        } else if (state.guides.isEmpty()) {
            refreshed.copy(isRefreshing = false)
        } else {
            state.copy(
                isLoading = false,
                isRefreshing = false,
                isAppending = false,
                isOffline = refreshed.isOffline,
                errorMessage = null,
                refreshMessage = strings.refreshFailed,
                appendErrorMessage = null,
            )
        }
    }

    suspend fun append(state: GuideDiscoveryUiState, strings: GuideDiscoveryStrings): GuideDiscoveryUiState {
        val cursor = state.nextCursor ?: return state.copy(isAppending = false)
        return when (
            val result = repository.listServices(
                filters = state.filters,
                page = GuidePageRequest(cursor = cursor),
            )
        ) {
            is DomainResult.Success -> {
                val existingIds = state.guides.mapTo(mutableSetOf(), GuideSummaryUiModel::id)
                val appendedItems = result.value.items.map(GuideSummary::toUiModel)
                if (result.value.nextCursor == cursor) {
                    state.appendFailure(strings, DomainError.Unexpected())
                } else if (appendedItems.any { item -> item.id in existingIds }) {
                    state.appendFailure(strings, DomainError.Unexpected())
                } else {
                    val merged = state.guides + appendedItems
                    state.copy(
                        guides = merged,
                        nextCursor = result.value.nextCursor,
                        resultCountLabel = merged.size.toResultCountLabel(strings),
                        isAppending = false,
                        isOffline = false,
                        appendErrorMessage = null,
                    )
                }
            }
            is DomainResult.Failure -> state.appendFailure(strings, result.error)
        }
    }
}

private fun initialFailure(
    filters: GuideDiscoveryFilters,
    strings: GuideDiscoveryStrings,
    error: DomainError,
    facets: List<GuideFacet> = emptyList(),
): GuideDiscoveryUiState = GuideDiscoveryUiState(
    filters = filters,
    cityOptions = facets.options(GuideFacetType.City),
    languageOptions = facets.options(GuideFacetType.Language),
    specialtyOptions = facets.options(GuideFacetType.Specialty),
    isLoading = false,
    isOffline = error is DomainError.NetworkUnavailable,
    errorMessage = strings.loadFailed,
)

private fun GuideDiscoveryUiState.appendFailure(
    strings: GuideDiscoveryStrings,
    error: DomainError,
): GuideDiscoveryUiState = copy(
    isAppending = false,
    isOffline = error is DomainError.NetworkUnavailable,
    appendErrorMessage = strings.loadMoreFailed,
)

private fun GuideDiscoveryFilters.retainAvailable(facets: List<GuideFacet>): GuideDiscoveryFilters = copy(
    cityId = cityId?.takeIf { id -> facets.contains(GuideFacetType.City, id) },
    languageId = languageId?.takeIf { id -> facets.contains(GuideFacetType.Language, id) },
    specialtyId = specialtyId?.takeIf { id -> facets.contains(GuideFacetType.Specialty, id) },
)

private fun List<GuideFacet>.contains(type: GuideFacetType, id: String): Boolean =
    any { facet -> facet.type == type && facet.id == id }

private fun List<GuideFacet>.options(type: GuideFacetType): List<GuideFilterOptionUiModel> = asSequence()
    .filter { facet -> facet.type == type }
    .map { facet -> GuideFilterOptionUiModel(id = facet.id, label = facet.label) }
    .toList()

private fun GuideSummary.toUiModel(): GuideSummaryUiModel = GuideSummaryUiModel(
    id = id,
    title = name,
    baseCityLabel = baseCityName,
    coverImageUrl = coverImageUrl,
    coverImageAlt = coverImageAlt,
    languages = languages.map(GuideFacet::label),
    coverageCities = coverageCities.map(GuideFacet::label),
    specialties = specialties.map(GuideFacet::label),
    indicativePrice = indicativePriceXof,
    ratingLabel = ratingAverage?.toRatingLabel(),
    ratingCount = ratingCount,
    verified = verified,
)

private fun Int.toResultCountLabel(strings: GuideDiscoveryStrings): String = when (this) {
    1 -> strings.oneResult
    else -> strings.manyResults.replace("{count}", toString())
}

private fun Double.toRatingLabel(): String {
    val rounded = (this * RATING_DECIMAL_SCALE).roundToInt() / RATING_DECIMAL_DIVISOR
    return rounded.toString().replace(oldChar = '.', newChar = ',')
}
