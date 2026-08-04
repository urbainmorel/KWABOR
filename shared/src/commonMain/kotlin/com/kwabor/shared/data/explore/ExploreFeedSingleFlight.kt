package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.explore.ExploreFeedQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ExploreFeedSingleFlight<T>(
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val requests = mutableMapOf<String, Deferred<DomainResult<T>>>()

    suspend fun execute(key: String, block: suspend () -> DomainResult<T>): DomainResult<T> {
        val request = mutex.withLock {
            requests[key] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    block()
                } finally {
                    withContext(NonCancellable) {
                        mutex.withLock { requests.remove(key) }
                    }
                }
            }.also { created ->
                requests[key] = created
                created.start()
            }
        }
        return request.await()
    }
}

internal fun ExploreFeedQuery.toRefreshSingleFlightKey(): String =
    toSingleFlightKey(operation = "refresh", cursor = null)

internal fun ExploreFeedQuery.toAppendSingleFlightKey(cursor: String): String =
    toSingleFlightKey(operation = "append", cursor = cursor)

private fun ExploreFeedQuery.toSingleFlightKey(operation: String, cursor: String?): String = buildString {
    append(toCacheKey())
    append("|operation=")
    append(operation)
    append("|cursor=")
    append(cursor.toSingleFlightValue())
}

private fun String?.toSingleFlightValue(): String = when (this) {
    null -> "n"
    else -> "v$length:$this"
}
