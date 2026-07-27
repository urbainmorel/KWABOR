package com.kwabor.android.auth

import java.util.UUID

internal fun interface IdempotencyKeyProvider {
    fun create(): String
}

internal object UuidIdempotencyKeyProvider : IdempotencyKeyProvider {
    override fun create(): String = UUID.randomUUID().toString()
}
