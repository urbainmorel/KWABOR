import Shared
import SwiftUI

struct SearchEntryField: View {
    @ObservedObject var searchStore: SearchStore
    let exploreState: ExploreUiState

    @FocusState private var isFieldFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.sm) {
            HStack(spacing: KwaborDesignTokens.Spacing.sm) {
                HStack(spacing: KwaborDesignTokens.Spacing.sm) {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                        .accessibilityHidden(true)

                    TextField(searchStore.strings.placeholder, text: queryBinding)
                        .focused($isFieldFocused)
                        .submitLabel(.search)
                        .onSubmit(searchStore.submit)
                        .accessibilityLabel(searchStore.strings.placeholder)
                        .accessibilityHint(searchStore.strings.initialHint)

                    if !searchStore.queryText.isEmpty {
                        Button(action: clearAndKeepFocus) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                                .frame(
                                    width: KwaborDesignTokens.Sizing.touchTarget,
                                    height: KwaborDesignTokens.Sizing.touchTarget
                                )
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(searchStore.strings.clear)
                    }
                }
                .padding(.leading, KwaborDesignTokens.Spacing.md)
                .padding(.trailing, KwaborDesignTokens.Spacing.xs)
                .frame(minHeight: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget)
                .background(KwaborDesignTokens.ColorToken.ink100)
                .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control))
                .contentShape(Rectangle())
                .onTapGesture {
                    isFieldFocused = true
                }

                if searchStore.state.isActive {
                    Button(action: close) {
                        Image(systemName: "xmark")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                            .frame(
                                width: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget,
                                height: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(searchStore.strings.close)
                }
            }

            if searchStore.state.isActive {
                SearchScopeSelector(store: searchStore)
            }
        }
        .onChange(of: isFieldFocused) { _, isFocused in
            if isFocused, !searchStore.state.isActive {
                searchStore.activate(exploreState: exploreState)
            }
        }
        .onChange(of: searchStore.state.isActive) { _, isActive in
            if isActive {
                isFieldFocused = true
            } else {
                isFieldFocused = false
            }
        }
    }

    private var queryBinding: Binding<String> {
        Binding(
            get: { searchStore.queryText },
            set: searchStore.queryChanged
        )
    }

    private func clearAndKeepFocus() {
        searchStore.clear()
        isFieldFocused = true
    }

    private func close() {
        isFieldFocused = false
        searchStore.close()
    }
}

private struct SearchScopeSelector: View {
    @ObservedObject var store: SearchStore

    var body: some View {
        HStack(spacing: KwaborDesignTokens.Spacing.sm) {
            SearchScopeButton(
                label: store.strings.activeTabScope,
                selected: store.state.isActiveTabScope,
                action: store.selectActiveTabScope
            )
            SearchScopeButton(
                label: store.strings.allScope,
                selected: store.state.isAllScope,
                action: store.selectAllScope
            )
        }
        .accessibilityElement(children: .contain)
    }
}

private struct SearchScopeButton: View {
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
                .frame(maxWidth: .infinity)
                .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
                .padding(.horizontal, KwaborDesignTokens.Spacing.md)
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

struct SearchActiveContent: View {
    @ObservedObject var store: SearchStore
    let commonStrings: KwaborStrings
    let columns: [GridItem]

