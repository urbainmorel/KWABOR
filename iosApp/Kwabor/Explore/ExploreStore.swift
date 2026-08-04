import Combine
import Foundation
import Shared

enum ExploreProtectedAction: Equatable {
    case like
    case favorite
}

struct ExploreAuthenticationRequest: Identifiable {
    let id: Int
    let listingID: String
    let action: ExploreProtectedAction
    let suggestedCityID: String?
}

@MainActor
final class ExploreStore: ObservableObject {
    @Published private(set) var state: ExploreUiState
    @Published private(set) var authenticationRequest: ExploreAuthenticationRequest?
    @Published private(set) var announcementRevision = 0

    let strings: KwaborStrings
    let isConfigured: Bool

    private let controller: IosExploreController
    private let locationProvider: ApproximateLocationProviding
    private let onProtectedActionReplayed: (AnalyticsEvent) -> Void
    private var paginationGuard = ExplorePaginationGuard()
    private var locationTask: Task<Void, Never>?
    private var lastAnnouncement: String?
    private var lastViewerID: String?
    private var hasAppliedViewerContext = false
    private var authenticationRequestRevision = 0

    private(set) var latestAnnouncement: String?

    init(
        controller: IosExploreController,
        locationProvider: ApproximateLocationProviding? = nil,
        onProtectedActionReplayed: @escaping (AnalyticsEvent) -> Void = { _ in }
    ) {
        self.controller = controller
        self.locationProvider = locationProvider ?? CoreLocationApproximateLocationProvider()
        self.onProtectedActionReplayed = onProtectedActionReplayed
        state = controller.currentState
        strings = controller.strings
        isConfigured = controller.isConfigured
        observeController()
    }

    deinit {
        locationTask?.cancel()
        controller.unobserve()
    }

    func updateViewerContext(_ viewerID: String?) {
        guard !hasAppliedViewerContext || viewerID != lastViewerID else { return }
        hasAppliedViewerContext = true
        lastViewerID = viewerID
        paginationGuard.reset()
        controller.interactionActions.updateViewerContext(viewerId: viewerID)
    }

    func selectPlacesTab() {
        paginationGuard.reset()
        controller.feedActions.selectPlacesTab()
    }

    func selectEventsTab() {
        paginationGuard.reset()
        controller.feedActions.selectEventsTab()
    }

    func selectHotelsRestaurantsTab() {
        paginationGuard.reset()
        controller.feedActions.selectHotelsRestaurantsTab()
    }

    func selectChip(_ chipID: String) {
        paginationGuard.reset()
        controller.feedActions.selectChip(chipId: chipID)
    }

    func retry() {
        paginationGuard.reset()
        controller.feedActions.retry()
    }

    func refresh() {
        guard !state.isLoading, !state.isRefreshing else { return }
        paginationGuard.reset()
        controller.feedActions.refresh()
    }

    func listingDidAppear(_ listingID: String) {
        guard let index = state.listings.firstIndex(where: { $0.id == listingID }) else { return }
        let thresholdIndex = max(state.listings.count - paginationThresholdItemCount, 0)
        let isNearEnd = index >= thresholdIndex
        if paginationGuard.shouldLoadNext(
            cursor: state.nextCursor,
            canLoadMore: state.canLoadMore,
            isNearEnd: isNearEnd,
            hasAppendError: state.appendErrorMessage != nil
        ) {
            controller.feedActions.loadNext()
        }
    }

    func retryAppend() {
        if paginationGuard.shouldRetry(cursor: state.nextCursor, canLoadMore: state.canLoadMore) {
            controller.feedActions.loadNext()
        }
    }

    func openCitySelector() {
        controller.cityActions.openCitySelector()
    }

    func closeCitySelector() {
        locationTask?.cancel()
        locationTask = nil
        controller.cityActions.closeCitySelector()
    }

    func selectCity(_ cityID: String) {
        locationTask?.cancel()
        locationTask = nil
        paginationGuard.reset()
        controller.cityActions.selectCity(cityId: cityID)
    }

    func requestLocation() {
        controller.cityActions.requestLocation()
    }

    func toggleLike(_ listingID: String) {
        controller.interactionActions.toggleLike(listingId: listingID)
    }

    func toggleFavorite(_ listingID: String) {
        controller.interactionActions.toggleFavorite(listingId: listingID)
    }

