import Combine
import Foundation
import Shared

@MainActor
final class FavoritesStore: ObservableObject {
    @Published private(set) var state: FavoritesUiState
    @Published private(set) var isViewerTransitionPending = false
    @Published private(set) var announcementRevision = 0

    let strings: FavoritesStrings
    let commonStrings: KwaborStrings
    let isConfigured: Bool

    private let controller: IosFavoritesController
    private let onListingOpen: (String) -> Void
    private let onFavoriteChanged: (String, Bool, ViewerSessionScope) -> Void
    private var paginationGuard = FavoritesPaginationGuard()
    private var currentViewerID: String?
    private var currentViewerScope: ViewerSessionScope?
    private var hasAppliedViewerContext = false
    private var removalOwnerByListingID: [String: String] = [:]
    private var lastAnnouncement: String?

    private(set) var latestAnnouncement: String?

    init(
        controller: IosFavoritesController,
        commonStrings: KwaborStrings,
        onListingOpen: @escaping (String) -> Void,
        onFavoriteChanged: @escaping (String, Bool, ViewerSessionScope) -> Void
    ) {
        self.controller = controller
        self.commonStrings = commonStrings
        self.onListingOpen = onListingOpen
        self.onFavoriteChanged = onFavoriteChanged
        state = controller.currentState
        strings = controller.strings
        isConfigured = controller.isConfigured
        observeController()
    }

    deinit {
        controller.unobserve()
    }

    var canDisplayPrivateContent: Bool {
        currentViewerID != nil && currentViewerScope != nil && !isViewerTransitionPending
    }

    var visibleItems: [FavoriteListingItem] {
        canDisplayPrivateContent ? state.items : []
    }

    func prepareViewerContext(_ rawAccountID: String?) {
        let accountID = FavoritesViewerTransitionPolicy.normalizedAccountID(rawAccountID)
        guard !hasAppliedViewerContext || accountID != currentViewerID else { return }
        let shouldHide = FavoritesViewerTransitionPolicy.shouldHidePrivateContent(
            currentAccountID: currentViewerID,
            nextAccountID: accountID
        )
        hasAppliedViewerContext = true
        currentViewerID = accountID
        currentViewerScope = nil
        isViewerTransitionPending = shouldHide && accountID != nil
        paginationGuard.reset()
        removalOwnerByListingID.removeAll()
    }

    func commitViewerScope(_ scope: ViewerSessionScope) {
        let accountID = FavoritesViewerTransitionPolicy.normalizedAccountID(scope.accountId)
        guard accountID == currentViewerID else {
            return
        }
        currentViewerScope = scope
    }

    func screenAppeared() {
        controller.actions.screenAppeared()
    }

    func screenDisappeared() {
        paginationGuard.reset()
        controller.actions.screenDisappeared()
    }

    func selectAll() {
        guard state.selectedFilter != .all else { return }
        selectFilter { controller.actions.selectAll() }
    }

    func selectPlaces() {
        guard state.selectedFilter != .places else { return }
        selectFilter { controller.actions.selectPlaces() }
    }

    func selectEvents() {
        guard state.selectedFilter != .events else { return }
        selectFilter { controller.actions.selectEvents() }
    }

    func selectHotelsRestaurants() {
        guard state.selectedFilter != .hotelsRestaurants else { return }
        selectFilter { controller.actions.selectHotelsRestaurants() }
    }

    func retry() {
        paginationGuard.reset()
        controller.actions.retry()
    }

    func refresh() {
        guard canDisplayPrivateContent,
              !state.isLoading,
              !state.isRefreshing,
              !state.isAppending else {
            return
        }
        paginationGuard.reset()
        controller.actions.refresh()
    }

    func itemDidAppear(_ listingID: String) {
        guard canDisplayPrivateContent,
              let index = visibleItems.firstIndex(where: { $0.id == listingID }) else {
            return
        }
        if paginationGuard.shouldLoadNext(
            cursor: state.nextCursor,
            canLoadMore: state.canLoadMore,
            isNearEnd: FavoritesPaginationPolicy.isNearEnd(
                index: index,
                itemCount: visibleItems.count
            ),
            hasAppendError: state.appendErrorMessage != nil
        ) {
            controller.actions.loadNext()
        }
    }

