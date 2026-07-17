import SwiftUI

struct AuthenticationSheet: View {
    @ObservedObject var coordinator: OnboardingCoordinator

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(coordinator.strings.authSubtitle)
                        .foregroundStyle(.secondary)
                }
                Section {
                    Button(coordinator.strings.signUp) {
                        coordinator.presentRegistrationFromAuthentication()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(KwaborDesignTokens.ColorToken.ink950)
                    .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)

                    Button(coordinator.strings.signIn) {
                        coordinator.presentRegistrationFromAuthentication()
                    }
                    .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)

                    Button(coordinator.strings.authContinueAsGuest, action: continueAsGuest)
                        .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                }
            }
            .navigationTitle(coordinator.strings.authTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(coordinator.strings.guestCancel, action: coordinator.dismissAuthentication)
                }
            }
        }
    }

    private func continueAsGuest() {
        coordinator.dismissAuthentication()
        coordinator.requestGuestAccess()
    }
}
