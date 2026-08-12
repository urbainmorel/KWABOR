package com.kwabor.android.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.kwabor.android.auth.AndroidDeepLinkClassifier
import com.kwabor.android.auth.AndroidDeepLinkDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AndroidDeepLinkDelivery(
    val deliveryId: Long,
    val rawUrl: String,
)

internal class AndroidDeepLinkSlotViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val restoredDelivery = restoreDelivery()
    private var lastDeliveryId = maxOf(
        savedStateHandle[LAST_DELIVERY_ID_KEY] ?: NO_DELIVERY_ID,
        restoredDelivery?.deliveryId ?: NO_DELIVERY_ID,
    )
    private val mutableDelivery = MutableStateFlow(restoredDelivery)
    val delivery: StateFlow<AndroidDeepLinkDelivery?> = mutableDelivery.asStateFlow()

    init {
        savedStateHandle[LAST_DELIVERY_ID_KEY] = lastDeliveryId
    }

    fun offer(rawUrl: String): Long? {
        val candidate = rawUrl.acceptedNavigationDeepLinkOrNull() ?: return null
        val current = mutableDelivery.value
        if (current?.rawUrl?.acceptedNavigationDeepLinkOrNull() == candidate) {
            return current.deliveryId
        }

        val delivery = AndroidDeepLinkDelivery(
            deliveryId = nextDeliveryId(),
            rawUrl = rawUrl,
        )
        persist(delivery)
        mutableDelivery.value = delivery
        return delivery.deliveryId
    }

    fun acknowledge(deliveryId: Long): Boolean {
        if (mutableDelivery.value?.deliveryId != deliveryId) return false
        clearPersistedDelivery()
        mutableDelivery.value = null
        return true
    }

    fun resetForSensitiveAuthTransition() {
        clearPersistedDelivery()
        mutableDelivery.value = null
    }

    private fun nextDeliveryId(): Long {
        lastDeliveryId += 1
        savedStateHandle[LAST_DELIVERY_ID_KEY] = lastDeliveryId
        return lastDeliveryId
    }

    private fun persist(delivery: AndroidDeepLinkDelivery) {
        savedStateHandle[PENDING_DELIVERY_KEY] = arrayListOf(
            delivery.deliveryId.toString(),
            delivery.rawUrl,
        )
    }

    private fun restoreDelivery(): AndroidDeepLinkDelivery? {
        val persisted = savedStateHandle.get<ArrayList<String>>(PENDING_DELIVERY_KEY) ?: return null
        if (persisted.size != PERSISTED_DELIVERY_FIELD_COUNT) return discardRestoredDelivery()
        val deliveryId = persisted[DELIVERY_ID_INDEX].toLongOrNull() ?: return discardRestoredDelivery()
        if (deliveryId <= NO_DELIVERY_ID) return discardRestoredDelivery()
        val rawUrl = persisted[RAW_URL_INDEX]
        if (rawUrl.acceptedNavigationDeepLinkOrNull() == null) return discardRestoredDelivery()
        return AndroidDeepLinkDelivery(deliveryId = deliveryId, rawUrl = rawUrl)
    }

    private fun discardRestoredDelivery(): AndroidDeepLinkDelivery? {
        clearPersistedDelivery()
        return null
    }

    private fun clearPersistedDelivery() {
        savedStateHandle.remove<ArrayList<String>>(PENDING_DELIVERY_KEY)
    }
}

private fun String.acceptedNavigationDeepLinkOrNull(): AndroidNavigationDeepLink? {
    val destination = AndroidDeepLinkClassifier.classify(this)
    if (
        destination != AndroidDeepLinkDestination.RootNavigation &&
        destination != AndroidDeepLinkDestination.CatalogDetail
    ) {
        return null
    }
    return when (val parsed = AndroidNavigationDeepLinkParser.parse(this)) {
        is AndroidNavigationDeepLink.CatalogDetail,
        is AndroidNavigationDeepLink.Root,
        AndroidNavigationDeepLink.UnavailableRoot,
        -> parsed
        AndroidNavigationDeepLink.Rejected -> null
    }
}

private const val PENDING_DELIVERY_KEY = "android.pending.deep.link"
private const val LAST_DELIVERY_ID_KEY = "android.pending.deep.link.last.delivery.id"
private const val NO_DELIVERY_ID = 0L
private const val DELIVERY_ID_INDEX = 0
private const val RAW_URL_INDEX = 1
private const val PERSISTED_DELIVERY_FIELD_COUNT = 2
