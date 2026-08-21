package com.kwabor.shared.presentation.explore

enum class ExploreSurfacePresentationKind {
    CitySelector,
    CatalogDetail,
}

data class ExploreSurfacePresentationToken(
    val generation: Long,
    val kind: ExploreSurfacePresentationKind,
)

class ExploreSurfacePresentationGate {
    private var generation = 0L
    private val activeTokens = mutableSetOf<ExploreSurfacePresentationToken>()

    val isObscured: Boolean
        get() = activeTokens.isNotEmpty()

    fun onPresentationStarted(kind: ExploreSurfacePresentationKind): ExploreSurfacePresentationToken {
        generation = generation.nextPresentationGeneration()
        val token = ExploreSurfacePresentationToken(generation = generation, kind = kind)
        activeTokens += token
        return token
    }

    fun onPresentationDismissed(token: ExploreSurfacePresentationToken): Boolean = activeTokens.remove(token)
}

private fun Long.nextPresentationGeneration(): Long = if (this == Long.MAX_VALUE) 1L else this + 1L
