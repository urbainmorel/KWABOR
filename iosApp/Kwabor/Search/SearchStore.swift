import Combine
import Shared

@MainActor
final class SearchStore: ObservableObject {
    @Published private(set) var state: SearchUiState
    @Published private(set) var queryText: String
    @Published private(set) var announcementRevision = 0

    let strings: SearchStrings
    let isConfigured: Bool
    let canOpenAssistant: Bool

    private let controller: IosSearchController
    private let offlineMessage: String
    private let loadingMessage: String
    private let onOpenListing: (String) -> Void
    private let onQuerySubmitted: (AnalyticsEvent) -> Void
    private let onOpenAssistant: (() -> Void)?
    private var paginationGuard = SearchPaginationGuard()
    private var lastAnnouncement: String?

    private(set) var latestAnnouncement: String?

    init(
        controller: IosSearchController,
        offlineMessage: String,
        loadingMessage: String,
        onOpenListing: @escaping (String) -> Void,
        onQuerySubmitted: @escaping (AnalyticsEvent) -> Void,
        onOpenAssistant: (() -> Void)? = nil
    ) {
        self.controller = controller
        self.offlineMessage = offlineMessage
        self.loadingMessage = loadingMessage
        self.onOpenListing = onOpenListing
        self.onQuerySubmitted = onQuerySubmitted
        self.onOpenAssistant = onOpenAssistant
        state = controller.currentState
        queryText = controller.currentState.queryText
        strings = controller.strings
        isConfigured = controller.isConfigured
        canOpenAssistant = onOpenAssistant != nil
        observeController()
    }

    deinit {
        controller.unobserve()
    }

    func activate(exploreState: ExploreUiState) {
        paginationGuard.reset()
        controller.actions.activate(exploreState: exploreState)
    }

    func updateExploreContext(_ exploreState: ExploreUiState) {
        controller.actions.updateExploreContext(exploreState: exploreState)
    }

    func queryChanged(_ text: String) {
        guard text != queryText else { return }
        queryText = text
        paginationGuard.reset()
        controller.actions.queryChanged(text: text)
    }

    func selectActiveTabScope() {
        paginationGuard.reset()
        controller.actions.selectActiveTabScope()
    }

    func selectAllScope() {
        paginationGuard.reset()
        controller.actions.selectAllScope()
    }

    func submit() {
        paginationGuard.reset()
        controller.actions.submit()
    }

    func clear() {
        queryText = ""
        paginationGuard.reset()
        controller.actions.clear()
    }

    func close() {
        queryText = ""
        paginationGuard.reset()
        controller.actions.close()
    }

    func retry() {
        paginationGuard.reset()
        controller.actions.retry()
    }

    func refresh() {
        guard state.hasSubmittedQuery,
              !state.isLoading,
              !state.isRefreshing,
              !state.isAppending else {
            return
        }
        paginationGuard.reset()
        controller.actions.refresh()
    }

    func listingDidAppear(_ listingID: String) {
        guard let index = state.listings.firstIndex(where: { $0.id == listingID }) else { return }
        if paginationGuard.shouldLoadNext(
            cursor: state.nextCursor,
            canLoadMore: state.canLoadMore,
            isNearEnd: SearchPaginationPolicy.isNearEnd(
                index: index,
                itemCount: state.listings.count
            ),
            hasAppendError: state.appendErrorMessage != nil
        ) {
            controller.actions.loadNext()
        }
    }

    func retryAppend() {
        if paginationGuard.shouldRetry(cursor: state.nextCursor, canLoadMore: state.canLoadMore) {
            controller.actions.loadNext()
        }
    }

    func openListing(_ listingID: String) {
        controller.actions.openListing(listingId: listingID)
    }

    func openAssistant() {
        guard canOpenAssistant else { return }
        controller.actions.openAssistant()
    }

    private func observeController() {
        controller.observe(
            stateObserver: { [weak self] updatedState in
                MainActor.assumeIsolated {
                    self?.accept(updatedState)
                }
            },
            effectObserver: { [weak self] effect in
                MainActor.assumeIsolated {
                    self?.accept(effect)
                }
            }
        )
    }

    private func accept(_ updatedState: SearchUiState) {
        let previousState = state
        if previousState.queryText != updatedState.queryText ||
            previousState.submittedQueryText != updatedState.submittedQueryText ||
            previousState.isActiveTabScope != updatedState.isActiveTabScope ||
            (!previousState.isLoading && updatedState.isLoading) {
            paginationGuard.reset()
        }
        if previousState.submittedQueryText != updatedState.submittedQueryText {
            lastAnnouncement = nil
        }
        state = updatedState
        if queryText != updatedState.queryText {
            queryText = updatedState.queryText
        }
        publishAnnouncementIfNeeded(previousState: previousState, updatedState: updatedState)
    }

    private func accept(_ effect: IosSearchEffect) {
        if effect.submitsQuery, let event = effect.querySubmittedEvent {
            onQuerySubmitted(event)
        } else if effect.opensCatalogDetail, let listingID = effect.listingId {
            onOpenListing(listingID)
        } else if effect.opensAssistant {
            onOpenAssistant?()
        }
        // HISTORY-001 will persist QuerySubmitted; SEARCH-001A only emits privacy-safe telemetry.
    }

    private func publishAnnouncementIfNeeded(
        previousState: SearchUiState,
        updatedState: SearchUiState
    ) {
        let announcement = asynchronousAnnouncement(
            previousState: previousState,
            updatedState: updatedState
        )
        guard announcement != lastAnnouncement else { return }
        lastAnnouncement = announcement
        guard let announcement else { return }
        latestAnnouncement = announcement
        announcementRevision += 1
    }

    private func asynchronousAnnouncement(
        previousState: SearchUiState,
        updatedState: SearchUiState
    ) -> String? {
        if updatedState.queryErrorMessage != previousState.queryErrorMessage,
           let queryErrorMessage = updatedState.queryErrorMessage {
            return queryErrorMessage
        }
        if updatedState.errorMessage != previousState.errorMessage,
           let errorMessage = updatedState.errorMessage {
            return errorMessage
        }
        if updatedState.appendErrorMessage != previousState.appendErrorMessage,
           let appendErrorMessage = updatedState.appendErrorMessage {
            return appendErrorMessage
        }
        if updatedState.refreshMessage != previousState.refreshMessage,
           let refreshMessage = updatedState.refreshMessage {
            return refreshMessage
        }
        if !previousState.isOffline, updatedState.isOffline {
            return offlineMessage
        }
        if (!previousState.isLoading && updatedState.isLoading) ||
            (!previousState.isAppending && updatedState.isAppending) {
            return loadingMessage
        }
        if updatedState.hasSubmittedQuery,
           !updatedState.isLoading,
           !updatedState.resultCountLabel.isEmpty,
           (updatedState.resultCountLabel != previousState.resultCountLabel ||
               updatedState.submittedQueryText != previousState.submittedQueryText) {
            return updatedState.resultCountLabel
        }
        return nil
    }
}
