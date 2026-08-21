import Shared
import SwiftUI
import UIKit

struct ExploreView: View {
    @ObservedObject var store: ExploreStore
    @ObservedObject var searchStore: SearchStore
    @ObservedObject var guideDiscoveryStore: GuideDiscoveryStore
    let onListingOpen: (String) -> Void
    let onAuthenticationRequired: (ExploreAuthenticationRequest) -> Void
    let showsClosedBetaDisclosure: Bool
    let showsGuideDiscoveryEntry: Bool
    let isObscured: Bool
    let performanceCollectionRequested: Bool

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var citySelectorSheetPresentation: ExploreSheetPresentation?

    var body: some View {
        VStack(spacing: 0) {
            if showsClosedBetaDisclosure {
                ExploreBanner(
                    text: store.strings.closedBetaDemoDisclosure,
                    foreground: KwaborDesignTokens.ColorToken.ink950,
                    background: KwaborDesignTokens.ColorToken.ink100
                )
            }
            if searchStore.state.isActive {
                SearchOfflineBanner(
                    store: searchStore,
                    offlineText: store.strings.offlineBanner
                )
            } else {
                ExploreOfflineBanner(store: store)
            }
            GeometryReader { proxy in
                ExploreScrollContent(
                    store: store,
                    searchStore: searchStore,
                    guideDiscoveryStore: guideDiscoveryStore,
                    showsGuideDiscoveryEntry: showsGuideDiscoveryEntry,
                    columns: gridColumns(availableWidth: proxy.size.width),
                    onListingOpen: onListingOpen
                )
            }
        }
        .background(KwaborDesignTokens.ColorToken.paper50)
        .background {
            if performanceCollectionRequested,
               probeSurfaceUnobscured,
               let token = store.firstUsableViewportDrawToken {
                ExploreFirstUsableViewportCommitReader(
                    token: token,
                    onCommitted: store.firstUsableViewportWasCommitted
                )
            }
        }
        .sheet(item: citySelectorBinding) { presentation in
            ExploreCitySelector(store: store)
                .background {
                    ExploreSheetDismissalObserver(
                        token: presentation.token,
                        onAttached: store.surfacePresentationAttached,
                        onRemoved: citySelectorPresentationDidRemove
                    )
                    .id(presentation.id)
                }
        }
        .onChange(of: store.authenticationRequest?.id) { _, _ in
            guard let request = store.authenticationRequest else { return }
            onAuthenticationRequired(request)
        }
        .onChange(of: store.announcementRevision) { _, _ in
            guard let announcement = store.latestAnnouncement else { return }
            UIAccessibility.post(notification: .announcement, argument: announcement)
        }
        .onChange(of: searchStore.announcementRevision) { _, _ in
            guard let announcement = searchStore.latestAnnouncement else { return }
            UIAccessibility.post(notification: .announcement, argument: announcement)
        }
        .onChange(of: searchContextFingerprint) { _, _ in
            searchStore.updateExploreContext(store.state)
        }
        .onChange(of: store.state.isCitySelectorOpen) { _, isPresented in
            reconcileCitySelectorPresentation(isPresented)
        }
        .onAppear {
            searchStore.updateExploreContext(store.state)
            store.screenAppeared(
                applicationActive: UIApplication.shared.applicationState == .active,
                surfaceUnobscured: probeSurfaceUnobscured
            )
            reconcileCitySelectorPresentation(store.state.isCitySelectorOpen)
        }
        .onDisappear {
            dismissCitySelectorSheetPresentation()
            store.closeCitySelector()
            store.screenDisappeared()
        }
        .onChange(of: probeSurfaceUnobscured) { _, unobscured in
            store.surfaceVisibilityChanged(unobscured)
        }
        .onChange(of: performanceCollectionRequested) { _, _ in
            store.performanceCollectionEligibilityChanged()
        }
    }

    private var citySelectorBinding: Binding<ExploreSheetPresentation?> {
        Binding(
            get: { citySelectorSheetPresentation },
            set: { presentation in
                if let presentation {
                    citySelectorSheetPresentation = presentation
                } else {
                    dismissCitySelectorSheetPresentation()
                    store.closeCitySelector()
                }
            }
        )
    }

