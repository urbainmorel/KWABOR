import Shared
import SwiftUI

struct ContentView: View {
    let bridge: KwaborSharedBridge
    let isGuestSession: Bool
    let strings: OnboardingStrings
    let isSigningOutAccount: Bool
    let accountSignOutErrorMessage: String?
    let onProtectedDestinationSelected: () -> Void
    let onSignOut: () -> Void
    let onDismissSignOutError: () -> Void
    @State private var selectedDestination = RootDestination.home

    init(
        bridge: KwaborSharedBridge,
        isGuestSession: Bool = false,
        strings: OnboardingStrings? = nil,
        isSigningOutAccount: Bool = false,
        accountSignOutErrorMessage: String? = nil,
        onProtectedDestinationSelected: @escaping () -> Void = {},
        onSignOut: @escaping () -> Void = {},
        onDismissSignOutError: @escaping () -> Void = {}
    ) {
        self.bridge = bridge
        self.isGuestSession = isGuestSession
        self.strings = strings ?? bridge.onboardingStrings()
        self.isSigningOutAccount = isSigningOutAccount
        self.accountSignOutErrorMessage = accountSignOutErrorMessage
        self.onProtectedDestinationSelected = onProtectedDestinationSelected
        self.onSignOut = onSignOut
        self.onDismissSignOutError = onDismissSignOutError
    }

    var body: some View {
        TabView(selection: destinationBinding) {
            ForEach(RootDestination.allCases) { destination in
                NavigationStack {
                    RootDestinationContent(
                        destination: destination,
                        bridge: bridge,
                        strings: strings,
                        isSigningOutAccount: isSigningOutAccount,
                        accountSignOutErrorMessage: accountSignOutErrorMessage,
                        onSignOut: onSignOut,
                        onDismissSignOutError: onDismissSignOutError
                    )
                }
                .tabItem {
                    Label(destination.label(using: bridge), systemImage: destination.systemImage)
                }
                .tag(destination)
            }
        }
        .onOpenURL { url in
            guard let routeKey = bridge.rootDestinationKeyForDeepLink(rawUrl: url.absoluteString),
                  let destination = RootDestination(rawValue: routeKey) else {
                return
            }
            requestDestination(destination)
        }
        .onChange(of: isGuestSession) { _, isGuest in
            if isGuest {
                selectedDestination = .home
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
        guard destination == .home || !isGuestSession else {
            onProtectedDestinationSelected()
            return
        }
        selectedDestination = destination
    }
}

private struct RootDestinationContent: View {
    let destination: RootDestination
    let bridge: KwaborSharedBridge
    let strings: OnboardingStrings
    let isSigningOutAccount: Bool
    let accountSignOutErrorMessage: String?
    let onSignOut: () -> Void
    let onDismissSignOutError: () -> Void

    var body: some View {
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
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        .padding(KwaborDesignTokens.Spacing.xxl)
        .background(KwaborDesignTokens.ColorToken.paper50)
        .navigationTitle(destination.label(using: bridge))
    }

    private var title: String {
        destination == .home ? bridge.homeTitle() : destination.label(using: bridge)
    }
}

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
