import Combine
import Shared

@MainActor
final class CatalogDetailStore: ObservableObject {
    @Published private(set) var state: CatalogDetailUiState
    @Published private(set) var announcementRevision = 0

    let strings: KwaborStrings

    private let controller: IosCatalogDetailController
    private var lastAnnouncement: String?

    private(set) var latestAnnouncement: String?

    init(controller: IosCatalogDetailController) {
        self.controller = controller
        state = controller.currentState
        strings = controller.strings
        observeController()
    }

    deinit {
        controller.unobserve()
    }

    var isPresented: Bool {
        !(state is CatalogDetailUiStateClosed)
    }

    func open(listingID: String) {
        controller.actions.open(listingId: listingID)
    }

    func retry() {
        controller.actions.retry()
    }

    func dismiss() {
        guard isPresented else { return }
        controller.actions.close()
    }

    func selectMedia(sourceIndex: Int) {
        guard let boundedIndex = Int32(exactly: sourceIndex) else { return }
        controller.actions.selectMedia(index: boundedIndex)
    }

    func toggleDescription() {
        controller.actions.toggleDescription()
    }

    private func observeController() {
        controller.observe { [weak self] updatedState in
            MainActor.assumeIsolated {
                self?.accept(updatedState)
            }
        }
    }

    private func accept(_ updatedState: CatalogDetailUiState) {
        state = updatedState
        publishAnnouncementIfNeeded(updatedState)
    }

    private func publishAnnouncementIfNeeded(_ updatedState: CatalogDetailUiState) {
        let announcement = announcement(for: updatedState)
        if updatedState is CatalogDetailUiStateClosed {
            lastAnnouncement = nil
            latestAnnouncement = nil
            return
        }
        guard announcement != lastAnnouncement else { return }
        lastAnnouncement = announcement
        guard let announcement else {
            latestAnnouncement = nil
            return
        }
        latestAnnouncement = announcement
        announcementRevision += 1
    }

    private func announcement(for state: CatalogDetailUiState) -> String? {
        if state is CatalogDetailUiStateLoading {
            return strings.detail.loading
        }
        if state is CatalogDetailUiStateNotFound {
            return strings.detail.unavailable
        }
        if state is CatalogDetailUiStateOfflineFailure {
            return strings.detail.offlineUnavailable
        }
        if state is CatalogDetailUiStateFailure {
            return strings.detail.loadFailed
        }
        if let contentState = state as? CatalogDetailUiStateContent,
           let event = contentState.model.content as? CatalogDetailContentUiModelEvent,
           event.isEnded {
            return strings.detail.eventEnded
        }
        return nil
    }
}
