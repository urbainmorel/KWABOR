package com.kwabor.android.app

import androidx.lifecycle.SavedStateHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidDeepLinkSlotViewModelTest {
    @Test
    fun lastValidLinkWinsWhilePendingDuplicatesCoalesce() {
        val viewModel = AndroidDeepLinkSlotViewModel(SavedStateHandle())
        val firstDeliveryId = assertNotNull(viewModel.offer(FIRST_DETAIL_LINK))

        assertEquals(
            firstDeliveryId,
            viewModel.offer("KWABOR://LISTING/${FIRST_LISTING_ID.uppercase()}"),
        )
        assertNull(viewModel.offer("kwabor://listing/not-a-uuid"))
        assertEquals(firstDeliveryId, viewModel.delivery.value?.deliveryId)

        val replacementDeliveryId = assertNotNull(viewModel.offer(SECOND_DETAIL_LINK))
        assertNotEquals(firstDeliveryId, replacementDeliveryId)
        assertEquals(SECOND_DETAIL_LINK, viewModel.delivery.value?.rawUrl)
        assertFalse(viewModel.acknowledge(firstDeliveryId))
        assertEquals(replacementDeliveryId, viewModel.delivery.value?.deliveryId)

        assertTrue(viewModel.acknowledge(replacementDeliveryId))
        assertNull(viewModel.delivery.value)
        val repeatedAfterAcknowledgement = assertNotNull(viewModel.offer(SECOND_DETAIL_LINK))
        assertNotEquals(replacementDeliveryId, repeatedAfterAcknowledgement)
    }

    @Test
    fun savedStateRestoresPendingDeliveryAndMonotonicIdentifier() {
        val savedStateHandle = SavedStateHandle()
        val original = AndroidDeepLinkSlotViewModel(savedStateHandle)
        val originalDeliveryId = assertNotNull(original.offer(FIRST_DETAIL_LINK))

        val restored = AndroidDeepLinkSlotViewModel(savedStateHandle)

        assertEquals(
            AndroidDeepLinkDelivery(originalDeliveryId, FIRST_DETAIL_LINK),
            restored.delivery.value,
        )
        val replacementDeliveryId = assertNotNull(restored.offer(SECOND_DETAIL_LINK))
        assertTrue(replacementDeliveryId > originalDeliveryId)
    }

    @Test
    fun sensitiveResetClearsPendingWithoutReusingItsIdentifier() {
        val viewModel = AndroidDeepLinkSlotViewModel(SavedStateHandle())
        val clearedDeliveryId = assertNotNull(viewModel.offer(FIRST_DETAIL_LINK))

        viewModel.resetForSensitiveAuthTransition()

        assertNull(viewModel.delivery.value)
        val nextDeliveryId = assertNotNull(viewModel.offer(FIRST_DETAIL_LINK))
        assertTrue(nextDeliveryId > clearedDeliveryId)
    }
}

private const val FIRST_LISTING_ID = "123e4567-e89b-42d3-a456-426614174000"
private const val SECOND_LISTING_ID = "223e4567-e89b-42d3-a456-426614174000"
private const val FIRST_DETAIL_LINK = "kwabor://listing/$FIRST_LISTING_ID"
private const val SECOND_DETAIL_LINK = "kwabor://listing/$SECOND_LISTING_ID"
