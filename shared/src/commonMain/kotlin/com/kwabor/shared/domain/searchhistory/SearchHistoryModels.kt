package com.kwabor.shared.domain.searchhistory

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.search.CanonicalSearchText

class SubmittedSearchQuery private constructor(
    val text: String,
) {
    companion object {
        fun from(text: String): DomainResult<SubmittedSearchQuery> {
            val canonicalText = CanonicalSearchText.from(text)
            if (canonicalText == null) {
                return DomainResult.Failure(SearchHistoryErrors.invalidSubmittedQuery)
            }
            return DomainResult.Success(SubmittedSearchQuery(canonicalText.value))
        }
    }

    override fun equals(other: Any?): Boolean = other is SubmittedSearchQuery && text == other.text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = "SubmittedSearchQuery(text=<redacted>)"
}

sealed interface SearchHistoryScope {
    data object Guest : SearchHistoryScope

    class Authenticated private constructor(
        val userId: String,
    ) : SearchHistoryScope {
        companion object {
            fun from(userId: String): DomainResult<Authenticated> {
                val canonicalUserId = userId.trim()
                if (!canonicalUserId.isValidSearchHistoryIdentifier()) {
                    return DomainResult.Failure(SearchHistoryErrors.invalidAccountScope)
                }
                return DomainResult.Success(Authenticated(canonicalUserId))
            }
        }

        override fun equals(other: Any?): Boolean = other is Authenticated && userId == other.userId

        override fun hashCode(): Int = userId.hashCode()

        override fun toString(): String = "Authenticated(userId=<redacted>)"
    }
}

class SearchHistoryEntryId private constructor(
    val value: String,
) {
    companion object {
        fun from(value: String): DomainResult<SearchHistoryEntryId> {
            val canonicalValue = value.trim()
            if (!canonicalValue.isValidSearchHistoryIdentifier()) {
                return DomainResult.Failure(SearchHistoryErrors.invalidEntryId)
            }
            return DomainResult.Success(SearchHistoryEntryId(canonicalValue))
        }
    }

    override fun equals(other: Any?): Boolean = other is SearchHistoryEntryId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "SearchHistoryEntryId(value=<redacted>)"
}

data class SearchHistoryEntry(
    val id: SearchHistoryEntryId,
    val scope: SearchHistoryScope,
    val query: SubmittedSearchQuery,
    val lastSubmittedAtEpochMilliseconds: Long,
) {
    init {
        require(lastSubmittedAtEpochMilliseconds >= 0) {
            "Search history submission timestamp must not be negative."
        }
    }
}

data class SearchHistoryPreferences(
    val activityPersonalizationEnabled: Boolean,
)

object SearchHistoryPolicy {
    const val MAX_SERVER_ACTIVE_QUERIES_PER_ACCOUNT: Int = 200
    const val MAX_LOCAL_QUERIES_PER_SCOPE: Int = 50

    fun defaultPreferences(): SearchHistoryPreferences =
        SearchHistoryPreferences(activityPersonalizationEnabled = false)
}

class SearchHistorySnapshot(
    val scope: SearchHistoryScope,
    entries: List<SearchHistoryEntry>,
    val preferences: SearchHistoryPreferences,
) {
    val entries: List<SearchHistoryEntry> = entries.toList()

    init {
        require(this.entries.all { entry -> entry.scope == scope }) {
            "Search history snapshot entries must belong to its scope."
        }
        require(this.entries.map(SearchHistoryEntry::id).distinct().size == this.entries.size) {
            "Search history snapshot must not contain duplicate entry ids."
        }
        require(this.entries.map(SearchHistoryEntry::query).distinct().size == this.entries.size) {
            "Search history snapshot must not contain duplicate canonical queries."
        }
        require(
            this.entries.zipWithNext().all { (current, next) ->
                current.lastSubmittedAtEpochMilliseconds >= next.lastSubmittedAtEpochMilliseconds
            },
        ) {
            "Search history snapshot entries must be ordered from newest to oldest."
        }
    }

    override fun equals(other: Any?): Boolean = other is SearchHistorySnapshot &&
        scope == other.scope &&
        entries == other.entries &&
        preferences == other.preferences

    override fun hashCode(): Int {
        var result = scope.hashCode()
        result = 31 * result + entries.hashCode()
        result = 31 * result + preferences.hashCode()
        return result
    }

    override fun toString(): String = "SearchHistorySnapshot(scope=$scope, entries=$entries, preferences=$preferences)"
}

data class RecordSubmittedSearchRequest(
    val scope: SearchHistoryScope,
    val query: SubmittedSearchQuery,
)

class GuestHistoryImportRequest private constructor(
    val destination: SearchHistoryScope.Authenticated,
) {
    companion object {
        fun create(
            destination: SearchHistoryScope.Authenticated,
            confirmedByUser: Boolean,
        ): DomainResult<GuestHistoryImportRequest> {
            if (!confirmedByUser) {
                return DomainResult.Failure(SearchHistoryErrors.importConfirmationRequired)
            }
            return DomainResult.Success(GuestHistoryImportRequest(destination))
        }
    }

    override fun equals(other: Any?): Boolean = other is GuestHistoryImportRequest && destination == other.destination

    override fun hashCode(): Int = destination.hashCode()

    override fun toString(): String = "GuestHistoryImportRequest(destination=<redacted>)"
}

private fun String.isValidSearchHistoryIdentifier(): Boolean = isNotEmpty() &&
    length <= MAX_SEARCH_HISTORY_IDENTIFIER_LENGTH &&
    SEARCH_HISTORY_IDENTIFIER_PATTERN.matches(this)

private val SEARCH_HISTORY_IDENTIFIER_PATTERN = Regex(pattern = "^[A-Za-z0-9][A-Za-z0-9_-]*$")

internal const val MAX_SEARCH_HISTORY_IDENTIFIER_LENGTH = 128
