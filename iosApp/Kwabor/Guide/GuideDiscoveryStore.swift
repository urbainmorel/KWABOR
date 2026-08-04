import Combine
import Shared

@MainActor
final class GuideDiscoveryStore: ObservableObject {
    @Published private(set) var state: GuideDiscoveryUiState
    @Published private(set) var announcementRevision = 0

    let strings: GuideDiscoveryStrings
    let commonStrings: KwaborStrings
    let isConfigured: Bool

    private let controller: IosGuideDiscoveryController
    private let onGuideOpen: (String) -> Void
    private var paginationGuard = ExplorePaginationGuard()
    private var hasStarted = false
    private var lastAnnouncement: String?

    private(set) var latestAnnouncement: String?

    init(
        controller: IosGuideDiscoveryController,
        commonStrings: KwaborStrings,
        onGuideOpen: @escaping (String) -> Void
    ) {
        self.controller = controller
        self.commonStrings = commonStrings
        self.onGuideOpen = onGuideOpen
        state = controller.currentState
        strings = controller.strings
        isConfigured = controller.isConfigured
        observeController()
    }

    deinit {
        controller.unobserve()
    }

    func start() {
        guard !hasStarted else { return }
        hasStarted = true
        controller.actions.start()
    }

    func retry() {
        paginationGuard.reset()
        controller.actions.retry()
    }

    func refresh() {
        guard !state.isLoading, !state.isRefreshing else { return }
        paginationGuard.reset()
        controller.actions.refresh()
    }

    func selectCity(_ cityID: String?) {
        guard cityID != state.filters.cityId else { return }
        paginationGuard.reset()
        controller.actions.selectCity(cityId: cityID)
    }

    func selectLanguage(_ languageID: String?) {
        guard languageID != state.filters.languageId else { return }
        paginationGuard.reset()
        controller.actions.selectLanguage(languageId: languageID)
    }

    func selectSpecialty(_ specialtyID: String?) {
        guard specialtyID != state.filters.specialtyId else { return }
        paginationGuard.reset()
        controller.actions.selectSpecialty(specialtyId: specialtyID)
    }

    func clearFilters() {
        guard state.hasActiveFilters else { return }
        paginationGuard.reset()
        controller.actions.clearFilters()
    }

    func guideDidAppear(_ guideID: String) {
        guard let index = state.guides.firstIndex(where: { $0.id == guideID }) else { return }
        let thresholdIndex = max(state.guides.count - guidePaginationThresholdItemCount, 0)
        if paginationGuard.shouldLoadNext(
            cursor: state.nextCursor,
            canLoadMore: state.canLoadMore,
            isNearEnd: index >= thresholdIndex,
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

    func openGuide(_ guideID: String) {
        controller.actions.openGuide(guideId: guideID)
    }

    private func observeController() {
        controller.observe(
            stateObserver: { [weak self] updatedState in
                MainActor.assumeIsolated {
                    self?.accept(updatedState)
                }
            },
            detailObserver: { [weak self] listingID in
                MainActor.assumeIsolated {
                    self?.onGuideOpen(listingID)
                }
            }
        )
    }

    private func accept(_ updatedState: GuideDiscoveryUiState) {
        let previousState = state
        if didGuideFiltersChange(previousState, updatedState) ||
            (!previousState.isRefreshing && updatedState.isRefreshing) {
            paginationGuard.reset()
        }
        state = updatedState
        publishAnnouncementIfNeeded(previousState: previousState, updatedState: updatedState)
    }

    private func publishAnnouncementIfNeeded(
        previousState: GuideDiscoveryUiState,
        updatedState: GuideDiscoveryUiState
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
        previousState: GuideDiscoveryUiState,
        updatedState: GuideDiscoveryUiState
    ) -> String? {
        if !previousState.isOffline, updatedState.isOffline {
            return commonStrings.offlineBanner
        }
        if updatedState.errorMessage != previousState.errorMessage,
           let errorMessage = updatedState.errorMessage {
            return [commonStrings.errorStateTitle, errorMessage].joined(separator: ". ")
        }
        if updatedState.refreshMessage != previousState.refreshMessage,
           let refreshMessage = updatedState.refreshMessage {
            return refreshMessage
        }
        if updatedState.appendErrorMessage != previousState.appendErrorMessage,
           let appendErrorMessage = updatedState.appendErrorMessage {
            return appendErrorMessage
        }
        if (!previousState.isLoading && updatedState.isLoading) ||
            (!previousState.isAppending && updatedState.isAppending) {
            return commonStrings.loading
        }
        if !updatedState.isLoading,
           !updatedState.resultCountLabel.isEmpty,
           previousState.resultCountLabel != updatedState.resultCountLabel {
            return updatedState.resultCountLabel
        }
        return nil
    }
}

private func didGuideFiltersChange(
    _ previousState: GuideDiscoveryUiState,
    _ updatedState: GuideDiscoveryUiState
) -> Bool {
    previousState.filters.cityId != updatedState.filters.cityId ||
        previousState.filters.languageId != updatedState.filters.languageId ||
        previousState.filters.specialtyId != updatedState.filters.specialtyId
}

private let guidePaginationThresholdItemCount = 3
