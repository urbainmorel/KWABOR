import Shared
import SwiftUI

struct OnboardingView: View {
    @ObservedObject var coordinator: OnboardingCoordinator
    @ObservedObject var exploreStore: ExploreStore
    let favoritesStore: FavoritesStore
    @ObservedObject var searchStore: SearchStore
    let guideDiscoveryStore: GuideDiscoveryStore
    let catalogDetailStore: CatalogDetailStore
    let onViewerContextChanged: (String?) -> ViewerSessionScope
    @State private var contextualSoftWallRequest: ExploreAuthenticationRequest?

    var body: some View {
        Group {
            switch coordinator.route {
            case .intro:
                IntroView(coordinator: coordinator)
            case .restoringSession:
                SessionRestoreView(coordinator: coordinator)
            case .authentication:
                OnboardingLandingView(coordinator: coordinator)
            case .home:
                ContentView(
                    bridge: coordinator.bridge,
                    exploreStore: exploreStore,
                    favoritesStore: favoritesStore,
                    searchStore: searchStore,
                    guideDiscoveryStore: guideDiscoveryStore,
                    catalogDetailStore: catalogDetailStore,
                    isGuestSession: coordinator.isGuestSession,
                    pendingProtectedDestinationKey: coordinator.pendingProtectedDestinationKey,
                    strings: coordinator.strings,
                    settingsStrings: coordinator.strings.settings,
                    accountEmail: coordinator.accountSettingsSession?.email,
                    accountAuthenticationMethod: coordinator.accountSettingsSession?.authenticationMethod,
                    observabilityConsent: coordinator.observabilityConsent,
                    observabilityConsentErrorMessage: coordinator.observabilityConsentErrorMessage,
                    accountSessionIdentity: coordinator.accountSettingsSession?.userId,
                    accountSecurityController: coordinator.authController,
                    federatedIdentityHintStore: coordinator.federatedIdentityHintStore,
                    latestAccountSecurityError: { coordinator.authState?.errorMessage },
                    isSigningOutAccount: coordinator.isSigningOutAccount,
                    accountSignOutErrorMessage: coordinator.accountSignOutErrorMessage,
                    onProtectedDestinationSelected: {
                        coordinator.presentAuthentication(forProtectedDestinationKey: $0)
                    },
                    onPendingProtectedDestinationConsumed: coordinator.consumePendingProtectedDestination,
                    onExploreAuthenticationRequired: presentContextualSoftWall,
                    onSignOut: {
                        coordinator.signOutCurrentAccount()
                        if coordinator.isSigningOutAccount {
                            applyViewerContext(nil)
                        }
                    },
                    onDismissSignOutError: coordinator.clearAccountSignOutError,
                    onAccountDeletionWillStart: coordinator.prepareForAccountDeletion,
                    onAccountDeletionStateChanged: {
                        coordinator.accountDeletionStateChanged(isInProgress: $0)
                        applyViewerContext(
                            $0 ? nil : coordinator.exploreViewerID
                        )
                    },
                    onAccountDeleted: {
                        coordinator.accountDeletionCompleted()
                        applyViewerContext(nil)
                    },
                    onObservabilityConsentChanged: coordinator.updateObservabilityConsent,
                    rootDeepLinkDelivery: coordinator.pendingRootDeepLinkDelivery,
                    onProtectedRootDeepLinkTransferred: {
                        coordinator.transferRootDeepLinkToProtectedAuthentication($0)
                    },
                    onRootDeepLinkAcknowledged: {
                        coordinator.acknowledgeRootDeepLink(delivery: $0)
                    },
                    catalogDetailDeepLinkDelivery: coordinator.catalogDetailDeepLinkDeliveryReadyForOpening,
                    isCatalogDetailDeepLinkCurrent: {
                        coordinator.isCurrentCatalogDetailDeepLink(delivery: $0)
                    },
                    onCatalogDetailDeepLinkAcknowledged: {
                        coordinator.acknowledgeCatalogDetailDeepLink(delivery: $0)
                    }
                )
            }
        }
        .sheet(
            isPresented: $coordinator.isAuthenticationPresented,
            onDismiss: coordinator.authenticationPresentationDismissed
        ) {
            AuthenticationSheet(coordinator: coordinator)
        }
        .fullScreenCover(
            isPresented: $coordinator.isRegistrationPresented,
            onDismiss: coordinator.registrationPresentationDismissed
        ) {
            RegistrationFlowView(coordinator: coordinator)
        }
        .fullScreenCover(isPresented: $coordinator.isPromoterActivationPresented) {
            if let context = coordinator.promoterActivationContext {
                PromoterActivationView(
                    context: context,
                    controller: coordinator.authController,
                    strings: coordinator.strings,
                    identityHintStore: coordinator.federatedIdentityHintStore,
                    latestAuthError: { coordinator.authState?.errorMessage },
                    onActivated: coordinator.completePromoterActivation,
                    onCancel: coordinator.cancelPromoterActivation
                )
            }
        }
        .alert(
            coordinator.strings.promoterActivationTitle,
            isPresented: Binding(
                get: { coordinator.promoterActivationErrorMessage != nil },
                set: { if !$0 { coordinator.dismissPromoterActivationError() } }
            )
        ) {
            Button(coordinator.strings.authConfirm, action: coordinator.dismissPromoterActivationError)
        } message: {
            Text(coordinator.promoterActivationErrorMessage ?? coordinator.strings.authPromoterInviteInvalid)
        }
        .alert(
            coordinator.bridge.appName(),
            isPresented: Binding(
                get: { coordinator.rootNavigationNotice != nil },
                set: { if !$0 { coordinator.dismissRootNavigationNotice() } }
            )
        ) {
            Button(coordinator.strings.authConfirm, action: coordinator.dismissRootNavigationNotice)
        } message: {
            Text(coordinator.rootNavigationNotice ?? "")
        }
        .onAppear {
            applyViewerContext(coordinator.exploreViewerID)
        }
        .onChange(of: coordinator.exploreViewerID) { _, viewerID in
            applyViewerContext(viewerID)
        }
        .onChange(of: coordinator.isSigningOutAccount) { _, isSigningOut in
            applyViewerContext(
                isSigningOut ? nil : coordinator.exploreViewerID
            )
        }
        .onChange(of: exploreStore.authenticationRequest?.id) { _, requestID in
            if requestID == nil {
                contextualSoftWallRequest = nil
            }
        }
        .onChange(of: coordinator.contextualAuthenticationCancellationRevision) { _, _ in
            contextualSoftWallRequest = nil
            exploreStore.clearPendingAuthentication()
        }
        .overlay {
            if let request = contextualSoftWallRequest,
               !coordinator.isAuthenticationPresented,
               !coordinator.isRegistrationPresented {
                ContextualSoftWallView(
                    request: request,
                    coordinator: coordinator,
                    exploreStrings: exploreStore.strings,
                    onRegistrationSelected: {
                        if coordinator.presentContextualRegistration(
                            suggestedCityId: request.suggestedCityID
                        ) {
                            contextualSoftWallRequest = nil
                        }
                    },
                    onAuthenticationSelected: {
                        if coordinator.presentContextualAuthentication(
                            suggestedCityId: request.suggestedCityID
                        ) {
                            contextualSoftWallRequest = nil
                        }
                    },
                    onFederatedAuthenticationSubmitted: {
                        contextualSoftWallRequest = nil
                    },
                    onLater: {
                        contextualSoftWallRequest = nil
                        coordinator.cancelContextualSoftWall()
                        exploreStore.clearPendingAuthentication()
                    }
                )
                .id(request.id)
            }
        }
    }

