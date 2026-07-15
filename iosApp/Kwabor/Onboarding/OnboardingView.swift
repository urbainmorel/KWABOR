import Shared
import SwiftUI

struct OnboardingView: View {
    @ObservedObject var coordinator: OnboardingCoordinator

    var body: some View {
        Group {
            switch coordinator.route {
            case .intro:
                IntroView(coordinator: coordinator)
            case .restoringSession:
                SessionRestoreView(bridge: coordinator.bridge)
            case .authentication:
                OnboardingLandingView(coordinator: coordinator)
            case .home:
                ContentView(
                    bridge: coordinator.bridge,
                    isGuestSession: coordinator.isGuestSession,
                    onProtectedDestinationSelected: coordinator.presentAuthentication
                )
            }
        }
        .sheet(isPresented: $coordinator.isAuthenticationPresented) {
            AuthenticationSheet(coordinator: coordinator)
        }
    }
}

private struct IntroView: View {
    @ObservedObject var coordinator: OnboardingCoordinator
    @Environment(\.accessibilityReduceMotion) private var reducedMotion

    var body: some View {
        ZStack {
            Image("IntroFallback")
                .resizable()
                .scaledToFill()
                .ignoresSafeArea()
                .accessibilityHidden(true)

            if !reducedMotion, let videoURL {
                IntroVideoPlayer(url: videoURL) {
                    coordinator.completeIntro(skipped: false)
                }
                .id(videoURL)
                .accessibilityHidden(true)
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
                if reducedMotion || videoURL == nil {
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
    }

    private var videoURL: URL? {
        coordinator.remoteIntroVideoURL ?? Bundle.main.url(
            forResource: "KwaborIntro",
            withExtension: "mp4"
        )
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
                        coordinator.presentAuthentication()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(KwaborDesignTokens.ColorToken.ink950)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)

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
    let bridge: KwaborSharedBridge

    var body: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.lg) {
            ProgressView()
            Text(bridge.appName())
                .font(.headline)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(KwaborDesignTokens.ColorToken.paper50)
    }
}
