package com.kwabor.shared.domain.social

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale

enum class SocialMediaType {
    Photo,
    Slideshow,
    Video,
}

enum class ModerationStatus {
    Pending,
    Published,
    Rejected,
    Hidden,
}

data class SocialMediaAsset(
    val url: String,
    val alt: String,
    val order: Int,
)

data class SocialPost(
    val id: String,
    val authorId: String,
    val listingId: String,
    val mediaType: SocialMediaType,
    val caption: String?,
    val contentLocale: AppLocale,
    val media: List<SocialMediaAsset>,
    val moderationStatus: ModerationStatus,
    val watermarkApplied: Boolean,
    val likesCount: Int,
    val createdAtEpochMilliseconds: Long,
)

class SocialPostDraft private constructor(
    val authorId: String,
    val listingId: String,
    val mediaType: SocialMediaType,
    val caption: String?,
    val contentLocale: AppLocale,
    val media: List<SocialMediaAsset>,
) {
    companion object {
        fun create(
            authorId: String,
            listingId: String,
            mediaType: SocialMediaType,
            caption: String?,
            contentLocale: AppLocale,
            media: List<SocialMediaAsset>,
        ): DomainResult<SocialPostDraft> {
            if (authorId.isBlank()) {
                return DomainResult.Failure(DomainError.Validation("error.social.author_required"))
            }

            if (listingId.isBlank()) {
                return DomainResult.Failure(DomainError.Validation("error.social.listing_required"))
            }

            if (media.isEmpty()) {
                return DomainResult.Failure(DomainError.Validation("error.social.media_required"))
            }

            return DomainResult.Success(
                SocialPostDraft(
                    authorId = authorId,
                    listingId = listingId,
                    mediaType = mediaType,
                    caption = caption,
                    contentLocale = contentLocale,
                    media = media.sortedBy { asset -> asset.order },
                ),
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is SocialPostDraft &&
        authorId == other.authorId &&
        listingId == other.listingId &&
        mediaType == other.mediaType &&
        caption == other.caption &&
        contentLocale == other.contentLocale &&
        media == other.media

    override fun hashCode(): Int {
        var result = authorId.hashCode()
        result = 31 * result + listingId.hashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + (caption?.hashCode() ?: 0)
        result = 31 * result + contentLocale.hashCode()
        result = 31 * result + media.hashCode()
        return result
    }

    override fun toString(): String =
        "SocialPostDraft(authorId=$authorId, listingId=$listingId, mediaType=$mediaType, contentLocale=$contentLocale)"
}
