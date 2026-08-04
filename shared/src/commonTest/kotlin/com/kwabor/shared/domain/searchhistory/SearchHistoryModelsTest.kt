package com.kwabor.shared.domain.searchhistory

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.search.SearchQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SearchHistoryModelsTest {
    @Test
    fun historyQueryValidationAndCanonicalizationStayInParityWithSearch() {
        val inputs = listOf(
            "a",
            "  Restaurant à Cotonou  ",
            "a".repeat(120),
            "   ",
            "a".repeat(121),
            "restaurant\nCotonou",
        )

        inputs.forEach { input ->
            val searchResult = SearchQuery.from(input)
            val historyResult = SubmittedSearchQuery.from(input)
            assertEquals(
                searchResult is DomainResult.Success,
                historyResult is DomainResult.Success,
                "Search and history validation differ for an input of length ${input.length}.",
            )
            if (searchResult is DomainResult.Success && historyResult is DomainResult.Success) {
                assertEquals(searchResult.value.text, historyResult.value.text)
            }
        }
    }

    @Test
    fun submittedQueryCanonicalizesValidTextAndRedactsItsRepresentation() {
        val result = SubmittedSearchQuery.from("  Restaurant calme à Cotonou  ")

        val query = assertIs<DomainResult.Success<SubmittedSearchQuery>>(result).value
        assertEquals("Restaurant calme à Cotonou", query.text)
        assertEquals("SubmittedSearchQuery(text=<redacted>)", query.toString())
    }

    @Test
    fun submittedQueryRejectsTextThatSearchCannotSubmit() {
        val invalidQueries = listOf(
            "   ",
            "a".repeat(121),
            "restaurant\nCotonou",
        )

        invalidQueries.forEach { text ->
            val failure = assertIs<DomainResult.Failure>(SubmittedSearchQuery.from(text))
            assertEquals(SearchHistoryErrors.invalidSubmittedQuery, failure.error)
        }
    }

    @Test
    fun authenticatedScopeCanonicalizesAnOpaqueIdAndRedactsIt() {
        val result = SearchHistoryScope.Authenticated.from("  user_123  ")

        val scope = assertIs<DomainResult.Success<SearchHistoryScope.Authenticated>>(result).value
        assertEquals("user_123", scope.userId)
        assertFalse(scope.toString().contains("user_123"))
        assertEquals(scope, authenticatedScope("user_123"))
    }

    @Test
    fun authenticatedScopeRejectsBlankOversizedOrUnsafeIds() {
        val invalidIds = listOf(
            "   ",
            "../account",
            "account id",
            "a".repeat(MAX_SEARCH_HISTORY_IDENTIFIER_LENGTH + 1),
        )

        invalidIds.forEach { userId ->
            val failure = assertIs<DomainResult.Failure>(SearchHistoryScope.Authenticated.from(userId))
            assertEquals(SearchHistoryErrors.invalidAccountScope, failure.error)
        }
    }

    @Test
    fun entryIdCanonicalizesSafeIdsAndRejectsUnsafeIds() {
        val entryId = assertIs<DomainResult.Success<SearchHistoryEntryId>>(
            SearchHistoryEntryId.from("  entry-123  "),
        ).value

        assertEquals("entry-123", entryId.value)
        assertFalse(entryId.toString().contains("entry-123"))
        listOf("", "entry/id", "entry id").forEach { unsafeId ->
            val failure = assertIs<DomainResult.Failure>(SearchHistoryEntryId.from(unsafeId))
            assertEquals(SearchHistoryErrors.invalidEntryId, failure.error)
        }
    }

    @Test
    fun entryRejectsNegativeAuthoritativeTimestamps() {
        assertFailsWith<IllegalArgumentException> {
            historyEntry(index = 1, lastSubmittedAtEpochMilliseconds = -1)
        }
    }

    @Test
    fun sensitiveModelsNeverRenderTheRawQueryOrAccountId() {
        val scope = authenticatedScope("private-user-id")
        val query = submittedQuery("restaurant secret")
        val request = RecordSubmittedSearchRequest(
            scope = scope,
            query = query,
        )
        val snapshot = SearchHistorySnapshot(
            scope = scope,
            entries = listOf(
                SearchHistoryEntry(
                    id = entryId("entry-private"),
                    scope = scope,
                    query = query,
                    lastSubmittedAtEpochMilliseconds = 1_000,
                ),
            ),
            preferences = SearchHistoryPreferences(activityPersonalizationEnabled = true),
        )

        listOf(request.toString(), snapshot.toString()).forEach { rendered ->
            assertFalse(rendered.contains("restaurant secret"))
            assertFalse(rendered.contains("private-user-id"))
        }
    }

    @Test
    fun policyBoundsHistoryAndKeepsPersonalizationDisabledByDefault() {
        val disabled = SearchHistoryPolicy.defaultPreferences()
        val enabled = SearchHistoryPreferences(activityPersonalizationEnabled = true)

        assertEquals(200, SearchHistoryPolicy.MAX_SERVER_ACTIVE_QUERIES_PER_ACCOUNT)
        assertEquals(50, SearchHistoryPolicy.MAX_LOCAL_QUERIES_PER_SCOPE)
        assertFalse(disabled.activityPersonalizationEnabled)
        assertTrue(enabled.activityPersonalizationEnabled)
    }

    @Test
    fun snapshotDefensivelyCopiesEntriesBeforeStorageAndValidation() {
        val originalEntry = historyEntry(index = 1, lastSubmittedAtEpochMilliseconds = 1_000)
        val sourceEntries = mutableListOf(originalEntry)
        val snapshot = SearchHistorySnapshot(
            scope = SearchHistoryScope.Guest,
            entries = sourceEntries,
            preferences = SearchHistoryPreferences(activityPersonalizationEnabled = true),
        )

        sourceEntries.clear()
        sourceEntries += historyEntry(
            index = 2,
            scope = authenticatedScope("account-2"),
            lastSubmittedAtEpochMilliseconds = 2_000,
        )

        assertEquals(listOf(originalEntry), snapshot.entries)
    }

    @Test
    fun snapshotRejectsCrossScopeDuplicatesAndInvalidOrdering() {
        val accountEntry = historyEntry(
            index = 1,
            scope = authenticatedScope("account-1"),
            lastSubmittedAtEpochMilliseconds = 1_000,
        )
        assertFailsWith<IllegalArgumentException> {
            SearchHistorySnapshot(
                scope = SearchHistoryScope.Guest,
                entries = listOf(accountEntry),
                preferences = SearchHistoryPreferences(activityPersonalizationEnabled = false),
            )
        }

        val first = historyEntry(index = 1, lastSubmittedAtEpochMilliseconds = 1_000)
        assertFailsWith<IllegalArgumentException> {
            SearchHistorySnapshot(
                scope = SearchHistoryScope.Guest,
                entries = listOf(first, first),
                preferences = SearchHistoryPreferences(activityPersonalizationEnabled = false),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SearchHistorySnapshot(
                scope = SearchHistoryScope.Guest,
                entries = listOf(
                    first,
                    historyEntry(index = 2, lastSubmittedAtEpochMilliseconds = 2_000),
                ),
                preferences = SearchHistoryPreferences(activityPersonalizationEnabled = false),
            )
        }
    }

    @Test
    fun snapshotRejectsRepeatedCanonicalQueriesBecauseResubmissionMovesTheExistingEntry() {
        val firstCanonicalQuery = submittedQuery(" restaurant Cotonou ")
        val secondCanonicalQuery = submittedQuery("restaurant Cotonou")
        assertEquals(firstCanonicalQuery, secondCanonicalQuery)
        val entries = listOf(
            SearchHistoryEntry(
                id = entryId("entry-newer"),
                scope = SearchHistoryScope.Guest,
                query = firstCanonicalQuery,
                lastSubmittedAtEpochMilliseconds = 2_000,
            ),
            SearchHistoryEntry(
                id = entryId("entry-older"),
                scope = SearchHistoryScope.Guest,
                query = secondCanonicalQuery,
                lastSubmittedAtEpochMilliseconds = 1_000,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            SearchHistorySnapshot(
                scope = SearchHistoryScope.Guest,
                entries = entries,
                preferences = SearchHistoryPolicy.defaultPreferences(),
            )
        }
    }

    @Test
    fun guestImportCannotExistWithoutExplicitConfirmation() {
        val destination = authenticatedScope("account-1")

        val rejected = GuestHistoryImportRequest.create(destination, confirmedByUser = false)
        assertEquals(
            SearchHistoryErrors.importConfirmationRequired,
            assertIs<DomainResult.Failure>(rejected).error,
        )

        val request = assertIs<DomainResult.Success<GuestHistoryImportRequest>>(
            GuestHistoryImportRequest.create(destination, confirmedByUser = true),
        ).value
        assertEquals(destination, request.destination)
        assertFalse(request.toString().contains("account-1"))
    }

    @Test
    fun featureErrorsKeepExpectedDomainCategoriesAndSafeKeys() {
        assertIs<DomainError.Validation>(SearchHistoryErrors.invalidSubmittedQuery)
        assertIs<DomainError.NotFound>(SearchHistoryErrors.entryNotFound)
        assertIs<DomainError.AuthenticationRequired>(SearchHistoryErrors.authenticationRequired)
        assertIs<DomainError.NetworkUnavailable>(SearchHistoryErrors.synchronizationUnavailable)
        assertIs<DomainError.LocalStorageUnavailable>(SearchHistoryErrors.localStorageUnavailable)
        assertIs<DomainError.Unexpected>(SearchHistoryErrors.unexpected)

        val errors = listOf(
            SearchHistoryErrors.invalidSubmittedQuery,
            SearchHistoryErrors.invalidAccountScope,
            SearchHistoryErrors.invalidEntryId,
            SearchHistoryErrors.importConfirmationRequired,
            SearchHistoryErrors.entryNotFound,
            SearchHistoryErrors.authenticationRequired,
            SearchHistoryErrors.synchronizationUnavailable,
            SearchHistoryErrors.localStorageUnavailable,
            SearchHistoryErrors.unexpected,
        )
        assertTrue(errors.all { error -> error.messageKey.startsWith("error.search_history.") })
    }
}

private fun authenticatedScope(userId: String): SearchHistoryScope.Authenticated =
    assertIs<DomainResult.Success<SearchHistoryScope.Authenticated>>(
        SearchHistoryScope.Authenticated.from(userId),
    ).value

private fun submittedQuery(text: String = "restaurant Cotonou"): SubmittedSearchQuery =
    assertIs<DomainResult.Success<SubmittedSearchQuery>>(SubmittedSearchQuery.from(text)).value

private fun entryId(value: String): SearchHistoryEntryId =
    assertIs<DomainResult.Success<SearchHistoryEntryId>>(SearchHistoryEntryId.from(value)).value

private fun historyEntry(
    index: Int,
    scope: SearchHistoryScope = SearchHistoryScope.Guest,
    lastSubmittedAtEpochMilliseconds: Long,
): SearchHistoryEntry = SearchHistoryEntry(
    id = entryId("entry-$index"),
    scope = scope,
    query = submittedQuery("query $index"),
    lastSubmittedAtEpochMilliseconds = lastSubmittedAtEpochMilliseconds,
)
