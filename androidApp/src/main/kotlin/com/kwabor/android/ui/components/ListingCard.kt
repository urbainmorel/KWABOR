package com.kwabor.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kwabor.android.design.KwaborAlpha
import com.kwabor.android.design.KwaborColors
import com.kwabor.android.design.KwaborRadius
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.KwaborStrings

private const val LISTING_CARD_WIDTH_RATIO = 3f
private const val LISTING_CARD_HEIGHT_RATIO = 4f

data class ListingCardActions(
    val onClick: () -> Unit = {},
    val onLikeClick: () -> Unit = {},
    val onFavoriteClick: () -> Unit = {},
)

@Composable
fun ListingCard(
    state: ListingCardState,
    strings: KwaborStrings,
    modifier: Modifier = Modifier,
    priceOptions: PriceTagOptions = PriceTagOptions(mode = PriceTagMode.Compact),
    actions: ListingCardActions = ListingCardActions(),
) {
    Surface(
        modifier = modifier
            .aspectRatio(LISTING_CARD_WIDTH_RATIO / LISTING_CARD_HEIGHT_RATIO)
            .clip(RoundedCornerShape(KwaborRadius.Card))
            .clickable(onClick = actions.onClick),
        color = KwaborColors.Ink950,
        shape = RoundedCornerShape(KwaborRadius.Card),
    ) {
        ListingCardContent(state = state, strings = strings, priceOptions = priceOptions, actions = actions)
    }
}

@Composable
private fun ListingCardContent(
    state: ListingCardState,
    strings: KwaborStrings,
    priceOptions: PriceTagOptions,
    actions: ListingCardActions,
) {
    Box(modifier = Modifier.fillMaxSize().background(placeholderGradient(state.placeholderColor))) {
        ListingCoverImage(imageUrl = state.coverImageUrl, modifier = Modifier.fillMaxSize())
        Box(modifier = Modifier.fillMaxSize().background(listingScrim()))
        ListingCardTopBar(
            state = state,
            strings = strings,
            actions = actions,
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(KwaborSpacing.Md),
        )
        ListingCardBody(
            state = state,
            strings = strings,
            priceOptions = priceOptions,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(KwaborSpacing.Lg),
        )
    }
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
private fun ListingCardBody(
    state: ListingCardState,
    strings: KwaborStrings,
    priceOptions: PriceTagOptions,
    modifier: Modifier = Modifier,
) {
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
        state.ratingLabel?.let { rating -> ListingRating(label = rating, strings = strings) }
        PriceTag(price = state.price, strings = strings, options = priceOptions)
    }
}

@Composable
private fun ListingRating(label: String, strings: KwaborStrings) {
    Row(
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
    val price: MoneyXof?,
    val ratingLabel: String? = null,
    val sponsored: Boolean = false,
    val liked: Boolean = false,
    val favorited: Boolean = false,
    val placeholderColor: Color = KwaborColors.Ink500,
)

@Composable
private fun ListingCardTopBar(
    state: ListingCardState,
    strings: KwaborStrings,
    actions: ListingCardActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
        verticalAlignment = Alignment.Top,
    ) {
        if (state.sponsored) {
            SponsoredBadge(strings = strings)
        }
        Spacer(modifier = Modifier.weight(1f))
        ListingActionButton(
            label = strings.favorite,
            selected = state.favorited,
            imageVector = Icons.Filled.Bookmark,
            onClick = actions.onFavoriteClick,
        )
        ListingActionButton(
            label = strings.like,
            selected = state.liked,
            selectedColor = KwaborColors.Ticket,
            imageVector = Icons.Filled.Favorite,
            onClick = actions.onLikeClick,
        )
    }
}

@Composable
private fun ListingActionButton(
    label: String,
    selected: Boolean,
    selectedColor: Color = KwaborColors.Ink950,
    imageVector: ImageVector,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(KwaborSizing.FloatingPill)
            .background(
                color = KwaborColors.Surface0.copy(alpha = KwaborAlpha.FROSTED_SURFACE),
                shape = CircleShape,
            ),
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = label,
            modifier = Modifier.size(KwaborSpacing.Xxl),
            tint = if (selected) selectedColor else KwaborColors.Surface0,
        )
    }
}
