package com.kwabor.android.presentation.auth

import com.kwabor.shared.domain.auth.LegalDocumentType
import kotlinx.coroutines.launch

internal class AuthPlatformCoordinator(
    private val runtime: AuthViewModelRuntime,
) {
    fun handle(intent: AuthIntent.Platform) {
        when (intent) {
            is AuthIntent.OpenLegalDocument -> openLegalDocument(intent.type)
            AuthIntent.LegalDocumentOpenFailed -> runtime.platformState.value = runtime.platformState.value.copy(
                legalDocumentOpenFailed = true,
            )
        }
    }

    private fun openLegalDocument(type: LegalDocumentType) {
        val state = runtime.registrationState.value
        val url = when (type) {
            LegalDocumentType.Terms -> state.termsDocument?.url
            LegalDocumentType.PrivacyPolicy -> state.privacyDocument?.url
            LegalDocumentType.UgcLicense -> state.ugcDocument?.url
        } ?: return
        runtime.platformState.value = runtime.platformState.value.copy(legalDocumentOpenFailed = false)
        runtime.coroutineScope.launch {
            runtime.platformEffectChannel.send(AuthPlatformEffect.OpenLegalDocument(url))
        }
    }
}
