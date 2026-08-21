import Shared
import SwiftUI

struct ContentView: View {
    let bridge: KwaborSharedBridge
    let exploreStore: ExploreStore
    let favoritesStore: FavoritesStore
    let searchStore: SearchStore
    let guideDiscoveryStore: GuideDiscoveryStore
    @ObservedObject var catalogDetailStore: CatalogDetailStore
    let isGuestSession: Bool
    let pendingProtectedDestinationKey: String?
    let strings: OnboardingStrings
    let settingsStrings: SettingsStrings
    let accountEmail: String?
    let accountAuthenticationMethod: AuthenticationMethod?
    let observabilityConsent: ObservabilityConsent
    let observabilityConsentErrorMessage: String?
    let accountSessionIdentity: String?
    let accountSecurityController: IosAuthController?
    let federatedIdentityHintStore: FederatedIdentityHintPersisting?
    let latestAccountSecurityError: () -> String?
    let isSigningOutAccount: Bool
    let accountSignOutErrorMessage: String?
    let onProtectedDestinationSelected: (String) -> Void
    let onPendingProtectedDestinationConsumed: (String) -> Bool
    let onExploreAuthenticationRequired: (ExploreAuthenticationRequest) -> Void
    let onSignOut: () -> Void
    let onDismissSignOutError: () -> Void
    let onAccountDeletionWillStart: () -> Bool
    let onAccountDeletionStateChanged: (Bool) -> Void
    let onAccountDeleted: () -> Void
    let onObservabilityConsentChanged: (ObservabilityConsentCategory, Bool) -> Void
    let rootDeepLinkDelivery: RootDeepLinkDelivery?
    let onProtectedRootDeepLinkTransferred: (RootDeepLinkDelivery) -> Bool
    let onRootDeepLinkAcknowledged: (RootDeepLinkDelivery) -> Bool
    let catalogDetailDeepLinkDelivery: CatalogDetailDeepLinkDelivery?
    let isCatalogDetailDeepLinkCurrent: (CatalogDetailDeepLinkDelivery) -> Bool
    let onCatalogDetailDeepLinkAcknowledged: (CatalogDetailDeepLinkDelivery) -> Bool
    @State private var selectedDestination = RootDestination.home
    @State private var catalogDetailSheetPresentation: ExploreSheetPresentation?

    private var isClosedBetaCatalog: Bool { bridge.isClosedBetaCatalog }

