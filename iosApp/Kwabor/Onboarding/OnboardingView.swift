import Shared
import SwiftUI

struct OnboardingView: View {
    @ObservedObject var coordinator: OnboardingCoordinator
    let exploreStore: ExploreStore
    let guideDiscoveryStore: GuideDiscoveryStore
    let catalogDetailStore: CatalogDetailStore

    var body: some View {
        Group {
            switch coordinator.route {
            case .intro:
                IntroView(coordinator: coordinator)
            case .restoringSession:
                SessionRestoreView(coordinator: coordinator)
            case .authentication:
                OnboardingLandingView(coordinator: coordinator)
            case .notificationPriming:
                RestoredSessionNotificationPrimingView(coordinator: coordinator)
            case .home:
                ContentView(
                    bridge: coordinator.bridge,
                    exploreStore: exploreStore,
                    guideDiscoveryStore: guideDiscoveryStore,
                    catalogDetailStore: catalogDetailStore,
                    isGuestSession: coordinator.isGuestSession,
                    strings: coordinator.strings,
                    settingsStrings: coordinator.strings.settings,
                    accountEmail: coordinator.accountSettingsSession?.email,
                    accountAuthenticationMethod: coordinator.accountSettingsSession?.authenticationMethod,
                    accountSessionIdentity: coordinator.accountSettingsSession?.userId,
                    accountSecurityController: coordinator.authController,
                    federatedIdentityHintStore: coordinator.federatedIdentityHintStore,
                    latestAccountSecurityError: { coordinator.authState?.errorMessage },
                    isSigningOutAccount: coordinator.isSigningOutAccount,
                    accountSignOutErrorMessage: coordinator.accountSignOutErrorMessage,
                    onProtectedDestinationSelected: coordinator.presentAuthentication,
                    onSignOut: coordinator.signOutCurrentAccount,
                    onDismissSignOutError: coordinator.clearAccountSignOutError,
                    onAccountDeletionStateChanged: {
                        coordinator.accountDeletionStateChanged(isInProgress: $0)
                    },
                    onAccountDeleted: coordinator.accountDeletionCompleted,
                    rootDeepLinkDestinationKey: coordinator.pendingRootDeepLinkDestinationKey,
                    onRootDeepLinkConsumed: coordinator.consumeRootDeepLinkDestination,
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
        .onAppear {
            exploreStore.updateViewerContext(coordinator.exploreViewerID)
        }
        .onChange(of: coordinator.exploreViewerID) { _, viewerID in
            exploreStore.updateViewerContext(viewerID)
        }
    }
}

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

            VStack {
                HStack {
                    Spacer()
                    Button(coordinator.strings.introSkip) {
                        coordinator.completeIntro(skipped: true)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(KwaborDesignTokens.ColorToken.ink950)
                }
                Spacer()
                if reducedMotion || coordinator.introVideoURL == nil {
                    Button(coordinator.strings.introContinue) {
                        coordinator.completeIntro(skipped: false)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(KwaborDesignTokens.ColorToken.ink950)
                }
            }
            .padding(KwaborDesignTokens.Spacing.xxl)
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
        }
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
                Text(coordinator.strings.authUnavailable)
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
