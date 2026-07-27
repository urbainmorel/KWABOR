package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AuthSessionPurpose
import com.kwabor.shared.domain.auth.SocialSignInRequest
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class SupabaseAuthSessionDataSource(
    private val auth: Auth,
    private val postgrest: Postgrest,
    private val passwordRecoverySessionStore: PasswordRecoverySessionStore,
) : AuthSessionDataSource {
    private val passwordRecoverySessionCoordinator =
        PasswordRecoverySessionCoordinator(passwordRecoverySessionStore)

    override suspend fun getCurrentSession(): AuthSessionDto? = runAuthRequest {
        auth.awaitInitialization()
        if (passwordRecoverySessionStore.isPasswordRecoveryInProgress()) {
            passwordRecoverySessionCoordinator.restoreRecoverySessionOrNull(
                currentSession = auth.currentSessionOrNull(),
                loadStoredSession = auth.sessionManager::loadSessionOrNull,
                clearCurrentSession = auth::clearSession,
            )?.toDto(
                onboardingCompleted = false,
                purpose = AuthSessionPurpose.PasswordRecovery,
            )
        } else {
            auth.currentSessionOrNull()?.toDtoWithServerStatus(
                postgrest = postgrest,
                purpose = AuthSessionPurpose.Standard,
            )
        }
    }

    override suspend fun signInWithEmail(email: String, password: String): AuthSessionDto = runAuthRequest {
        auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        passwordRecoverySessionStore.clearPasswordRecovery()
        val session = auth.currentSessionOrNull() ?: throw AuthDataException.AuthenticationRequired()
        session.toDtoWithServerStatus(
            postgrest = postgrest,
            purpose = AuthSessionPurpose.Standard,
        )
    }

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): AuthSessionDto {
        var sessionEstablished = false
        val signInResult = runCatching {
            runAuthRequest {
                auth.signInWith(IDToken) {
                    idToken = request.idToken
                    nonce = request.rawNonce
                    provider = request.provider.toSupabaseProvider()
                }
                sessionEstablished = true
                passwordRecoverySessionStore.clearPasswordRecovery()
                val session = auth.currentSessionOrNull() ?: throw AuthDataException.AuthenticationRequired()
                session.toDtoWithServerStatus(
                    postgrest = postgrest,
                    purpose = AuthSessionPurpose.Standard,
                    authenticationMethod = request.provider.toAuthenticationMethod(),
                    suggestedFirstName = request.suggestedFirstName,
                    suggestedLastName = request.suggestedLastName,
                )
            }
        }
        val signInFailure = signInResult.exceptionOrNull() ?: return signInResult.getOrThrow()
        if (sessionEstablished) {
            runCatching {
                withContext(NonCancellable) {
                    discardTemporarySession()
                }
            }.exceptionOrNull()?.let(signInFailure::addSuppressed)
        }
        throw signInFailure
    }

    override suspend fun discardTemporarySession() {
        var discardFailure = runCatching {
            runAuthRequest {
                auth.signOut(SignOutScope.LOCAL)
            }
        }.exceptionOrNull()
        runCatching {
            runAuthRequest {
                passwordRecoverySessionStore.clearPasswordRecovery()
            }
        }.exceptionOrNull()?.let { failure ->
            discardFailure = discardFailure.mergeWith(failure)
        }
        runCatching {
            runAuthRequest {
                auth.clearSession()
            }
        }.exceptionOrNull()?.let { failure ->
            discardFailure = discardFailure.mergeWith(failure)
        }
        discardFailure?.let { failure -> throw failure }
    }

    override suspend fun signOut(): Unit = runAuthRequest {
        auth.awaitInitialization()
        if (auth.currentSessionOrNull() == null) {
            passwordRecoverySessionStore.clearPasswordRecovery()
            auth.clearSession()
        } else {
            passwordRecoverySessionCoordinator.signOut {
                auth.signOut(SignOutScope.LOCAL)
            }
        }
    }
}

private fun Throwable?.mergeWith(additionalFailure: Throwable): Throwable =
    this?.also { failure -> failure.addSuppressed(additionalFailure) } ?: additionalFailure