    private func presentContextualSoftWall(_ request: ExploreAuthenticationRequest) {
        contextualSoftWallRequest = request
    }

    private func applyViewerContext(_ accountID: String?) {
        exploreStore.prepareViewerContext(accountID)
        favoritesStore.prepareViewerContext(accountID)
        let scope = onViewerContextChanged(accountID)
        exploreStore.commitViewerScope(scope)
        favoritesStore.commitViewerScope(scope)
    }
}

private struct ContextualSoftWallView: View {
    let request: ExploreAuthenticationRequest
    @ObservedObject var coordinator: OnboardingCoordinator
    let exploreStrings: KwaborStrings
    let onRegistrationSelected: () -> Void
    let onAuthenticationSelected: () -> Void
    let onFederatedAuthenticationSubmitted: () -> Void
    let onLater: () -> Void
    @StateObject private var federatedStore: FederatedSignInStore

    init(
        request: ExploreAuthenticationRequest,
        coordinator: OnboardingCoordinator,
        exploreStrings: KwaborStrings,
        onRegistrationSelected: @escaping () -> Void,
        onAuthenticationSelected: @escaping () -> Void,
        onFederatedAuthenticationSubmitted: @escaping () -> Void,
        onLater: @escaping () -> Void
    ) {
        self.request = request
        self.coordinator = coordinator
        self.exploreStrings = exploreStrings
        self.onRegistrationSelected = onRegistrationSelected
        self.onAuthenticationSelected = onAuthenticationSelected
        self.onFederatedAuthenticationSubmitted = onFederatedAuthenticationSubmitted
        self.onLater = onLater
        _federatedStore = StateObject(
            wrappedValue: FederatedSignInStore(
                strings: coordinator.strings,
                presenterProvider: WindowScenePresentingViewControllerProvider(),
                identityHintStore: coordinator.federatedIdentityHintStore,
                attemptPreflight: {
                    coordinator.prepareContextualFederatedAuthentication(
                        suggestedCityId: request.suggestedCityID
                    )
                },
                onCredential: { credential, completion in
                    coordinator.signInWithFederatedCredential(credential) { completed in
                        if completed {
                            onFederatedAuthenticationSubmitted()
                        }
                        completion(completed)
                    }
                }
            )
        )
    }

