package com.kwabor.shared.domain.auth

data class AuthSession(
    val userId: String,
    val email: String?,
    val expiresAtEpochMilliseconds: Long,
)

data class EmailSignUpRequest(
    val email: String,
    val password: String,
    val firstName: String,
    val lastName: String,
    val cityId: String?,
)
