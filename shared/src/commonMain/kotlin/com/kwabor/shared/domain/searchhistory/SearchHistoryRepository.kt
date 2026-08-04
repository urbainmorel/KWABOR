package com.kwabor.shared.domain.searchhistory

import com.kwabor.shared.domain.core.DomainResult

interface SearchHistoryRepository {
    suspend fun loadRecent(scope: SearchHistoryScope): DomainResult<SearchHistorySnapshot>

    /**
     * Records a submitted query. Repeating the same canonical query keeps the entry identity and updates
     * [SearchHistoryEntry.lastSubmittedAtEpochMilliseconds].
     */
    suspend fun recordSubmittedQuery(request: RecordSubmittedSearchRequest): DomainResult<SearchHistoryEntry>

    suspend fun deleteEntry(scope: SearchHistoryScope, entryId: SearchHistoryEntryId): DomainResult<Unit>

    suspend fun clear(scope: SearchHistoryScope): DomainResult<Unit>

    suspend fun importGuestHistory(request: GuestHistoryImportRequest): DomainResult<SearchHistorySnapshot>

    suspend fun setActivityPersonalization(
        scope: SearchHistoryScope,
        enabled: Boolean,
    ): DomainResult<SearchHistoryPreferences>
}
