import Foundation
import GoogleSignIn
import Shared
import SwiftUI

@main
struct KwaborApp: App {
    @Environment(\.scenePhase) private var scenePhase
    private let compositionRoot: IosKwaborCompositionRoot
    @StateObject private var coordinator: OnboardingCoordinator
    @StateObject private var exploreStore: ExploreStore
    @StateObject private var favoritesStore: FavoritesStore
    @StateObject private var searchStore: SearchStore
    @StateObject private var guideDiscoveryStore: GuideDiscoveryStore
    @StateObject private var catalogDetailStore: CatalogDetailStore

    @MainActor
    init() {
        GoogleSignInBootstrap.configureIfPossible()
        let legacyRemoteIntroCleaner = LegacyRemoteIntroCleaner()
        _ = Task.detached(priority: .utility) {
            await legacyRemoteIntroCleaner.cleanIfNeeded()
        }
        let observability = FirebaseObservability()
        let compositionRoot = IosKwaborCompositionRoot(
            environmentName: KwaborConfiguration.value("KWABOR_ENVIRONMENT"),
            supabaseUrl: KwaborConfiguration.value("KWABOR_SUPABASE_URL"),
            supabasePublishableKey: KwaborConfiguration.value("KWABOR_SUPABASE_PUBLISHABLE_KEY")
        )
        self.compositionRoot = compositionRoot
        let exploreStore = ExploreStore(
            controller: compositionRoot.exploreController,
            onProtectedActionReplayed: observability.track
        )
        let catalogDetailStore = CatalogDetailStore(controller: compositionRoot.catalogDetailController)
        let favoritesStore = FavoritesStore(
            controller: compositionRoot.favoritesController,
            commonStrings: exploreStore.strings,
            onListingOpen: catalogDetailStore.open,
            onFavoriteChanged: { [weak exploreStore] listingID, favorited, scope in
                exploreStore?.applyFavoriteState(
                    listingID: listingID,
                    favorited: favorited,
                    scope: scope
                )
            }
        )
        exploreStore.setFavoriteChangeHandler { [weak favoritesStore] listingID, favorited, scope in
            favoritesStore?.applyFavoriteState(
                listingID: listingID,
                favorited: favorited,
                scope: scope
            )
        }
        _exploreStore = StateObject(wrappedValue: exploreStore)
        _favoritesStore = StateObject(wrappedValue: favoritesStore)
        _catalogDetailStore = StateObject(wrappedValue: catalogDetailStore)
        _searchStore = StateObject(
            wrappedValue: SearchStore(
                controller: compositionRoot.searchController,
                offlineMessage: exploreStore.strings.offlineBanner,
                loadingMessage: exploreStore.strings.loading,
                onOpenListing: catalogDetailStore.open,
                onQuerySubmitted: observability.track,
                onOpenAssistant: nil
            )
        )
        _guideDiscoveryStore = StateObject(
            wrappedValue: GuideDiscoveryStore(
                controller: compositionRoot.guideDiscoveryController,
                commonStrings: exploreStore.strings,
                onGuideOpen: catalogDetailStore.open
            )
        )
        _coordinator = StateObject(
            wrappedValue: OnboardingCoordinator(
                bridge: compositionRoot.bridge,
                authController: compositionRoot.authController,
                passwordRecoveryController: compositionRoot.passwordRecoveryController,
                registrationController: compositionRoot.registrationController,
                observability: observability
            )
        )
    }

    var body: some Scene {
        WindowGroup {
            OnboardingView(
                coordinator: coordinator,
                exploreStore: exploreStore,
                favoritesStore: favoritesStore,
                searchStore: searchStore,
                guideDiscoveryStore: guideDiscoveryStore,
                catalogDetailStore: catalogDetailStore,
                onViewerContextChanged: { accountID in
                    compositionRoot.updateViewerSessionScope(
                        accountId: accountID,
                        accountSetupComplete: accountID != nil
                    )
                }
            )
                .onOpenURL { url in
                    if !GIDSignIn.sharedInstance.handle(url) {
                        _ = coordinator.handleIncomingUrl(url)
                    }
                }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active {
                        coordinator.applicationBecameActive()
                    }
                }
        }
    }
}

private enum KwaborConfiguration {
    static func value(_ key: String) -> String? {
        if let environmentValue = ProcessInfo.processInfo.environment[key], !environmentValue.isEmpty {
            return environmentValue
        }
        if let bundleValue = Bundle.main.object(forInfoDictionaryKey: key) as? String, !bundleValue.isEmpty {
            return bundleValue
        }
        return nil
    }
}
