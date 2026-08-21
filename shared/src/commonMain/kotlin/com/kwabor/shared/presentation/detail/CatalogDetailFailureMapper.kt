package com.kwabor.shared.presentation.detail

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.i18n.CatalogDetailStrings

internal fun DomainError.toCatalogDetailFailure(
    listingId: String,
    openRequestId: CatalogDetailOpenRequestId,
    strings: CatalogDetailStrings,
): CatalogDetailUiState = when (this) {
    is DomainError.NotFound -> CatalogDetailUiState.NotFound(listingId, openRequestId, strings.unavailable)
    is DomainError.NetworkUnavailable ->
        CatalogDetailUiState.OfflineFailure(listingId, openRequestId, strings.offlineUnavailable)
    is DomainError.AuthenticationRequired,
    is DomainError.LocalStorageUnavailable,
    is DomainError.PermissionDenied,
    is DomainError.Unexpected,
    is DomainError.Validation,
    -> CatalogDetailUiState.Failure(listingId, openRequestId, strings.loadFailed)
}
