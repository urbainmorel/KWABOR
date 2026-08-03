package com.kwabor.android.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

@Composable
internal fun SensitiveAuthDeepLinkResetHandler(
    signOutInProgress: Boolean,
    accountDeletionInProgress: Boolean,
    onReset: () -> Unit,
) {
    val currentReset by rememberUpdatedState(onReset)
    LaunchedEffect(signOutInProgress, accountDeletionInProgress) {
        if (
            AndroidSensitiveAuthDeepLinkPolicy.shouldResetPending(
                signOutInProgress = signOutInProgress,
                accountDeletionInProgress = accountDeletionInProgress,
            )
        ) {
            currentReset()
        }
    }
}

@Composable
internal fun DeepLinkEffectHandler(
    deepLink: AndroidDeepLinkDelivery?,
    actions: AndroidNavigationDeepLinkDispatchActions,
) {
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(deepLink?.deliveryId) {
        val currentDeepLink = deepLink ?: return@LaunchedEffect
        dispatchAndroidNavigationDeepLink(currentDeepLink, currentActions)
    }
}
