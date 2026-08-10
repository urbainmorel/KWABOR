package com.kwabor.android.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal fun interface GoogleNonceGenerator {
    fun generate(): String
}

internal class SecureGoogleNonceGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : GoogleNonceGenerator {
    override fun generate(): String {
        val bytes = ByteArray(NONCE_BYTE_LENGTH)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

internal class GoogleProfileHint(
    val firstName: String,
    val lastName: String,
) {
    override fun toString(): String = "GoogleProfileHint(<redacted>)"
}

internal sealed interface GoogleIdentityResult {
    class Success(
        val idToken: String,
        val nonce: String,
        val profileHint: GoogleProfileHint,
    ) : GoogleIdentityResult {
        override fun toString(): String =
            "GoogleIdentityResult.Success(idToken=<redacted>, nonce=<redacted>, profileHint=<redacted>)"
    }

    data object Cancelled : GoogleIdentityResult

    data object Unavailable : GoogleIdentityResult
}

internal interface GoogleIdentityProvider {
    val isConfigured: Boolean

    fun attachActivity(activity: Activity) = Unit

    fun detachActivity(activity: Activity) = Unit

    suspend fun acquireIdToken(): GoogleIdentityResult

    suspend fun clearCredentialState(): Boolean
}

internal class AndroidGoogleIdentityProvider(
    context: Context,
    serverClientId: String,
    private val nonceGenerator: GoogleNonceGenerator = SecureGoogleNonceGenerator(),
) : GoogleIdentityProvider {
    private val applicationContext = context.applicationContext
    private val activityReference = LifecycleBoundReference<Activity>()
    private val validatedServerClientId = serverClientId.trim().takeIf(::isValidGoogleWebClientId)

    override val isConfigured: Boolean = validatedServerClientId != null

    override fun attachActivity(activity: Activity) {
        activityReference.attach(activity)
    }

    override fun detachActivity(activity: Activity) {
        activityReference.detach(activity)
    }

    override suspend fun acquireIdToken(): GoogleIdentityResult {
        val clientId = validatedServerClientId ?: return GoogleIdentityResult.Unavailable
        val activity = activityReference.current() ?: return GoogleIdentityResult.Unavailable
        val rawNonce = nonceGenerator.generate()
        val hashedNonce = rawNonce.sha256Hex()
        val request = explicitGoogleSignInRequest(clientId = clientId, hashedNonce = hashedNonce)
        val credential = try {
            CredentialManager.create(activity)
                .getCredential(context = activity, request = request)
                .credential
        } catch (_: GetCredentialCancellationException) {
            return GoogleIdentityResult.Cancelled
        } catch (_: GetCredentialException) {
            return GoogleIdentityResult.Unavailable
        }
        return credential.toGoogleIdentityResult(rawNonce)
    }

    override suspend fun clearCredentialState(): Boolean = try {
        CredentialManager.create(applicationContext)
            .clearCredentialState(ClearCredentialStateRequest())
        true
    } catch (_: ClearCredentialException) {
        false
    }
}

internal class LifecycleBoundReference<T : Any> {
    private var reference: WeakReference<T>? = null

    fun attach(value: T) {
        reference = WeakReference(value)
    }

    fun detach(value: T) {
        val currentReference = reference ?: return
        if (currentReference.get() !== value) return
        currentReference.clear()
        reference = null
    }

    fun current(): T? = reference?.get()
}

private fun explicitGoogleSignInRequest(clientId: String, hashedNonce: String): GetCredentialRequest {
    val googleIdOption = GetSignInWithGoogleOption.Builder(clientId)
        .setNonce(hashedNonce)
        .build()
    return GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()
}

internal val explicitGoogleCredentialOptionType = GetSignInWithGoogleOption::class

private fun androidx.credentials.Credential.toGoogleIdentityResult(nonce: String): GoogleIdentityResult {
    if (this !is CustomCredential || type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        return GoogleIdentityResult.Unavailable
    }
    val credential = try {
        GoogleIdTokenCredential.createFrom(data)
    } catch (_: GoogleIdTokenParsingException) {
        return GoogleIdentityResult.Unavailable
    }
    if (credential.idToken.isBlank()) return GoogleIdentityResult.Unavailable
    return GoogleIdentityResult.Success(
        idToken = credential.idToken,
        nonce = nonce,
        profileHint = googleProfileHint(
            givenName = credential.givenName,
            familyName = credential.familyName,
            displayName = credential.displayName,
        ),
    )
}

internal fun isValidGoogleWebClientId(value: String): Boolean = GOOGLE_WEB_CLIENT_ID_PATTERN.matches(value)

internal fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private const val NONCE_BYTE_LENGTH = 32
private val GOOGLE_WEB_CLIENT_ID_PATTERN = Regex("[A-Za-z0-9-]+\\.apps\\.googleusercontent\\.com")

internal fun googleProfileHint(givenName: String?, familyName: String?, displayName: String?): GoogleProfileHint {
    val firstName = givenName.orEmpty().trim()
    val lastName = familyName.orEmpty().trim()
    if (firstName.isNotEmpty() || lastName.isNotEmpty()) return GoogleProfileHint(firstName, lastName)
    val displayParts = displayName.orEmpty().trim().split(WHITESPACE_PATTERN, limit = 2)
    return GoogleProfileHint(
        firstName = displayParts.firstOrNull().orEmpty(),
        lastName = displayParts.getOrNull(1).orEmpty(),
    )
}

private val WHITESPACE_PATTERN = Regex("\\s+")
