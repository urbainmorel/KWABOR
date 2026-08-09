import Shared
import SwiftUI
import UIKit

struct FavoritesEntryLink: View {
    @ObservedObject var store: FavoritesStore

    var body: some View {
        NavigationLink {
            FavoritesView(store: store)
        } label: {
            HStack(spacing: KwaborDesignTokens.Spacing.md) {
                Image(systemName: "bookmark.fill")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(KwaborDesignTokens.ColorToken.surface0)
                    .frame(
                        width: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget,
                        height: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget
                    )
                    .background(KwaborDesignTokens.ColorToken.ink950)
                    .clipShape(Circle())
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
                    Text(store.strings.title)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                    Text(store.strings.emptyMessage)
                        .font(.body)
                        .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: KwaborDesignTokens.Spacing.sm)
                Image(systemName: "chevron.right")
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                    .accessibilityHidden(true)
            }
            .frame(
                maxWidth: .infinity,
                minHeight: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget,
                alignment: .leading
            )
            .padding(KwaborDesignTokens.Spacing.lg)
            .background(KwaborDesignTokens.ColorToken.surface0)
            .contentShape(Rectangle())
            .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
        }
        .buttonStyle(.plain)
        .accessibilityLabel([store.strings.title, store.strings.emptyMessage].joined(separator: ". "))
    }
}

struct FavoritesView: View {
    @ObservedObject var store: FavoritesStore
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        VStack(spacing: 0) {
            if store.state.isOffline {
                FavoritesBanner(
                    text: store.commonStrings.offlineBanner,
                    foreground: KwaborDesignTokens.ColorToken.surface0,
                    background: KwaborDesignTokens.ColorToken.ink900
                )
            }
            GeometryReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.lg) {
                        FavoritesTransientState(store: store)
                        FavoritesFilters(store: store)
                        FavoritesContent(
                            store: store,
                            columns: gridColumns(availableWidth: proxy.size.width)
                        )
                        FavoritesAppendFooter(store: store)
                    }
                    .padding(.horizontal, KwaborDesignTokens.Spacing.lg)
                    .padding(.top, KwaborDesignTokens.Spacing.lg)
                    .padding(.bottom, KwaborDesignTokens.Spacing.xxxl)
                }
                .refreshable {
                    store.refresh()
                }
            }
        }
        .background(KwaborDesignTokens.ColorToken.paper50)
        .navigationTitle(store.strings.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .onAppear(perform: store.screenAppeared)
        .onDisappear(perform: store.screenDisappeared)
        .onChange(of: store.announcementRevision) { _, _ in
            guard let announcement = store.latestAnnouncement else { return }
            UIAccessibility.post(notification: .announcement, argument: announcement)
        }
    }

    private func gridColumns(availableWidth: CGFloat) -> [GridItem] {
        let count = FavoritesGridPolicy.columnCount(
            availableWidth: Double(availableWidth),
            tabletBreakpoint: Double(KwaborDesignTokens.Sizing.exploreTabletBreakpoint),
            usesAccessibilityLayout: dynamicTypeSize >= .accessibility1
        )
        return Array(
            repeating: GridItem(
                .flexible(minimum: 0, maximum: .infinity),
                spacing: KwaborDesignTokens.Spacing.md,
                alignment: .top
            ),
            count: count
        )
    }
}

private struct FavoritesFilters: View {
    @ObservedObject var store: FavoritesStore

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: KwaborDesignTokens.Spacing.sm) {
                FavoritesFilterPill(
                    label: store.strings.allFilter,
                    selected: store.state.selectedFilter == .all,
                    action: store.selectAll
                )
                FavoritesFilterPill(
                    label: store.strings.placesFilter,
                    selected: store.state.selectedFilter == .places,
                    action: store.selectPlaces
                )
                FavoritesFilterPill(
                    label: store.strings.eventsFilter,
                    selected: store.state.selectedFilter == .events,
                    action: store.selectEvents
                )
                FavoritesFilterPill(
                    label: store.strings.hotelsRestaurantsFilter,
                    selected: store.state.selectedFilter == .hotelsRestaurants,
                    action: store.selectHotelsRestaurants
                )
            }
        }
        .scrollIndicators(.hidden)
        .accessibilityElement(children: .contain)
    }
}

