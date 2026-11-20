package com.kwabor.shared.presentation.explore

class ExploreSurfacePresentationRegistry {
    private val entriesByGeneration = mutableMapOf<Long, PresentationEntry>()

    fun begin(token: ExploreSurfacePresentationToken) {
        val existing = entriesByGeneration[token.generation]
        if (existing?.token == token) return
        entriesByGeneration[token.generation] = PresentationEntry(token = token)
    }

    fun attached(token: ExploreSurfacePresentationToken) {
        val entry = entryFor(token) ?: return
        when (entry.phase) {
            PresentationPhase.Queued -> entry.phase = PresentationPhase.Attached
            PresentationPhase.Attached,
            PresentationPhase.DismissRequested,
            PresentationPhase.Completed,
            -> Unit
        }
    }

    fun dismissRequested(token: ExploreSurfacePresentationToken): ExploreSurfacePresentationToken? {
        val entry = entryFor(token) ?: return null
        return when (entry.phase) {
            PresentationPhase.Queued -> {
                entry.phase = PresentationPhase.DismissRequested
                complete(entry)
            }
            PresentationPhase.Attached -> {
                entry.phase = PresentationPhase.DismissRequested
                if (entry.removalObserved) complete(entry) else null
            }
            PresentationPhase.DismissRequested -> if (entry.removalObserved) complete(entry) else null
            PresentationPhase.Completed -> null
        }
    }

    fun removed(token: ExploreSurfacePresentationToken): ExploreSurfacePresentationToken? {
        val entry = entryFor(token) ?: return null
        return when (entry.phase) {
            PresentationPhase.Queued -> null
            PresentationPhase.Attached -> {
                entry.removalObserved = true
                null
            }
            PresentationPhase.DismissRequested -> complete(entry)
            PresentationPhase.Completed -> null
        }
    }

    private fun entryFor(token: ExploreSurfacePresentationToken): PresentationEntry? =
        entriesByGeneration[token.generation]?.takeIf { it.token == token }

    private fun complete(entry: PresentationEntry): ExploreSurfacePresentationToken {
        entry.phase = PresentationPhase.Completed
        entriesByGeneration.remove(entry.token.generation)
        return entry.token
    }
}

private data class PresentationEntry(
    val token: ExploreSurfacePresentationToken,
    var phase: PresentationPhase = PresentationPhase.Queued,
    var removalObserved: Boolean = false,
)

private enum class PresentationPhase {
    Queued,
    Attached,
    DismissRequested,
    Completed,
}
