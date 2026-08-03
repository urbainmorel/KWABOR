package com.kwabor.android.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.kwabor.android.R
import com.kwabor.android.design.KwaborRadius
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.ui.components.PriceTag
import com.kwabor.android.ui.components.PriceTagMode
import com.kwabor.android.ui.components.PriceTagOptions
import com.kwabor.shared.i18n.CatalogDetailStrings
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.detail.CatalogDetailLocationUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailMetricsUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailOpeningDayUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailPriceUiModel

@Composable
internal fun DetailMetrics(metrics: CatalogDetailMetricsUiModel, strings: CatalogDetailStrings, modifier: Modifier) {
    DetailSection(title = stringResource(R.string.detail_metrics), modifier = modifier) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm)) {
            metrics.ratingLabel?.let { rating ->
                item {
                    DetailMetric(
                        label = "${strings.rating} $rating / 5",
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                modifier = Modifier.size(KwaborSpacing.Lg),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                    )
                }
            }
            item { DetailMetric(label = detailCountLabel(metrics.ratingCount, strings.review, strings.reviews)) }
            item { DetailMetric(label = detailCountLabel(metrics.viewsCount, strings.view, strings.views)) }
            item { DetailMetric(label = detailCountLabel(metrics.likesCount, strings.like, strings.likes)) }
        }
    }
}

@Composable
private fun DetailMetric(label: String, leadingIcon: (@Composable () -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(KwaborRadius.Pill),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = KwaborSpacing.Md, vertical = KwaborSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.invoke()
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun DetailDescription(
    description: String,
    expanded: Boolean,
    strings: CatalogDetailStrings,
    onToggle: () -> Unit,
    modifier: Modifier,
) {
    DetailSection(title = strings.description, modifier = modifier) {
        Text(
            text = detailDescription(description, expanded),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (shouldOfferDescriptionExpansion(description)) {
            TextButton(onClick = onToggle) {
                Text(text = if (expanded) strings.showLess else strings.readMore)
            }
        }
    }
}

@Composable
internal fun DetailPrice(
    price: CatalogDetailPriceUiModel,
    strings: CatalogDetailStrings,
    commonStrings: KwaborStrings,
    modifier: Modifier,
) {
    DetailSection(title = strings.price, modifier = modifier) {
        price.prefixLabel?.let { label -> Text(text = label, style = MaterialTheme.typography.bodyMedium) }
        PriceTag(
            price = price.amount,
            strings = commonStrings,
            options = PriceTagOptions(mode = PriceTagMode.Full),
        )
        price.unitLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
internal fun DetailOpeningHours(
    openingStatusLabel: String?,
    days: List<CatalogDetailOpeningDayUiModel>,
    strings: CatalogDetailStrings,
    modifier: Modifier,
) {
    DetailSection(title = strings.openingHours, modifier = modifier) {
        openingStatusLabel?.let { status ->
            Text(text = status, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        if (days.isEmpty()) {
            Text(
                text = strings.unspecifiedHours,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else {
            days.forEach { day -> DetailOpeningDay(day) }
        }
    }
}

@Composable
private fun DetailOpeningDay(day: CatalogDetailOpeningDayUiModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = day.dayLabel,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.width(KwaborSpacing.Md))
        Text(
            text = day.hoursLabel,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun DetailLocation(
    location: CatalogDetailLocationUiModel,
    strings: CatalogDetailStrings,
    modifier: Modifier,
) {
    DetailSection(title = strings.location, modifier = modifier) {
        Text(text = location.cityLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        location.districtLabel?.let { district -> Text(text = district, style = MaterialTheme.typography.bodyMedium) }
        Text(
            text = location.addressLabel ?: strings.addressUnavailable,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
internal fun DetailLabelList(heading: String, labels: List<String>, modifier: Modifier) {
    DetailSection(title = heading, modifier = modifier) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm)) {
            itemsIndexed(items = labels, key = { index, _ -> index }) { _, label -> DetailLabelPill(label) }
        }
    }
}

@Composable
private fun DetailLabelPill(label: String) {
    Surface(shape = RoundedCornerShape(KwaborRadius.Pill), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = KwaborSpacing.Md, vertical = KwaborSpacing.Sm),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun DetailSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}
