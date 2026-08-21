package com.kwabor.shared.presentation.explore

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExploreSurfacePresentationRegistryTest {
    @Test
    fun constructedButNeverAttachedPresentationCompletesImmediatelyWhenDismissed() {
        val registry = ExploreSurfacePresentationRegistry()
        val token = token(generation = 1L)
        registry.begin(token)

        assertTrue(registry.dismissRequested(token) == token)
        assertNull(registry.dismissRequested(token))
        assertNull(registry.removed(token))
    }

    @Test
    fun attachedPresentationWaitsForRealRemovalAndCompletesExactlyOnce() {
        val registry = ExploreSurfacePresentationRegistry()
        val token = token(generation = 1L)
        registry.begin(token)
        registry.attached(token)

        assertNull(registry.dismissRequested(token))
        assertTrue(registry.removed(token) == token)
        assertNull(registry.removed(token))
    }

    @Test
    fun cancelledQueuedBAndDelayedCallbackAAllowFuturePresentationC() {
        val gate = ExploreSurfacePresentationGate()
        val registry = ExploreSurfacePresentationRegistry()
        val presentationA = gate.onPresentationStarted(ExploreSurfacePresentationKind.CatalogDetail)
        registry.begin(presentationA)
        registry.attached(presentationA)
        assertNull(registry.dismissRequested(presentationA))

        val presentationB = gate.onPresentationStarted(ExploreSurfacePresentationKind.CatalogDetail)
        registry.begin(presentationB)
        val completedB = registry.dismissRequested(presentationB)
        assertTrue(gate.onPresentationDismissed(requireNotNull(completedB)))
        assertTrue(gate.isObscured)

        val delayedA = registry.removed(presentationA)
        assertTrue(gate.onPresentationDismissed(requireNotNull(delayedA)))
        assertFalse(gate.onPresentationDismissed(presentationA))
        assertFalse(gate.isObscured)
        assertNull(registry.removed(presentationA))

        val presentationC = gate.onPresentationStarted(ExploreSurfacePresentationKind.CatalogDetail)
        registry.begin(presentationC)
        assertTrue(gate.isObscured)
        assertTrue(gate.onPresentationDismissed(requireNotNull(registry.dismissRequested(presentationC))))
        assertFalse(gate.isObscured)
    }
}

private fun token(generation: Long): ExploreSurfacePresentationToken = ExploreSurfacePresentationToken(
    generation = generation,
    kind = ExploreSurfacePresentationKind.CitySelector,
)
