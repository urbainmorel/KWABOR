import Shared
import SwiftUI
import UIKit

struct ExploreView: View {
    @ObservedObject var store: ExploreStore
    @ObservedObject var guideDiscoveryStore: GuideDiscoveryStore
    let onListingOpen: (String) -> Void
    let onAuthenticationRequired: (ExploreAuthenticationRequest) -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        VStack(spacing: 0) {
            ExploreOfflineBanner(store: store)
            GeometryReader { proxy in
                ExploreScrollContent(
                    store: store,
                    guideDiscoveryStore: guideDiscoveryStore,
                    columns: gridColumns(availableWidth: proxy.size.width),
                    onListingOpen: onListingOpen
                )
            }
        }
        .background(KwaborDesignTokens.ColorToken.paper50)
        .sheet(isPresented: citySelectorBinding) {
            ExploreCitySelector(store: store)
        }
        .onChange(of: store.authenticationRequest?.id) { _, _ in
            guard let request = store.authenticationRequest else { return }
            onAuthenticationRequired(request)
        }
        .onChange(of: store.announcementRevision) { _, _ in
            guard let announcement = store.latestAnnouncement else { return }
            UIAccessibility.post(notification: .announcement, argument: announcement)
        }
    }

    private var citySelectorBinding: Binding<Bool> {
        Binding(
            get: { store.state.isCitySelectorOpen },
            set: { isPresented in
                if !isPresented {
                    store.closeCitySelector()
                }
            }
        )
    }

    private func gridColumns(availableWidth: CGFloat) -> [GridItem] {
        let count: Int
        if dynamicTypeSize >= .xxLarge {
            count = 1
        } else if availableWidth >= KwaborDesignTokens.Sizing.exploreTabletBreakpoint {
            count = 3
        } else {
            count = 2
        }
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

private struct ExploreScrollContent: View {
    @ObservedObject var store: ExploreStore
    @ObservedObject var guideDiscoveryStore: GuideDiscoveryStore
    let columns: [GridItem]
    let onListingOpen: (String) -> Void

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.lg) {
                ExploreTransientBanners(store: store)
                ExploreHeader(store: store)
                GuideDiscoveryEntryLink(store: guideDiscoveryStore)
                if store.state.isRefreshing {
                    HStack(spacing: KwaborDesignTokens.Spacing.sm) {
                        ProgressView()
                        Text(store.strings.loading)
                            .font(.callout)
                    }
                    .frame(maxWidth: .infinity)
                    .accessibilityElement(children: .combine)
                }
                ExploreFeedContent(
                    store: store,
                    columns: columns,
                    onListingOpen: onListingOpen
                )
                ExploreAppendFooter(store: store)
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

private struct ExploreHeader: View {
    @ObservedObject var store: ExploreStore

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.md) {
            Button(action: store.openCitySelector) {
                HStack(spacing: KwaborDesignTokens.Spacing.xs) {
                    Image(systemName: "location.fill")
                    Text(store.state.cityLabel)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.caption.weight(.bold))
                }
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                .frame(minHeight: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(store.strings.location)
            .accessibilityValue(store.state.cityLabel)

            Text(store.strings.homeTitle)
                .font(.system(.title, design: .default, weight: .bold))
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                .fixedSize(horizontal: false, vertical: true)

            ExploreTabs(store: store)
            if !store.state.chips.isEmpty {
                ExploreChips(store: store)
            }
        }
    }
}

private struct ExploreTabs: View {
    @ObservedObject var store: ExploreStore

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: KwaborDesignTokens.Spacing.sm) {
                ExploreSelectionPill(
                    label: store.strings.places,
                    selected: store.state.isPlacesTabSelected,
                    action: store.selectPlacesTab
                )
                ExploreSelectionPill(
                    label: store.strings.events,
                    selected: store.state.isEventsTabSelected,
                    action: store.selectEventsTab
                )
                ExploreSelectionPill(
                    label: store.strings.hotelsRestaurants,
                    selected: store.state.isHotelsRestaurantsTabSelected,
                    action: store.selectHotelsRestaurantsTab
                )
            }
        }
        .scrollIndicators(.hidden)
    }
}

private struct ExploreChips: View {
    @ObservedObject var store: ExploreStore

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: KwaborDesignTokens.Spacing.sm) {
                ForEach(store.state.chips, id: \.id) { chip in
                    ExploreSelectionPill(
                        label: chip.label,
                        selected: store.state.selectedChipId == chip.id,
                        action: { store.selectChip(chip.id) }
                    )
                }
            }
        }
        .scrollIndicators(.hidden)
    }
}

private struct ExploreSelectionPill: View {
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
                .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
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

private struct ExploreFeedContent: View {
    @ObservedObject var store: ExploreStore
    let columns: [GridItem]
    let onListingOpen: (String) -> Void