    private func reconcileCitySelectorPresentation(_ isPresented: Bool) {
        if isPresented {
            guard citySelectorSheetPresentation == nil else { return }
            let token = store.surfacePresentationStarted(.citySelector)
            citySelectorSheetPresentation = ExploreSheetPresentation(token: token)
        } else {
            dismissCitySelectorSheetPresentation()
        }
    }

    private func dismissCitySelectorSheetPresentation() {
        guard let presentation = citySelectorSheetPresentation else { return }
        store.surfacePresentationDismissRequested(presentation.token)
        citySelectorSheetPresentation = nil
    }

    private func citySelectorPresentationDidRemove(_ token: ExploreSurfacePresentationToken) {
        store.surfacePresentationRemoved(token)
    }

    private func gridColumns(availableWidth: CGFloat) -> [GridItem] {
        let count = SearchGridPolicy.columnCount(
            availableWidth: Double(availableWidth),
            tabletBreakpoint: Double(KwaborDesignTokens.Sizing.exploreTabletBreakpoint),
            usesAccessibilityLayout: dynamicTypeSize >= .xxLarge
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

    private var searchContextFingerprint: String {
        let tab: String
        if store.state.isPlacesTabSelected {
            tab = "places"
        } else if store.state.isEventsTabSelected {
            tab = "events"
        } else {
            tab = "establishments"
        }
        let cityFingerprint = store.state.availableCities
            .map { "\($0.id):\($0.label)" }
            .joined(separator: "|")
        return [
            tab,
            store.state.selectedChipId ?? "",
            store.state.selectedCityId ?? "",
            String(describing: store.state.currency),
            cityFingerprint,
        ].joined(separator: "#")
    }

    private var probeSurfaceUnobscured: Bool {
        !searchStore.state.isActive &&
            !store.state.isCitySelectorOpen &&
            !store.surfacePresentationObscured &&
            !isObscured
    }
}

struct ExploreSheetPresentation: Identifiable {
    let token: ExploreSurfacePresentationToken

    var id: Int64 { token.generation }
}

struct ExploreSheetDismissalObserver: UIViewRepresentable {
    let token: ExploreSurfacePresentationToken
    let onAttached: (ExploreSurfacePresentationToken) -> Void
    let onRemoved: (ExploreSurfacePresentationToken) -> Void

    func makeUIView(context: Context) -> ExploreSheetDismissalSentinelView {
        ExploreSheetDismissalSentinelView(
            token: token,
            onAttached: onAttached,
            onRemoved: onRemoved
        )
    }

    func updateUIView(_ view: ExploreSheetDismissalSentinelView, context: Context) {
        view.update(onAttached: onAttached, onRemoved: onRemoved)
    }
}

final class ExploreSheetDismissalSentinelView: UIView {
    private let token: ExploreSurfacePresentationToken
    private var onAttached: ((ExploreSurfacePresentationToken) -> Void)?
    private var onRemoved: ((ExploreSurfacePresentationToken) -> Void)?
    private var didDeliverAttachment = false
    private var didDeliverRemoval = false

    init(
        token: ExploreSurfacePresentationToken,
        onAttached: @escaping (ExploreSurfacePresentationToken) -> Void,
        onRemoved: @escaping (ExploreSurfacePresentationToken) -> Void
    ) {
        self.token = token
        self.onAttached = onAttached
        self.onRemoved = onRemoved
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        backgroundColor = .clear
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    func update(
        onAttached: @escaping (ExploreSurfacePresentationToken) -> Void,
        onRemoved: @escaping (ExploreSurfacePresentationToken) -> Void
    ) {
        self.onAttached = onAttached
        self.onRemoved = onRemoved
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            deliverAttachment()
            return
        }
        guard didDeliverAttachment else { return }
        deliverRemoval()
    }

    private func deliverAttachment() {
        guard !didDeliverAttachment else { return }
        didDeliverAttachment = true
        let attachment = onAttached
        onAttached = nil
        attachment?(token)
    }

    private func deliverRemoval() {
        guard !didDeliverRemoval else { return }
        didDeliverRemoval = true
        let completion = onRemoved
        onRemoved = nil
        let removedToken = token
        DispatchQueue.main.async {
            completion?(removedToken)
        }
    }
}

private struct ExploreFirstUsableViewportCommitReader: UIViewRepresentable {
    let token: ExploreFirstUsableViewportDrawToken
    let onCommitted: (ExploreFirstUsableViewportDrawToken) -> Void

    func makeUIView(context: Context) -> ExploreFirstUsableViewportCommitView {
        ExploreFirstUsableViewportCommitView()
    }

    func updateUIView(_ view: ExploreFirstUsableViewportCommitView, context: Context) {
        view.update(token: token, onCommitted: onCommitted)
    }

    static func dismantleUIView(_ view: ExploreFirstUsableViewportCommitView, coordinator: Void) {
        view.clear()
    }
}

private final class ExploreFirstUsableViewportCommitView: UIView {
    private var token: ExploreFirstUsableViewportDrawToken?
    private var tokenSignature: String?
    private var committedSignature: String?
    private var onCommitted: ((ExploreFirstUsableViewportDrawToken) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        isUserInteractionEnabled = false
        backgroundColor = .clear
        contentMode = .redraw
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    func update(
        token: ExploreFirstUsableViewportDrawToken,
        onCommitted: @escaping (ExploreFirstUsableViewportDrawToken) -> Void
    ) {
        self.token = token
        tokenSignature = "\(token.generation):\(token.viewportState.wireName)"
        self.onCommitted = onCommitted
        setNeedsDisplay()
    }

    func clear() {
        token = nil
        tokenSignature = nil
        committedSignature = nil
        onCommitted = nil
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        setNeedsDisplay()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.width > 0, bounds.height > 0 {
            setNeedsDisplay()
        }
    }

    override func draw(_ rect: CGRect) {
        super.draw(rect)
        guard window != nil,
              bounds.width > 0,
              bounds.height > 0,
              let token,
              let signature = tokenSignature,
              committedSignature != signature else {
            return
        }
        committedSignature = signature
        DispatchQueue.main.async { [weak self] in
            guard let self,
                  self.window != nil,
                  self.tokenSignature == signature else {
                return
            }
            self.onCommitted?(token)
        }
    }
}

private struct ExploreScrollContent: View {
    @ObservedObject var store: ExploreStore
    @ObservedObject var searchStore: SearchStore
    @ObservedObject var guideDiscoveryStore: GuideDiscoveryStore
    let showsGuideDiscoveryEntry: Bool
    let columns: [GridItem]
    let onListingOpen: (String) -> Void

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.lg) {
                if searchStore.state.isActive {
                    SearchTransientBanners(store: searchStore)
                } else {
                    if showsGuideDiscoveryEntry {
                        GuideDiscoveryEntryLink(store: guideDiscoveryStore)
                    }
                    ExploreTransientBanners(store: store)
                }
                ExploreHeader(store: store, searchStore: searchStore)
                if searchStore.state.isActive {
                    SearchActiveContent(
                        store: searchStore,
                        commonStrings: store.strings,
                        columns: columns
                    )
                    SearchAppendFooter(
                        store: searchStore,
                        commonStrings: store.strings
                    )
                } else {
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
            }
            .padding(.horizontal, KwaborDesignTokens.Spacing.lg)
            .padding(.top, KwaborDesignTokens.Spacing.lg)
            .padding(.bottom, KwaborDesignTokens.Spacing.xxxl)
        }
        .refreshable {
            if searchStore.state.isActive {
                searchStore.refresh()
            } else {
                store.refresh()
            }
        }
    }
}

private struct ExploreHeader: View {
    @ObservedObject var store: ExploreStore
    @ObservedObject var searchStore: SearchStore

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
            SearchEntryField(
                searchStore: searchStore,
                exploreState: store.state
            )
            if !store.state.chips.isEmpty,
               (!searchStore.state.isActive || searchStore.state.isActiveTabScope) {
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

private struct SearchOfflineBanner: View {
    @ObservedObject var store: SearchStore
    let offlineText: String

    var body: some View {
        if store.state.isOffline {
            ExploreBanner(
                text: offlineText,
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
