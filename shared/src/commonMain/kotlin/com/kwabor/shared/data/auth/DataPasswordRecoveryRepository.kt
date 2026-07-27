package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.PasswordRecoveryRepository
import com.kwabor.shared.domain.core.DomainResult

internal class DataPasswordRecoveryRepository(
    private val dataSource: PasswordRecoveryAuthDataSource,
) : PasswordRecoveryRepository {
    override suspend fun requestPasswordRecovery(email: String): DomainResult<Unit> = runAuthCall {
        requireValidEmail(email)
        dataSource.requestPasswordRecovery(email.trim())
    }

    override suspend fun verifyPasswordRecoveryOtp(email: String, otpCode: String): DomainResult<AuthSession> =
        runAuthCall {
            requireValidEmail(email)
            requireOtpCode(otpCode)
            dataSource.verifyPasswordRecoveryOtp(
                email = email.trim(),
                otpCode = otpCode.trim(),
            ).toDomain()
        }

    override suspend fun completePasswordRecovery(newPassword: String): DomainResult<Unit> = runAuthCall {
        requirePassword(newPassword)
        dataSource.completePasswordRecovery(newPassword)
    }

    override suspend fun cancelPasswordRecovery(): DomainResult<Unit> = runAuthCall {
        dataSource.cancelPasswordRecovery()
    }
}
