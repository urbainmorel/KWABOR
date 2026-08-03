import Shared
import SwiftUI
import UIKit

struct CatalogDetailSheet: View {
    @ObservedObject var store: CatalogDetailStore

    var body: some View {
        GeometryReader { proxy in
            Group {
                if store.state is CatalogDetailUiStateLoading {
                    CatalogDetailLoadingState(store: store)
                } else if let content = store.state as? CatalogDetailUiStateContent {
                    CatalogDetailContentView(
                        state: content,
                        store: store,
                        sheetHeight: proxy.size.height
                    )
                } else if let failure = failureContent(store.state, strings: store.strings.detail) {
                    CatalogDetailFailureState(
                        failure: failure,
                        store: store
                    )
                }
            }
            .frame(
                width: CatalogDetailLayoutPolicy.sheetWidth(
                    availableWidth: proxy.size.width
                )
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .background(KwaborDesignTokens.ColorToken.surface0)
        .preferredColorScheme(.light)
        .presentationDragIndicator(.hidden)
        .presentationCornerRadius(KwaborDesignTokens.Radius.sheet)
        .onAppear(perform: announceLatestState)
        .onChange(of: store.announcementRevision) { _, _ in
            announceLatestState()
        }
    }

    private func announceLatestState() {
        guard let announcement = store.latestAnnouncement else { return }
        UIAccessibility.post(notification: .announcement, argument: announcement)
    }
}

private struct CatalogDetailLoadingState: View {
    @ObservedObject var store: CatalogDetailStore

    var body: some View {
        ScrollView {
            LazyVStack(spacing: KwaborDesignTokens.Spacing.xxl) {
                CatalogDetailCloseRow(
                    label: store.strings.detail.close,
                    action: store.dismiss
                )
                VStack(spacing: KwaborDesignTokens.Spacing.lg) {
                    ProgressView()
                    Text(store.strings.detail.loading)
                        .font(.headline)
                        .multilineTextAlignment(.center)
                }
                .frame(
                    maxWidth: .infinity,
                    minHeight: KwaborDesignTokens.Sizing.detailStateMinimumHeight
                )
                .accessibilityElement(children: .combine)
                .accessibilityLabel(store.strings.detail.loading)
            }
        }
    }
}

private struct CatalogDetailFailureState: View {
    let failure: CatalogDetailFailureContent
    @ObservedObject var store: CatalogDetailStore

    var body: some View {
        ScrollView {
            LazyVStack(spacing: KwaborDesignTokens.Spacing.xxl) {
                CatalogDetailCloseRow(
                    label: store.strings.detail.close,
                    action: store.dismiss
                )
                VStack(spacing: KwaborDesignTokens.Spacing.lg) {
                    Image(systemName: "exclamationmark.circle")
                        .font(.largeTitle)
                        .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                        .accessibilityHidden(true)
                    Text(failure.title)
                        .font(.title3.bold())
                        .multilineTextAlignment(.center)
                        .accessibilityAddTraits(.isHeader)
                    Text(failure.message)
                        .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                        .multilineTextAlignment(.center)
                    Button(store.strings.retry, action: store.retry)
                        .buttonStyle(.borderedProminent)
                        .tint(KwaborDesignTokens.ColorToken.ink950)
                        .frame(minHeight: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget)
                }
                .frame(
                    maxWidth: .infinity,
                    minHeight: KwaborDesignTokens.Sizing.detailStateMinimumHeight
                )
                .padding(.horizontal, KwaborDesignTokens.Spacing.xxl)
            }
        }
    }
}

private struct CatalogDetailContentView: View {
    let state: CatalogDetailUiStateContent
    @ObservedObject var store: CatalogDetailStore
    let sheetHeight: CGFloat

    private var visibleMedia: [VisibleCatalogDetailMedia] {
        visibleCatalogDetailMedia(state.model.media)
    }

    private var selectedSourceIndex: Int {
        let requested = Int(state.selectedMediaIndex)
        if visibleMedia.contains(where: { $0.sourceIndex == requested }) {
            return requested
        }
        return visibleMedia.first?.sourceIndex ?? 0
    }

    private var selectedMedia: CatalogDetailMediaUiModel? {
        visibleMedia.first(where: { $0.sourceIndex == selectedSourceIndex })?.media
    }

    private var showsSummaryPrice: Bool {
        state.model.content is CatalogDetailContentUiModelPlace ||
            state.model.content is CatalogDetailContentUiModelLodging ||
            state.model.content is CatalogDetailContentUiModelFood ||
            state.model.content is CatalogDetailContentUiModelNightlife
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xxl) {
                CatalogDetailHero(
                    model: state.model,
                    selectedMedia: selectedMedia,
                    strings: store.strings.detail,
                    height: CatalogDetailLayoutPolicy.heroHeight(forSheetHeight: sheetHeight),
                    onDismiss: store.dismiss
                )
                CatalogDetailGallery(
                    media: visibleMedia,
                    selectedSourceIndex: selectedSourceIndex,
                    selectImageLabel: store.strings.detail.selectImage,
                    onSelect: store.selectMedia
                )
                CatalogDetailMetricsSection(
                    metrics: state.model.metrics,
                    strings: store.strings.detail
                )
                .detailHorizontalPadding()
                CatalogDetailDescriptionSection(
                    description: state.model.description,
                    expanded: state.isDescriptionExpanded,
                    strings: store.strings.detail,
                    onToggle: store.toggleDescription
                )
                .detailHorizontalPadding()
                if showsSummaryPrice {
                    CatalogDetailPriceSection(
                        price: state.model.price,
                        strings: store.strings.detail,
                        freeLabel: store.strings.free
                    )
                    .detailHorizontalPadding()
                }
                CatalogDetailTypedContent(
                    content: state.model.content,
                    strings: store.strings.detail,
                    commonStrings: store.strings
                )
                .detailHorizontalPadding()
                if !(state.model.content is CatalogDetailContentUiModelEvent) {
                    CatalogDetailOpeningHoursSection(
                        statusLabel: state.model.openingStatusLabel,
                        hours: state.model.openingHours,
                        strings: store.strings.detail
                    )
                    .detailHorizontalPadding()
                }
                CatalogDetailLabelsSection(
                    title: store.strings.detail.amenities,
                    labels: state.model.amenities
                )
                .detailHorizontalPadding()
                CatalogDetailLocationSection(
                    location: state.model.location,
                    strings: store.strings.detail
                )
                .detailHorizontalPadding()
                CatalogDetailLabelsSection(
                    title: store.strings.detail.tags,
                    labels: state.model.tags
                )
                .detailHorizontalPadding()
                Spacer(minLength: KwaborDesignTokens.Spacing.xxxl)
            }
        }
        .background(KwaborDesignTokens.ColorToken.surface0)
    }
}

