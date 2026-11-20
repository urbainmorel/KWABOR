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

struct ExploreFirstUsableViewportDrawToken {
    let generation: Int64
    let viewportState: PerformanceViewportState
}

@MainActor
final class ExploreStore: ObservableObject {
    @Published private(set) var state: ExploreUiState
    @Published private(set) var authenticationRequest: ExploreAuthenticationRequest?
    @Published private(set) var announcementRevision = 0
    @Published private(set) var firstUsableViewportSample: ExploreFirstUsableViewportSample?
    @Published private(set) var surfacePresentationObscured = false

    let strings: KwaborStrings
    let isConfigured: Bool

    private let controller: IosExploreController
    private let locationProvider: ApproximateLocationProviding
    private let onProtectedActionReplayed: (AnalyticsEvent) -> Void
    private let isPerformanceCollectionAllowed: () -> Bool
    private let recordPerformanceMeasurement: (PerformanceMeasurement) -> Void
    private let monotonicNowNanoseconds: () -> Int64
    private let firstUsableViewportProbe = ExploreFirstUsableViewportProbe()
    private let surfacePresentationGate = ExploreSurfacePresentationGate()
    private let surfacePresentationRegistry = ExploreSurfacePresentationRegistry()
    private var onFavoriteChanged: (String, Bool, Int64, ViewerSessionScope) -> Void
    private var paginationGuard = ExplorePaginationGuard()
    private var locationTask: Task<Void, Never>?
    private var lastAnnouncement: String?
    private var lastViewerID: String?
    private var currentViewerScope: ViewerSessionScope?
    private var hasAppliedViewerContext = false
    private var authenticationRequestRevision = 0
    private var screenVisible = false
    private var surfaceUnobscured = false
    private var applicationActive = false

    private(set) var latestAnnouncement: String?

    init(
        controller: IosExploreController,
        locationProvider: ApproximateLocationProviding? = nil,
        onProtectedActionReplayed: @escaping (AnalyticsEvent) -> Void = { _ in },
        isPerformanceCollectionAllowed: @escaping () -> Bool = { false },
        recordPerformanceMeasurement: @escaping (PerformanceMeasurement) -> Void = { _ in },
        monotonicNowNanoseconds: @escaping () -> Int64 = monotonicUptimeNanoseconds,
        onFavoriteChanged: @escaping (String, Bool, Int64, ViewerSessionScope) -> Void = { _, _, _, _ in }
    ) {
        self.controller = controller
        self.locationProvider = locationProvider ?? CoreLocationApproximateLocationProvider()
        self.onProtectedActionReplayed = onProtectedActionReplayed
        self.isPerformanceCollectionAllowed = isPerformanceCollectionAllowed
        self.recordPerformanceMeasurement = recordPerformanceMeasurement
        self.monotonicNowNanoseconds = monotonicNowNanoseconds
        self.onFavoriteChanged = onFavoriteChanged
        state = controller.currentState
        strings = controller.strings
        isConfigured = controller.isConfigured
        observeController()
    }

    deinit {
        locationTask?.cancel()
        firstUsableViewportProbe.onHidden()
        controller.unobserve()
    }

    var firstUsableViewportDrawToken: ExploreFirstUsableViewportDrawToken? {
        guard let sample = firstUsableViewportSample,
              let viewportState = state.firstUsableViewportState else {
            return nil
        }
        return ExploreFirstUsableViewportDrawToken(
            generation: sample.generation,
            viewportState: viewportState
        )
    }

    func screenAppeared(applicationActive: Bool, surfaceUnobscured: Bool) {
        screenVisible = true
        self.applicationActive = applicationActive
        self.surfaceUnobscured = surfaceUnobscured
        reconcileFirstUsableViewportProbe()
    }

    func screenDisappeared() {
        screenVisible = false
        reconcileFirstUsableViewportProbe()
    }

    func applicationBecameActive() {
        applicationActive = true
        reconcileFirstUsableViewportProbe()
    }

    func applicationBecameInactive() {
        applicationActive = false
        reconcileFirstUsableViewportProbe()
    }

    func surfaceVisibilityChanged(_ unobscured: Bool) {
        surfaceUnobscured = unobscured
        reconcileFirstUsableViewportProbe()
    }

    func surfacePresentationStarted(
        _ kind: ExploreSurfacePresentationKind
    ) -> ExploreSurfacePresentationToken {
        let token = surfacePresentationGate.onPresentationStarted(kind: kind)
        surfacePresentationRegistry.begin(token: token)
        surfacePresentationObscured = surfacePresentationGate.isObscured
        reconcileFirstUsableViewportProbe()
        return token
    }

    func surfacePresentationAttached(_ token: ExploreSurfacePresentationToken) {
        surfacePresentationRegistry.attached(token: token)
    }

