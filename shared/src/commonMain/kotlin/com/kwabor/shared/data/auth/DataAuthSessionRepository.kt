package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.AuthSessionRepository
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainResult

internal class DataAuthSessionRepository(
    private val dataSource: AuthSessionDataSource,
) : AuthSessionRepository {
    override suspend fun getCurrentSession(): DomainResult<AuthSession?> = runAuthCall {
        dataSource.getCurrentSession()?.toDomain()
    }

    override suspend fun signInWithEmail(email: String, password: String): DomainResult<AuthSession> = runAuthCall {
        requireValidEmail(email)
        requireSignInPassword(password)
        dataSource.signInWithEmail(email = email.trim(), password = password).toDomain()
    }

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): DomainResult<AuthSession> =
        runAuthCall {
            val normalizedRequest = request.normalized()
            requireSocialRequest(normalizedRequest)
            dataSource.signInWithSocialProvider(normalizedRequest).toDomain()
        }

    override suspend fun signOut(): DomainResult<Unit> = runAuthCall {
        dataSource.signOut()
    }
}
