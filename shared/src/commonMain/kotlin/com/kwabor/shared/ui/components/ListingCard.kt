package com.kwabor.shared.ui.components

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
import com.kwabor.shared.design.KwaborAlpha
import com.kwabor.shared.design.KwaborColors
import com.kwabor.shared.design.KwaborRadius
import com.kwabor.shared.design.KwaborSizing
import com.kwabor.shared.design.KwaborSpacing
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.KwaborStrings

@Composable
fun ListingCard(
    state: ListingCardState,
    strings: KwaborStrings,
    modifier: Modifier = Modifier,
    currency: KwaborCurrency = KwaborCurrency.Xof,
    convertedAmount: Double? = null,
    onClick: () -> Unit = {},
    onLikeClick: () -> Unit = {},
    onFavoriteClick: () -> Unit = {},
) {
    Surface(
        modifier = modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(KwaborRadius.Card))
            .clickable(onClick = onClick),
        color = KwaborColors.Ink950,
        shape = RoundedCornerShape(KwaborRadius.Card),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            state.placeholderColor,
                            KwaborColors.Ink950,
                        ),
                    ),
                ),
        ) {
            ListingCoverImage(
                imageUrl = state.coverImageUrl,
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                KwaborColors.Ink950.copy(alpha = KwaborAlpha.SCRIM_LOW),
                                KwaborColors.Ink950.copy(alpha = KwaborAlpha.SCRIM_HIGH),
                            ),
                        ),
                    ),
            )

            ListingCardTopBar(
                strings = strings,
                sponsored = state.sponsored,
                liked = state.liked,
                favorited = state.favorited,
                onLikeClick = onLikeClick,
                onFavoriteClick = onFavoriteClick,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(KwaborSpacing.Md),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(KwaborSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
            ) {
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
                state.ratingLabel?.let { rating ->
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
                            text = rating,
                            color = KwaborColors.Surface0,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                PriceTag(
                    price = state.price,
                    strings = strings,
                    currency = currency,
                    mode = PriceTagMode.Compact,
                    convertedAmount = convertedAmount,
                )
            }
        }
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
    strings: KwaborStrings,
    sponsored: Boolean,
    liked: Boolean,
    favorited: Boolean,
    onLikeClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
        verticalAlignment = Alignment.Top,
    ) {
        if (sponsored) {
            SponsoredBadge(strings = strings)
        }
        Spacer(modifier = Modifier.weight(1f))
        ListingActionButton(
            label = strings.favorite,
            selected = favorited,
            imageVector = Icons.Filled.Bookmark,
            onClick = onFavoriteClick,
        )
        ListingActionButton(
            label = strings.like,
            selected = liked,
            selectedColor = KwaborColors.Ticket,
            imageVector = Icons.Filled.Favorite,
            onClick = onLikeClick,
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
