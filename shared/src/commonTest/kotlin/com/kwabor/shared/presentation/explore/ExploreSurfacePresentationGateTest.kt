package com.kwabor.shared.presentation.explore

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExploreSurfacePresentationGateTest {
    @Test
    fun businessDismissalDoesNotUnblockBeforeThePresentationCallback() {
        val gate = ExploreSurfacePresentationGate()
        val token = gate.onPresentationStarted(ExploreSurfacePresentationKind.CitySelector)

        assertTrue(gate.isObscured)
        assertTrue(gate.onPresentationDismissed(token))
        assertFalse(gate.isObscured)
    }

    @Test
    fun delayedAndDuplicateCallbackFromPresentationACannotFinishReopenedPresentationB() {
        val gate = ExploreSurfacePresentationGate()
        val presentationA = gate.onPresentationStarted(ExploreSurfacePresentationKind.CatalogDetail)
        val presentationB = gate.onPresentationStarted(ExploreSurfacePresentationKind.CatalogDetail)

        assertTrue(gate.onPresentationDismissed(presentationA))
        assertFalse(gate.onPresentationDismissed(presentationA))
        assertTrue(gate.isObscured)
        assertTrue(gate.onPresentationDismissed(presentationB))
        assertFalse(gate.isObscured)
    }

    @Test
    fun dismissingOnePresentationKeepsTheOtherObstructionActive() {
        val gate = ExploreSurfacePresentationGate()
        val city = gate.onPresentationStarted(ExploreSurfacePresentationKind.CitySelector)
        val detail = gate.onPresentationStarted(ExploreSurfacePresentationKind.CatalogDetail)

        assertTrue(gate.onPresentationDismissed(detail))
        assertTrue(gate.isObscured)
        assertTrue(gate.onPresentationDismissed(city))
        assertFalse(gate.isObscured)
    }

    @Test
    fun interleavedGenerationsAndKindsRemainObscuredUntilEveryTokenCompletes() {
        val gate = ExploreSurfacePresentationGate()
        val cityA = gate.onPresentationStarted(ExploreSurfacePresentationKind.CitySelector)
        val detailA = gate.onPresentationStarted(ExploreSurfacePresentationKind.CatalogDetail)
        val cityB = gate.onPresentationStarted(ExploreSurfacePresentationKind.CitySelector)
        val detailB = gate.onPresentationStarted(ExploreSurfacePresentationKind.CatalogDetail)

        assertTrue(gate.onPresentationDismissed(cityB))
        assertTrue(gate.onPresentationDismissed(detailA))
        assertFalse(gate.onPresentationDismissed(cityB))
        assertTrue(gate.isObscured)

        assertTrue(gate.onPresentationDismissed(cityA))
        assertTrue(gate.isObscured)
        assertTrue(gate.onPresentationDismissed(detailB))
        assertFalse(gate.isObscured)
    }
}
