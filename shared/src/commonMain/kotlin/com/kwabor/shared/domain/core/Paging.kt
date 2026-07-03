package com.kwabor.shared.domain.core

data class PageRequest(val offset: Int = 0, val limit: Int = DEFAULT_LIMIT) {
    init {
        require(offset >= 0) { "Page offset must be positive or zero." }
        require(limit in 1..MAX_LIMIT) { "Page limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
    }
}

data class PageResult<T>(
    val items: List<T>,
    val nextOffset: Int?,
)
