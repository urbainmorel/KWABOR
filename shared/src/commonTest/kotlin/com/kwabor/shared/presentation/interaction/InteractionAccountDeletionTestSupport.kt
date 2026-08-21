package com.kwabor.shared.presentation.interaction

internal suspend fun InteractionCoordinator.commitAccountDeletionBlock(
    accountId: String,
): InteractionDeletionBlockToken {
    val owner = when (val registration = registerAccountDeletionBlock(accountId)) {
        InteractionDeletionBlockRegistration.AlreadyBlocked -> error("Account is already blocked")
        is InteractionDeletionBlockRegistration.Owner -> registration
    }
    owner.idle?.await()
    invalidateAfterCompositePurge(accountId)
    check(finishAccountDeletionBlock(owner.token, committed = true))
    return owner.token
}