    func surfacePresentationDismissRequested(_ token: ExploreSurfacePresentationToken) {
        completeSurfacePresentation(
            surfacePresentationRegistry.dismissRequested(token: token)
        )
    }

    func surfacePresentationRemoved(_ token: ExploreSurfacePresentationToken) {
        completeSurfacePresentation(surfacePresentationRegistry.removed(token: token))
    }

    private func completeSurfacePresentation(_ token: ExploreSurfacePresentationToken?) {
        guard let token else { return }
        _ = surfacePresentationGate.onPresentationDismissed(token: token)
        surfacePresentationObscured = surfacePresentationGate.isObscured
        reconcileFirstUsableViewportProbe()
    }

    func performanceCollectionEligibilityChanged() {
        reconcileFirstUsableViewportProbe()
    }

    func firstUsableViewportWasCommitted(_ token: ExploreFirstUsableViewportDrawToken) {
        guard firstUsableViewportSample?.generation == token.generation else { return }
        let measurement = firstUsableViewportProbe.onViewportCommitted(
            generation: token.generation,
            viewportState: token.viewportState,
            committedAtNanoseconds: monotonicNowNanoseconds()
        )
        firstUsableViewportSample = nil
        if let measurement {
            recordPerformanceMeasurement(measurement.performanceMeasurement)
        }
    }

    func prepareViewerContext(_ rawViewerID: String?) {
        let viewerID = normalizedViewerID(rawViewerID)
        guard !hasAppliedViewerContext || viewerID != lastViewerID else { return }
        hasAppliedViewerContext = true
        lastViewerID = viewerID
        currentViewerScope = nil
        authenticationRequest = nil
        paginationGuard.reset()
    }

    func commitViewerScope(_ scope: ViewerSessionScope) {
        guard scope.accountId == lastViewerID else { return }
        currentViewerScope = scope
    }

    func setFavoriteChangeHandler(
        _ handler: @escaping (String, Bool, Int64, ViewerSessionScope) -> Void
    ) {
        onFavoriteChanged = handler
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
        guard currentViewerScope != nil else { return }
        controller.interactionActions.toggleLike(listingId: listingID)
    }

    func toggleFavorite(_ listingID: String) {
        guard currentViewerScope != nil else { return }
        controller.interactionActions.toggleFavorite(listingId: listingID)
    }

    func applyFavoriteState(
        listingID: String,
        favorited: Bool,
        clientMutationSequence: Int64,
        scope: ViewerSessionScope
    ) {
        guard matchesCurrentViewerScope(scope) else { return }
        controller.interactionActions.applyFavoriteState(
            listingId: listingID,
            favorited: favorited,
            clientMutationSequence: clientMutationSequence,
            scope: scope
        )
    }

    func clearPendingAuthentication() {
        authenticationRequest = nil
        controller.interactionActions.clearPendingAuthentication()
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
            },
            favoriteObserver: { [weak self] listingID, favorited, clientMutationSequence, scope in
                MainActor.assumeIsolated {
                    guard let self, self.matchesCurrentViewerScope(scope) else { return }
                    self.onFavoriteChanged(
                        listingID,
                        favorited.boolValue,
                        clientMutationSequence.int64Value,
                        scope
                    )
                }
            }
        )
    }

    private func reconcileFirstUsableViewportProbe() {
        guard screenVisible,
              surfaceUnobscured,
              applicationActive,
              !surfacePresentationGate.isObscured else {
            firstUsableViewportProbe.onHidden()
            firstUsableViewportSample = nil
            return
        }
        guard isPerformanceCollectionAllowed() else {
            firstUsableViewportProbe.onHidden()
            firstUsableViewportSample = nil
            return
        }
        firstUsableViewportSample = firstUsableViewportProbe.onVisible(
            diagnosticsAllowed: true,
            startedAtNanoseconds: monotonicNowNanoseconds()
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
        publishAnnouncementIfNeeded(previousState: previousState, updatedState: updatedState)
    }

    private func accept(_ effect: IosExploreEffect) {
        if effect.requestsLocation {
            resolveLocation()
            return
        }
        guard let scope = effect.scope, matchesCurrentViewerScope(scope) else { return }
        if effect.requiresAuthentication {
            publishAuthenticationRequestIfNeeded(state.pendingAuthInteraction)
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

    private func matchesCurrentViewerScope(_ scope: ViewerSessionScope) -> Bool {
        guard let currentViewerScope else { return false }
        return scope.accountId == currentViewerScope.accountId &&
            scope.epoch == currentViewerScope.epoch
    }

    private func normalizedViewerID(_ rawValue: String?) -> String? {
        let candidate = rawValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return candidate.isEmpty ? nil : candidate
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

private func monotonicUptimeNanoseconds() -> Int64 {
    Int64(clamping: DispatchTime.now().uptimeNanoseconds)
}
