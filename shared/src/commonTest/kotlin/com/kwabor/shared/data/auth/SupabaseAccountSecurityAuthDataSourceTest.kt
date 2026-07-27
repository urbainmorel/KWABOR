package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_OWNERSHIP_ERROR_KEY
import com.kwabor.shared.domain.core.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class SupabaseAccountSecurityAuthDataSourceTest {
    @Test
    fun knownBusinessErrorKeepsItsDedicatedDomainError() {
        val cause = IllegalStateException("SDK response")

        val result = mapAccountDeletionError(
            errorCode = "organization_ownership_conflict",
            cause = cause,
        )

        val domainError = assertIs<DomainError.Validation>(result.domainError)
        assertEquals(AUTH_ACCOUNT_DELETION_OWNERSHIP_ERROR_KEY, domainError.messageKey)
        assertSame(cause, result.cause)
    }

    @Test
    fun unknownRestErrorNeverFallsBackToLegalDocumentsError() {
        val cause = IllegalStateException("SDK response")

        val result = mapAccountDeletionError(
            errorCode = "function_not_found",
            cause = cause,
        )

        assertIs<AuthDataException.Unexpected>(result)
        assertIs<DomainError.Unexpected>(result.domainError)
        assertSame(cause, result.cause)
    }
}