    init(
        bridge: KwaborSharedBridge,
        exploreStore: ExploreStore,
        favoritesStore: FavoritesStore,
        searchStore: SearchStore,
        guideDiscoveryStore: GuideDiscoveryStore,
        catalogDetailStore: CatalogDetailStore,
        isGuestSession: Bool = false,
        pendingProtectedDestinationKey: String? = nil,
        strings: OnboardingStrings? = nil,
        settingsStrings: SettingsStrings? = nil,
        accountEmail: String? = nil,
        accountAuthenticationMethod: AuthenticationMethod? = nil,
        observabilityConsent: ObservabilityConsent = ObservabilityConsent(
            analyticsAllowed: false,
            diagnosticsAllowed: false,
            remoteConfigurationAllowed: false
        ),
        observabilityConsentErrorMessage: String? = nil,
        accountSessionIdentity: String? = nil,
        accountSecurityController: IosAuthController? = nil,
        federatedIdentityHintStore: FederatedIdentityHintPersisting? = nil,
        latestAccountSecurityError: @escaping () -> String? = { nil },
        isSigningOutAccount: Bool = false,
        accountSignOutErrorMessage: String? = nil,
        onProtectedDestinationSelected: @escaping (String) -> Void = { _ in },
        onPendingProtectedDestinationConsumed: @escaping (String) -> Bool = { _ in true },
        onExploreAuthenticationRequired: @escaping (ExploreAuthenticationRequest) -> Void = { _ in },
        onSignOut: @escaping () -> Void = {},
        onDismissSignOutError: @escaping () -> Void = {},
        onAccountDeletionWillStart: @escaping () -> Bool = { true },
        onAccountDeletionStateChanged: @escaping (Bool) -> Void = { _ in },
        onAccountDeleted: @escaping () -> Void = {},
        onObservabilityConsentChanged: @escaping (ObservabilityConsentCategory, Bool) -> Void = { _, _ in },
        rootDeepLinkDelivery: RootDeepLinkDelivery? = nil,
        onProtectedRootDeepLinkTransferred: @escaping (RootDeepLinkDelivery) -> Bool = { _ in false },
        onRootDeepLinkAcknowledged: @escaping (RootDeepLinkDelivery) -> Bool = { _ in false },
        catalogDetailDeepLinkDelivery: CatalogDetailDeepLinkDelivery? = nil,
        isCatalogDetailDeepLinkCurrent: @escaping (CatalogDetailDeepLinkDelivery) -> Bool = { _ in false },
        onCatalogDetailDeepLinkAcknowledged: @escaping (CatalogDetailDeepLinkDelivery) -> Bool = { _ in false }
    ) {
        self.bridge = bridge
        self.exploreStore = exploreStore
        self.favoritesStore = favoritesStore
        self.searchStore = searchStore
        self.guideDiscoveryStore = guideDiscoveryStore
        self.catalogDetailStore = catalogDetailStore
        self.isGuestSession = isGuestSession
        self.pendingProtectedDestinationKey = pendingProtectedDestinationKey
        let resolvedStrings = strings ?? bridge.onboardingStrings()
        self.strings = resolvedStrings
        self.settingsStrings = settingsStrings ?? resolvedStrings.settings
        self.accountEmail = accountEmail
        self.accountAuthenticationMethod = accountAuthenticationMethod
        self.observabilityConsent = observabilityConsent
        self.observabilityConsentErrorMessage = observabilityConsentErrorMessage
        self.accountSessionIdentity = accountSessionIdentity
        self.accountSecurityController = accountSecurityController
        self.federatedIdentityHintStore = federatedIdentityHintStore
        self.latestAccountSecurityError = latestAccountSecurityError
        self.isSigningOutAccount = isSigningOutAccount
        self.accountSignOutErrorMessage = accountSignOutErrorMessage
        self.onProtectedDestinationSelected = onProtectedDestinationSelected
        self.onPendingProtectedDestinationConsumed = onPendingProtectedDestinationConsumed
        self.onExploreAuthenticationRequired = onExploreAuthenticationRequired
        self.onSignOut = onSignOut
        self.onDismissSignOutError = onDismissSignOutError
        self.onAccountDeletionWillStart = onAccountDeletionWillStart
        self.onAccountDeletionStateChanged = onAccountDeletionStateChanged
        self.onAccountDeleted = onAccountDeleted
        self.onObservabilityConsentChanged = onObservabilityConsentChanged
        self.rootDeepLinkDelivery = rootDeepLinkDelivery
        self.onProtectedRootDeepLinkTransferred = onProtectedRootDeepLinkTransferred
        self.onRootDeepLinkAcknowledged = onRootDeepLinkAcknowledged
        self.catalogDetailDeepLinkDelivery = catalogDetailDeepLinkDelivery
        self.isCatalogDetailDeepLinkCurrent = isCatalogDetailDeepLinkCurrent
        self.onCatalogDetailDeepLinkAcknowledged = onCatalogDetailDeepLinkAcknowledged
    }

