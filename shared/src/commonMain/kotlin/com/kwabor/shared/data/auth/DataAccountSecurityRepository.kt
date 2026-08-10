package com.kwabor.shared.data.auth

import com.kwabor.shared.data.core.isValidUuid
import com.kwabor.shared.domain.auth.AccountDeletionCredential
import com.kwabor.shared.domain.auth.AccountDeletionOutcome
import com.kwabor.shared.domain.auth.AccountDeletionOutcomeUnknownCancellation
import com.kwabor.shared.domain.auth.AccountDeletionOutcomeUnknownCleanupPendingCancellation
import com.kwabor.shared.domain.auth.AccountDeletionPreTransportCancellation
import com.kwabor.shared.domain.auth.AccountDeletionPreTransportCleanupPendingCancellation
import com.kwabor.shared.domain.auth.AccountDeletionRequest
import com.kwabor.shared.domain.auth.AccountSecurityRepository
import com.kwabor.shared.domain.core.DomainResult
import kotlinx.coroutines.CancellationException

internal class DataAccountSecurityRepository(
    private val dataSource: AccountSecurityAuthDataSource,
) : AccountSecurityRepository {
    override suspend fun deleteAccount(request: AccountDeletionRequest): DomainResult<AccountDeletionOutcome> = try {
        runAuthCall {
            if (!request.idempotencyKey.isValidUuid()) {
                throw AuthDataException.Validation("error.auth.account_deletion_idempotency_key_invalid")
            }
            when (val credential = request.credential) {
                is AccountDeletionCredential.Password -> requireSignInPassword(credential.password)
                is AccountDeletionCredential.Social -> requireSocialRequest(credential.request)
            }
            dataSource.deleteAccount(request).toDomain()
        }
    } catch (cancellation: AccountDeletionPreTransportCleanupPendingCancellation) {
        throw cancellation
    } catch (cancellation: AccountDeletionPreTransportCancellation) {
        throw cancellation
    } catch (cancellation: AccountDeletionOutcomeUnknownCleanupPendingCancellation) {
        throw cancellation
    } catch (cancellation: AccountDeletionOutcomeUnknownCancellation) {
        throw cancellation
    } catch (cancellation: CancellationException) {
        throw AccountDeletionPreTransportCancellation(cancellation)
    }
}

private fun AccountDeletionDataOutcome.toDomain(): AccountDeletionOutcome = when (this) {
    AccountDeletionDataOutcome.Deleted -> AccountDeletionOutcome.Deleted
    AccountDeletionDataOutcome.OutcomeUnknown -> AccountDeletionOutcome.OutcomeUnknown
    AccountDeletionDataOutcome.LocalCleanupPending -> AccountDeletionOutcome.LocalCleanupPending
    is AccountDeletionDataOutcome.RejectedCleanupPending ->
        AccountDeletionOutcome.RejectedCleanupPending(rejection.domainError)
}