    var body: some View {
        ZStack {
            KwaborDesignTokens.ColorToken.ink950
                .opacity(KwaborDesignTokens.Alpha.scrimHigh)
                .ignoresSafeArea()
            VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.lg) {
                Text(actionTitle)
                    .font(.title2.bold())
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                Text(exploreStrings.signInRequiredForInteraction)
                    .font(.body)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                FederatedSignInButtons(store: federatedStore, isDisabled: federatedStore.isLoading)
                Button(coordinator.strings.signUp, action: onRegistrationSelected)
                    .buttonStyle(.borderedProminent)
                    .tint(KwaborDesignTokens.ColorToken.ink950)
                    .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                    .disabled(federatedStore.isLoading)
                Button(coordinator.strings.signIn, action: onAuthenticationSelected)
                    .buttonStyle(.bordered)
                    .tint(KwaborDesignTokens.ColorToken.ink950)
                    .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                    .disabled(federatedStore.isLoading)
                Button(coordinator.strings.registrationLater, action: onLater)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                    .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                    .disabled(federatedStore.isLoading)
            }
            .padding(KwaborDesignTokens.Spacing.xxl)
            .background(KwaborDesignTokens.ColorToken.surface0)
            .clipShape(RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.card))
            .padding(KwaborDesignTokens.Spacing.xxl)
            .frame(maxWidth: contextualSoftWallMaxWidth)
            .accessibilityElement(children: .contain)
        }
    }

    private var actionTitle: String {
        switch request.action {
        case .like:
            exploreStrings.like
        case .favorite:
            exploreStrings.favorite
        }
    }
}

private let contextualSoftWallMaxWidth: CGFloat = 520

private struct IntroView: View {
    @ObservedObject var coordinator: OnboardingCoordinator
    @Environment(\.accessibilityReduceMotion) private var reducedMotion
    @State private var isVideoReadyForDisplay = false

    var body: some View {
        ZStack {
            if reducedMotion || coordinator.introVideoURL == nil {
                IntroStaticFallbackView()
            } else if let videoURL = coordinator.introVideoURL {
                IntroVideoPlayer(
                    url: videoURL,
                    onReadyForDisplay: {
                        isVideoReadyForDisplay = true
                    },
                    onCompleted: {
                        coordinator.completeIntro(skipped: false)
                    },
                    onFailed: {
                        isVideoReadyForDisplay = false
                        coordinator.introPlaybackFailed()
                    }
                )
                .id(videoURL)
                .accessibilityHidden(true)

                if !isVideoReadyForDisplay {
                    LaunchWordmarkContinuityView()
                }
            }

            KwaborDesignTokens.ColorToken.ink950
                .opacity(KwaborDesignTokens.Alpha.scrimHigh)
                .ignoresSafeArea()
                .accessibilityHidden(true)

            VStack {
                HStack {
                    Button(coordinator.strings.introSkip) {
                        coordinator.completeIntro(skipped: true)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(KwaborDesignTokens.ColorToken.ink950)
                    Spacer()
                }
                Spacer()
            }
            .padding(KwaborDesignTokens.Spacing.xxl)
            OnboardingEntryActions(coordinator: coordinator)
        }
        .onAppear { coordinator.introDisplayed() }
        .onChange(of: coordinator.introVideoURL) { _, _ in
            isVideoReadyForDisplay = false
        }
        .onChange(of: reducedMotion) { _, _ in
            isVideoReadyForDisplay = false
        }
    }
}

private struct LaunchWordmarkContinuityView: View {
    var body: some View {
        ZStack {
            Color("LaunchBackground")
                .ignoresSafeArea()
            Image("LaunchWordmark")
                .resizable()
                .scaledToFit()
                .padding(.horizontal, KwaborDesignTokens.Spacing.xxl)
        }
        .accessibilityHidden(true)
    }
}

private struct IntroStaticFallbackView: View {
    var body: some View {
        Image("IntroFallback")
            .resizable()
            .scaledToFill()
            .ignoresSafeArea()
            .accessibilityHidden(true)
    }
}

private struct OnboardingLandingView: View {
    @ObservedObject var coordinator: OnboardingCoordinator

