import Shared
import SwiftUI

struct GuideDiscoveryCard: View {
    let guide: GuideSummaryUiModel
    let strings: GuideDiscoveryStrings
    let commonStrings: KwaborStrings
    let onOpen: () -> Void

    private var priceLabel: String {
        PriceLabelFormatter.shared.compactXof(
            price: guide.indicativePrice,
            freeLabel: commonStrings.free
        )
    }

    var body: some View {
        Button(action: onOpen) {
            VStack(alignment: .leading, spacing: 0) {
                GuideDiscoveryCardHero(guide: guide, commonStrings: commonStrings)
                VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.md) {
                    GuideDiscoveryMetadataRow(
                        label: strings.languagesLabel,
                        value: guide.languages.joined(separator: ", "),
                        systemImage: "character.book.closed"
                    )
                    GuideDiscoveryMetadataRow(
                        label: strings.coveredCitiesLabel,
                        value: guide.coverageCities.joined(separator: ", "),
                        systemImage: "map"
                    )
                    GuideDiscoveryMetadataRow(
                        label: strings.specialtiesLabel,
                        value: guide.specialties.joined(separator: ", "),
                        systemImage: "sparkles"
                    )
                    HStack(alignment: .firstTextBaseline, spacing: KwaborDesignTokens.Spacing.sm) {
                        Text(strings.indicativePriceLabel)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                        Spacer(minLength: KwaborDesignTokens.Spacing.sm)
                        CatalogDetailPriceTag(label: priceLabel, transactional: false)
                    }
                }
                .padding(KwaborDesignTokens.Spacing.lg)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(KwaborDesignTokens.ColorToken.surface0)
            .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
            .overlay {
                RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card)
                    .stroke(
                        KwaborDesignTokens.ColorToken.ink200,
                        lineWidth: KwaborDesignTokens.Sizing.outline
                    )
            }
            .contentShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilitySummary)
        .accessibilityHint(strings.openGuideLabel)
    }

    private var accessibilitySummary: String {
        var parts = [guide.title, guide.coverImageAlt, guide.baseCityLabel]
        if guide.verified {
            parts.append(commonStrings.detail.verified)
        }
        if let rating = ratingAccessibilityLabel {
            parts.append(rating)
        }
        parts.append("\(strings.languagesLabel) : \(guide.languages.joined(separator: ", "))")
        parts.append("\(strings.coveredCitiesLabel) : \(guide.coverageCities.joined(separator: ", "))")
        parts.append("\(strings.specialtiesLabel) : \(guide.specialties.joined(separator: ", "))")
        parts.append("\(strings.indicativePriceLabel) : \(priceLabel)")
        return parts.joined(separator: ". ")
    }

    private var ratingAccessibilityLabel: String? {
        guard let ratingLabel = guide.ratingLabel else { return nil }
        return "\(commonStrings.detail.rating) : \(ratingLabel), \(reviewCountLabel)"
    }

    private var reviewCountLabel: String {
        let reviewLabel = guide.ratingCount == 1
            ? commonStrings.detail.review
            : commonStrings.detail.reviews
        return "\(guide.ratingCount) \(reviewLabel)"
    }
}

private struct GuideDiscoveryCardHero: View {
    let guide: GuideSummaryUiModel
    let commonStrings: KwaborStrings

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            ExploreRemoteImage(rawURL: guide.coverImageUrl)
                .frame(height: KwaborDesignTokens.Sizing.guideCardHeroHeight)
            LinearGradient(
                colors: [
                    Color.clear,
                    KwaborDesignTokens.ColorToken.ink950.opacity(KwaborDesignTokens.Alpha.scrimHigh),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
                HStack(alignment: .firstTextBaseline, spacing: KwaborDesignTokens.Spacing.sm) {
                    Text(guide.title)
                        .font(.title3.weight(.bold))
                        .foregroundStyle(KwaborDesignTokens.ColorToken.surface0)
                        .fixedSize(horizontal: false, vertical: true)
                    if guide.verified {
                        Image(systemName: "checkmark.seal.fill")
                            .foregroundStyle(KwaborDesignTokens.ColorToken.surface0)
                            .accessibilityLabel(commonStrings.detail.verified)
                    }
                }
                HStack(spacing: KwaborDesignTokens.Spacing.sm) {
                    Label(guide.baseCityLabel, systemImage: "location.fill")
                    if let ratingLabel = guide.ratingLabel {
                        Label(ratingLabel, systemImage: "star.fill")
                    }
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink100)
            }
            .padding(KwaborDesignTokens.Spacing.lg)
        }
        .accessibilityHidden(true)
    }
}

private struct GuideDiscoveryMetadataRow: View {
    let label: String
    let value: String
    let systemImage: String

    var body: some View {
        HStack(alignment: .top, spacing: KwaborDesignTokens.Spacing.sm) {
            Image(systemName: systemImage)
                .frame(width: KwaborDesignTokens.Sizing.touchTarget / 2)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
                Text(label)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                Text(value)
                    .font(.subheadline)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

struct GuideDiscoverySkeletonCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            LinearGradient(
                colors: [
                    KwaborDesignTokens.ColorToken.ink200,
                    KwaborDesignTokens.ColorToken.ink300,
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .frame(height: KwaborDesignTokens.Sizing.guideCardHeroHeight)
            VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.md) {
                ForEach(0..<guideSkeletonLineCount, id: \.self) { index in
                    RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control)
                        .fill(KwaborDesignTokens.ColorToken.ink100)
                        .frame(
                            maxWidth: index == guideSkeletonLineCount - 1
                                ? KwaborDesignTokens.Sizing.guideSkeletonLastLineWidth
                                : .infinity
                        )
                        .frame(height: KwaborDesignTokens.Sizing.guideSkeletonLineHeight)
                }
            }
            .padding(KwaborDesignTokens.Spacing.lg)
        }
        .background(KwaborDesignTokens.ColorToken.surface0)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
        .accessibilityHidden(true)
    }
}

private let guideSkeletonLineCount = 4
