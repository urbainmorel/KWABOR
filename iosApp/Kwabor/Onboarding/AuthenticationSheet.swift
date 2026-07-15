import Shared
import SwiftUI

struct AuthenticationSheet: View {
    @ObservedObject var coordinator: OnboardingCoordinator

    var body: some View {
        NavigationStack {
            Group {
                if let state = coordinator.authState {
                    AuthenticationForm(
                        state: state,
                        strings: coordinator.strings,
                        controller: coordinator.authController,
                        onContinueAsGuest: continueAsGuest
                    )
                } else {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .navigationTitle(coordinator.strings.authTitle)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(coordinator.strings.guestCancel) {
                        coordinator.dismissAuthentication()
                    }
                }
            }
        }
    }

    private func continueAsGuest() {
        coordinator.dismissAuthentication()
        coordinator.requestGuestAccess()
    }
}

private struct AuthenticationForm: View {
    let state: AuthUiState
    let strings: OnboardingStrings
    let controller: IosAuthController
    let onContinueAsGuest: () -> Void

    var body: some View {
        Form {
            Section {
                Text(strings.authSubtitle)
                    .foregroundStyle(.secondary)
                messageViews
            }
            Section {
                TextField(strings.authEmail, text: emailBinding)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.emailAddress)
                    .textContentType(.emailAddress)
                    .disabled(state.isLoading)
                if state.step == .otp {
                    TextField(strings.authFirstName, text: firstNameBinding)
                        .textContentType(.givenName)
                        .disabled(state.isLoading)
                    TextField(strings.authLastName, text: lastNameBinding)
                        .textContentType(.familyName)
                        .disabled(state.isLoading)
                    TextField(strings.authOtpCode, text: otpBinding)
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        .disabled(state.isLoading)
                    Toggle(strings.authLegalAcceptance, isOn: legalBinding)
                        .disabled(state.isLoading)
                }
            }
            Section {
                Button(actionLabel) {
                    submit()
                }
                .disabled(state.isLoading)
                .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)

                Button(strings.authContinueAsGuest, action: onContinueAsGuest)
                    .disabled(state.isLoading)
                    .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
            }
        }
    }

    @ViewBuilder
    private var messageViews: some View {
        if let notice = state.noticeMessage {
            Text(notice)
                .foregroundStyle(.secondary)
        }
        if let error = state.errorMessage {
            Text(error)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
                .accessibilityLabel(error)
        }
        if state.isLoading {
            ProgressView()
        }
    }

    private var actionLabel: String {
        state.step == .email ? strings.authRequestOtp : strings.authVerifyOtp
    }

    private var emailBinding: Binding<String> {
        Binding(
            get: { state.email },
            set: { controller.updateEmail(email: $0) }
        )
    }

    private var firstNameBinding: Binding<String> {
        Binding(
            get: { state.firstName },
            set: { controller.updateFirstName(firstName: $0) }
        )
    }

    private var lastNameBinding: Binding<String> {
        Binding(
            get: { state.lastName },
            set: { controller.updateLastName(lastName: $0) }
        )
    }

    private var otpBinding: Binding<String> {
        Binding(
            get: { state.otpCode },
            set: { controller.updateOtpCode(otpCode: $0) }
        )
    }

    private var legalBinding: Binding<Bool> {
        Binding(
            get: { state.legalAccepted },
            set: { controller.updateLegalAccepted(accepted: $0) }
        )
    }

    private func submit() {
        switch state.step {
        case .email:
            controller.requestEmailOtp()
        case .otp:
            controller.verifyEmailOtp()
        }
    }
}
