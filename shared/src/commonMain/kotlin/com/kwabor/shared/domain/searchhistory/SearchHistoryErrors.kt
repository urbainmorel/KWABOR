package com.kwabor.shared.domain.searchhistory

import com.kwabor.shared.domain.core.DomainError

object SearchHistoryErrors {
    val invalidSubmittedQuery: DomainError.Validation =
        DomainError.Validation("error.search_history.query_invalid")
    val invalidAccountScope: DomainError.Validation =
        DomainError.Validation("error.search_history.account_scope_invalid")
    val invalidEntryId: DomainError.Validation =
        DomainError.Validation("error.search_history.entry_id_invalid")
    val importConfirmationRequired: DomainError.Validation =
        DomainError.Validation("error.search_history.import_confirmation_required")
    val entryNotFound: DomainError.NotFound =
        DomainError.NotFound("error.search_history.entry_not_found")
    val authenticationRequired: DomainError.AuthenticationRequired =
        DomainError.AuthenticationRequired("error.search_history.authentication_required")
    val synchronizationUnavailable: DomainError.NetworkUnavailable =
        DomainError.NetworkUnavailable("error.search_history.sync_unavailable")
    val localStorageUnavailable: DomainError.LocalStorageUnavailable =
        DomainError.LocalStorageUnavailable("error.search_history.storage_unavailable")
    val unexpected: DomainError.Unexpected =
        DomainError.Unexpected("error.search_history.unexpected")
}
