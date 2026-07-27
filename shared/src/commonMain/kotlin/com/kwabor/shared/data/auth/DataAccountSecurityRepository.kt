package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AccountDeletionCredential
import com.kwabor.shared.domain.auth.AccountDeletionRequest
import com.kwabor.shared.domain.auth.AccountSecurityRepository
import com.kwabor.shared.domain.core.DomainResult

private val UUID_PATTERN = Regex(
    pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    option = RegexOption.IGNORE_CASE,
)

internal class DataAccountSecurityRepository(
    private val dataSource: AccountSecurityAuthDataSource,
) : AccountSecurityRepository {
    override suspend fun deleteAccount(request: AccountDeletionRequest): DomainResult<Unit> = runAuthCall {
        if (!UUID_PATTERN.matches(request.idempotencyKey)) {
            throw AuthDataException.Validation("error.auth.account_deletion_idempotency_key_invalid")
        }
        when (val credential = request.credential) {
            is AccountDeletionCredential.Password -> requireSignInPassword(credential.password)
            is AccountDeletionCredential.Social -> requireSocialRequest(credential.request)
        }
        dataSource.deleteAccount(request)
    }
}
