package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AUTH_PROMOTER_INVITE_EXPIRED_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_PROMOTER_INVITE_INVALID_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_PROMOTER_INVITE_USED_ERROR_KEY
import com.kwabor.shared.domain.auth.AuthSessionPurpose
import com.kwabor.shared.domain.auth.PromoterActivationContext
import com.kwabor.shared.domain.auth.PromoterActivationSessionProof
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal class SupabasePromoterActivationAuthDataSource(
    private val auth: Auth,
    private val postgrest: Postgrest,
    private val passwordRecoverySessionStore: PasswordRecoverySessionStore,
) : PromoterActivationAuthDataSource {
    override suspend fun establishPromoterActivationSession(proof: PromoterActivationSessionProof): Unit =
        runAuthRequest {
            when (proof) {
                is PromoterActivationSessionProof.PkceCode ->
                    auth.exchangeCodeForSession(code = proof.code, saveSession = true)
                PromoterActivationSessionProof.ExistingSession -> {
                    auth.awaitInitialization()
                    if (auth.currentSessionOrNull() == null) throw AuthDataException.AuthenticationRequired()
                }
            }
            passwordRecoverySessionStore.clearPasswordRecovery()
        }

    override suspend fun previewPromoterInvite(inviteToken: String): PromoterActivationContext = runAuthRequest {
        postgrest.rpc(
            function = PREVIEW_PROMOTER_INVITE_RPC,
            parameters = PromoterInviteTokenRpcDto(inviteToken),
        ).decodeSingle<PromoterInviteStatusDto>().toContext(inviteToken)
    }

    override suspend fun activatePromoterInvite(inviteToken: String): PromoterActivationResultDto = runAuthRequest {
        val activation = postgrest.rpc(
            function = ACTIVATE_PROMOTER_INVITE_RPC,
            parameters = PromoterInviteTokenRpcDto(inviteToken),
        ).decodeSingle<PromoterInviteStatusDto>().requireActivated()
        val session = auth.currentSessionOrNull() ?: throw AuthDataException.AuthenticationRequired()
        PromoterActivationResultDto(
            session = session.toDtoWithServerStatus(
                postgrest = postgrest,
                purpose = AuthSessionPurpose.Standard,
            ),
            organizationId = activation.organizationId.orEmpty(),
            listingId = activation.listingId.orEmpty(),
        )
    }
}

private fun PromoterInviteStatusDto.toContext(inviteToken: String): PromoterActivationContext {
    requireReadyStatus()
    return PromoterActivationContext(
        inviteToken = inviteToken,
        organizationId = organizationId.orEmpty(),
        listingId = listingId.orEmpty(),
        businessName = businessName.orEmpty(),
    )
}

private fun PromoterInviteStatusDto.requireActivated(): PromoterInviteStatusDto {
    if (status != ACTIVATED_STATUS) requireReadyStatus()
    if (organizationId == null || listingId == null) throw AuthDataException.Unexpected()
    return this
}

private fun PromoterInviteStatusDto.requireReadyStatus() {
    val statusFailure = when (status) {
        READY_STATUS, ACTIVATED_STATUS -> {
            if (organizationId == null || listingId == null || businessName.isNullOrBlank()) {
                AuthDataException.Unexpected()
            } else {
                null
            }
        }
        EXPIRED_STATUS -> AuthDataException.Validation(AUTH_PROMOTER_INVITE_EXPIRED_ERROR_KEY)
        ACCEPTED_STATUS, REVOKED_STATUS -> AuthDataException.Validation(AUTH_PROMOTER_INVITE_USED_ERROR_KEY)
        else -> AuthDataException.Validation(AUTH_PROMOTER_INVITE_INVALID_ERROR_KEY)
    }
    if (statusFailure != null) throw statusFailure
}

@Serializable
private data class PromoterInviteTokenRpcDto(
    @SerialName("p_invite_token")
    val inviteToken: String,
)

@Serializable
private data class PromoterInviteStatusDto(
    val status: String,
    @SerialName("organization_id")
    val organizationId: String? = null,
    @SerialName("listing_id")
    val listingId: String? = null,
    @SerialName("business_name")
    val businessName: String? = null,
)

private const val PREVIEW_PROMOTER_INVITE_RPC = "preview_promoter_invite"
private const val ACTIVATE_PROMOTER_INVITE_RPC = "activate_promoter_invite"
private const val READY_STATUS = "ready"
private const val ACTIVATED_STATUS = "activated"
private const val EXPIRED_STATUS = "expired"
private const val ACCEPTED_STATUS = "accepted"
private const val REVOKED_STATUS = "revoked"
