package com.kwabor.shared.data.core

private val UUID_PATTERN = Regex(
    pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    option = RegexOption.IGNORE_CASE,
)

internal fun String.isValidUuid(): Boolean = UUID_PATTERN.matches(this)
