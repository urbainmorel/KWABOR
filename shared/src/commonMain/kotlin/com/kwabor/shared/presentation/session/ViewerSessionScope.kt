package com.kwabor.shared.presentation.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ViewerSessionScope(
    val accountId: String?,
    val epoch: Long,
) {
    init {
        require(epoch >= 0L) { "Viewer session epoch must be non-negative." }
        require(accountId == accountId?.trim()?.takeIf(String::isNotEmpty)) {
            "Viewer account id must be null or normalized."
        }
    }

    val isAuthenticated: Boolean
        get() = accountId != null

    companion object {
        val InitialGuest = ViewerSessionScope(accountId = null, epoch = 0L)
    }
}

class ViewerSessionScopeTracker {
    private val mutableScope = MutableStateFlow(ViewerSessionScope.InitialGuest)
    val scope: StateFlow<ViewerSessionScope> = mutableScope.asStateFlow()

    val currentScope: ViewerSessionScope
        get() = mutableScope.value

    fun update(accountId: String?, accountSetupComplete: Boolean): ViewerSessionScope {
        val normalizedAccountId = accountId
            ?.trim()
            ?.takeIf { candidate -> accountSetupComplete && candidate.isNotEmpty() }
        while (true) {
            val current = mutableScope.value
            if (current.accountId == normalizedAccountId) return current
            check(current.epoch < Long.MAX_VALUE) { "Viewer session epoch is exhausted." }
            val next = ViewerSessionScope(
                accountId = normalizedAccountId,
                epoch = current.epoch + 1L,
            )
            if (mutableScope.compareAndSet(current, next)) return next
        }
    }
}