private struct CatalogDetailCloseRow: View {
    let label: String
    let action: () -> Void

    var body: some View {
        HStack {
            CatalogDetailCloseButton(label: label, action: action)
            Spacer()
        }
        .padding(.horizontal, KwaborDesignTokens.Spacing.lg)
        .padding(.top, KwaborDesignTokens.Spacing.sm)
        .padding(.bottom, KwaborDesignTokens.Spacing.sm)
        .background(KwaborDesignTokens.ColorToken.surface0)
        .overlay(alignment: .bottom) {
            Divider()
        }
    }
}

private struct CatalogDetailFailureContent {
    let title: String
    let message: String
}

private func failureContent(
    _ state: CatalogDetailUiState,
    strings: CatalogDetailStrings
) -> CatalogDetailFailureContent? {
    if let notFound = state as? CatalogDetailUiStateNotFound {
        return CatalogDetailFailureContent(title: strings.notFoundTitle, message: notFound.message)
    }
    if let offline = state as? CatalogDetailUiStateOfflineFailure {
        return CatalogDetailFailureContent(title: strings.offlineTitle, message: offline.message)
    }
    if let failure = state as? CatalogDetailUiStateFailure {
        return CatalogDetailFailureContent(title: strings.errorTitle, message: failure.message)
    }
    return nil
}

private extension View {
    func detailHorizontalPadding() -> some View {
        padding(.horizontal, KwaborDesignTokens.Spacing.xxl)
    }
}