    func clearPendingAuthentication() {
        authenticationRequest = nil
        controller.interactionActions.updateViewerContext(viewerId: nil)
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

    private func accept(_ updatedState: ExploreUiState) {
        let previousState = state
        if didSelectedTabChange(previousState, updatedState) ||
            previousState.selectedChipId != updatedState.selectedChipId ||
            previousState.selectedCityId != updatedState.selectedCityId ||
            (!previousState.isRefreshing && updatedState.isRefreshing) {
            paginationGuard.reset()
        }
        state = updatedState
        publishAuthenticationRequestIfNeeded(updatedState.pendingAuthInteraction)
        publishAnnouncementIfNeeded(previousState: previousState, updatedState: updatedState)
    }

    private func accept(_ effect: IosExploreEffect) {
        if effect.requiresAuthentication {
            publishAuthenticationRequestIfNeeded(state.pendingAuthInteraction)
        } else if effect.requestsLocation {
            resolveLocation()
        } else if effect.replaysProtectedAction {
            authenticationRequest = nil
            if let event = effect.replayAnalyticsEvent {
                onProtectedActionReplayed(event)
            }
        }
    }

    private func publishAuthenticationRequestIfNeeded(_ pending: PendingExploreAuthInteraction?) {
        guard let pending else {
            authenticationRequest = nil
            return
        }
        let action: ExploreProtectedAction = pending.kind == .like ? .like : .favorite
        if let current = authenticationRequest,
           current.listingID == pending.listingId,
           current.action == action {
            return
        }
        authenticationRequestRevision += 1
        authenticationRequest = ExploreAuthenticationRequest(
            id: authenticationRequestRevision,
            listingID: pending.listingId,
            action: action,
            suggestedCityID: pending.suggestedCityId
        )
    }

    private func resolveLocation() {
        locationTask?.cancel()
        locationTask = Task { [weak self] in
            guard let self else { return }
            let result = await locationProvider.requestCurrentLocation()
            guard !Task.isCancelled else { return }
            switch result {
            case let .coordinate(coordinate):
                controller.cityActions.locationCoordinates(
                    latitude: coordinate.latitude,
                    longitude: coordinate.longitude
                )
            case .permissionDenied:
                controller.cityActions.locationPermissionDenied()
            case .disabled:
                controller.cityActions.locationDisabled()
            case .unavailable:
                controller.cityActions.locationUnavailable()
            }
            locationTask = nil
        }
    }

    private func publishAnnouncementIfNeeded(
        previousState: ExploreUiState,
        updatedState: ExploreUiState
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
        previousState: ExploreUiState,
        updatedState: ExploreUiState
    ) -> String? {
        if !previousState.isOffline, updatedState.isOffline {
            return strings.offlineBanner
        }
        if updatedState.errorMessage != previousState.errorMessage,
           let errorMessage = updatedState.errorMessage {
            return [strings.errorStateTitle, errorMessage].joined(separator: ". ")
        }
        if updatedState.appendErrorMessage != previousState.appendErrorMessage,
           let appendErrorMessage = updatedState.appendErrorMessage {
            return [strings.errorStateTitle, appendErrorMessage].joined(separator: ". ")
        }
        if updatedState.refreshMessage != previousState.refreshMessage,
           let refreshMessage = updatedState.refreshMessage {
            return refreshMessage
        }
        if updatedState.locationMessage != previousState.locationMessage,
           let locationMessage = updatedState.locationMessage {
            return locationMessage
        }
        if updatedState.interactionMessage != previousState.interactionMessage,
           let interactionMessage = updatedState.interactionMessage {
            return interactionMessage
        }
        if (!previousState.isLoading && updatedState.isLoading) ||
            (!previousState.isAppending && updatedState.isAppending) {
            return strings.loading
        }
        return nil
    }
}

private func didSelectedTabChange(
    _ previousState: ExploreUiState,
    _ updatedState: ExploreUiState
) -> Bool {
    previousState.isPlacesTabSelected != updatedState.isPlacesTabSelected ||
        previousState.isEventsTabSelected != updatedState.isEventsTabSelected ||
        previousState.isHotelsRestaurantsTabSelected != updatedState.isHotelsRestaurantsTabSelected
}

private let paginationThresholdItemCount = 4
