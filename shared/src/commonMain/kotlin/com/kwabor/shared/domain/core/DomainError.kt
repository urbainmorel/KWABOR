package com.kwabor.shared.domain.core

sealed interface DomainError {
    val messageKey: String

    data class Validation(override val messageKey: String) : DomainError

    data class NotFound(override val messageKey: String) : DomainError

    data class PermissionDenied(override val messageKey: String) : DomainError

    data class AuthenticationRequired(override val messageKey: String = "error.auth.session_required") : DomainError

    data class NetworkUnavailable(override val messageKey: String = "error.network.unavailable") : DomainError

    data class Unexpected(override val messageKey: String = "error.unexpected") : DomainError
}