    func retryAppend() {
        guard canDisplayPrivateContent else { return }
        if paginationGuard.shouldRetry(cursor: state.nextCursor, canLoadMore: state.canLoadMore) {
            controller.actions.loadNext()
        }
    }

    func openListing(_ listingID: String) {
        guard canDisplayPrivateContent,
              state.items.contains(where: { $0.id == listingID }) else {
            return
        }
        controller.actions.openListing(listingId: listingID)
    }

    func removeFavorite(_ listingID: String) {
        guard let viewerID = currentViewerID,
              canDisplayPrivateContent,
              state.items.contains(where: { $0.id == listingID }),
              !state.removingListingIds.contains(listingID) else {
            return
        }
        removalOwnerByListingID[listingID] = viewerID
        controller.actions.removeFavorite(listingId: listingID)
    }

    func applyFavoriteState(
        listingID: String,
        favorited: Bool,
        scope: ViewerSessionScope
    ) {
        guard matchesCurrentViewerScope(scope) else { return }
        controller.actions.applyExternalFavoriteState(
            listingId: listingID,
            favorited: favorited,
            scope: scope
        )
    }

    private func selectFilter(_ action: () -> Void) {
        guard canDisplayPrivateContent else { return }
        paginationGuard.reset()
        action()
    }

    private func observeController() {
        controller.observe(
            stateObserver: { [weak self] updatedState in
                MainActor.assumeIsolated {
                    self?.accept(updatedState)
                }
            },
            detailObserver: { [weak self] listingID, scope in
                MainActor.assumeIsolated {
                    self?.acceptListingOpen(listingID, scope: scope)
                }
            },
            favoriteObserver: { [weak self] listingID, favorited, scope in
                MainActor.assumeIsolated {
                    self?.acceptFavoriteChanged(
                        listingID: listingID,
                        favorited: favorited.boolValue,
                        scope: scope
                    )
                }
            }
        )
    }

    private func accept(_ updatedState: FavoritesUiState) {
        let previousState = state
        if previousState.selectedFilter != updatedState.selectedFilter ||
            (!previousState.isRefreshing && updatedState.isRefreshing) {
            paginationGuard.reset()
        }
        state = updatedState
        if updatedState.isAccountReady == (currentViewerID != nil) {
            isViewerTransitionPending = false
        }
        clearFailedRemovalOwnership(using: updatedState)
        publishAnnouncementIfNeeded(previousState: previousState, updatedState: updatedState)
    }

    private func acceptListingOpen(_ listingID: String, scope: ViewerSessionScope) {
        guard canDisplayPrivateContent,
              matchesCurrentViewerScope(scope),
              state.items.contains(where: { $0.id == listingID }) else {
            return
        }
        onListingOpen(listingID)
    }

    private func acceptFavoriteChanged(
        listingID: String,
        favorited: Bool,
        scope: ViewerSessionScope
    ) {
        guard let viewerID = currentViewerID,
              matchesCurrentViewerScope(scope),
              canDisplayPrivateContent else {
            return
        }
        if !favorited {
            guard removalOwnerByListingID[listingID] == viewerID else { return }
            removalOwnerByListingID[listingID] = nil
        }
        onFavoriteChanged(listingID, favorited, scope)
    }

    private func matchesCurrentViewerScope(_ scope: ViewerSessionScope) -> Bool {
        guard let currentViewerScope else { return false }
        return scope.accountId == currentViewerScope.accountId &&
            scope.epoch == currentViewerScope.epoch
    }

    private func clearFailedRemovalOwnership(using updatedState: FavoritesUiState) {
        let visibleIDs = Set(updatedState.items.map(\.id))
        for listingID in Array(removalOwnerByListingID.keys) where
            visibleIDs.contains(listingID) &&
            !updatedState.removingListingIds.contains(listingID) {
            removalOwnerByListingID[listingID] = nil
        }
    }

    private func publishAnnouncementIfNeeded(
        previousState: FavoritesUiState,
        updatedState: FavoritesUiState
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
        previousState: FavoritesUiState,
        updatedState: FavoritesUiState
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
        if updatedState.mutationMessage != previousState.mutationMessage,
           let mutationMessage = updatedState.mutationMessage {
            return mutationMessage
        }
        if (!previousState.isLoading && updatedState.isLoading) ||
            (!previousState.isAppending && updatedState.isAppending) {
            return commonStrings.loading
        }
        return nil
    }
}
