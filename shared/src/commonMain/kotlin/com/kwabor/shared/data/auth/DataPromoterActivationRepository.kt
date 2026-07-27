package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AUTH_PROMOTER_INVITE_INVALID_ERROR_KEY
import com.kwabor.shared.domain.auth.PromoterActivationContext
import com.kwabor.shared.domain.auth.PromoterActivationDeepLinkParser
import com.kwabor.shared.domain.auth.PromoterActivationDeepLinkResult
import com.kwabor.shared.domain.auth.PromoterActivationRepository
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.PromoterActivationResult
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val MAX_AUTH_CALLBACK_LENGTH = 12_288

internal class DataPromoterActivationRepository(
    private val dataSource: AuthDataSource,
) : PromoterActivationRepository {
    override suspend fun handlePromoterActivationCallback(
        callbackUrl: String,
    ): DomainResult<PromoterActivationContext> = runAuthCall {
        requireValidCallbackUrl(callbackUrl)
        when (val deepLink = PromoterActivationDeepLinkParser.parse(callbackUrl)) {
            is PromoterActivationDeepLinkResult.Accepted -> previewPromoterInvite(deepLink)
            is PromoterActivationDeepLinkResult.Rejected ->
                throw invalidPromoterInviteException()
        }
    }

    override suspend fun activatePromoterInvite(
        request: PromoterActivationRequest,
    ): DomainResult<PromoterActivationResult> = runAuthCall {
        requirePromoterInviteToken(request.inviteToken)
        when {
            request.password != null && request.socialSignInRequest == null ->
                activateWithPassword(request.inviteToken, request.password)
            request.password == null && request.socialSignInRequest != null ->
                activateWithSocial(request.inviteToken, request.socialSignInRequest)
            else -> throw AuthDataException.Validation("error.auth.promoter_activation_method_required")
        }
    }

    private suspend fun previewPromoterInvite(
        deepLink: PromoterActivationDeepLinkResult.Accepted,
    ): PromoterActivationContext {
        if (dataSource.getCurrentSession() != null) {
            return dataSource.previewPromoterInvite(deepLink.inviteToken)
        }
        dataSource.establishPromoterActivationSession(deepLink.sessionProof)
        val previewResult = runCatching {
            dataSource.previewPromoterInvite(deepLink.inviteToken)
                .withSessionImportedForActivation()
        }
        val previewFailure = previewResult.exceptionOrNull() ?: return previewResult.getOrThrow()
        discardTemporarySessionPreserving(previewFailure)
        throw previewFailure
    }

    private suspend fun activateWithPassword(inviteToken: String, password: String): PromoterActivationResult {
        requireSignInPassword(password)
        val currentSession = requireCurrentSession()
        val email = currentSession.email?.takeIf(String::isNotBlank)
            ?: throw AuthDataException.AuthenticationRequired()
        val reauthenticatedSession = dataSource.signInWithEmail(email, password)
        requireSameUserOrDiscard(currentSession, reauthenticatedSession)
        return dataSource.activatePromoterInvite(inviteToken).toDomain()
    }

    private suspend fun activateWithSocial(
        inviteToken: String,
        socialRequest: SocialSignInRequest,
    ): PromoterActivationResult {
        requireSocialRequest(socialRequest)
        val currentSession = requireCurrentSession()
        val reauthenticatedSession = dataSource.signInWithSocialProvider(socialRequest)
        requireSameUserOrDiscard(currentSession, reauthenticatedSession)
        return dataSource.activatePromoterInvite(inviteToken).toDomain()
    }

    private suspend fun requireCurrentSession(): AuthSessionDto =
        dataSource.getCurrentSession() ?: throw AuthDataException.AuthenticationRequired()

    private suspend fun requireSameUserOrDiscard(
        currentSession: AuthSessionDto,
        reauthenticatedSession: AuthSessionDto,
    ) {
        if (reauthenticatedSession.userId == currentSession.userId) return
        val identityMismatch = AuthDataException.Validation("error.auth.invalid_credentials")
        discardTemporarySessionPreserving(identityMismatch)
        throw identityMismatch
    }

    private suspend fun discardTemporarySessionPreserving(originalException: Throwable) {
        runCatching {
            withContext(NonCancellable) {
                dataSource.discardTemporarySession()
            }
        }.exceptionOrNull()?.let(originalException::addSuppressed)
    }
}

private fun requireValidCallbackUrl(callbackUrl: String) {
    if (callbackUrl.isMalformedAuthCallback()) {
        throw invalidPromoterInviteException()
    }
}

private fun String.isMalformedAuthCallback(): Boolean {
    val hasInvalidShape = isBlank() || length > MAX_AUTH_CALLBACK_LENGTH || this != trim()
    return hasInvalidShape || any(Char::isISOControl)
}

private fun requirePromoterInviteToken(inviteToken: String) {
    val deepLink = PromoterActivationDeepLinkParser.parse("kwabor://auth/promoter-activate?token=$inviteToken")
    if (deepLink !is PromoterActivationDeepLinkResult.Accepted) {
        throw invalidPromoterInviteException()
    }
}

private fun PromoterActivationContext.withSessionImportedForActivation(): PromoterActivationContext =
    PromoterActivationContext(
        inviteToken = inviteToken,
        organizationId = organizationId,
        listingId = listingId,
        businessName = businessName,
        sessionImportedForActivation = true,
    )

private fun invalidPromoterInviteException(): AuthDataException.Validation =
    AuthDataException.Validation(AUTH_PROMOTER_INVITE_INVALID_ERROR_KEY)
