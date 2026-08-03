import Shared
import SwiftUI
import UIKit

struct GuideDiscoveryEntryLink: View {
    @ObservedObject var store: GuideDiscoveryStore

    var body: some View {
        NavigationLink {
            GuideDiscoveryView(store: store)
        } label: {
            HStack(spacing: KwaborDesignTokens.Spacing.lg) {
                Image(systemName: "person.2.fill")
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
                        .font(.headline.weight(.bold))
                        .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                    Text(store.strings.entrySubtitle)
                        .font(.subheadline)
                        .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: KwaborDesignTokens.Spacing.sm)
                Image(systemName: "chevron.right")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                    .accessibilityHidden(true)
            }
            .padding(KwaborDesignTokens.Spacing.lg)
            .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget)
            .background(KwaborDesignTokens.ColorToken.surface0)
            .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
            .overlay {
                RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card)
                    .stroke(
                        KwaborDesignTokens.ColorToken.ink200,
                        lineWidth: KwaborDesignTokens.Sizing.outline
                    )
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel([store.strings.title, store.strings.entrySubtitle].joined(separator: ". "))
    }
}

struct GuideDiscoveryView: View {
    @ObservedObject var store: GuideDiscoveryStore
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        VStack(spacing: 0) {
            if store.state.isOffline {
                GuideDiscoveryBanner(
                    text: store.commonStrings.offlineBanner,
                    foreground: KwaborDesignTokens.ColorToken.surface0,
                    background: KwaborDesignTokens.ColorToken.ink900
                )
            }
            GeometryReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.lg) {
                        GuideDiscoveryTransientState(store: store)
                        GuideDiscoveryFilters(store: store)
                        if !store.state.resultCountLabel.isEmpty {
                            Text(store.state.resultCountLabel)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                                .accessibilityAddTraits(.updatesFrequently)
                        }
                        GuideDiscoveryContent(
                            store: store,
                            columns: gridColumns(availableWidth: proxy.size.width)
                        )
                        GuideDiscoveryAppendFooter(store: store)
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
        .onAppear(perform: store.start)
        .onChange(of: store.announcementRevision) { _, _ in
            guard let announcement = store.latestAnnouncement else { return }
            UIAccessibility.post(notification: .announcement, argument: announcement)
        }
    }

    private func gridColumns(availableWidth: CGFloat) -> [GridItem] {
        let count = dynamicTypeSize >= .accessibility1 ||
            availableWidth < KwaborDesignTokens.Sizing.guideTabletBreakpoint ? 1 : 2
        return Array(
            repeating: GridItem(
                .flexible(minimum: 0, maximum: .infinity),
                spacing: KwaborDesignTokens.Spacing.lg,
                alignment: .top
            ),
            count: count
        )
    }
}

private struct GuideDiscoveryFilters: View {
    @ObservedObject var store: GuideDiscoveryStore

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.md) {
            HStack(alignment: .firstTextBaseline, spacing: KwaborDesignTokens.Spacing.sm) {
                Text(store.strings.filtersTitle)
                    .font(.title3.weight(.bold))
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: KwaborDesignTokens.Spacing.sm)
                if store.state.hasActiveFilters {
                    Button(store.strings.resetFilters, action: store.clearFilters)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                        .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
                }
            }
            GuideDiscoveryFilterMenu(
                title: store.strings.cityFilter,
                allLabel: store.strings.allCities,
                selectedID: store.state.filters.cityId,
                options: store.state.cityOptions,
                onSelect: store.selectCity
            )
            GuideDiscoveryFilterMenu(
                title: store.strings.languageFilter,
                allLabel: store.strings.allLanguages,
                selectedID: store.state.filters.languageId,
                options: store.state.languageOptions,
                onSelect: store.selectLanguage
            )
            GuideDiscoveryFilterMenu(
                title: store.strings.specialtyFilter,
                allLabel: store.strings.allSpecialties,
                selectedID: store.state.filters.specialtyId,
                options: store.state.specialtyOptions,
                onSelect: store.selectSpecialty
            )
        }
        .padding(KwaborDesignTokens.Spacing.lg)
        .background(KwaborDesignTokens.ColorToken.surface0)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
    }
}

private struct GuideDiscoveryFilterMenu: View {
    let title: String
    let allLabel: String
    let selectedID: String?
    let options: [GuideFilterOptionUiModel]
    let onSelect: (String?) -> Void

