package com.kwabor.shared.domain.social

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SocialPostDraftTest {
    @Test
    fun create_requiresAttachedListing() {
        val result = SocialPostDraft.create(
            authorId = "user-1",
            listingId = "",
            mediaType = SocialMediaType.Photo,
            caption = null,
            contentLocale = AppLocale.French,
            media = listOf(sampleMediaAsset()),
        )

        assertIs<DomainResult.Failure>(result)
    }

    @Test
    fun create_sortsMediaByOrder() {
        val result = SocialPostDraft.create(
            authorId = "user-1",
            listingId = "listing-1",
            mediaType = SocialMediaType.Slideshow,
            caption = "Week-end à Ouidah",
            contentLocale = AppLocale.French,
            media = listOf(
                sampleMediaAsset(order = 2),
                sampleMediaAsset(order = 1),
            ),
        )

        val success = assertIs<DomainResult.Success<SocialPostDraft>>(result)
        assertEquals(listOf(1, 2), success.value.media.map { asset -> asset.order })
    }

    private fun sampleMediaAsset(order: Int = 1): SocialMediaAsset = SocialMediaAsset(
        url = "https://cdn.kwabor.test/media-$order.webp",
        alt = "Vue $order",
        order = order,
    )
}
