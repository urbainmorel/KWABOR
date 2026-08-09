package com.kwabor.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kwabor.android.design.KwaborAlpha
import com.kwabor.android.design.KwaborColors
import com.kwabor.android.design.KwaborRadius
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.media.ListingMediaUrlPolicy
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.KwaborStrings

private const val LISTING_CARD_WIDTH_RATIO = 3f
private const val LISTING_CARD_HEIGHT_RATIO = 4f

data class ListingCardActions(
    val onClick: (() -> Unit)?,
    val onLikeClick: (() -> Unit)? = null,
    val onFavoriteClick: (() -> Unit)? = null,
    val favoriteLabel: String? = null,
    val favoriteEnabled: Boolean = true,
    val favoriteInProgress: Boolean = false,
    val openAccessibilityDescription: String? = null,
)

@Composable
fun ListingCard(
    state: ListingCardState,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    modifier: Modifier = Modifier,
    actions: ListingCardActions,
) {
    Surface(
        modifier = modifier
            .aspectRatio(LISTING_CARD_WIDTH_RATIO / LISTING_CARD_HEIGHT_RATIO)
            .clip(RoundedCornerShape(KwaborRadius.Card))
            .listingClick(
                actions.onClick.takeIf { actions.openAccessibilityDescription == null },
            ),
        color = KwaborColors.Ink950,
        shape = RoundedCornerShape(KwaborRadius.Card),
    ) {
        ListingCardContent(
            state = state,
            strings = strings,
            mediaUrlPolicy = mediaUrlPolicy,
            actions = actions,
        )
    }
}

@Composable
private fun ListingCardContent(
    state: ListingCardState,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    actions: ListingCardActions,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(placeholderGradient(state.placeholderColor))
            .listingTraversalGroup(actions.openAccessibilityDescription != null),
    ) {
        ListingCoverImage(
            imageUrl = state.coverImageUrl,
            mediaUrlPolicy = mediaUrlPolicy,
            modifier = Modifier.fillMaxSize(),
            contentDescription = state.imageAccessibilityDescription(
                exposeImageSemantics = actions.openAccessibilityDescription == null,
            ),
        )
        Box(modifier = Modifier.fillMaxSize().background(listingScrim()))
        ListingCardBody(
            state = state,
            strings = strings,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(
                start = KwaborSpacing.Lg,
                top = KwaborSpacing.Lg,
                end = KwaborSizing.MinimumAccessibleTouchTarget + KwaborSpacing.Md,
                bottom = KwaborSpacing.Lg,
            ).clearVisualSemantics(actions.openAccessibilityDescription != null),
        )
        ListingCardOpenAction(
            actions = actions,
            modifier = Modifier.fillMaxSize(),
        )
        ListingCardTopBar(
            state = state,
            strings = strings,
            actions = actions,
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(KwaborSpacing.Md),
        )
    }
}

@Composable
private fun ListingCardOpenAction(actions: ListingCardActions, modifier: Modifier) {
    val description = actions.openAccessibilityDescription ?: return
    val onClick = actions.onClick ?: return
    Box(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = description
                traversalIndex = OPEN_ACTION_TRAVERSAL_INDEX
                onClick(label = null) {
                    onClick()
                    true
                }
            },
    )
}

private fun placeholderGradient(color: Color): Brush = Brush.verticalGradient(
    colors = listOf(color, KwaborColors.Ink950),
)

private fun listingScrim(): Brush = Brush.verticalGradient(
    colors = listOf(
        Color.Transparent,
        KwaborColors.Ink950.copy(alpha = KwaborAlpha.SCRIM_LOW),
        KwaborColors.Ink950.copy(alpha = KwaborAlpha.SCRIM_HIGH),
    ),
)

@Composable
private fun ListingCardBody(state: ListingCardState, strings: KwaborStrings, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm)) {
        Text(
            text = state.title,
            color = KwaborColors.Surface0,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = state.cityLabel,
            color = KwaborColors.Ink100,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        PriceTag(price = state.price, strings = strings, options = state.priceOptions)
    }
}