private struct FavoritesFilterPill: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(
                    selected
                        ? KwaborDesignTokens.ColorToken.surface0
                        : KwaborDesignTokens.ColorToken.ink700
                )
                .lineLimit(1)
                .padding(.horizontal, KwaborDesignTokens.Spacing.lg)
                .frame(minHeight: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget)
                .background(
                    selected
                        ? KwaborDesignTokens.ColorToken.ink950
                        : KwaborDesignTokens.ColorToken.ink100
                )
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct FavoritesContent: View {
    @ObservedObject var store: FavoritesStore
    let columns: [GridItem]

    var body: some View {
        if !store.isConfigured {
            FavoritesStateMessage(
                title: store.commonStrings.errorStateTitle,
                supportingText: store.commonStrings.configurationUnavailable,
                actionLabel: nil,
                action: nil
            )
        } else if store.isViewerTransitionPending ||
                    (store.state.isLoading && store.visibleItems.isEmpty) {
            LazyVGrid(columns: columns, spacing: KwaborDesignTokens.Spacing.md) {
                ForEach(
                    0..<max(columns.count * favoritesSkeletonRows, favoritesMinimumSkeletonCount),
                    id: \.self
                ) { _ in
                    ExploreSkeletonCard()
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(store.commonStrings.loading)
        } else if let errorMessage = store.state.errorMessage {
            FavoritesStateMessage(
                title: store.commonStrings.errorStateTitle,
                supportingText: errorMessage,
                actionLabel: store.commonStrings.retry,
                action: store.retry
            )
        } else if store.state.isEmpty || !store.canDisplayPrivateContent {
            FavoritesStateMessage(
                title: store.strings.emptyTitle,
                supportingText: store.strings.emptyMessage,
                actionLabel: nil,
                action: nil
            )
        } else {
            LazyVGrid(columns: columns, spacing: KwaborDesignTokens.Spacing.md) {
                ForEach(store.visibleItems, id: \.id) { item in
                    FavoriteListingCard(
                        item: item,
                        strings: store.strings,
                        commonStrings: store.commonStrings,
                        isRemoving: store.state.removingListingIds.contains(item.id),
                        onOpen: { store.openListing(item.id) },
                        onRemove: { store.removeFavorite(item.id) }
                    )
                    .onAppear {
                        store.itemDidAppear(item.id)
                    }
                }
            }
        }
    }
}

private struct FavoriteListingCard: View {
    let item: FavoriteListingItem
    let strings: FavoritesStrings
    let commonStrings: KwaborStrings
    let isRemoving: Bool
    let onOpen: () -> Void
    let onRemove: () -> Void

    private var priceLabel: String {
        PriceLabelFormatter.shared.compactXof(price: item.price, freeLabel: commonStrings.free)
    }

    private var decorationVisibility: FavoritesCardDecorationVisibility {
        FavoritesCardDecorationPolicy.visibility(
            isEventEnded: item.isEventEnded,
            ratingLabel: item.ratingLabel
        )
    }

    var body: some View {
        ZStack {
            Button(action: onOpen) {
                ZStack {
                    ExploreRemoteImage(rawURL: item.coverImageUrl)
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
                            if decorationVisibility.showsRating,
                               let rating = item.ratingLabel {
                                FavoriteRatingBadge(rating: rating)
                            }
                            Spacer(minLength: KwaborDesignTokens.Sizing.touchTarget)
                        }
                        .padding(
                            .top,
                            decorationVisibility.showsEndedRibbon
                                ? favoritesEndedRibbonClearance
                                : 0
                        )
                        Spacer(minLength: KwaborDesignTokens.Spacing.sm)
                        FavoriteListingInformation(item: item, priceLabel: priceLabel)
                    }
                    .padding(KwaborDesignTokens.Spacing.md)
                }
                .overlay(alignment: .topLeading) {
                    if decorationVisibility.showsEndedRibbon {
                        FavoriteEndedRibbon(label: strings.eventEnded)
                            .rotationEffect(.degrees(-favoritesEndedRibbonAngle))
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
            .accessibilityHint(strings.openListing)
            .accessibilitySortPriority(2)

            VStack {
                HStack {
                    Spacer()
                    Button(action: onRemove) {
                        Group {
                            if isRemoving {
                                ProgressView()
                                    .tint(KwaborDesignTokens.ColorToken.ink950)
                            } else {
                                Image(systemName: "bookmark.fill")
                                    .font(.system(size: 18, weight: .semibold))
                            }
                        }
                        .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                        .frame(
                            width: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget,
                            height: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget
                        )
                        .background(.ultraThinMaterial)
                        .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .disabled(isRemoving)
                    .accessibilityLabel(strings.removeFavorite)
                    .accessibilityValue(isRemoving ? commonStrings.loading : "")
                    .accessibilitySortPriority(1)
                }
                Spacer()
            }
            .padding(KwaborDesignTokens.Spacing.md)
        }
        .aspectRatio(favoritesCardAspectRatio, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
        .accessibilityElement(children: .contain)
    }

    private var accessibilitySummary: String {
        var parts = [item.title]
        if let imageAlt = item.coverImageAlt, !imageAlt.isEmpty {
            parts.append(imageAlt)
        }
        parts.append(item.cityLabel)
        if item.verified {
            parts.append(commonStrings.detail.verified)
        }
        if let rating = item.ratingLabel {
            parts.append("\(commonStrings.rating) \(rating)")
        }
        if item.isEventEnded {
            parts.append(strings.eventEndedAccessibility)
        }
        parts.append(priceLabel)
        return parts.joined(separator: ". ")
    }
}

private struct FavoriteListingInformation: View {
    let item: FavoriteListingItem
    let priceLabel: String

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
            HStack(alignment: .firstTextBaseline, spacing: KwaborDesignTokens.Spacing.xs) {
                Text(item.title)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(KwaborDesignTokens.ColorToken.surface0)
                    .lineLimit(2)
                if item.verified {
                    Image(systemName: "checkmark.seal.fill")
                        .font(.caption)
                        .foregroundStyle(KwaborDesignTokens.ColorToken.surface0)
                }
            }
            Label(item.cityLabel, systemImage: "location.fill")
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

private struct FavoriteEndedRibbon: View {
    let label: String

    var body: some View {
        Text(label)
            .font(.caption.weight(.bold))
            .foregroundStyle(KwaborDesignTokens.ColorToken.surface0)
            .lineLimit(1)
            .frame(
                width: favoritesEndedRibbonWidth,
                height: KwaborDesignTokens.Spacing.xxl
            )
            .background(KwaborDesignTokens.ColorToken.ink500)
            .accessibilityHidden(true)
    }
}

private struct FavoriteRatingBadge: View {
    let rating: String

    var body: some View {
        Label(rating, systemImage: "star.fill")
            .font(.caption.weight(.semibold))
            .foregroundStyle(KwaborDesignTokens.ColorToken.surface0)
            .padding(.horizontal, KwaborDesignTokens.Spacing.sm)
            .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
            .background(.ultraThinMaterial)
            .clipShape(Capsule())
            .accessibilityHidden(true)
    }
}

private struct FavoritesTransientState: View {
    @ObservedObject var store: FavoritesStore

    var body: some View {
        if store.state.isRefreshing {
            HStack(spacing: KwaborDesignTokens.Spacing.sm) {
                ProgressView()
                Text(store.commonStrings.loading)
                    .font(.callout)
            }
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .combine)
        } else if let refreshMessage = store.state.refreshMessage {
            FavoritesBanner(
                text: refreshMessage,
                foreground: KwaborDesignTokens.ColorToken.ink950,
                background: KwaborDesignTokens.ColorToken.ink100
            )
        } else if let mutationMessage = store.state.mutationMessage {
            FavoritesBanner(
                text: mutationMessage,
                foreground: KwaborDesignTokens.ColorToken.ink950,
                background: KwaborDesignTokens.ColorToken.ink100
            )
        }
    }
}

private struct FavoritesAppendFooter: View {
    @ObservedObject var store: FavoritesStore

    var body: some View {
        if store.state.isAppending {
            ProgressView(store.commonStrings.loading)
                .frame(maxWidth: .infinity)
                .padding(KwaborDesignTokens.Spacing.lg)
        } else if let appendError = store.state.appendErrorMessage {
            FavoritesStateMessage(
                title: store.commonStrings.errorStateTitle,
                supportingText: appendError,
                actionLabel: store.commonStrings.retry,
                action: store.retryAppend
            )
        }
    }
}

private struct FavoritesStateMessage: View {
    let title: String
    let supportingText: String
    let actionLabel: String?
    let action: (() -> Void)?

    var body: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.md) {
            Text(title)
                .font(.headline)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Text(supportingText)
                .font(.body)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            if let actionLabel, let action {
                Button(actionLabel, action: action)
                    .buttonStyle(.borderedProminent)
                    .tint(KwaborDesignTokens.ColorToken.ink950)
                    .frame(minHeight: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget)
            }
        }
        .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.detailStateMinimumHeight)
        .padding(KwaborDesignTokens.Spacing.xxl)
        .background(KwaborDesignTokens.ColorToken.surface0)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
    }
}

private struct FavoritesBanner: View {
    let text: String
    let foreground: Color
    let background: Color

    var body: some View {
        Text(text)
            .font(.callout.weight(.semibold))
            .foregroundStyle(foreground)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, KwaborDesignTokens.Spacing.lg)
            .padding(.vertical, KwaborDesignTokens.Spacing.sm)
            .background(background)
            .accessibilityElement(children: .combine)
    }
}

private let favoritesSkeletonRows = 2
private let favoritesMinimumSkeletonCount = 4
private let favoritesCardAspectRatio: CGFloat = 3 / 4
private let favoritesEndedRibbonWidth = KwaborDesignTokens.Sizing.touchTarget * 3
private let favoritesEndedRibbonClearance =
    KwaborDesignTokens.Sizing.touchTarget + KwaborDesignTokens.Spacing.lg
private let favoritesEndedRibbonAngle = 45.0
