package com.kwabor.shared.domain.auth

import com.kwabor.shared.domain.core.DomainResult

interface AuthRepository {
    suspend fun getCurrentSession(): DomainResult<AuthSession?>

    suspend fun requestEmailOtp(email: String): DomainResult<Unit>

    suspend fun signUpWithEmail(request: EmailSignUpRequest): DomainResult<AuthSession>

    suspend fun signInWithEmail(email: String, password: String): DomainResult<AuthSession>

    suspend fun signOut(): DomainResult<Unit>
}
