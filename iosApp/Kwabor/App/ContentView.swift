import Shared
import SwiftUI

struct ContentView: View {
    let bridge: KwaborSharedBridge
    let isGuestSession: Bool
    let strings: OnboardingStrings
    let accountSecurityController: IosAuthController?
    let federatedIdentityHintStore: FederatedIdentityHintPersisting?
    let latestAccountSecurityError: () -> String?
    let isSigningOutAccount: Bool
    let accountSignOutErrorMessage: String?
    let onProtectedDestinationSelected: () -> Void
    let onSignOut: () -> Void
    let onDismissSignOutError: () -> Void
    let onAccountDeletionStateChanged: (Bool) -> Void
    let onAccountDeleted: () -> Void
    let rootDeepLinkDestinationKey: String?
    let onRootDeepLinkConsumed: () -> Void
    @State private var selectedDestination = RootDestination.home

    init(
        bridge: KwaborSharedBridge,
        isGuestSession: Bool = false,
        strings: OnboardingStrings? = nil,
        accountSecurityController: IosAuthController? = nil,
        federatedIdentityHintStore: FederatedIdentityHintPersisting? = nil,
        latestAccountSecurityError: @escaping () -> String? = { nil },
        isSigningOutAccount: Bool = false,
        accountSignOutErrorMessage: String? = nil,
        onProtectedDestinationSelected: @escaping () -> Void = {},
        onSignOut: @escaping () -> Void = {},
        onDismissSignOutError: @escaping () -> Void = {},
        onAccountDeletionStateChanged: @escaping (Bool) -> Void = { _ in },
        onAccountDeleted: @escaping () -> Void = {},
        rootDeepLinkDestinationKey: String? = nil,
        onRootDeepLinkConsumed: @escaping () -> Void = {}
    ) {
        self.bridge = bridge
        self.isGuestSession = isGuestSession
        self.strings = strings ?? bridge.onboardingStrings()
        self.accountSecurityController = accountSecurityController
        self.federatedIdentityHintStore = federatedIdentityHintStore
        self.latestAccountSecurityError = latestAccountSecurityError
        self.isSigningOutAccount = isSigningOutAccount
        self.accountSignOutErrorMessage = accountSignOutErrorMessage
        self.onProtectedDestinationSelected = onProtectedDestinationSelected
        self.onSignOut = onSignOut
        self.onDismissSignOutError = onDismissSignOutError
        self.onAccountDeletionStateChanged = onAccountDeletionStateChanged
        self.onAccountDeleted = onAccountDeleted
        self.rootDeepLinkDestinationKey = rootDeepLinkDestinationKey
        self.onRootDeepLinkConsumed = onRootDeepLinkConsumed
    }

    var body: some View {
        TabView(selection: destinationBinding) {
            ForEach(RootDestination.allCases) { destination in
                NavigationStack {
                    RootDestinationContent(
                        destination: destination,
                        bridge: bridge,
                        strings: strings,
                        accountSecurityController: accountSecurityController,
                        federatedIdentityHintStore: federatedIdentityHintStore,
                        latestAccountSecurityError: latestAccountSecurityError,
                        isSigningOutAccount: isSigningOutAccount,
                        accountSignOutErrorMessage: accountSignOutErrorMessage,
                        onSignOut: onSignOut,
                        onDismissSignOutError: onDismissSignOutError,
                        onAccountDeletionStateChanged: onAccountDeletionStateChanged,
                        onAccountDeleted: onAccountDeleted
                    )
                }
                .tabItem {
                    Label(destination.label(using: bridge), systemImage: destination.systemImage)
                }
                .tag(destination)
            }
        }
        .onAppear(perform: applyPendingRootDeepLink)
        .onChange(of: rootDeepLinkDestinationKey) { _, _ in applyPendingRootDeepLink() }
        .onChange(of: isGuestSession) { _, isGuest in
            if isGuest {
                selectedDestination = .home
            } else {
                applyPendingRootDeepLink()
            }
        }
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
}

private struct RootDestinationContent: View {
    let destination: RootDestination
    let bridge: KwaborSharedBridge
    let strings: OnboardingStrings
    let accountSecurityController: IosAuthController?
    let federatedIdentityHintStore: FederatedIdentityHintPersisting?
    let latestAccountSecurityError: () -> String?
    let isSigningOutAccount: Bool
    let accountSignOutErrorMessage: String?
    let onSignOut: () -> Void
    let onDismissSignOutError: () -> Void
    let onAccountDeletionStateChanged: (Bool) -> Void
    let onAccountDeleted: () -> Void

    var body: some View {
        Group {
            if destination == .profile {
                ScrollView {
                    destinationContent
                        .frame(maxWidth: profileContentMaxWidth, alignment: .leading)
                        .frame(maxWidth: .infinity, alignment: .top)
                        .padding(KwaborDesignTokens.Spacing.xxl)
                }
            } else {
                destinationContent
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
                    .padding(KwaborDesignTokens.Spacing.xxl)
            }
        }
        .background(KwaborDesignTokens.ColorToken.paper50)
        .navigationTitle(destination.label(using: bridge))
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

            if destination == .profile {
                AccountSessionSection(
                    strings: strings,
                    isSigningOut: isSigningOutAccount,
                    errorMessage: accountSignOutErrorMessage,
                    onSignOut: onSignOut,
                    onDismissError: onDismissSignOutError
                )
                if let accountSecurityController, let federatedIdentityHintStore {
                    AccountDeletionSection(
                        controller: accountSecurityController,
                        strings: strings,
                        identityHintStore: federatedIdentityHintStore,
                        latestAuthError: latestAccountSecurityError,
                        onDeletionStateChanged: onAccountDeletionStateChanged,
                        onDeleted: onAccountDeleted
                    )
                }
            }
        }
    }

    private var title: String {
        destination == .home ? bridge.homeTitle() : destination.label(using: bridge)
    }
}

private let profileContentMaxWidth: CGFloat = 560

private struct AccountSessionSection: View {
    let strings: OnboardingStrings
    let isSigningOut: Bool
    let errorMessage: String?
    let onSignOut: () -> Void
    let onDismissError: () -> Void
    @State private var isConfirmationPresented = false

    var body: some View {
        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.lg) {
            Text(strings.authAccount)
                .font(.headline)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)

            Button(role: .destructive) {
                isConfirmationPresented = true
            } label: {
                HStack {
                    Text(strings.authSignOut)
                    Spacer()
                    if isSigningOut {
                        ProgressView()
                            .accessibilityLabel(strings.authSignOut)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
            }
            .disabled(isSigningOut)

            if let errorMessage {
                Text(errorMessage)
                    .font(.callout)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
                    .accessibilityLabel(errorMessage)
                    .onDisappear(perform: onDismissError)
            }
        }
        .padding(KwaborDesignTokens.Spacing.lg)
        .background(KwaborDesignTokens.ColorToken.surface0)
        .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
        .alert(strings.authSignOutTitle, isPresented: $isConfirmationPresented) {
            Button(strings.authCancel, role: .cancel) {}
            Button(strings.authConfirm, role: .destructive, action: onSignOut)
        } message: {
            Text(strings.authSignOutConfirmation)
        }
    }
}

#Preview {
    ContentView(bridge: KwaborSharedBridge())
}
