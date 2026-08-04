package com.kwabor.shared.data.auth

import com.kwabor.shared.data.core.isValidUuid
import com.kwabor.shared.domain.auth.AccountDeletionCredential
import com.kwabor.shared.domain.auth.AccountDeletionRequest
import com.kwabor.shared.domain.auth.AccountSecurityRepository
import com.kwabor.shared.domain.core.DomainResult

internal class DataAccountSecurityRepository(
    private val dataSource: AccountSecurityAuthDataSource,
) : AccountSecurityRepository {
    override suspend fun deleteAccount(request: AccountDeletionRequest): DomainResult<Unit> = runAuthCall {
        if (!request.idempotencyKey.isValidUuid()) {
            throw AuthDataException.Validation("error.auth.account_deletion_idempotency_key_invalid")
        }
        when (val credential = request.credential) {
            is AccountDeletionCredential.Password -> requireSignInPassword(credential.password)
            is AccountDeletionCredential.Social -> requireSocialRequest(credential.request)
        }
        dataSource.deleteAccount(request)
    }
}