    var body: some View {
        if !store.isConfigured {
            ExploreStateMessage(
                title: store.strings.errorStateTitle,
                supportingText: store.strings.configurationUnavailable,
                actionLabel: nil,
                action: nil
            )
        } else if store.state.isLoading && store.state.listings.isEmpty {
            LazyVGrid(columns: columns, spacing: KwaborDesignTokens.Spacing.md) {
                ForEach(0..<max(columns.count * skeletonRows, minimumSkeletonCount), id: \.self) { _ in
                    ExploreSkeletonCard()
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(store.strings.loading)
        } else if let errorMessage = store.state.errorMessage {
            ExploreStateMessage(
                title: store.strings.errorStateTitle,
                supportingText: errorMessage,
                actionLabel: store.strings.retry,
                action: store.retry
            )
        } else if store.state.isEmpty {
            ExploreStateMessage(
                title: store.strings.emptyStateTitle,
                supportingText: store.strings.exploreEmptyMessage,
                actionLabel: store.strings.retry,
                action: store.retry
            )
        } else {
            LazyVGrid(columns: columns, spacing: KwaborDesignTokens.Spacing.md) {
                ForEach(store.state.listings, id: \.id) { listing in
                    ExploreCard(
                        listing: listing,
                        strings: store.strings,
                        onOpen: { onListingOpen(listing.id) },
                        onLike: { store.toggleLike(listing.id) },
                        onFavorite: { store.toggleFavorite(listing.id) }
                    )
                    .onAppear {
                        store.listingDidAppear(listing.id)
                    }
                }
            }
        }
    }
}

private struct ExploreAppendFooter: View {
    @ObservedObject var store: ExploreStore

    var body: some View {
        if store.state.isAppending {
            ProgressView(store.strings.loading)
                .frame(maxWidth: .infinity)
                .padding(KwaborDesignTokens.Spacing.lg)
        } else if let appendError = store.state.appendErrorMessage {
            ExploreStateMessage(
                title: store.strings.errorStateTitle,
                supportingText: appendError,
                actionLabel: store.strings.retry,
                action: store.retryAppend
            )
        }
    }
}

private struct ExploreOfflineBanner: View {
    @ObservedObject var store: ExploreStore

    var body: some View {
        if store.state.isOffline {
            ExploreBanner(
                text: store.strings.offlineBanner,
                foreground: KwaborDesignTokens.ColorToken.surface0,
                background: KwaborDesignTokens.ColorToken.ink900
            )
        }
    }
}

private struct ExploreTransientBanners: View {
    @ObservedObject var store: ExploreStore

    var body: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(messages.enumerated()), id: \.offset) { _, message in
                ExploreBanner(
                    text: message,
                    foreground: KwaborDesignTokens.ColorToken.ink950,
                    background: KwaborDesignTokens.ColorToken.ink100
                )
            }
        }
    }

    private var messages: [String] {
        var values = [
            store.state.interactionMessage,
            store.state.refreshMessage,
            store.state.isCitySelectorOpen ? nil : store.state.locationMessage,
        ].compactMap { $0 }
        if store.state.isLocalCacheUnavailable,
           store.state.errorMessage == nil,
           !values.contains(store.strings.errorStateTitle) {
            values.append(store.strings.errorStateTitle)
        }
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}

private struct ExploreBanner: View {
    let text: String
    let foreground: Color
    let background: Color

    var body: some View {
        Text(text)
            .font(.callout.weight(.medium))
            .foregroundStyle(foreground)
            .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
            .padding(.horizontal, KwaborDesignTokens.Spacing.lg)
            .background(background)
            .accessibilityLabel(text)
    }
}

private struct ExploreStateMessage: View {
    let title: String
    let supportingText: String?
    let actionLabel: String?
    let action: (() -> Void)?

    var body: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.md) {
            Text(title)
                .font(.headline)
                .multilineTextAlignment(.center)
            if let supportingText {
                Text(supportingText)
                    .font(.body)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                    .multilineTextAlignment(.center)
            }
            if let actionLabel, let action {
                Button(actionLabel, action: action)
                    .buttonStyle(.borderedProminent)
                    .tint(KwaborDesignTokens.ColorToken.ink950)
                    .frame(minHeight: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget)
            }
        }
        .frame(maxWidth: .infinity, minHeight: stateMessageMinimumHeight)
        .padding(KwaborDesignTokens.Spacing.xxl)
        .background(KwaborDesignTokens.ColorToken.surface0)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
        .accessibilityElement(children: .contain)
    }
}

private let skeletonRows = 2
private let minimumSkeletonCount = 4
private let stateMessageMinimumHeight: CGFloat = 180
