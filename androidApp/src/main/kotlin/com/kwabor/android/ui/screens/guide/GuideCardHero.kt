package com.kwabor.android.ui.screens.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kwabor.android.design.KwaborAlpha
import com.kwabor.android.design.KwaborColors
import com.kwabor.android.design.KwaborRadius
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.media.ListingMediaUrlPolicy
import com.kwabor.android.ui.components.ListingCoverImage
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.guide.GuideSummaryUiModel

@Composable
internal fun GuideCardHero(model: GuideSummaryUiModel, strings: KwaborStrings, mediaUrlPolicy: ListingMediaUrlPolicy) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(KwaborSizing.GUIDE_CARD_IMAGE_ASPECT_RATIO)
            .background(KwaborColors.Ink700),
    ) {
        GuideHeroMedia(model = model, mediaUrlPolicy = mediaUrlPolicy)
        GuideHeroTitle(model = model, modifier = Modifier.align(Alignment.BottomStart))
        if (model.verified) {
            GuideVerifiedBadge(
                label = strings.detail.verified,
                modifier = Modifier.align(Alignment.TopEnd).padding(KwaborSpacing.Lg),
            )
        }
    }
}

@Composable
private fun GuideHeroMedia(model: GuideSummaryUiModel, mediaUrlPolicy: ListingMediaUrlPolicy) {
    ListingCoverImage(
        imageUrl = model.coverImageUrl,
        mediaUrlPolicy = mediaUrlPolicy,
        modifier = Modifier.fillMaxSize(),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        KwaborColors.Transparent,
                        KwaborColors.Ink950.copy(alpha = KwaborAlpha.SCRIM_HIGH),
                    ),
                ),
            ),
    )
}

@Composable
private fun GuideHeroTitle(model: GuideSummaryUiModel, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(KwaborSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs),
    ) {
        Text(
            text = model.title,
            color = KwaborColors.Surface0,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = GUIDE_TITLE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = model.baseCityLabel,
            color = KwaborColors.Ink100,
            style = MaterialTheme.typography.labelLarge,
            maxLines = GUIDE_CITY_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GuideVerifiedBadge(label: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(KwaborRadius.Pill), color = KwaborColors.Surface0) {
        Row(
            modifier = Modifier.padding(horizontal = KwaborSpacing.Md, vertical = KwaborSpacing.Xs),
            horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(KwaborSpacing.Lg),
                tint = KwaborColors.Ink950,
            )
            Text(
                text = label,
                color = KwaborColors.Ink950,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private const val GUIDE_TITLE_MAX_LINES = 2
private const val GUIDE_CITY_MAX_LINES = 1
