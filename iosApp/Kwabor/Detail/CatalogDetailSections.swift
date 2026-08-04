import Shared
import SwiftUI

struct CatalogDetailMetricsSection: View {
    let metrics: CatalogDetailMetricsUiModel
    let strings: CatalogDetailStrings

    var body: some View {
        CatalogDetailSection(title: strings.metrics) {
            ScrollView(.horizontal) {
                HStack(spacing: KwaborDesignTokens.Spacing.sm) {
                    if let rating = metrics.ratingLabel {
                        CatalogDetailMetric(
                            icon: "star.fill",
                            value: "\(rating) \(strings.ratingOutOfFive)",
                            label: strings.rating
                        )
                    }
                    CatalogDetailMetric(
                        icon: "text.bubble",
                        value: String(metrics.ratingCount),
                        label: CatalogDetailLabelPolicy.pluralizedLabel(
                            count: Int(metrics.ratingCount),
                            singular: strings.review,
                            plural: strings.reviews
                        )
                    )
                    CatalogDetailMetric(
                        icon: "eye",
                        value: String(metrics.viewsCount),
                        label: CatalogDetailLabelPolicy.pluralizedLabel(
                            count: Int(metrics.viewsCount),
                            singular: strings.view,
                            plural: strings.views
                        )
                    )
                    CatalogDetailMetric(
                        icon: "heart",
                        value: String(metrics.likesCount),
                        label: CatalogDetailLabelPolicy.pluralizedLabel(
                            count: Int(metrics.likesCount),
                            singular: strings.like,
                            plural: strings.likes
                        )
                    )
                }
            }
            .scrollIndicators(.hidden)
        }
    }
}

private struct CatalogDetailMetric: View {
    let icon: String
    let value: String
    let label: String

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
                Text(value)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                Text(label)
                    .font(.caption)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
            }
        } icon: {
            Image(systemName: icon)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
        }
        .padding(KwaborDesignTokens.Spacing.md)
        .frame(minHeight: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget)
        .background(KwaborDesignTokens.ColorToken.ink100)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control))
        .accessibilityElement(children: .combine)
    }
}

struct CatalogDetailDescriptionSection: View {
    let description: String
    let expanded: Bool
    let strings: CatalogDetailStrings
    let onToggle: () -> Void

    var body: some View {
        CatalogDetailSection(title: strings.description) {
            Text(expanded ? description : CatalogDetailDescriptionPolicy.preview(description))
                .font(.body)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                .fixedSize(horizontal: false, vertical: true)
            if CatalogDetailDescriptionPolicy.needsExpansion(description) {
                Button(expanded ? strings.showLess : strings.readMore, action: onToggle)
                    .buttonStyle(.plain)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                    .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
            }
        }
    }
}

struct CatalogDetailPriceSection: View {
    let price: CatalogDetailPriceUiModel
    let strings: CatalogDetailStrings
    let freeLabel: String

    var body: some View {
        CatalogDetailSection(title: strings.price) {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .firstTextBaseline, spacing: KwaborDesignTokens.Spacing.sm) {
                    priceContent()
                }
                VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
                    priceContent()
                }
            }
        }
    }

    @ViewBuilder
    private func priceContent() -> some View {
        if let prefix = price.prefixLabel {
            Text(prefix)
                .font(.subheadline)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
        }
        CatalogDetailPriceTag(
            label: detailPriceLabel(price.amount, freeLabel: freeLabel),
            transactional: false
        )
        if let unit = price.unitLabel {
            Text(unit)
                .font(.subheadline)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
        }
    }
}

struct CatalogDetailPriceTag: View {
    let label: String
    let transactional: Bool

    var body: some View {
        Text(label)
            .font(.body.weight(.semibold))
            .foregroundStyle(
                transactional
                    ? KwaborDesignTokens.ColorToken.surface0
                    : KwaborDesignTokens.ColorToken.ink950
            )
            .padding(.horizontal, KwaborDesignTokens.Spacing.md)
            .padding(.vertical, KwaborDesignTokens.Spacing.sm)
            .background(
                transactional
                    ? KwaborDesignTokens.ColorToken.ticket
                    : KwaborDesignTokens.ColorToken.ink100
            )
            .clipShape(Capsule())
            .accessibilityLabel(label)
    }
}

struct CatalogDetailOpeningHoursSection: View {
    let statusLabel: String?
    let hours: [CatalogDetailOpeningDayUiModel]
    let strings: CatalogDetailStrings

    var body: some View {
        CatalogDetailSection(title: strings.openingHours) {
            if let statusLabel {
                Text(statusLabel)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
            }
            if hours.isEmpty {
                Text(strings.unspecifiedHours)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
            } else {
                VStack(spacing: KwaborDesignTokens.Spacing.sm) {
                    ForEach(Array(hours.enumerated()), id: \.offset) { _, day in
                        ViewThatFits(in: .horizontal) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(day.dayLabel)
                                    .fontWeight(.semibold)
                                Spacer(minLength: KwaborDesignTokens.Spacing.md)
                                Text(day.hoursLabel)
                                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                            }
                            VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
                                Text(day.dayLabel)
                                    .fontWeight(.semibold)
                                Text(day.hoursLabel)
                                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                            }
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
            }
        }
    }
}

struct CatalogDetailLocationSection: View {
    let location: CatalogDetailLocationUiModel
    let strings: CatalogDetailStrings

    var body: some View {
        CatalogDetailSection(title: strings.location) {
            VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.sm) {
                Label(location.cityLabel, systemImage: "mappin.and.ellipse")
                    .font(.body.weight(.semibold))
                if let district = location.districtLabel {
                    Text(district)
                        .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                }
                Text(location.addressLabel ?? strings.addressUnavailable)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
            }
        }
    }
}

struct CatalogDetailLabelsSection: View {
    let title: String
    let labels: [String]

    var body: some View {
        if !labels.isEmpty {
            CatalogDetailSection(title: title) {
                ScrollView(.horizontal) {
                    LazyHStack(spacing: KwaborDesignTokens.Spacing.sm) {
                        ForEach(Array(labels.enumerated()), id: \.offset) { _, label in
                            Text(label)
                                .font(.subheadline.weight(.medium))
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
}

struct CatalogDetailSection<Content: View>: View {
    let title: String
    let content: Content

    init(title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.md) {
            Text(title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                .accessibilityAddTraits(.isHeader)
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

func detailPriceLabel(_ price: MoneyXof?, freeLabel: String) -> String {
    guard let price, price.amount > 0 else {
        return freeLabel
    }
    return PriceLabelFormatter.shared.fullXof(price: price)
}
