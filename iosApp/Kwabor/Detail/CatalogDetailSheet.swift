import Shared
import SwiftUI
import UIKit

struct CatalogDetailSheet: View {
    @ObservedObject var store: CatalogDetailStore
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @State private var presentedContact: CatalogDetailContactUiModel?
    @State private var externalActionFailed = false
    @State private var contactExternalActionFailed = false
    @State private var pendingContentPresentation: CatalogDetailPresentationCandidate?
    @State private var deliveredContentPresentation: CatalogDetailPresentationCandidate?
    var onContentPresented: @MainActor (CatalogDetailOpenRequestId, String) -> Void = { _, _ in }

    var body: some View {
        GeometryReader { proxy in
            Group {
                if store.state is CatalogDetailUiStateLoading {
                    CatalogDetailLoadingState(store: store)
                } else if let content = store.state as? CatalogDetailUiStateContent {
                    CatalogDetailContentView(
                        state: content,
                        store: store,
                        sheetHeight: proxy.size.height,
                        onOpenExternal: openExternal,
                        onContactRequested: { contact in
                            presentedContact = contact
                        },
                        onPresented: contentPresented,
                        onDismissed: contentDismissed
                    )
                    .id("\(content.openRequestId.value):\(content.model.id)")
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
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                deliverPendingContentPresentationIfPossible()
            }
        }
        .sheet(
            isPresented: Binding(
                get: { presentedContact != nil },
                set: { isPresented in
                    if !isPresented {
                        presentedContact = nil
                    }
                }
            )
        ) {
            if let contact = presentedContact {
                CatalogDetailContactSheet(
                    contact: contact,
                    strings: store.strings.detail,
                    onOpenExternal: openContactExternal,
                    onDismiss: {
                        contactExternalActionFailed = false
                        presentedContact = nil
                    }
                )
                .alert(
                    store.strings.detail.externalActionFailed,
                    isPresented: $contactExternalActionFailed
                ) {
                    Button(store.strings.detail.dismiss, role: .cancel) {}
                }
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
            }
        }
        .alert(
            store.strings.detail.externalActionFailed,
            isPresented: $externalActionFailed
        ) {
            Button(store.strings.detail.dismiss, role: .cancel) {}
        }
    }

    private func announceLatestState() {
        guard let announcement = store.latestAnnouncement else { return }
        UIAccessibility.post(notification: .announcement, argument: announcement)
    }

    @MainActor
    private func contentPresented(_ state: CatalogDetailUiStateContent) {
        pendingContentPresentation = CatalogDetailPresentationCandidate(
            openRequestId: state.openRequestId,
            listingID: state.model.id
        )
        deliverPendingContentPresentationIfPossible()
    }

    @MainActor
    private func contentDismissed(_ state: CatalogDetailUiStateContent) {
        let candidate = CatalogDetailPresentationCandidate(
            openRequestId: state.openRequestId,
            listingID: state.model.id
        )
        if pendingContentPresentation == candidate {
            pendingContentPresentation = nil
        }
    }

    @MainActor
    private func deliverPendingContentPresentationIfPossible() {
        guard scenePhase == .active,
              let candidate = pendingContentPresentation,
              candidate != deliveredContentPresentation else {
            return
        }
        deliveredContentPresentation = candidate
        onContentPresented(candidate.openRequestId, candidate.listingID)
    }

    @MainActor
    private func openExternal(_ target: CatalogDetailExternalURLTarget) {
        launchExternal(target) {
            externalActionFailed = true
        }
    }

    @MainActor
    private func openContactExternal(_ target: CatalogDetailExternalURLTarget) {
        launchExternal(target) {
            contactExternalActionFailed = true
        }
    }

    @MainActor
    private func launchExternal(
        _ target: CatalogDetailExternalURLTarget,
        onFailure: @escaping @MainActor () -> Void
    ) {
        guard let url = CatalogDetailExternalURLPolicy.url(for: target) else {
            onFailure()
            return
        }
        openURL(url) { accepted in
            Task { @MainActor in
                if accepted {
                    UIAccessibility.post(
                        notification: .announcement,
                        argument: store.strings.detail.opensExternally
                    )
                } else {
                    onFailure()
                }
            }
        }
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
    let onOpenExternal: @MainActor (CatalogDetailExternalURLTarget) -> Void
    let onContactRequested: @MainActor (CatalogDetailContactUiModel) -> Void
    let onPresented: @MainActor (CatalogDetailUiStateContent) -> Void
    let onDismissed: @MainActor (CatalogDetailUiStateContent) -> Void

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
                .overlay(alignment: .topTrailing) {
                    if let event = state.model.content as? CatalogDetailContentUiModelEvent,
                       event.isEnded {
                        CatalogDetailEndedHeroBadge(label: store.strings.detail.eventEnded)
                            .padding(.top, KwaborDesignTokens.Spacing.lg)
                            .padding(.trailing, KwaborDesignTokens.Spacing.lg)
                    }
                }
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
                    commonStrings: store.strings,
                    onOpenExternal: onOpenExternal
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
                if let directions = secondaryDirections {
                    CatalogDetailExternalButton(
                        title: store.strings.detail.directions,
                        systemImage: "arrow.triangle.turn.up.right.diamond",
                        tint: KwaborDesignTokens.ColorToken.ink950,
                        accessibilityHint: store.strings.detail.opensExternally,
                        action: {
                            onOpenExternal(.directions(
                                latitude: directions.latitude,
                                longitude: directions.longitude,
                                label: directions.label
                            ))
                        }
                    )
                    .detailHorizontalPadding()
                }
                CatalogDetailLabelsSection(
                    title: store.strings.detail.tags,
                    labels: state.model.tags
                )
                .detailHorizontalPadding()
                Spacer(minLength: KwaborDesignTokens.Spacing.xxxl)
            }
        }
        .background(KwaborDesignTokens.ColorToken.surface0)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let primaryAction {
                CatalogDetailPrimaryActionBar(
                    action: primaryAction,
                    strings: store.strings.detail,
                    onOpenExternal: onOpenExternal,
                    onContactRequested: onContactRequested
                )
            }
        }
        .onAppear { onPresented(state) }
        .onDisappear { onDismissed(state) }
    }

    private var primaryAction: CatalogDetailPrimaryAction? {
        let content = state.model.content
        if content is CatalogDetailContentUiModelPlace,
           let directions = state.model.directions,
           CatalogDetailExternalURLPolicy.url(for: directions.target) != nil {
            return .directions(directions)
        }
        if content is CatalogDetailContentUiModelLodging ||
            content is CatalogDetailContentUiModelFood ||
            content is CatalogDetailContentUiModelNightlife ||
            content is CatalogDetailContentUiModelGuide,
           let contact = state.model.contact,
           !CatalogDetailContactOption.options(contact: contact, strings: store.strings.detail).isEmpty {
            return .contact(contact)
        }
        if let event = content as? CatalogDetailContentUiModelEvent,
           let url = event.ticketing.externalURL,
           CatalogDetailExternalURLPolicy.url(for: .https(url)) != nil {
            return .ticket(url: url, enabled: !event.isEnded)
        }
        return nil
    }

    private var secondaryDirections: CatalogDetailDirectionsUiModel? {
        guard !(state.model.content is CatalogDetailContentUiModelPlace),
              let directions = state.model.directions,
              CatalogDetailExternalURLPolicy.url(for: directions.target) != nil else {
            return nil
        }
        return directions
    }
}

