package com.kwabor.shared.i18n

data class ExploreDateStrings(
    val monthNames: List<String>,
    val forwardIndicator: String,
) {
    init {
        require(monthNames.size == MONTHS_PER_YEAR) {
            "Explore date strings require exactly twelve month names."
        }
        require(monthNames.all(String::isNotBlank)) {
            "Explore month names must not be blank."
        }
        require(forwardIndicator.isNotBlank()) {
            "Explore date forward indicator must not be blank."
        }
    }
}

internal val frenchExploreDateStrings = ExploreDateStrings(
    monthNames = listOf(
        "janvier",
        "février",
        "mars",
        "avril",
        "mai",
        "juin",
        "juillet",
        "août",
        "septembre",
        "octobre",
        "novembre",
        "décembre",
    ),
    forwardIndicator = "›",
)

private const val MONTHS_PER_YEAR = 12