    var body: some View {
        ZStack {
            Image("IntroFallback")
                .resizable()
                .scaledToFill()
                .ignoresSafeArea()
                .accessibilityHidden(true)
            KwaborDesignTokens.ColorToken.ink950
                .opacity(KwaborDesignTokens.Alpha.scrimHigh)
                .ignoresSafeArea()
                .accessibilityHidden(true)

            OnboardingEntryActions(coordinator: coordinator)
        }
    }
}

private struct OnboardingEntryActions: View {
    @ObservedObject var coordinator: OnboardingCoordinator

    var body: some View {
        VStack {
            HStack {
                Spacer()
                Text(coordinator.strings.languageLabel)
                    .font(.headline)
                    .foregroundStyle(.white)
            }
            Spacer()
            VStack(spacing: KwaborDesignTokens.Spacing.lg) {
                Text(coordinator.strings.title)
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white)
                Text(coordinator.strings.subtitle)
                    .font(.title3)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white)
                Button(coordinator.strings.signUp) {
                    coordinator.presentRegistration()
                }
                .buttonStyle(.borderedProminent)
                .tint(KwaborDesignTokens.ColorToken.ink950)
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                .disabled(coordinator.requiresProtectedAuthentication)

                Button(coordinator.strings.signIn) {
                    coordinator.presentAuthentication()
                }
                .buttonStyle(.bordered)
                .tint(.white)
                .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)

                Button(coordinator.strings.continueWithoutAccount) {
                    coordinator.requestGuestAccess()
                }
                .foregroundStyle(.white)
                .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
                .disabled(coordinator.requiresProtectedAuthentication)
            }
        }
        .padding(KwaborDesignTokens.Spacing.xxl)
        .alert(
            coordinator.strings.continueWithoutAccount,
            isPresented: guestDisclosureBinding
        ) {
            Button(coordinator.strings.guestCancel, role: .cancel) {
                coordinator.cancelGuestAccess()
            }
            Button(coordinator.strings.guestConfirm) {
                coordinator.confirmGuestAccess()
            }
        } message: {
            Text(coordinator.strings.guestDisclosure)
        }
    }

    private var guestDisclosureBinding: Binding<Bool> {
        Binding(
            get: { coordinator.isGuestDisclosurePresented },
            set: { isPresented in
                if !isPresented {
                    coordinator.cancelGuestAccess()
                }
            }
        )
    }
}

private struct SessionRestoreView: View {
    @ObservedObject var coordinator: OnboardingCoordinator

    var body: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.lg) {
            if coordinator.sessionRestoreFailed {
                Image(systemName: "exclamationmark.shield")
                    .font(.largeTitle)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
                    .accessibilityHidden(true)
                Text(coordinator.observabilityConsentErrorMessage ?? coordinator.strings.authUnavailable)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                Button(coordinator.strings.retry) {
                    coordinator.retrySessionRestore()
                }
                .buttonStyle(.borderedProminent)
                .tint(KwaborDesignTokens.ColorToken.ink950)
                .frame(minHeight: KwaborDesignTokens.Sizing.touchTarget)
            } else {
                ProgressView()
                    .accessibilityLabel(coordinator.bridge.appName())
                Text(coordinator.bridge.appName())
                    .font(.headline)
            }
        }
        .padding(KwaborDesignTokens.Spacing.xxl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(KwaborDesignTokens.ColorToken.paper50)
    }
}