    var body: some View {
        GeometryReader { proxy in
            TabView(selection: destinationBinding) {
                ForEach(RootDestination.visibleCases(closedBetaCatalog: isClosedBetaCatalog)) { destination in
                    NavigationStack {
                        RootDestinationContent(
                            destination: destination,
                            bridge: bridge,
                            exploreStore: exploreStore,
                            favoritesStore: favoritesStore,
                            searchStore: searchStore,
                            guideDiscoveryStore: guideDiscoveryStore,
                            strings: strings,
                            settingsStrings: settingsStrings,
                            accountEmail: accountEmail,
                            accountAuthenticationMethod: accountAuthenticationMethod,
                            observabilityConsent: observabilityConsent,
                            observabilityConsentErrorMessage: observabilityConsentErrorMessage,
                            accountSecurityController: accountSecurityController,
                            federatedIdentityHintStore: federatedIdentityHintStore,
                            latestAccountSecurityError: latestAccountSecurityError,
                            isSigningOutAccount: isSigningOutAccount,
                            accountSignOutErrorMessage: accountSignOutErrorMessage,
                            isClosedBetaCatalog: isClosedBetaCatalog,
                            isExploreSurfaceObscured:
                                catalogDetailStore.isPresented ||
                                selectedDestination != .home,
                            onExploreAuthenticationRequired: onExploreAuthenticationRequired,
                            onListingOpen: catalogDetailStore.open,
                            onSignOut: onSignOut,
                            onDismissSignOutError: onDismissSignOutError,
                            onAccountDeletionWillStart: onAccountDeletionWillStart,
                            onAccountDeletionStateChanged: onAccountDeletionStateChanged,
                            onAccountDeleted: onAccountDeleted,
                            onObservabilityConsentChanged: onObservabilityConsentChanged
                        )
                    }
                    .id(
                        RootNavigationStackIdentity(
                            destination: destination,
                            accountSessionIdentity: accountSessionIdentity
                        )
                    )
                    .tabItem {
                        Label(destination.label(using: bridge), systemImage: destination.systemImage)
                    }
                    .tag(destination)
                }
            }
            .sheet(item: catalogDetailPresentationBinding) { presentation in
                CatalogDetailSheet(store: catalogDetailStore)
                    .presentationDetents([
                        .fraction(
                            CatalogDetailLayoutPolicy.sheetHeightFraction(
                                forWidth: proxy.size.width
                            )
                        ),
                    ])
                    .presentationContentInteraction(.scrolls)
                    .background {
                        ExploreSheetDismissalObserver(
                            token: presentation.token,
                            onAttached: exploreStore.surfacePresentationAttached,
                            onRemoved: catalogDetailPresentationDidRemove
                        )
                        .id(presentation.id)
                    }
            }
            .onAppear(perform: replayPendingDestinationAfterAuthentication)
            .onAppear(perform: applyPendingCatalogDetailDeepLink)
            .onAppear {
                reconcileCatalogDetailPresentation(catalogDetailStore.isPresented)
            }
            .onChange(of: rootDeepLinkDelivery) { _, _ in
                replayPendingDestinationAfterAuthentication()
            }
            .onChange(of: pendingProtectedDestinationKey) { _, _ in
                replayPendingDestinationAfterAuthentication()
            }
            .onChange(of: catalogDetailDeepLinkDelivery) { _, _ in
                applyPendingCatalogDetailDeepLink()
            }
            .onChange(of: catalogDetailStore.isPresented) { _, isPresented in
                reconcileCatalogDetailPresentation(isPresented)
            }
            .onChange(of: isGuestSession) { _, isGuest in
                if isGuest {
                    selectedDestination = .home
                } else {
                    replayPendingDestinationAfterAuthentication()
                }
            }
            .onChange(of: accountSessionIdentity) { _, _ in
                searchStore.close()
            }
            .onDisappear {
                dismissCatalogDetailSheetPresentation()
                catalogDetailStore.dismiss()
            }
        }
    }

    private var catalogDetailPresentationBinding: Binding<ExploreSheetPresentation?> {
        Binding(
            get: { catalogDetailSheetPresentation },
            set: { presentation in
                if let presentation {
                    catalogDetailSheetPresentation = presentation
                } else {
                    dismissCatalogDetailSheetPresentation()
                    catalogDetailStore.dismiss()
                }
            }
        )
    }

    private func reconcileCatalogDetailPresentation(_ isPresented: Bool) {
        if isPresented {
            guard catalogDetailSheetPresentation == nil else { return }
            let token = exploreStore.surfacePresentationStarted(.catalogDetail)
            catalogDetailSheetPresentation = ExploreSheetPresentation(token: token)
        } else {
            dismissCatalogDetailSheetPresentation()
        }
    }

    private func dismissCatalogDetailSheetPresentation() {
        guard let presentation = catalogDetailSheetPresentation else { return }
        exploreStore.surfacePresentationDismissRequested(presentation.token)
        catalogDetailSheetPresentation = nil
    }

    private func catalogDetailPresentationDidRemove(_ token: ExploreSurfacePresentationToken) {
        exploreStore.surfacePresentationRemoved(token)
    }

    private var destinationBinding: Binding<RootDestination> {
        Binding(
            get: { selectedDestination },
            set: requestDestination
        )
    }

    private func requestDestination(_ destination: RootDestination) {
        _ = selectDestinationIfAllowed(destination)
    }

    @discardableResult
    private func selectDestinationIfAllowed(_ destination: RootDestination) -> Bool {
        guard destinationIsVisible(destination) else {
            selectedDestination = .home
            return false
        }
        guard destination == .home || !isGuestSession else {
            onProtectedDestinationSelected(destination.rawValue)
            return false
        }
        selectedDestination = destination
        return true
    }

