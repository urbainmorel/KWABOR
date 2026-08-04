package com.kwabor.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.kwabor.android.media.ListingMediaUrlPolicy

@Composable
internal fun ListingCoverImage(
    imageUrl: String?,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    modifier: Modifier,
    contentDescription: String? = null,
) {
    val safeImageUrl = remember(imageUrl, mediaUrlPolicy) {
        mediaUrlPolicy.safeUrlOrNull(imageUrl)
    } ?: return
    AsyncImage(
        model = safeImageUrl,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}
