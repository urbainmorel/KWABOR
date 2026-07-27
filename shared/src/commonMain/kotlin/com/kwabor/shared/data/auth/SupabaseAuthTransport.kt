package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AUTH_EMAIL_NOT_CONFIRMED_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_INVALID_CREDENTIALS_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_OTP_EXPIRED_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_PASSWORD_SAME_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_PASSWORD_TOO_WEAK_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_RATE_LIMITED_ERROR_KEY
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthSessionMissingException
import io.github.jan.supabase.auth.exception.AuthWeakPasswordException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_UNPROCESSABLE_CONTENT = 422
private const val HTTP_TOO_MANY_REQUESTS = 429

internal suspend fun <T> runAuthRequest(block: suspend () -> T): T =
    runAuthTransportRequest { runAuthSdkRequest(block) }

private suspend fun <T> runAuthSdkRequest(block: suspend () -> T): T = try {
    block()
} catch (exception: AuthDataException) {
    throw exception
} catch (exception: AuthWeakPasswordException) {
    throw AuthDataException.Validation(AUTH_PASSWORD_TOO_WEAK_ERROR_KEY, exception)
} catch (exception: AuthSessionMissingException) {
    throw AuthDataException.AuthenticationRequired(cause = exception)
} catch (exception: AuthRestException) {
    throw exception.toAuthDataException()
}

private suspend fun <T> runAuthTransportRequest(block: suspend () -> T): T = try {
    block()
} catch (exception: PostgrestRestException) {
    throw exception.toAuthDataException()
} catch (exception: RestException) {
    throw exception.toAuthDataException()
} catch (exception: HttpRequestTimeoutException) {
    throw AuthDataException.NetworkUnavailable(exception)
} catch (exception: HttpRequestException) {
    throw AuthDataException.NetworkUnavailable(exception)
} catch (exception: SerializationException) {
    throw AuthDataException.Unexpected(exception)
} catch (exception: CancellationException) {
    throw exception
} catch (exception: IllegalArgumentException) {
    throw AuthDataException.Unexpected(exception)
} catch (exception: IllegalStateException) {
    throw AuthDataException.Unexpected(exception)
}

private fun RestException.toAuthDataException(): AuthDataException =
    (this as? AuthRestException)?.toAuthCodeDataExceptionOrNull()
        ?: (this as? PostgrestRestException)?.toPostgrestCodeDataExceptionOrNull()
        ?: toHttpStatusDataException()

private fun AuthRestException.toAuthCodeDataExceptionOrNull(): AuthDataException? = when (errorCode?.name) {
    "EmailProviderDisabled",
    "OtpDisabled",
    "ProviderDisabled",
    "ProviderEmailNeedsVerification",
    -> AuthDataException.Validation(cause = this)

    "InvalidCredentials" -> AuthDataException.Validation(AUTH_INVALID_CREDENTIALS_ERROR_KEY, this)
    "EmailNotConfirmed" -> AuthDataException.Validation(AUTH_EMAIL_NOT_CONFIRMED_ERROR_KEY, this)
    "OverEmailSendRateLimit",
    "OverRequestRateLimit",
    -> AuthDataException.Validation(AUTH_RATE_LIMITED_ERROR_KEY, this)

    "BadJwt",
    "SessionExpired",
    "SessionNotFound",
    -> AuthDataException.AuthenticationRequired(cause = this)

    "OtpExpired" -> AuthDataException.Validation(AUTH_OTP_EXPIRED_ERROR_KEY, this)
    "WeakPassword" -> AuthDataException.Validation(AUTH_PASSWORD_TOO_WEAK_ERROR_KEY, this)
    "SamePassword" -> AuthDataException.Validation(AUTH_PASSWORD_SAME_ERROR_KEY, this)
    else -> null
}

internal fun AuthRestException.isUnknownAccountError(): Boolean = errorCode?.name == "UserNotFound"

private fun PostgrestRestException.toPostgrestCodeDataExceptionOrNull(): AuthDataException? = when (code) {
    "42501" -> AuthDataException.PermissionDenied(cause = this)
    "P0001", "22023", "23503", "23505", "23514" -> AuthDataException.Validation(cause = this)
    "P0002", "PGRST116" -> AuthDataException.LegalDocumentsUnavailable(this)
    else -> null
}

internal fun RestException.toHttpStatusDataException(): AuthDataException = when (statusCode) {
    HTTP_BAD_REQUEST,
    HTTP_CONFLICT,
    HTTP_UNPROCESSABLE_CONTENT,
    -> AuthDataException.Validation(cause = this)
    HTTP_UNAUTHORIZED -> AuthDataException.AuthenticationRequired(cause = this)
    HTTP_FORBIDDEN -> AuthDataException.PermissionDenied(cause = this)
    HTTP_NOT_FOUND -> AuthDataException.LegalDocumentsUnavailable(this)
    HTTP_TOO_MANY_REQUESTS -> AuthDataException.Validation(AUTH_RATE_LIMITED_ERROR_KEY, this)
    else -> AuthDataException.Unexpected(this)
}