    private func replayPendingDestinationAfterAuthentication() {
        let rootDestination = rootDeepLinkDelivery
            .flatMap { RootDestination(rawValue: $0.destinationKey) }
            .flatMap { destinationIsVisible($0) ? $0 : nil }
        switch ProtectedDestinationReplayPolicy.action(
            isGuest: isGuestSession,
            hasPendingRootDeepLink: rootDeepLinkDelivery != nil,
            isRootDeepLinkProtected: rootDestination != nil && rootDestination != .home,
            pendingDestinationKey: pendingProtectedDestinationKey
        ) {
        case let .applyRootDeepLink(discardProtectedDestination):
            guard let rootDeepLinkDelivery else { return }
            guard let rootDestination, destinationIsVisible(rootDestination) else {
                _ = onRootDeepLinkAcknowledged(rootDeepLinkDelivery)
                selectedDestination = .home
                return
            }
            guard onRootDeepLinkAcknowledged(rootDeepLinkDelivery) else { return }
            if discardProtectedDestination, let pendingProtectedDestinationKey {
                _ = onPendingProtectedDestinationConsumed(pendingProtectedDestinationKey)
            }
            selectedDestination = rootDestination
        case .transferRootDeepLinkToAuthentication:
            guard let rootDeepLinkDelivery else { return }
            _ = onProtectedRootDeepLinkTransferred(rootDeepLinkDelivery)
        case let .select(destinationKey):
            guard let destination = RootDestination(rawValue: destinationKey),
                  destinationIsVisible(destination) else {
                _ = onPendingProtectedDestinationConsumed(destinationKey)
                selectedDestination = .home
                return
            }
            guard onPendingProtectedDestinationConsumed(destinationKey) else { return }
            selectedDestination = destination
        case .wait:
            break
        }
    }

    private func applyPendingCatalogDetailDeepLink() {
        guard let delivery = catalogDetailDeepLinkDelivery,
              isCatalogDetailDeepLinkCurrent(delivery) else {
            return
        }
        selectedDestination = .home
        catalogDetailStore.open(listingID: delivery.listingID)
        _ = onCatalogDetailDeepLinkAcknowledged(delivery)
    }

    private func destinationIsVisible(_ destination: RootDestination) -> Bool {
        !isClosedBetaCatalog || destination.isClosedBetaVisible
    }
}

private struct RootNavigationStackIdentity: Hashable {
    let destination: RootDestination
    let accountSessionIdentity: String?

    init(destination: RootDestination, accountSessionIdentity: String?) {
        self.destination = destination
        self.accountSessionIdentity = destination == .profile ? accountSessionIdentity : nil
    }
}

private struct RootDestinationContent: View {
    let destination: RootDestination
    let bridge: KwaborSharedBridge
    let exploreStore: ExploreStore
    let favoritesStore: FavoritesStore
    let searchStore: SearchStore
    let guideDiscoveryStore: GuideDiscoveryStore
    let strings: OnboardingStrings
    let settingsStrings: SettingsStrings
    let accountEmail: String?
    let accountAuthenticationMethod: AuthenticationMethod?
    let observabilityConsent: ObservabilityConsent
    let observabilityConsentErrorMessage: String?
    let accountSecurityController: IosAuthController?
    let federatedIdentityHintStore: FederatedIdentityHintPersisting?
    let latestAccountSecurityError: () -> String?
    let isSigningOutAccount: Bool
    let accountSignOutErrorMessage: String?
    let isClosedBetaCatalog: Bool
    let isExploreSurfaceObscured: Bool
    let onExploreAuthenticationRequired: (ExploreAuthenticationRequest) -> Void
    let onListingOpen: (String) -> Void
    let onSignOut: () -> Void
    let onDismissSignOutError: () -> Void
    let onAccountDeletionWillStart: () -> Bool
    let onAccountDeletionStateChanged: (Bool) -> Void
    let onAccountDeleted: () -> Void
    let onObservabilityConsentChanged: (ObservabilityConsentCategory, Bool) -> Void

