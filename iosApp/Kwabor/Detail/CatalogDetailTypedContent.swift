import Shared
import SwiftUI

struct CatalogDetailTypedContent: View {
    let content: CatalogDetailContentUiModel
    let strings: CatalogDetailStrings
    let commonStrings: KwaborStrings

    @ViewBuilder
    var body: some View {
        if let place = content as? CatalogDetailContentUiModelPlace {
            placeContent(place)
        } else if let lodging = content as? CatalogDetailContentUiModelLodging {
            lodgingContent(lodging)
        } else if let food = content as? CatalogDetailContentUiModelFood {
            foodContent(food)
        } else if let nightlife = content as? CatalogDetailContentUiModelNightlife {
            nightlifeContent(nightlife)
        } else if let guide = content as? CatalogDetailContentUiModelGuide {
            guideContent(guide)
        } else if let event = content as? CatalogDetailContentUiModelEvent {
            eventContent(event)
        }
    }

    private func placeContent(_ place: CatalogDetailContentUiModelPlace) -> some View {
        CatalogDetailSection(title: place.heading) {
            CatalogDetailFact(label: strings.placeCategory, value: place.placeCategoryLabel)
            if let feeNote = place.feeNote {
                CatalogDetailFact(label: strings.feeNote, value: feeNote)
            }
        }
    }

    private func lodgingContent(_ lodging: CatalogDetailContentUiModelLodging) -> some View {
        CatalogDetailSection(title: lodging.heading) {
            CatalogDetailFacts(facts: lodging.facts)
            if !lodging.roomTypes.isEmpty {
                CatalogDetailSubheading(title: strings.roomTypes)
                CatalogDetailPricedItems(
                    items: lodging.roomTypes,
                    freeLabel: commonStrings.free,
                    transactional: false
                )
            }
        }
    }

    private func foodContent(_ food: CatalogDetailContentUiModelFood) -> some View {
        CatalogDetailSection(title: food.heading) {
            CatalogDetailLabelGroup(title: strings.cuisines, labels: food.cuisines)
            CatalogDetailLabelGroup(title: strings.meals, labels: food.meals)
            Text(food.reservationLabel)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
            Text(food.menuAvailable ? strings.menuAvailable : strings.menuUnavailable)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
        }
    }

    private func nightlifeContent(_ nightlife: CatalogDetailContentUiModelNightlife) -> some View {
        CatalogDetailSection(title: nightlife.heading) {
            CatalogDetailFacts(facts: nightlife.facts)
        }
    }

    private func guideContent(_ guide: CatalogDetailContentUiModelGuide) -> some View {
        CatalogDetailSection(title: guide.heading) {
            CatalogDetailLabelGroup(title: strings.languages, labels: guide.languages)
            CatalogDetailLabelGroup(title: strings.zones, labels: guide.zones)
            CatalogDetailLabelGroup(title: strings.specialties, labels: guide.specialties)
            CatalogDetailFacts(facts: guide.facts)
            if let price = guide.indicativePrice {
                CatalogDetailSubheading(title: strings.indicativePrice)
                CatalogDetailPriceTag(
                    label: detailPriceLabel(price, freeLabel: commonStrings.free),
                    transactional: false
                )
            }
        }
    }

    private func eventContent(_ event: CatalogDetailContentUiModelEvent) -> some View {
        CatalogDetailSection(title: event.heading) {
            if event.isEnded {
                Text(strings.eventEnded)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                    .padding(.horizontal, KwaborDesignTokens.Spacing.md)
                    .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
                    .background(KwaborDesignTokens.ColorToken.ink100)
                    .clipShape(Capsule())
                    .accessibilityLabel(strings.eventEnded)
            }
            CatalogDetailFact(label: strings.startsAt, value: event.startsAtLabel)
            if let endsAt = event.endsAtLabel {
                CatalogDetailFact(label: strings.endsAt, value: endsAt)
            }
            CatalogDetailFact(label: strings.venue, value: event.venueLabel)
            CatalogDetailFact(label: strings.organizer, value: event.organizerLabel)
            if let capacity = event.capacityLabel {
                CatalogDetailFact(label: strings.capacity, value: capacity)
            }
            CatalogDetailTicketing(
                ticketing: event.ticketing,
                strings: strings,
                freeLabel: commonStrings.free
            )
        }
    }
}

private struct CatalogDetailFacts: View {
    let facts: [CatalogDetailFactUiModel]

    var body: some View {
        ForEach(Array(facts.enumerated()), id: \.offset) { _, fact in
            CatalogDetailFact(label: fact.label, value: fact.value)
        }
    }
}

private struct CatalogDetailFact: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
            Text(label)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
            Text(value)
                .font(.body)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct CatalogDetailSubheading: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.headline)
            .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
            .accessibilityAddTraits(.isHeader)
    }
}

private struct CatalogDetailLabelGroup: View {
    let title: String
    let labels: [String]

    var body: some View {
        if !labels.isEmpty {
            CatalogDetailSubheading(title: title)
            ScrollView(.horizontal) {
                LazyHStack(spacing: KwaborDesignTokens.Spacing.sm) {
                    ForEach(Array(labels.enumerated()), id: \.offset) { _, label in
                        Text(label)
                            .font(.subheadline)
                            .padding(.horizontal, KwaborDesignTokens.Spacing.md)
                            .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
                            .background(KwaborDesignTokens.ColorToken.ink100)
                            .clipShape(Capsule())
                    }
                }
            }
            .scrollIndicators(.hidden)
        }
    }
}

private struct CatalogDetailPricedItems: View {
    let items: [CatalogDetailPricedItemUiModel]
    let freeLabel: String
    let transactional: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.sm) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(item.label)
                        Spacer(minLength: KwaborDesignTokens.Spacing.md)
                        price(item.price)
                    }
                    VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
                        Text(item.label)
                        price(item.price)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }
    }

    private func price(_ amount: MoneyXof) -> some View {
        CatalogDetailPriceTag(
            label: detailPriceLabel(amount, freeLabel: freeLabel),
            transactional: transactional
        )
    }
}

private struct CatalogDetailTicketing: View {
    let ticketing: CatalogDetailTicketingUiModel
    let strings: CatalogDetailStrings
    let freeLabel: String

    @ViewBuilder
    var body: some View {
        CatalogDetailSubheading(title: strings.ticketing)
        if let free = ticketing as? CatalogDetailTicketingUiModelFree {
            Text(strings.freeEvent)
                .font(.body.weight(.semibold))
            Text(
                free.registrationAvailable
                    ? strings.registrationAvailable
                    : strings.registrationUnavailable
            )
            .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
        } else if let paid = ticketing as? CatalogDetailTicketingUiModelPaid {
            Text(strings.paidEvent)
                .font(.body.weight(.semibold))
            CatalogDetailPricedItems(
                items: paid.tiers,
                freeLabel: freeLabel,
                transactional: true
            )
        }
    }
}