    var body: some View {
        Group {
            if !store.isConfigured {
                SearchStateMessage(
                    title: commonStrings.errorStateTitle,
                    supportingText: store.strings.loadFailed,
                    actionLabel: nil,
                    action: nil
                )
            } else if let queryErrorMessage = store.state.queryErrorMessage {
                SearchStateMessage(
                    title: store.strings.title,
                    supportingText: queryErrorMessage,
                    actionLabel: nil,
                    action: nil
                )
            } else if !store.state.hasSubmittedQuery {
                SearchInitialHint(text: store.strings.initialHint)
            } else if store.state.isLoading && store.state.listings.isEmpty {
                SearchLoadingGrid(
                    columns: columns,
                    loadingLabel: commonStrings.loading
                )
            } else if let errorMessage = store.state.errorMessage {
                SearchStateMessage(
                    title: commonStrings.errorStateTitle,
                    supportingText: errorMessage,
                    actionLabel: commonStrings.retry,
                    action: store.retry
                )
            } else if store.state.isEmpty {
                SearchStateMessage(
                    title: store.strings.emptyTitle,
                    supportingText: store.strings.emptyMessage,
                    actionLabel: store.canOpenAssistant ? store.strings.tryAssistant : nil,
                    action: store.canOpenAssistant ? store.openAssistant : nil
                )
            } else {
                SearchResultsGrid(
                    store: store,
                    commonStrings: commonStrings,
                    columns: columns
                )
            }
        }
    }
}

private struct SearchInitialHint: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.body)
            .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(KwaborDesignTokens.Spacing.lg)
            .background(KwaborDesignTokens.ColorToken.surface0)
            .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
    }
}

private struct SearchLoadingGrid: View {
    let columns: [GridItem]
    let loadingLabel: String

    var body: some View {
        LazyVGrid(columns: columns, spacing: KwaborDesignTokens.Spacing.md) {
            ForEach(0..<max(columns.count * searchSkeletonRows, minimumSearchSkeletonCount), id: \.self) { _ in
                ExploreSkeletonCard()
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(loadingLabel)
    }
}

private struct SearchResultsGrid: View {
    @ObservedObject var store: SearchStore
    let commonStrings: KwaborStrings
    let columns: [GridItem]

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.md) {
            if store.state.isRefreshing {
                ProgressView(commonStrings.loading)
                    .frame(maxWidth: .infinity)
            }

            if !store.state.resultCountLabel.isEmpty {
                Text(store.state.resultCountLabel)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                    .accessibilityAddTraits(.isHeader)
            }

            LazyVGrid(columns: columns, spacing: KwaborDesignTokens.Spacing.md) {
                ForEach(store.state.listings, id: \.id) { listing in
                    ExploreCard(
                        listing: listing,
                        strings: commonStrings,
                        showsInteractions: false,
                        onOpen: { store.openListing(listing.id) },
                        onLike: {},
                        onFavorite: {}
                    )
                    .onAppear {
                        store.listingDidAppear(listing.id)
                    }
                }
            }
        }
    }
}

struct SearchAppendFooter: View {
    @ObservedObject var store: SearchStore
    let commonStrings: KwaborStrings

    var body: some View {
        if store.state.isAppending {
            ProgressView(commonStrings.loading)
                .frame(maxWidth: .infinity)
                .padding(KwaborDesignTokens.Spacing.lg)
        } else if let appendErrorMessage = store.state.appendErrorMessage {
            SearchStateMessage(
                title: commonStrings.errorStateTitle,
                supportingText: appendErrorMessage,
                actionLabel: commonStrings.retry,
                action: store.retryAppend
            )
        }
    }
}

struct SearchTransientBanners: View {
    @ObservedObject var store: SearchStore

    var body: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(messages.enumerated()), id: \.offset) { _, message in
                Text(message)
                    .font(.callout.weight(.medium))
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                    .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                    .padding(.horizontal, KwaborDesignTokens.Spacing.lg)
                    .background(KwaborDesignTokens.ColorToken.ink100)
                    .accessibilityLabel(message)
            }
        }
    }

    private var messages: [String] {
        [store.state.refreshMessage].compactMap { $0 }
    }
}

private struct SearchStateMessage: View {
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
        .frame(maxWidth: .infinity, minHeight: searchStateMinimumHeight)
        .padding(KwaborDesignTokens.Spacing.xxl)
        .background(KwaborDesignTokens.ColorToken.surface0)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
        .accessibilityElement(children: .contain)
    }
}

private let searchSkeletonRows = 2
private let minimumSearchSkeletonCount = 4
private let searchStateMinimumHeight: CGFloat = 180
