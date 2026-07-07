package com.kwabor.shared.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

@Composable
internal actual fun ListingCoverImage(imageUrl: String?, modifier: Modifier) {
    val safeImageUrl = imageUrl?.takeIf { value -> value.isNotBlank() } ?: return
    AsyncImage(
        model = safeImageUrl,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}
