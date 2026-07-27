package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AccountSecurityRepository
import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthRegistrationRepository
import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.AuthSessionRepository
import com.kwabor.shared.domain.auth.PasswordRecoveryRepository
import com.kwabor.shared.domain.auth.PromoterActivationRepository
import com.kwabor.shared.domain.auth.PromoterActivationResult
import com.kwabor.shared.domain.core.DomainResult

class DataAuthRepository internal constructor(
    dataSource: AuthDataSource,
) : AuthRepository,
    AuthSessionRepository by DataAuthSessionRepository(dataSource),
    AuthRegistrationRepository by DataAuthRegistrationRepository(dataSource),
    PasswordRecoveryRepository by DataPasswordRecoveryRepository(dataSource),
    PromoterActivationRepository by DataPromoterActivationRepository(dataSource),
    AccountSecurityRepository by DataAccountSecurityRepository(dataSource)

internal suspend fun <T> runAuthCall(block: suspend () -> T): DomainResult<T> = try {
    DomainResult.Success(block())
} catch (exception: AuthDataException) {
    DomainResult.Failure(exception.domainError)
}

internal fun AuthSessionDto.toDomain(): AuthSession = AuthSession(
    userId = userId,
    email = email,
    expiresAtEpochMilliseconds = expiresAtEpochMilliseconds,
    accountSetupStatus = if (onboardingCompleted) {
        AccountSetupStatus.Complete
    } else {
        AccountSetupStatus.OnboardingRequired
    },
    purpose = purpose,
    authenticationMethod = authenticationMethod,
    suggestedFirstName = suggestedFirstName,
    suggestedLastName = suggestedLastName,
)

internal fun PromoterActivationResultDto.toDomain(): PromoterActivationResult = PromoterActivationResult(
    session = session.toDomain(),
    organizationId = organizationId,
    listingId = listingId,
)
