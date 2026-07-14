package com.kwabor.shared.ui.previews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kwabor.shared.design.KwaborSpacing
import com.kwabor.shared.design.KwaborTheme
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.ui.components.KwaborLoadingState
import com.kwabor.shared.ui.components.KwaborSkeletonCard
import com.kwabor.shared.ui.components.KwaborStateMessage
import com.kwabor.shared.ui.components.ListingCard
import com.kwabor.shared.ui.components.ListingCardState
import com.kwabor.shared.ui.components.OfflineBanner
import com.kwabor.shared.ui.components.PriceTag
import com.kwabor.shared.ui.components.PriceTagMode
import com.kwabor.shared.ui.components.PriceTagOptions
import com.kwabor.shared.ui.components.SponsoredBadge
import org.jetbrains.compose.ui.tooling.preview.Preview

private const val PREVIEW_PRIMARY_PRICE = 25_000L
private const val PREVIEW_SECONDARY_PRICE = 5_000L
private const val PREVIEW_CONVERTED_PRICE = 7.62

@Preview
@Composable
fun PriceTagPreview() {
    val strings = stringsFor(AppLocale.French)
    KwaborTheme {
        Row(
            modifier = Modifier.padding(KwaborSpacing.Lg),
            horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
        ) {
            PriceTag(
                price = money(PREVIEW_PRIMARY_PRICE),
                strings = strings,
                options = PriceTagOptions(mode = PriceTagMode.Compact),
            )
            PriceTag(price = money(PREVIEW_SECONDARY_PRICE), strings = strings)
            PriceTag(
                price = money(PREVIEW_SECONDARY_PRICE),
                strings = strings,
                options = PriceTagOptions(
                    currency = KwaborCurrency.Eur,
                    convertedAmount = PREVIEW_CONVERTED_PRICE,
                    transactional = true,
                ),
            )
        }
    }
}

@Preview
@Composable
fun ListingCardPreview() {
    val strings = stringsFor(AppLocale.French)
    KwaborTheme {
        ListingCard(
            state = previewListingState(),
            strings = strings,
            modifier = Modifier
                .fillMaxWidth()
                .padding(KwaborSpacing.Lg),
        )
    }
}

@Preview
@Composable
fun FoundationStatesPreview() {
    val strings = stringsFor(AppLocale.French)
    KwaborTheme {
        Column(
            modifier = Modifier.padding(KwaborSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Lg),
        ) {
            SponsoredBadge(strings = strings)
            OfflineBanner(strings = strings)
            KwaborLoadingState(strings = strings)
            KwaborSkeletonCard()
            KwaborStateMessage(
                title = strings.emptyStateTitle,
                supportingText = strings.foundationStatus,
                actionLabel = strings.retry,
                onAction = {},
            )
        }
    }
}

@Preview
@Composable
fun FoundationStatesDarkPreview() {
    val strings = stringsFor(AppLocale.French)
    KwaborTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(KwaborSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Lg),
        ) {
            OfflineBanner(strings = strings)
            KwaborStateMessage(
                title = strings.errorStateTitle,
                supportingText = strings.foundationStatus,
                actionLabel = strings.retry,
                onAction = {},
            )
        }
    }
}

private fun previewListingState(): ListingCardState = ListingCardState(
    title = "Maison Ganhi",
    cityLabel = "Cotonou",
    price = money(PREVIEW_PRIMARY_PRICE),
    ratingLabel = "4,7",
    sponsored = true,
    liked = true,
    favorited = true,
)

private fun money(amount: Long): MoneyXof = when (val result = MoneyXof.fromAmount(amount)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> error("Invalid preview money")
}