private struct CatalogDetailEndedHeroBadge: View {
    let label: String

    var body: some View {
        Text(label)
            .font(.caption.weight(.bold))
            .foregroundStyle(KwaborDesignTokens.ColorToken.surface0)
            .multilineTextAlignment(.center)
            .lineLimit(2)
            .padding(.horizontal, KwaborDesignTokens.Spacing.md)
            .padding(.vertical, KwaborDesignTokens.Spacing.sm)
            .background(KwaborDesignTokens.ColorToken.ink700)
            .clipShape(Capsule())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(label)
            .accessibilitySortPriority(4.5)
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

private struct CatalogDetailPresentationCandidate: Equatable {
    let openRequestId: CatalogDetailOpenRequestId
    let listingID: String

    static func == (
        lhs: CatalogDetailPresentationCandidate,
        rhs: CatalogDetailPresentationCandidate
    ) -> Bool {
        lhs.openRequestId.value == rhs.openRequestId.value && lhs.listingID == rhs.listingID
    }
}

private enum CatalogDetailPrimaryAction {
    case directions(CatalogDetailDirectionsUiModel)
    case contact(CatalogDetailContactUiModel)
    case ticket(url: String, enabled: Bool)
}

private struct CatalogDetailPrimaryActionBar: View {
    let action: CatalogDetailPrimaryAction
    let strings: CatalogDetailStrings
    let onOpenExternal: @MainActor (CatalogDetailExternalURLTarget) -> Void
    let onContactRequested: @MainActor (CatalogDetailContactUiModel) -> Void

    var body: some View {
        VStack(spacing: 0) {
            Divider()
            switch action {
            case let .directions(directions):
                CatalogDetailExternalButton(
                    title: strings.directions,
                    systemImage: "arrow.triangle.turn.up.right.diamond",
                    tint: KwaborDesignTokens.ColorToken.ink950,
                    accessibilityHint: strings.opensExternally,
                    action: { onOpenExternal(directions.target) }
                )
            case let .contact(contact):
                CatalogDetailExternalButton(
                    title: strings.contact,
                    systemImage: "phone",
                    tint: KwaborDesignTokens.ColorToken.ink950,
                    accessibilityHint: nil,
                    action: { onContactRequested(contact) }
                )
            case let .ticket(url, enabled):
                if enabled {
                    CatalogDetailExternalButton(
                        title: strings.ticket,
                        systemImage: "ticket",
                        tint: KwaborDesignTokens.ColorToken.ticket,
                        accessibilityHint: strings.opensExternally,
                        action: { onOpenExternal(.https(url)) }
                    )
                } else {
                    CatalogDetailDisabledTicketButton(
                        title: strings.ticket,
                        reason: strings.eventEnded
                    )
                }
            }
        }
        .padding(.horizontal, KwaborDesignTokens.Spacing.xxl)
        .padding(.vertical, KwaborDesignTokens.Spacing.sm)
        .background(KwaborDesignTokens.ColorToken.surface0)
    }
}

private struct CatalogDetailDisabledTicketButton: View {
    let title: String
    let reason: String

    var body: some View {
        Button(action: {}) {
            Label(title, systemImage: "ticket")
                .font(.body.weight(.semibold))
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                .frame(maxWidth: .infinity)
                .frame(minHeight: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget)
                .background(KwaborDesignTokens.ColorToken.ink100)
                .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control))
        }
        .buttonStyle(.plain)
        .disabled(true)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(reason)
    }
}

