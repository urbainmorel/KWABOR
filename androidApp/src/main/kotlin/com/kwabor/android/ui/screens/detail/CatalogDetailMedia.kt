package com.kwabor.android.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kwabor.android.R
import com.kwabor.android.design.KwaborAlpha
import com.kwabor.android.design.KwaborColors
import com.kwabor.android.design.KwaborRadius
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.media.ListingMediaUrlPolicy
import com.kwabor.android.ui.components.ListingCoverImage
import com.kwabor.shared.presentation.detail.CatalogDetailContentUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailMediaUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailUiModel

@Composable
internal fun DetailHero(
    model: CatalogDetailUiModel,
    media: CatalogDetailMediaUiModel?,
    resources: CatalogDetailContentResources,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(KwaborColors.Ink700)
            .semantics { isTraversalGroup = true },
    ) {
        DetailHeroImage(media = media, mediaUrlPolicy = resources.mediaUrlPolicy)
        DetailHeroScrim()
        DetailHeroClose(
            label = resources.strings.detail.close,
            onDismiss = resources.actions.onDismiss,
            modifier = Modifier.align(Alignment.TopStart),
        )
        if ((model.content as? CatalogDetailContentUiModel.Event)?.isEnded == true) {
            DetailHeroEventEndedRibbon(
                label = resources.strings.detail.eventEnded,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        DetailHeroTitle(
            model = model,
            verifiedLabel = resources.strings.detail.verified,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

@Composable
private fun DetailHeroEventEndedRibbon(label: String, modifier: Modifier) {
    Surface(
        modifier = modifier
            .padding(top = KwaborSpacing.Xxxl, end = KwaborSpacing.Xxl)
            .rotate(EVENT_ENDED_RIBBON_ROTATION_DEGREES)
            .clearAndSetSemantics {
                contentDescription = label
                traversalIndex = HERO_EVENT_ENDED_TRAVERSAL_INDEX
            },
        shape = RoundedCornerShape(KwaborRadius.Control),
        color = KwaborColors.Ink500,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = KwaborSpacing.Xl, vertical = KwaborSpacing.Sm),
            color = KwaborColors.Surface0,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun DetailHeroImage(media: CatalogDetailMediaUiModel?, mediaUrlPolicy: ListingMediaUrlPolicy) {
    media?.let { selectedMedia ->
        ListingCoverImage(
            imageUrl = selectedMedia.url,
            mediaUrlPolicy = mediaUrlPolicy,
            contentDescription = selectedMedia.alt,
            modifier = Modifier
                .fillMaxSize()
                .semantics { traversalIndex = HERO_IMAGE_TRAVERSAL_INDEX },
        )
    }
}

@Composable
private fun DetailHeroScrim() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, KwaborColors.Ink950.copy(alpha = KwaborAlpha.SCRIM_HERO)),
                ),
            ),
    )
}

@Composable
private fun DetailHeroClose(label: String, onDismiss: () -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier.padding(KwaborSpacing.Lg),
        shape = CircleShape,
        color = KwaborColors.Ink950.copy(alpha = KwaborAlpha.SCRIM_HIGH),
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(KwaborSizing.MinimumAccessibleTouchTarget)
                .semantics { traversalIndex = HERO_CLOSE_TRAVERSAL_INDEX },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = label,
                tint = KwaborColors.Surface0,
            )
        }
    }
}

@Composable
private fun DetailHeroTitle(model: CatalogDetailUiModel, verifiedLabel: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(KwaborSpacing.Xxl)
            .semantics {
                isTraversalGroup = true
                traversalIndex = HERO_TITLE_TRAVERSAL_INDEX
            },
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
    ) {
        Text(
            text = model.contextLabel,
            color = KwaborColors.Ink100,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = model.title,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
                color = KwaborColors.Surface0,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (model.verified) {
                DetailVerifiedBadge(verifiedLabel)
            }
        }
    }
}

@Composable
private fun DetailVerifiedBadge(label: String) {
    Surface(shape = RoundedCornerShape(KwaborRadius.Pill), color = KwaborColors.Surface0) {
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

@Composable
internal fun DetailGallery(
    media: List<VisibleCatalogDetailMedia>,
    selectedVisibleIndex: Int,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    onMediaSelected: (Int) -> Unit,
    modifier: Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
    ) {
        itemsIndexed(items = media, key = { _, item -> item.sourceIndex }) { index, item ->
            DetailGalleryItem(
                item = item,
                selected = index == selectedVisibleIndex,
                mediaUrlPolicy = mediaUrlPolicy,
                onMediaSelected = onMediaSelected,
            )
        }
    }
}

@Composable
private fun DetailGalleryItem(
    item: VisibleCatalogDetailMedia,
    selected: Boolean,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    onMediaSelected: (Int) -> Unit,
) {
    val selectionLabel = stringResource(R.string.detail_select_image, item.media.alt)
    ListingCoverImage(
        imageUrl = item.media.url,
        mediaUrlPolicy = mediaUrlPolicy,
        contentDescription = null,
        modifier = Modifier
            .size(KwaborSizing.DetailGalleryThumbnail)
            .clip(RoundedCornerShape(KwaborRadius.Control))
            .then(selectedGalleryBorder(selected))
            .clickable(
                onClickLabel = selectionLabel,
                role = Role.Button,
                onClick = { onMediaSelected(item.sourceIndex) },
            )
            .semantics {
                contentDescription = selectionLabel
                this.selected = selected
            },
    )
}

@Composable
private fun selectedGalleryBorder(selected: Boolean): Modifier = if (selected) {
    Modifier.border(
        width = KwaborSizing.Hairline,
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(KwaborRadius.Control),
    )
} else {
    Modifier
}

private const val HERO_CLOSE_TRAVERSAL_INDEX = 0f
private const val HERO_TITLE_TRAVERSAL_INDEX = 1f
private const val HERO_EVENT_ENDED_TRAVERSAL_INDEX = 1.5f
private const val HERO_IMAGE_TRAVERSAL_INDEX = 2f
private const val EVENT_ENDED_RIBBON_ROTATION_DEGREES = 45f
