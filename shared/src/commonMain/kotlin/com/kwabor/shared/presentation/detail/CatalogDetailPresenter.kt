package com.kwabor.shared.presentation.detail

import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.catalog.CatalogQueryRepository
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.i18n.KwaborStrings

class CatalogDetailPresenter(
    private val catalogRepository: CatalogQueryRepository,
    private val clockProvider: ClockProvider,
) {
    suspend fun load(listingId: String, strings: KwaborStrings): CatalogDetailUiState =
        loadPresentation(listingId, strings).state

    internal suspend fun loadPresentation(listingId: String, strings: KwaborStrings): LoadedCatalogDetailPresentation {
        val normalizedListingId = listingId.trim()
        return when (val result = catalogRepository.getListingDetail(normalizedListingId)) {
            is DomainResult.Success -> LoadedCatalogDetailPresentation(
                source = result.value,
                state = present(result.value, strings),
            )
            is DomainResult.Failure -> LoadedCatalogDetailPresentation(
                source = null,
                state = result.error.toCatalogDetailFailure(normalizedListingId, strings.detail),
            )
        }
    }

    internal fun present(detail: CatalogDetail, strings: KwaborStrings): CatalogDetailUiState.Content =
        detail.toContentState(strings, clockProvider.nowEpochMilliseconds())
}

internal data class LoadedCatalogDetailPresentation(
    val source: CatalogDetail?,
    val state: CatalogDetailUiState,
)

private fun CatalogDetail.toContentState(
    strings: KwaborStrings,
    nowEpochMilliseconds: Long,
): CatalogDetailUiState.Content {
    val model = toCatalogDetailUiModel(strings, nowEpochMilliseconds)
    val selectedMediaIndex = model.media.indexOfFirst { media -> media.isCover }.coerceAtLeast(0)
    return CatalogDetailUiState.Content(
        model = model,
        selectedMediaIndex = selectedMediaIndex,
    )
}