struct CatalogDetailExternalButton: View {
    let title: String
    let systemImage: String
    let tint: Color
    let accessibilityHint: String?
    let action: @MainActor () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity)
                .frame(minHeight: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget)
        }
        .buttonStyle(.borderedProminent)
        .tint(tint)
        .accessibilityHint(accessibilityHint ?? "")
    }
}

private struct CatalogDetailContactSheet: View {
    let contact: CatalogDetailContactUiModel
    let strings: CatalogDetailStrings
    let onOpenExternal: @MainActor (CatalogDetailExternalURLTarget) -> Void
    let onDismiss: @MainActor () -> Void

    private var options: [CatalogDetailContactOption] {
        CatalogDetailContactOption.options(contact: contact, strings: strings)
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.lg) {
                HStack {
                    Text(strings.contact)
                        .font(.title3.weight(.semibold))
                        .accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button(strings.dismiss, action: onDismiss)
                        .frame(minHeight: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget)
                }
                ForEach(options) { option in
                    CatalogDetailExternalButton(
                        title: option.title,
                        systemImage: option.systemImage,
                        tint: KwaborDesignTokens.ColorToken.ink950,
                        accessibilityHint: strings.opensExternally,
                        action: { onOpenExternal(option.target) }
                    )
                }
                Spacer(minLength: KwaborDesignTokens.Spacing.sm)
            }
            .padding(KwaborDesignTokens.Spacing.xxl)
        }
        .background(KwaborDesignTokens.ColorToken.surface0)
    }
}

private struct CatalogDetailContactOption: Identifiable {
    enum Identifier: String {
        case phone
        case whatsapp
        case website
        case email
    }

    let id: Identifier
    let title: String
    let systemImage: String
    let target: CatalogDetailExternalURLTarget

    static func options(
        contact: CatalogDetailContactUiModel,
        strings: CatalogDetailStrings
    ) -> [CatalogDetailContactOption] {
        var options: [CatalogDetailContactOption] = []
        append(
            id: .phone,
            title: strings.call,
            systemImage: "phone",
            target: contact.phoneNumber.map(CatalogDetailExternalURLTarget.phone),
            to: &options
        )
        append(
            id: .whatsapp,
            title: strings.whatsapp,
            systemImage: "message",
            target: contact.whatsappNumber.map(CatalogDetailExternalURLTarget.whatsapp),
            to: &options
        )
        append(
            id: .website,
            title: strings.website,
            systemImage: "safari",
            target: contact.websiteUrl.map(CatalogDetailExternalURLTarget.https),
            to: &options
        )
        append(
            id: .email,
            title: strings.email,
            systemImage: "envelope",
            target: contact.emailAddress.map(CatalogDetailExternalURLTarget.email),
            to: &options
        )
        return options
    }

    private static func append(
        id: Identifier,
        title: String,
        systemImage: String,
        target: CatalogDetailExternalURLTarget?,
        to options: inout [CatalogDetailContactOption]
    ) {
        guard let target,
              CatalogDetailExternalURLPolicy.url(for: target) != nil else {
            return
        }
        options.append(
            CatalogDetailContactOption(
                id: id,
                title: title,
                systemImage: systemImage,
                target: target
            )
        )
    }
}

private extension CatalogDetailDirectionsUiModel {
    var target: CatalogDetailExternalURLTarget {
        .directions(latitude: latitude, longitude: longitude, label: label)
    }
}

private extension CatalogDetailTicketingUiModel {
    var externalURL: String? {
        if let free = self as? CatalogDetailTicketingUiModelFree {
            return free.externalUrl
        }
        if let paid = self as? CatalogDetailTicketingUiModelPaid {
            return paid.externalUrl
        }
        return nil
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
