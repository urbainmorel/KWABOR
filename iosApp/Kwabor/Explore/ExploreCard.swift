import Shared
import SwiftUI

struct ExploreCard: View {
    let listing: ExploreListingItem
    let strings: KwaborStrings
    let onLike: () -> Void
    let onFavorite: () -> Void

    private var priceLabel: String {
        PriceLabelFormatter.shared.compactXof(price: listing.price, freeLabel: strings.free)
    }

    var body: some View {
        ZStack {
            ExploreRemoteImage(rawURL: listing.coverImageUrl)
            LinearGradient(
                colors: [
                    Color.clear,
                    KwaborDesignTokens.ColorToken.ink950.opacity(KwaborDesignTokens.Alpha.scrimLow),
                    KwaborDesignTokens.ColorToken.ink950.opacity(KwaborDesignTokens.Alpha.scrimHigh),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.sm) {
                HStack(alignment: .top, spacing: KwaborDesignTokens.Spacing.sm) {
                    if listing.sponsored {
                        SponsoredBadge(label: strings.sponsored)
                    } else if let rating = listing.ratingLabel {
                        ExploreRatingBadge(rating: rating)
                    }
                    Spacer(minLength: 0)
                    VStack(spacing: KwaborDesignTokens.Spacing.sm) {
                        ExploreCardActionButton(
                            label: strings.like,
                            systemImage: listing.liked ? "heart.fill" : "heart",
                            selected: listing.liked,
                            selectedColor: KwaborDesignTokens.ColorToken.ticket,
                            accessibilitySortPriority: 2,
                            action: onLike
                        )
                        ExploreCardActionButton(
                            label: strings.favorite,
                            systemImage: listing.favorited ? "bookmark.fill" : "bookmark",
                            selected: listing.favorited,
                            selectedColor: KwaborDesignTokens.ColorToken.ink950,
                            accessibilitySortPriority: 1,
                            action: onFavorite
                        )
                    }
                }
                Spacer(minLength: KwaborDesignTokens.Spacing.sm)
                ExploreCardInformation(
                    listing: listing,
                    strings: strings,
                    priceLabel: priceLabel
                )
            }
            .padding(KwaborDesignTokens.Spacing.md)
        }
        .aspectRatio(threeToFourAspectRatio, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
        .accessibilityElement(children: .contain)
    }
}

private struct ExploreCardInformation: View {
    let listing: ExploreListingItem
    let strings: KwaborStrings
    let priceLabel: String

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
            Text(listing.title)
                .font(.headline.weight(.bold))
                .foregroundStyle(KwaborDesignTokens.ColorToken.surface0)
                .lineLimit(2)
            Label(listing.cityLabel, systemImage: "location.fill")
                .font(.caption.weight(.medium))
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink100)
                .lineLimit(1)
            Text(priceLabel)
                .font(.caption.weight(.semibold))
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                .padding(.horizontal, KwaborDesignTokens.Spacing.sm)
                .padding(.vertical, KwaborDesignTokens.Spacing.xs)
                .background(KwaborDesignTokens.ColorToken.ink100)
                .clipShape(Capsule())
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilitySummary)
        .accessibilitySortPriority(3)
    }

    private var accessibilitySummary: String {
        var parts = [listing.title, listing.cityLabel]
        if listing.sponsored {
            parts.append(strings.sponsored)
        }
        if !listing.sponsored, let rating = listing.ratingLabel {
            parts.append("\(strings.rating) \(rating)")
        }
        parts.append(priceLabel)
        return parts.joined(separator: ". ")
    }
}

private struct ExploreRatingLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: KwaborDesignTokens.Spacing.xs) {
            configuration.icon
                .foregroundStyle(KwaborDesignTokens.ColorToken.sponsored)
            configuration.title
        }
    }
}

private struct ExploreRatingBadge: View {
    let rating: String

    var body: some View {
        Label(rating, systemImage: "star.fill")
            .font(.caption.weight(.semibold))
            .foregroundStyle(KwaborDesignTokens.ColorToken.surface0)
            .labelStyle(ExploreRatingLabelStyle())
            .padding(.horizontal, KwaborDesignTokens.Spacing.sm)
            .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
            .background(.ultraThinMaterial)
            .clipShape(Capsule())
            .accessibilityHidden(true)
    }
}

private struct SponsoredBadge: View {
    let label: String

    var body: some View {
        Text(label)
            .font(.caption2.weight(.bold))
            .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
            .lineLimit(1)
            .padding(.horizontal, KwaborDesignTokens.Spacing.sm)
            .padding(.vertical, KwaborDesignTokens.Spacing.xs)
            .background(KwaborDesignTokens.ColorToken.sponsored)
            .clipShape(Capsule())
            .accessibilityHidden(true)
    }
}

private struct ExploreCardActionButton: View {
    let label: String
    let systemImage: String
    let selected: Bool
    let selectedColor: Color
    let accessibilitySortPriority: Double
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(selected ? selectedColor : KwaborDesignTokens.ColorToken.surface0)
                .frame(
                    width: KwaborDesignTokens.Sizing.touchTarget,
                    height: KwaborDesignTokens.Sizing.touchTarget
                )
                .background(.ultraThinMaterial)
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilitySortPriority(accessibilitySortPriority)
    }
}

struct ExploreSkeletonCard: View {
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            LinearGradient(
                colors: [
                    KwaborDesignTokens.ColorToken.ink200,
                    KwaborDesignTokens.ColorToken.ink300,
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.sm) {
                RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control)
                    .fill(KwaborDesignTokens.ColorToken.ink100.opacity(0.72))
                    .frame(height: 18)
                RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control)
                    .fill(KwaborDesignTokens.ColorToken.ink100.opacity(0.54))
                    .frame(width: 84, height: 12)
            }
            .padding(KwaborDesignTokens.Spacing.lg)
        }
        .aspectRatio(threeToFourAspectRatio, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
        .accessibilityHidden(true)
    }
}

private let threeToFourAspectRatio: CGFloat = 3 / 4
