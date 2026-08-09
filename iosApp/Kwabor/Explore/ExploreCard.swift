import Shared
import SwiftUI

struct ExploreCard: View {
    let listing: ExploreListingItem
    let strings: KwaborStrings
    let showsInteractions: Bool
    let onOpen: () -> Void
    let onLike: () -> Void
    let onFavorite: () -> Void
    private let priceLabel: String
    private let decoration: ExploreCardDecorationPresentation
    private let imageAccessibilityDescription: String

    init(
        listing: ExploreListingItem,
        strings: KwaborStrings,
        showsInteractions: Bool = true,
        onOpen: @escaping () -> Void,
        onLike: @escaping () -> Void,
        onFavorite: @escaping () -> Void
    ) {
        self.listing = listing
        self.strings = strings
        self.showsInteractions = showsInteractions
        self.onOpen = onOpen
        self.onLike = onLike
        self.onFavorite = onFavorite
        priceLabel = PriceLabelFormatter.shared.compactXof(price: listing.price, freeLabel: strings.free)
        decoration = ExploreCardDecorationPolicy.presentation(
            isSponsoredPlacement: listing.sponsored,
            ratingLabel: listing.ratingLabel,
            eventDateLabel: listing.eventDateLabel,
            isEventEnded: listing.isEventEnded
        )
        imageAccessibilityDescription = ExploreCardImageAccessibilityPolicy.description(
            coverImageAlt: listing.coverImageAlt,
            fallbackTitle: listing.title
        )
    }

    var body: some View {
        ZStack {
            Button(action: onOpen) {
                ZStack {
                    ExploreRemoteImage(
                        rawURL: listing.coverImageUrl,
                        accessibilityLabel: imageAccessibilityDescription
                    )
                    LinearGradient(
                        colors: [
                            Color.clear,
                            KwaborDesignTokens.ColorToken.ink950.opacity(
                                KwaborDesignTokens.Alpha.scrimLow
                            ),
                            KwaborDesignTokens.ColorToken.ink950.opacity(
                                KwaborDesignTokens.Alpha.scrimHigh
                            ),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.sm) {
                        HStack(alignment: .top, spacing: KwaborDesignTokens.Spacing.sm) {
                            VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
                                if decoration.showsSponsoredBadge {
                                    SponsoredBadge(label: strings.sponsored)
                                } else if let ratingLabel = decoration.ratingLabel {
                                    ExploreRatingBadge(rating: ratingLabel)
                                }
                                if let eventDateLabel = decoration.eventDateLabel {
                                    ExploreEventDateBadge(label: eventDateLabel)
                                }
                            }
                            Spacer(minLength: KwaborDesignTokens.Sizing.touchTarget)
                        }
                        .padding(
                            .top,
                            decoration.showsEndedRibbon ? exploreEndedRibbonClearance : 0
                        )
                        Spacer(minLength: KwaborDesignTokens.Spacing.sm)
                        ExploreCardInformation(
                            listing: listing,
                            priceLabel: priceLabel
                        )
                    }
                    .padding(KwaborDesignTokens.Spacing.md)
                }
                .overlay(alignment: .topLeading) {
                    if decoration.showsEndedRibbon {
                        ExploreEndedRibbon(label: strings.favorites.eventEnded)
                            .rotationEffect(.degrees(-exploreEndedRibbonAngle))
                            .offset(
                                x: -KwaborDesignTokens.Spacing.xxl,
                                y: KwaborDesignTokens.Spacing.lg
                            )
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilitySummary)
            .accessibilitySortPriority(3)

            if showsInteractions {
                VStack {
                    HStack {
                        Spacer()
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
                    Spacer()
                }
                .padding(KwaborDesignTokens.Spacing.md)
            }
        }
        .aspectRatio(threeToFourAspectRatio, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
        .accessibilityElement(children: .contain)
    }

    private var accessibilitySummary: String {
        var parts = [listing.title]
        if imageAccessibilityDescription != listing.title {
            parts.append(imageAccessibilityDescription)
        }
        parts.append(listing.cityLabel)
        if decoration.showsSponsoredBadge {
            parts.append(strings.sponsored)
        }
        if let eventDateLabel = decoration.eventDateLabel {
            parts.append(eventDateLabel)
        }
        if let rating = decoration.ratingLabel {
            parts.append("\(strings.rating) \(rating)")
        }
        if decoration.showsEndedRibbon {
            parts.append(strings.favorites.eventEndedAccessibility)
        }
        parts.append(priceLabel)
        return parts.joined(separator: ". ")
    }
}

private struct ExploreCardInformation: View {
    let listing: ExploreListingItem
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
        .accessibilityHidden(true)
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
            .padding(.vertical, KwaborDesignTokens.Spacing.xs)
            .background(.ultraThinMaterial)
            .clipShape(Capsule())
            .accessibilityHidden(true)
    }
}

private struct ExploreEventDateBadge: View {
    let label: String

    var body: some View {
        Label(label, systemImage: "calendar")
            .font(.caption.weight(.bold))
            .foregroundStyle(KwaborDesignTokens.ColorToken.surface0)
            .lineLimit(1)
            .padding(.horizontal, KwaborDesignTokens.Spacing.sm)
            .padding(.vertical, KwaborDesignTokens.Spacing.xs)
            .background(.ultraThinMaterial)
            .clipShape(Capsule())
            .accessibilityHidden(true)
    }
}

private struct ExploreEndedRibbon: View {
    let label: String

    var body: some View {
        Text(label)
            .font(.caption.weight(.bold))
            .foregroundStyle(KwaborDesignTokens.ColorToken.surface0)
            .lineLimit(1)
            .frame(
                width: exploreEndedRibbonWidth,
                height: KwaborDesignTokens.Spacing.xxl
            )
            .background(KwaborDesignTokens.ColorToken.ink500)
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
private let exploreEndedRibbonWidth = KwaborDesignTokens.Sizing.touchTarget * 3
private let exploreEndedRibbonClearance =
    KwaborDesignTokens.Spacing.xxl + KwaborDesignTokens.Spacing.lg
private let exploreEndedRibbonAngle = 45.0