    var body: some View {
        Group {
            if destination == .home {
                ExploreView(
                    store: exploreStore,
                    searchStore: searchStore,
                    guideDiscoveryStore: guideDiscoveryStore,
                    onListingOpen: onListingOpen,
                    onAuthenticationRequired: onExploreAuthenticationRequired,
                    showsClosedBetaDisclosure: isClosedBetaCatalog,
                    showsGuideDiscoveryEntry: !isClosedBetaCatalog,
                    isObscured: isExploreSurfaceObscured,
                    performanceCollectionRequested: observabilityConsent.diagnosticsAllowed
                )
                .toolbar(.hidden, for: .navigationBar)
            } else if destination == .profile {
                ScrollView {
                    profileContent
                        .frame(maxWidth: profileContentMaxWidth, alignment: .leading)
                        .frame(maxWidth: .infinity, alignment: .top)
                        .padding(KwaborDesignTokens.Spacing.xxl)
                }
                .navigationTitle(destination.label(using: bridge))
            } else {
                destinationContent
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
                    .padding(KwaborDesignTokens.Spacing.xxl)
                    .navigationTitle(destination.label(using: bridge))
            }
        }
        .background(KwaborDesignTokens.ColorToken.paper50)
    }

    private var destinationContent: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.md) {
            Text(bridge.appName())
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.secondary)

            Text(title)
                .font(.system(size: 32, weight: .bold))
                .foregroundStyle(.primary)

            Text(bridge.foundationStatus())
                .font(.system(size: 16, weight: .regular))
                .foregroundStyle(.secondary)
        }
    }

    private var profileContent: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.lg) {
            ProfileAccountIdentity(
                title: settingsStrings.accountSectionTitle,
                email: displayedAccountEmail
            )

            FavoritesEntryLink(
                store: favoritesStore,
                showsClosedBetaDisclosure: isClosedBetaCatalog
            )

            NavigationLink {
                AccountSettingsView(
                    settingsStrings: settingsStrings,
                    onboardingStrings: strings,
                    email: displayedAccountEmail,
                    authenticationMethodName: displayedAuthenticationMethodName,
                    observabilityConsent: observabilityConsent,
                    observabilityConsentErrorMessage: observabilityConsentErrorMessage,
                    accountSecurityController: accountSecurityController,
                    federatedIdentityHintStore: federatedIdentityHintStore,
                    latestAccountSecurityError: latestAccountSecurityError,
                    isSigningOutAccount: isSigningOutAccount,
                    accountSignOutErrorMessage: accountSignOutErrorMessage,
                    onSignOut: onSignOut,
                    onDismissSignOutError: onDismissSignOutError,
                    onAccountDeletionWillStart: onAccountDeletionWillStart,
                    onAccountDeletionStateChanged: onAccountDeletionStateChanged,
                    onAccountDeleted: onAccountDeleted,
                    onObservabilityConsentChanged: onObservabilityConsentChanged
                )
            } label: {
                SettingsNavigationRow(strings: settingsStrings)
            }
            .buttonStyle(.plain)
        }
    }

    private var displayedAccountEmail: String {
        settingsStrings.accountEmail(rawValue: accountEmail)
    }

    private var displayedAuthenticationMethodName: String {
        settingsStrings.authenticationMethodName(authenticationMethod: accountAuthenticationMethod)
    }

    private var title: String {
        destination.label(using: bridge)
    }
}

private let profileContentMaxWidth: CGFloat = 560

private struct ProfileAccountIdentity: View {
    let title: String
    let email: String

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.sm) {
            Text(title)
                .font(.headline)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
            Text(email)
                .font(.body)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(KwaborDesignTokens.Spacing.lg)
        .background(KwaborDesignTokens.ColorToken.surface0)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
        .accessibilityElement(children: .combine)
    }
}

private struct SettingsNavigationRow: View {
    let strings: SettingsStrings

    var body: some View {
        HStack(spacing: KwaborDesignTokens.Spacing.md) {
            VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
                Text(strings.title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                Text(strings.profileEntrySubtitle)
                    .font(.body)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: KwaborDesignTokens.Spacing.sm)
            Image(systemName: "chevron.right")
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                .accessibilityHidden(true)
        }
        .frame(
            maxWidth: .infinity,
            minHeight: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget,
            alignment: .leading
        )
        .padding(KwaborDesignTokens.Spacing.lg)
        .background(KwaborDesignTokens.ColorToken.surface0)
        .contentShape(Rectangle())
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
        .accessibilityElement(children: .combine)
    }
}

