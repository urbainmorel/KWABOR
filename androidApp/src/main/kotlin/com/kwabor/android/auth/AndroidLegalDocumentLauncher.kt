package com.kwabor.android.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI
import java.net.URISyntaxException

internal sealed interface LegalDocumentOpenResult {
    data object Opened : LegalDocumentOpenResult

    data object Rejected : LegalDocumentOpenResult

    data class Unavailable(val cause: ActivityNotFoundException) : LegalDocumentOpenResult
}

internal fun interface LegalDocumentLauncher {
    fun openHttps(url: String): LegalDocumentOpenResult
}

internal class AndroidLegalDocumentLauncher(
    private val context: Context,
) : LegalDocumentLauncher {
    override fun openHttps(url: String): LegalDocumentOpenResult {
        val uri = url.toSafeHttpsUri() ?: return LegalDocumentOpenResult.Rejected
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            LegalDocumentOpenResult.Opened
        } catch (exception: ActivityNotFoundException) {
            LegalDocumentOpenResult.Unavailable(exception)
        }
    }
}

private fun String.toSafeHttpsUri(): Uri? = takeIf(String::isSafeHttpsLegalDocumentUrl)?.let(Uri::parse)

internal fun String.isSafeHttpsLegalDocumentUrl(): Boolean {
    if (length !in MINIMUM_HTTPS_URL_LENGTH..MAXIMUM_HTTPS_URL_LENGTH || any(Char::isWhitespace)) {
        return false
    }
    val uri = try {
        URI(this)
    } catch (_: URISyntaxException) {
        return false
    }
    return uri.scheme.equals(HTTPS_SCHEME, ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null
}

private const val HTTPS_SCHEME = "https"
private const val MINIMUM_HTTPS_URL_LENGTH = 9
private const val MAXIMUM_HTTPS_URL_LENGTH = 2_048
