package com.kwabor.android.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.kwabor.shared.presentation.detail.CatalogDetailContentUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailFactUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailPricedItemUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailTicketingUiModel

@Composable
internal fun DetailTypedContent(
    content: CatalogDetailContentUiModel,
    strings: CatalogDetailStrings,
    commonStrings: KwaborStrings,
    modifier: Modifier,
) {
    when (content) {
        is CatalogDetailContentUiModel.Place -> DetailPlace(content, strings, modifier)
        is CatalogDetailContentUiModel.Lodging -> DetailLodging(content, strings, commonStrings, modifier)
        is CatalogDetailContentUiModel.Food -> DetailFood(content, strings, modifier)
        is CatalogDetailContentUiModel.Nightlife -> DetailFacts(content.heading, content.facts, modifier)
        is CatalogDetailContentUiModel.Guide -> DetailGuide(content, strings, commonStrings, modifier)
        is CatalogDetailContentUiModel.Event -> DetailEvent(content, strings, commonStrings, modifier)
    }
}

@Composable
private fun DetailPlace(
    content: CatalogDetailContentUiModel.Place,
    strings: CatalogDetailStrings,
    modifier: Modifier,
) {
    TypedDetailSection(title = content.heading, modifier = modifier) {
        TypedDetailFact(label = strings.placeCategory, value = content.placeCategoryLabel)
        content.feeNote?.let { note -> TypedDetailFact(label = strings.feeNote, value = note) }
    }
}

@Composable
private fun DetailLodging(
    content: CatalogDetailContentUiModel.Lodging,
    strings: CatalogDetailStrings,
    commonStrings: KwaborStrings,
    modifier: Modifier,
) {
    TypedDetailSection(title = content.heading, modifier = modifier) {
        DetailFactList(content.facts)
        if (content.roomTypes.isNotEmpty()) {
            TypedDetailSubheading(strings.roomTypes)
            DetailPricedItems(content.roomTypes, commonStrings)
        }
    }
}

@Composable
private fun DetailFood(content: CatalogDetailContentUiModel.Food, strings: CatalogDetailStrings, modifier: Modifier) {
    TypedDetailSection(title = content.heading, modifier = modifier) {
        TypedDetailLabelGroup(strings.cuisines, content.cuisines)
        TypedDetailLabelGroup(strings.meals, content.meals)
        Text(text = content.reservationLabel, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (content.menuAvailable) {
                strings.menuAvailable
            } else {
                stringResource(
                    R.string.detail_menu_unavailable,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DetailGuide(
    content: CatalogDetailContentUiModel.Guide,
    strings: CatalogDetailStrings,
    commonStrings: KwaborStrings,
    modifier: Modifier,
) {
    TypedDetailSection(title = content.heading, modifier = modifier) {
        TypedDetailLabelGroup(strings.languages, content.languages)
        TypedDetailLabelGroup(strings.zones, content.zones)
        TypedDetailLabelGroup(strings.specialties, content.specialties)
        DetailFactList(content.facts)
        content.indicativePrice?.let { price ->
            TypedDetailSubheading(strings.indicativePrice)
            PriceTag(
                price = price,
                strings = commonStrings,
                options = PriceTagOptions(mode = PriceTagMode.Full),
            )
        }
    }
}

@Composable
private fun DetailEvent(
    content: CatalogDetailContentUiModel.Event,
    strings: CatalogDetailStrings,
    commonStrings: KwaborStrings,
    modifier: Modifier,
) {
    TypedDetailSection(title = content.heading, modifier = modifier) {
        if (content.isEnded) DetailEventEndedBadge(strings.eventEnded)
        TypedDetailFact(label = strings.startsAt, value = content.startsAtLabel)
        content.endsAtLabel?.let { end -> TypedDetailFact(label = strings.endsAt, value = end) }
        TypedDetailFact(label = strings.venue, value = content.venueLabel)
        TypedDetailFact(label = strings.organizer, value = content.organizerLabel)
        content.capacityLabel?.let { capacity -> TypedDetailFact(label = strings.capacity, value = capacity) }
        TypedDetailSubheading(strings.ticketing)
        DetailEventTicketing(content.ticketing, strings, commonStrings)
    }
}

@Composable
private fun DetailEventEndedBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(KwaborRadius.Pill),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = KwaborSpacing.Md, vertical = KwaborSpacing.Sm),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DetailEventTicketing(
    ticketing: CatalogDetailTicketingUiModel,
    strings: CatalogDetailStrings,
    commonStrings: KwaborStrings,
) {
    when (ticketing) {
        is CatalogDetailTicketingUiModel.Free -> {
            Text(text = strings.freeEvent, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (ticketing.registrationAvailable) {
                    stringResource(R.string.detail_registration_available)
                } else {
                    stringResource(R.string.detail_registration_unavailable)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        is CatalogDetailTicketingUiModel.Paid -> {
            Text(text = strings.paidEvent, style = MaterialTheme.typography.bodyMedium)
            DetailPricedItems(ticketing.tiers, commonStrings)
        }
    }
}

@Composable
private fun DetailFacts(heading: String, facts: List<CatalogDetailFactUiModel>, modifier: Modifier) {
    TypedDetailSection(title = heading, modifier = modifier) { DetailFactList(facts) }
}

@Composable
private fun DetailFactList(facts: List<CatalogDetailFactUiModel>) {
    facts.forEach { fact -> TypedDetailFact(label = fact.label, value = fact.value) }
}

@Composable
private fun DetailPricedItems(items: List<CatalogDetailPricedItemUiModel>, strings: KwaborStrings) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Lg)) {
        items(items = items, key = CatalogDetailPricedItemUiModel::label) { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = item.label, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.width(KwaborSpacing.Md))
                PriceTag(
                    price = item.price,
                    strings = strings,
                    options = PriceTagOptions(mode = PriceTagMode.Full, transactional = true),
                )
            }
        }
    }
}

@Composable
private fun TypedDetailLabelGroup(heading: String, labels: List<String>) {
    if (labels.isEmpty()) return
    TypedDetailSubheading(heading)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm)) {
        itemsIndexed(items = labels, key = { index, _ -> index }) { _, label ->
            Surface(shape = RoundedCornerShape(KwaborRadius.Pill), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = KwaborSpacing.Md, vertical = KwaborSpacing.Sm),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun TypedDetailFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TypedDetailSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md)) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun TypedDetailSubheading(title: String) {
    Text(
        text = title,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
}
