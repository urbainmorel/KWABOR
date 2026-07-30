import Shared
import SwiftUI

struct ContentView: View {
    let bridge: KwaborSharedBridge
    let exploreStore: ExploreStore
    let guideDiscoveryStore: GuideDiscoveryStore
    @ObservedObject var catalogDetailStore: CatalogDetailStore
    let isGuestSession: Bool
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
    let onProtectedDestinationSelected: () -> Void
    let onExploreAuthenticationRequired: (ExploreAuthenticationRequest) -> Void
    let onSignOut: () -> Void
    let onDismissSignOutError: () -> Void
    let onAccountDeletionWillStart: () -> Bool
    let onAccountDeletionStateChanged: (Bool) -> Void
    let onAccountDeleted: () -> Void
    let onObservabilityConsentChanged: (ObservabilityConsentCategory, Bool) -> Void
    let rootDeepLinkDestinationKey: String?
    let onRootDeepLinkConsumed: () -> Void
    let catalogDetailDeepLinkDelivery: CatalogDetailDeepLinkDelivery?
    let isCatalogDetailDeepLinkCurrent: (CatalogDetailDeepLinkDelivery) -> Bool
    let onCatalogDetailDeepLinkAcknowledged: (CatalogDetailDeepLinkDelivery) -> Bool
    @State private var selectedDestination = RootDestination.home

    init(
        bridge: KwaborSharedBridge,
        exploreStore: ExploreStore,
        guideDiscoveryStore: GuideDiscoveryStore,
        catalogDetailStore: CatalogDetailStore,
        isGuestSession: Bool = false,
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
        onProtectedDestinationSelected: @escaping () -> Void = {},
        onExploreAuthenticationRequired: @escaping (ExploreAuthenticationRequest) -> Void = { _ in },
        onSignOut: @escaping () -> Void = {},
        onDismissSignOutError: @escaping () -> Void = {},
        onAccountDeletionWillStart: @escaping () -> Bool = { true },
        onAccountDeletionStateChanged: @escaping (Bool) -> Void = { _ in },
        onAccountDeleted: @escaping () -> Void = {},
        onObservabilityConsentChanged: @escaping (ObservabilityConsentCategory, Bool) -> Void = { _, _ in },
        rootDeepLinkDestinationKey: String? = nil,
        onRootDeepLinkConsumed: @escaping () -> Void = {},
        catalogDetailDeepLinkDelivery: CatalogDetailDeepLinkDelivery? = nil,
        isCatalogDetailDeepLinkCurrent: @escaping (CatalogDetailDeepLinkDelivery) -> Bool = { _ in false },
        onCatalogDetailDeepLinkAcknowledged: @escaping (CatalogDetailDeepLinkDelivery) -> Bool = { _ in false }
    ) {
        self.bridge = bridge
        self.exploreStore = exploreStore
        self.guideDiscoveryStore = guideDiscoveryStore
        self.catalogDetailStore = catalogDetailStore
        self.isGuestSession = isGuestSession
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
        self.onExploreAuthenticationRequired = onExploreAuthenticationRequired
        self.onSignOut = onSignOut
        self.onDismissSignOutError = onDismissSignOutError
        self.onAccountDeletionWillStart = onAccountDeletionWillStart
        self.onAccountDeletionStateChanged = onAccountDeletionStateChanged
        self.onAccountDeleted = onAccountDeleted
        self.onObservabilityConsentChanged = onObservabilityConsentChanged
        self.rootDeepLinkDestinationKey = rootDeepLinkDestinationKey
        self.onRootDeepLinkConsumed = onRootDeepLinkConsumed
        self.catalogDetailDeepLinkDelivery = catalogDetailDeepLinkDelivery
        self.isCatalogDetailDeepLinkCurrent = isCatalogDetailDeepLinkCurrent
        self.onCatalogDetailDeepLinkAcknowledged = onCatalogDetailDeepLinkAcknowledged
    }

    var body: some View {
        GeometryReader { proxy in
            TabView(selection: destinationBinding) {
                ForEach(RootDestination.allCases) { destination in
                    NavigationStack {
                        RootDestinationContent(
                            destination: destination,
                            bridge: bridge,
                            exploreStore: exploreStore,
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
                            onProtectedDestinationSelected: onProtectedDestinationSelected,
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
            .sheet(isPresented: catalogDetailPresentationBinding) {
                CatalogDetailSheet(store: catalogDetailStore)
                    .presentationDetents([
                        .fraction(
                            CatalogDetailLayoutPolicy.sheetHeightFraction(
                                forWidth: proxy.size.width
                            )
                        ),
                    ])
                    .presentationContentInteraction(.scrolls)
            }
            .onAppear(perform: applyPendingRootDeepLink)
            .onAppear(perform: applyPendingCatalogDetailDeepLink)
            .onChange(of: rootDeepLinkDestinationKey) { _, _ in
                applyPendingRootDeepLink()
            }
            .onChange(of: catalogDetailDeepLinkDelivery) { _, _ in
                applyPendingCatalogDetailDeepLink()
            }
            .onChange(of: isGuestSession) { _, isGuest in
                if isGuest {
                    selectedDestination = .home
                } else {
                    applyPendingRootDeepLink()
                }
            }
            .onDisappear(perform: catalogDetailStore.dismiss)
        }
    }

    private var catalogDetailPresentationBinding: Binding<Bool> {
        Binding(
            get: { catalogDetailStore.isPresented },
            set: { isPresented in
                if !isPresented {
                    catalogDetailStore.dismiss()
                }
            }
        )
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
        guard destination == .home || !isGuestSession else {
            onProtectedDestinationSelected()
            return false
        }
        selectedDestination = destination
        return true
    }

    private func applyPendingRootDeepLink() {
        guard let rootDeepLinkDestinationKey,
              let destination = RootDestination(rawValue: rootDeepLinkDestinationKey) else {
            return
        }
        if selectDestinationIfAllowed(destination) {
            onRootDeepLinkConsumed()
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
    let onProtectedDestinationSelected: () -> Void
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
                    guideDiscoveryStore: guideDiscoveryStore,
                    onListingOpen: onListingOpen,
                    onAuthenticationRequired: onExploreAuthenticationRequired
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