private struct AccountSettingsView: View {
    let settingsStrings: SettingsStrings
    let onboardingStrings: OnboardingStrings
    let email: String
    let authenticationMethodName: String
    let observabilityConsent: ObservabilityConsent
    let observabilityConsentErrorMessage: String?
    let accountSecurityController: IosAuthController?
    let federatedIdentityHintStore: FederatedIdentityHintPersisting?
    let latestAccountSecurityError: () -> String?
    let isSigningOutAccount: Bool
    let accountSignOutErrorMessage: String?
    let onSignOut: () -> Void
    let onDismissSignOutError: () -> Void
    let onAccountDeletionWillStart: () -> Bool
    let onAccountDeletionStateChanged: (Bool) -> Void
    let onAccountDeleted: () -> Void
    let onObservabilityConsentChanged: (ObservabilityConsentCategory, Bool) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.lg) {
                AccountIdentitySection(
                    strings: settingsStrings,
                    email: email,
                    authenticationMethodName: authenticationMethodName
                )
                PrivacyPreferencesSection(
                    strings: settingsStrings,
                    consent: observabilityConsent,
                    errorMessage: observabilityConsentErrorMessage,
                    onConsentChanged: onObservabilityConsentChanged
                )
                AccountDangerZoneSection(
                    controller: accountSecurityController,
                    strings: onboardingStrings,
                    identityHintStore: federatedIdentityHintStore,
                    latestAuthError: latestAccountSecurityError,
                    isSigningOut: isSigningOutAccount,
                    signOutErrorMessage: accountSignOutErrorMessage,
                    onSignOut: onSignOut,
                    onDismissSignOutError: onDismissSignOutError,
                    onDeletionWillStart: onAccountDeletionWillStart,
                    onDeletionStateChanged: onAccountDeletionStateChanged,
                    onDeleted: onAccountDeleted
                )
            }
            .frame(maxWidth: profileContentMaxWidth, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .top)
            .padding(KwaborDesignTokens.Spacing.xxl)
        }
        .background(KwaborDesignTokens.ColorToken.paper50)
        .navigationTitle(settingsStrings.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
    }
}

private struct PrivacyPreferencesSection: View {
    let strings: SettingsStrings
    let consent: ObservabilityConsent
    let errorMessage: String?
    let onConsentChanged: (ObservabilityConsentCategory, Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.md) {
            Text(strings.privacySectionTitle)
                .font(.headline)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
            Text(strings.privacySectionSupport)
                .font(.body)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
            Toggle(strings.analyticsConsent, isOn: analyticsBinding)
            Divider()
            Toggle(strings.diagnosticsConsent, isOn: diagnosticsBinding)
            Divider()
            Toggle(strings.remoteConfigurationConsent, isOn: remoteConfigurationBinding)
            if let errorMessage {
                Text(errorMessage)
                    .font(.callout)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
                    .accessibilityLabel(errorMessage)
            }
        }
        .tint(KwaborDesignTokens.ColorToken.ink950)
        .padding(KwaborDesignTokens.Spacing.lg)
        .background(KwaborDesignTokens.ColorToken.surface0)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
    }

    private var analyticsBinding: Binding<Bool> {
        Binding(
            get: { consent.analyticsAllowed },
            set: { allowed in
                onConsentChanged(.analytics, allowed)
            }
        )
    }

    private var diagnosticsBinding: Binding<Bool> {
        Binding(
            get: { consent.diagnosticsAllowed },
            set: { allowed in
                onConsentChanged(.diagnostics, allowed)
            }
        )
    }

    private var remoteConfigurationBinding: Binding<Bool> {
        Binding(
            get: { consent.remoteConfigurationAllowed },
            set: { allowed in
                onConsentChanged(.remoteConfiguration, allowed)
            }
        )
    }
}

private struct AccountIdentitySection: View {
    let strings: SettingsStrings
    let email: String
    let authenticationMethodName: String

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.sm) {
            Text(strings.accountSectionTitle)
                .font(.headline)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
            AccountIdentityValueRow(label: strings.emailLabel, value: email)
            Divider()
            AccountIdentityValueRow(
                label: strings.authenticationMethodLabel,
                value: authenticationMethodName
            )
        }
        .padding(KwaborDesignTokens.Spacing.lg)
        .background(KwaborDesignTokens.ColorToken.surface0)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
    }
}

private struct AccountIdentityValueRow: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
            Text(label)
                .font(.body.weight(.semibold))
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
            Text(value)
                .font(.body)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                .textSelection(.enabled)
        }
        .frame(
            maxWidth: .infinity,
            minHeight: KwaborDesignTokens.Sizing.minimumAccessibleTouchTarget,
            alignment: .leading
        )
        .accessibilityElement(children: .combine)
    }
}