    var body: some View {
        Menu {
            Button {
                onSelect(nil)
            } label: {
                GuideDiscoveryMenuOption(label: allLabel, selected: selectedID == nil)
            }
            ForEach(options, id: \.id) { option in
                Button {
                    onSelect(option.id)
                } label: {
                    GuideDiscoveryMenuOption(label: option.label, selected: selectedID == option.id)
                }
            }
        } label: {
            HStack(spacing: KwaborDesignTokens.Spacing.sm) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                Spacer(minLength: KwaborDesignTokens.Spacing.sm)
                Text(selectedLabel)
                    .font(.subheadline)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                    .multilineTextAlignment(.trailing)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, KwaborDesignTokens.Spacing.md)
            .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget)
            .background(KwaborDesignTokens.ColorToken.ink100)
            .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityValue(selectedLabel)
    }

    private var selectedLabel: String {
        guard let selectedID,
              let option = options.first(where: { $0.id == selectedID }) else {
            return allLabel
        }
        return option.label
    }
}

private struct GuideDiscoveryMenuOption: View {
    let label: String
    let selected: Bool

    var body: some View {
        if selected {
            Label(label, systemImage: "checkmark")
        } else {
            Text(label)
        }
    }
}

private struct GuideDiscoveryContent: View {
    @ObservedObject var store: GuideDiscoveryStore
    let columns: [GridItem]

    var body: some View {
        if !store.isConfigured {
            GuideDiscoveryStateMessage(
                title: store.commonStrings.errorStateTitle,
                supportingText: store.commonStrings.configurationUnavailable,
                actionLabel: nil,
                action: nil
            )
        } else if store.state.isLoading && store.state.guides.isEmpty {
            LazyVGrid(columns: columns, spacing: KwaborDesignTokens.Spacing.lg) {
                ForEach(0..<max(columns.count * guideSkeletonRows, guideMinimumSkeletonCount), id: \.self) { _ in
                    GuideDiscoverySkeletonCard()
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(store.commonStrings.loading)
        } else if let errorMessage = store.state.errorMessage {
            GuideDiscoveryStateMessage(
                title: store.commonStrings.errorStateTitle,
                supportingText: errorMessage,
                actionLabel: store.commonStrings.retry,
                action: store.retry
            )
        } else if store.state.isEmpty {
            GuideDiscoveryStateMessage(
                title: store.strings.emptyTitle,
                supportingText: store.strings.emptyMessage,
                actionLabel: store.state.hasActiveFilters
                    ? store.strings.resetFilters
                    : store.commonStrings.retry,
                action: store.state.hasActiveFilters ? store.clearFilters : store.retry
            )
        } else {
            LazyVGrid(columns: columns, spacing: KwaborDesignTokens.Spacing.lg) {
                ForEach(store.state.guides, id: \.id) { guide in
                    GuideDiscoveryCard(
                        guide: guide,
                        strings: store.strings,
                        commonStrings: store.commonStrings,
                        onOpen: { store.openGuide(guide.id) }
                    )
                    .onAppear {
                        store.guideDidAppear(guide.id)
                    }
                }
            }
        }
    }
}

private struct GuideDiscoveryTransientState: View {
    @ObservedObject var store: GuideDiscoveryStore

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
            GuideDiscoveryBanner(
                text: refreshMessage,
                foreground: KwaborDesignTokens.ColorToken.ink950,
                background: KwaborDesignTokens.ColorToken.ink100
            )
        }
    }
}

private struct GuideDiscoveryAppendFooter: View {
    @ObservedObject var store: GuideDiscoveryStore

    var body: some View {
        if store.state.isAppending {
            ProgressView(store.commonStrings.loading)
                .frame(maxWidth: .infinity)
                .padding(KwaborDesignTokens.Spacing.lg)
        } else if let appendError = store.state.appendErrorMessage {
            GuideDiscoveryStateMessage(
                title: store.commonStrings.errorStateTitle,
                supportingText: appendError,
                actionLabel: store.commonStrings.retry,
                action: store.retryAppend
            )
        }
    }
}

private struct GuideDiscoveryStateMessage: View {
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
                    .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
            }
        }
        .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.guideStateMinimumHeight)
        .padding(KwaborDesignTokens.Spacing.xxl)
        .background(KwaborDesignTokens.ColorToken.surface0)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
    }
}

private struct GuideDiscoveryBanner: View {
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

private let guideSkeletonRows = 2
private let guideMinimumSkeletonCount = 2