@Composable
private fun ListingRatingBadge(label: String, strings: KwaborStrings) {
    Row(
        modifier = Modifier
            .background(
                color = KwaborColors.Ink950.copy(alpha = KwaborAlpha.SCRIM_HIGH),
                shape = RoundedCornerShape(KwaborRadius.Pill),
            )
            .padding(horizontal = KwaborSpacing.Md, vertical = KwaborSpacing.Xs),
        horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = strings.rating,
            modifier = Modifier.size(KwaborSpacing.Lg),
            tint = KwaborColors.Sponsored,
        )
        Text(
            text = label,
            color = KwaborColors.Surface0,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

data class ListingCardState(
    val title: String,
    val cityLabel: String,
    val coverImageUrl: String? = null,
    val coverImageAlt: String? = null,
    val price: MoneyXof?,
    val priceOptions: PriceTagOptions = PriceTagOptions(mode = PriceTagMode.Compact),
    val ratingLabel: String? = null,
    val eventDateLabel: String? = null,
    val sponsored: Boolean = false,
    val liked: Boolean = false,
    val favorited: Boolean = false,
    val eventEnded: Boolean = false,
    val placeholderColor: Color = KwaborColors.Ink500,
)

@Composable
private fun ListingCardTopBar(
    state: ListingCardState,
    strings: KwaborStrings,
    actions: ListingCardActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        ListingCardBadges(
            state = state,
            strings = strings,
            modifier = Modifier
                .align(Alignment.TopStart)
                .endedRibbonClearance(state.eventEnded)
                .clearVisualSemantics(actions.openAccessibilityDescription != null),
        )
        if (state.eventEnded) {
            EventEndedRibbon(
                label = strings.favorites.eventEnded,
                accessibilityLabel = strings.favorites.eventEndedAccessibility,
                traversalOrder = EVENT_ENDED_RIBBON_TRAVERSAL_INDEX,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = -KwaborSpacing.Xxl, y = KwaborSpacing.Lg),
            )
        }
        ListingCardActionButtons(
            state = state,
            strings = strings,
            actions = actions,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun ListingCardBadges(state: ListingCardState, strings: KwaborStrings, modifier: Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs),
    ) {
        if (state.sponsored) {
            SponsoredBadge(strings = strings)
        } else {
            state.ratingLabel
                ?.takeIf(String::isNotBlank)
                ?.let { rating -> ListingRatingBadge(label = rating, strings = strings) }
        }
        state.eventDateLabel
            ?.takeIf(String::isNotBlank)
            ?.let { eventDate -> ListingEventDateBadge(label = eventDate) }
    }
}

@Composable
private fun ListingEventDateBadge(label: String) {
    Text(
        text = label,
        modifier = Modifier
            .background(
                color = KwaborColors.Ink950.copy(alpha = KwaborAlpha.SCRIM_HIGH),
                shape = RoundedCornerShape(KwaborRadius.Pill),
            )
            .padding(horizontal = KwaborSpacing.Md, vertical = KwaborSpacing.Xs),
        color = KwaborColors.Surface0,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ListingCardActionButtons(
    state: ListingCardState,
    strings: KwaborStrings,
    actions: ListingCardActions,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        actions.onFavoriteClick?.let { onFavoriteClick ->
            ListingActionButton(
                model = actions.favoriteButtonModel(state, strings),
                onClick = onFavoriteClick,
            )
        }
        actions.onLikeClick?.let { onLikeClick ->
            ListingActionButton(
                model = actions.likeButtonModel(state, strings),
                onClick = onLikeClick,
            )
        }
    }
}

private data class ListingActionButtonModel(
    val label: String,
    val selected: Boolean,
    val selectedColor: Color = KwaborColors.Ink950,
    val imageVector: ImageVector,
    val enabled: Boolean = true,
    val progressLabel: String? = null,
    val traversalIndex: Float? = null,
)

private fun ListingCardActions.favoriteButtonModel(
    state: ListingCardState,
    strings: KwaborStrings,
): ListingActionButtonModel = ListingActionButtonModel(
    label = favoriteLabel ?: strings.favorite,
    selected = state.favorited,
    imageVector = Icons.Filled.Bookmark,
    enabled = favoriteEnabled,
    progressLabel = if (favoriteInProgress) strings.loading else null,
    traversalIndex = openAccessibilityDescription?.let { FAVORITE_ACTION_TRAVERSAL_INDEX },
)

private fun ListingCardActions.likeButtonModel(
    state: ListingCardState,
    strings: KwaborStrings,
): ListingActionButtonModel = ListingActionButtonModel(
    label = strings.like,
    selected = state.liked,
    selectedColor = KwaborColors.Ticket,
    imageVector = Icons.Filled.Favorite,
    traversalIndex = openAccessibilityDescription?.let { LIKE_ACTION_TRAVERSAL_INDEX },
)

@Composable
private fun ListingActionButton(model: ListingActionButtonModel, onClick: () -> Unit) {
    val progressLabel = model.progressLabel
    IconToggleButton(
        checked = model.selected,
        onCheckedChange = { onClick() },
        enabled = model.enabled && progressLabel == null,
        modifier = Modifier
            .size(KwaborSizing.MinimumAccessibleTouchTarget)
            .listingActionTraversal(model.traversalIndex),
    ) {
        Box(
            modifier = Modifier
                .size(KwaborSizing.FloatingPill)
                .background(
                    color = KwaborColors.Surface0.copy(alpha = KwaborAlpha.FROSTED_SURFACE),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (progressLabel != null) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(KwaborSpacing.Xxl)
                        .semantics { contentDescription = progressLabel },
                    color = KwaborColors.Ink950,
                )
            } else {
                Icon(
                    imageVector = model.imageVector,
                    contentDescription = model.label,
                    modifier = Modifier.size(KwaborSpacing.Xxl),
                    tint = if (model.selected) model.selectedColor else KwaborColors.Surface0,
                )
            }
        }
    }
}

private fun Modifier.listingClick(onClick: (() -> Unit)?): Modifier =
    if (onClick == null) this else clickable(role = Role.Button, onClick = onClick)

private fun Modifier.clearVisualSemantics(enabled: Boolean): Modifier = if (enabled) clearAndSetSemantics {} else this

private fun Modifier.listingTraversalGroup(enabled: Boolean): Modifier = if (enabled) {
    semantics { isTraversalGroup = true }
} else {
    this
}

private fun Modifier.listingActionTraversal(index: Float?): Modifier = if (index == null) {
    this
} else {
    semantics { traversalIndex = index }
}

private fun Modifier.endedRibbonClearance(enabled: Boolean): Modifier = if (enabled) {
    padding(top = KwaborSpacing.Xxxl + KwaborSpacing.Lg)
} else {
    this
}

private const val OPEN_ACTION_TRAVERSAL_INDEX = 0f
private const val EVENT_ENDED_RIBBON_TRAVERSAL_INDEX = 0.5f
private const val FAVORITE_ACTION_TRAVERSAL_INDEX = 1f
private const val LIKE_ACTION_TRAVERSAL_INDEX = 2f
