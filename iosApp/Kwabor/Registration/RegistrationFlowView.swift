import Foundation
import Shared
import SwiftUI

struct RegistrationFlowView: View {
    @ObservedObject private var coordinator: OnboardingCoordinator
    @StateObject private var store: RegistrationStore
    @StateObject private var federatedStore: FederatedSignInStore

    init(coordinator: OnboardingCoordinator) {
        self.coordinator = coordinator
        _store = StateObject(
            wrappedValue: RegistrationStore(
                controller: coordinator.registrationController,
                strings: coordinator.strings,
                interruptedAuthJourneyStore: coordinator.interruptedAuthJourneyStore,
                onCompleted: coordinator.completeRegistration,
                onExistingAccountAuthenticated: coordinator.handleExistingRegistrationAccount,
                onCancel: coordinator.cancelRegistration,
                onEmailMethodChosen: coordinator.trackRegistrationEmailMethod,
                onOtpValidated: coordinator.trackRegistrationOtpValidated,
                onProfileResult: coordinator.trackRegistrationProfileResult
            )
        )
        _federatedStore = StateObject(
            wrappedValue: FederatedSignInStore(
                strings: coordinator.strings,
                presenterProvider: WindowScenePresentingViewControllerProvider(),
                identityHintStore: coordinator.federatedIdentityHintStore,
                onCredential: coordinator.signInWithFederatedCredential
            )
        )
    }

    var body: some View {
        NavigationStack {
            Group {
                if let state = store.state {
                    RegistrationStepContent(
                        state: state,
                        externalErrorMessage: coordinator.registrationCancellationErrorMessage,
                        store: store,
                        federatedStore: federatedStore
                    )
                } else {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .background(KwaborDesignTokens.ColorToken.paper50)
            .navigationTitle(store.strings.registrationTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if store.canGoBack {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button(store.strings.registrationBack, action: store.goBack)
                            .disabled((store.state?.isLoading ?? false) || federatedStore.isLoading)
                    }
                }
                if store.canCancel {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(store.strings.guestCancel, action: store.requestCancellation)
                            .disabled(
                                (store.state?.isLoading ?? false) ||
                                    coordinator.isCancellingRegistration ||
                                    federatedStore.isLoading
                            )
                    }
                }
            }
        }
        .interactiveDismissDisabled(
            store.state?.currentSession != nil ||
                store.state?.isLoading == true ||
                federatedStore.isLoading
        )
    }
}

private struct RegistrationStepContent: View {
    let state: RegistrationUiState
    let externalErrorMessage: String?
    @ObservedObject var store: RegistrationStore
    @ObservedObject var federatedStore: FederatedSignInStore
    @State private var lastAutoSubmittedOtp: String?
    @State private var isPasswordVisible = false
    @AccessibilityFocusState private var accessibilityFocus: RegistrationAccessibilityFocus?

    var body: some View {
        VStack(spacing: 0) {
            progress
            Form {
                Section {
                    VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
                        Text(stepTitle)
                            .font(.title2.bold())
                            .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                        if state.step == .profile {
                            Text(store.strings.registrationProfileSupport)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                registrationStep
                feedback
            }
            .scrollDismissesKeyboard(.interactively)
            .disabled(state.isLoading || federatedStore.isLoading || !store.isConfigured)

            if state.step != .completed {
                Button(primaryActionTitle, action: store.submitPrimaryAction)
                    .buttonStyle(.borderedProminent)
                    .tint(KwaborDesignTokens.ColorToken.ink950)
                    .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                    .padding(.horizontal, KwaborDesignTokens.Spacing.xxl)
                    .padding(.vertical, KwaborDesignTokens.Spacing.md)
                    .disabled(primaryActionDisabled)
            }
        }
        .overlay {
            if state.isLoading {
                ProgressView()
                    .padding(KwaborDesignTokens.Spacing.lg)
                    .background(
                        .regularMaterial,
                        in: RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control)
                    )
                    .accessibilityLabel(store.strings.registrationContinue)
            }
        }
        .onChange(of: store.otpCode) { _, code in
            guard state.step == .otp,
                  code.count == otpCodeLength,
                  code != lastAutoSubmittedOtp,
                  !state.isLoading,
                  !federatedStore.isLoading else {
                return
            }
            lastAutoSubmittedOtp = code
            store.submitPrimaryAction()
        }
        .onAppear { updateAccessibilityFocus(for: state.step) }
        .onChange(of: state.step) { _, step in
            updateAccessibilityFocus(for: step)
        }
    }

    @ViewBuilder
    private var progress: some View {
        if let registrationProgress = state.progress {
            VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
                Text(
                    state.method == .federated
                        ? store.strings.registrationFinalStep
                        : store.strings.registrationStepProgress
                            .replacingOccurrences(
                                of: "{current}",
                                with: String(registrationProgress.current)
                            )
                            .replacingOccurrences(
                                of: "{total}",
                                with: String(registrationProgress.total)
                            )
                )
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                ProgressView(
                    value: Double(registrationProgress.current),
                    total: Double(registrationProgress.total)
                )
                .tint(KwaborDesignTokens.ColorToken.ink950)
                .accessibilityHidden(true)
            }
            .padding(.horizontal, KwaborDesignTokens.Spacing.xxl)
            .padding(.top, KwaborDesignTokens.Spacing.md)
        }
    }

    @ViewBuilder
    private var registrationStep: some View {
        if state.step == .email {
            emailStep
        } else if state.step == .otp {
            otpStep
        } else if state.step == .password {
            passwordStep
        } else if state.step == .profile {
            profileStep
        } else {
            EmptyView()
        }
    }

    private var emailStep: some View {
        Group {
            Section {
                FederatedSignInButtons(
                    store: federatedStore,
                    isDisabled: state.isLoading
                )
            }
            Section {
                TextField(
                    store.strings.authEmail,
                    text: Binding(
                        get: { state.email },
                        set: store.updateEmail
                    )
                )
                .textInputAutocapitalization(.never)
                .keyboardType(.emailAddress)
                .textContentType(.emailAddress)
                .submitLabel(.continue)
                .onSubmit(store.submitPrimaryAction)
                .accessibilityFocused($accessibilityFocus, equals: .email)
            }
        }
    }

    private var otpStep: some View {
        Section {
            ZStack {
                HStack(spacing: KwaborDesignTokens.Spacing.sm) {
                    ForEach(0..<otpCodeLength, id: \.self) { index in
                        Text(otpDigit(at: index))
                            .font(.title2.monospacedDigit())
                            .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                            .overlay {
                                RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control)
                                    .stroke(
                                        index == store.otpCode.count
                                            ? KwaborDesignTokens.ColorToken.ink950
                                            : KwaborDesignTokens.ColorToken.ink100
                                    )
                            }
                            .accessibilityHidden(true)
                    }
                }
                TextField("", text: otpBinding)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .foregroundStyle(.clear)
                    .tint(.clear)
                    .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                    .accessibilityLabel(store.strings.authOtpCode)
                    .accessibilityValue(store.otpCode)
                    .accessibilityFocused($accessibilityFocus, equals: .otp)
            }
            Button(store.resendLabel, action: store.resendOtp)
                .disabled(!store.canResendOtp || state.isLoading)
            Button(store.strings.registrationEditEmail, action: store.goBack)
                .disabled(state.isLoading)
        }
    }

    private var passwordStep: some View {
        Section {
            HStack {
                Group {
                    if isPasswordVisible {
                        TextField(store.strings.registrationPassword, text: $store.password)
                            .accessibilityFocused($accessibilityFocus, equals: .password)
                    } else {
                        SecureField(store.strings.registrationPassword, text: $store.password)
                            .accessibilityFocused($accessibilityFocus, equals: .password)
                    }
                }
                .textContentType(.newPassword)
                .submitLabel(.continue)
                .onSubmit(store.submitPrimaryAction)
                Button {
                    isPasswordVisible.toggle()
                } label: {
                    Image(systemName: isPasswordVisible ? "eye.slash" : "eye")
                }
                .buttonStyle(.plain)
                .accessibilityLabel(
                    isPasswordVisible
                        ? store.strings.registrationPasswordHide
                        : store.strings.registrationPasswordShow
                )
            }
            Text(store.strings.registrationPasswordTooShort)
                .font(.caption)
                .foregroundStyle(
                    store.password.count >= minimumPasswordLength
                        ? KwaborDesignTokens.ColorToken.ink950
                        : Color.secondary
                )
        }
    }

    private var profileStep: some View {
        Group {
            Section {
                TextField(
                    store.strings.authFirstName,
                    text: Binding(
                        get: { state.firstName },
                        set: store.updateFirstName
                    )
                )
                .textContentType(.givenName)
                .accessibilityFocused($accessibilityFocus, equals: .profile)
                TextField(
                    store.strings.authLastName,
                    text: Binding(
                        get: { state.lastName },
                        set: store.updateLastName
                    )
                )
                .textContentType(.familyName)
            }
            requirementsSection
            citySection
            currencySection
            legalSection
        }
    }

    @ViewBuilder
    private var requirementsSection: some View {
        if state.requirementsStatus == .loading {
            Section {
                HStack {
                    ProgressView()
                    Text(store.strings.loading)
                }
            }
        } else if state.requirementsStatus == .failed {
            Section {
                if let error = state.requirementsErrorMessage {
                    Text(error)
                        .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
                }
                Button(store.strings.retry, action: store.retryRequirements)
            }
        }
    }

    private var citySection: some View {
        Section(store.strings.registrationCityTitle) {
            TextField(store.strings.registrationCityTitle, text: $store.cityQuery)
                .textInputAutocapitalization(.words)
            ForEach(store.filteredCities, id: \.id) { city in
                Button {
                    store.selectCity(city.id)
                } label: {
                    HStack {
                        Text(city.name)
                            .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                        Spacer()
                        if state.selectedCityId == city.id {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                                .accessibilityHidden(true)
                        }
                    }
                }
                .accessibilityAddTraits(state.selectedCityId == city.id ? .isSelected : [])
            }
        }
    }

    private var currencySection: some View {
        Section(store.strings.registrationCurrencyTitle) {
            Menu {
                ForEach(availableCurrencies.indices, id: \.self) { index in
                    let currency = availableCurrencies[index]
                    Button {
                        store.selectCurrency(currency)
                    } label: {
                        Text("\(currency.name.uppercased()) · \(currency.symbol)")
                    }
                }
            } label: {
                HStack {
                    Text("\(state.preferredCurrency.name.uppercased()) · \(state.preferredCurrency.symbol)")
                    Spacer()
                    Image(systemName: "chevron.up.chevron.down")
                        .accessibilityHidden(true)
                }
            }
        }
    }

    private var legalSection: some View {
        Section(store.strings.registrationLegalTitle) {
            legalAcceptance(
                document: state.termsDocument,
                title: store.strings.registrationTermsAcceptance,
                isAccepted: state.termsAccepted,
                onAcceptedChange: store.updateTermsAcceptance
            )
            legalAcceptance(
                document: state.privacyDocument,
                title: store.strings.registrationPrivacyAcceptance,
                isAccepted: state.privacyAccepted,
                onAcceptedChange: store.updatePrivacyAcceptance
            )
            legalAcceptance(
                document: state.ugcDocument,
                title: store.strings.registrationUgcAcceptance,
                isAccepted: state.ugcAccepted,
                onAcceptedChange: store.updateUgcAcceptance
            )
        }
    }

    @ViewBuilder
    private var feedback: some View {
        if !store.isConfigured {
            Section {
                Text(store.strings.authUnavailable)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
            }
        }
        if let externalErrorMessage {
            Section {
                Text(externalErrorMessage)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
            }
        }
        if let notice = state.noticeMessage {
            Section { Text(notice).foregroundStyle(.secondary) }
        }
        if let error = state.errorMessage {
            Section {
                Text(error)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
            }
        }
    }

    private var stepTitle: String {
        if state.step == .email {
            return store.strings.registrationTitle
        }
        if state.step == .otp {
            return store.strings.authOtpCode
        }
        if state.step == .password {
            return store.strings.registrationPassword
        }
        return store.strings.registrationProfileTitle
    }

    private var primaryActionTitle: String {
        if state.step == .email {
            return store.strings.authRequestOtp
        }
        if state.step == .otp {
            return store.strings.authVerifyOtp
        }
        if state.step == .profile {
            return state.startContext.suggestedCityId == nil
                ? store.strings.registrationCompleteDefault
                : store.strings.registrationCompleteContextual
        }
        return store.strings.registrationContinue
    }

    private var primaryActionDisabled: Bool {
        if state.isLoading || federatedStore.isLoading || !store.isConfigured { return true }
        if state.step == .email {
            return state.email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        if state.step == .otp {
            return store.otpCode.count != otpCodeLength
        }
        if state.step == .password {
            return store.password.count < minimumPasswordLength
        }
        if state.step == .profile {
            return !state.requirementsReady ||
                state.firstName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                state.lastName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                state.selectedCityId == nil ||
                !state.termsAccepted ||
                !state.privacyAccepted ||
                !state.ugcAccepted
        }
        return true
    }

    private var otpBinding: Binding<String> {
        Binding(
            get: { store.otpCode },
            set: { value in
                store.otpCode = String(value.filter(\.isNumber).prefix(otpCodeLength))
            }
        )
    }

    private func otpDigit(at index: Int) -> String {
        String(store.otpCode.dropFirst(index).prefix(1))
    }

    private func updateAccessibilityFocus(for step: RegistrationStep) {
        if step == .email {
            accessibilityFocus = .email
        } else if step == .otp {
            accessibilityFocus = .otp
        } else if step == .password {
            accessibilityFocus = .password
        } else if step == .profile {
            accessibilityFocus = .profile
        } else {
            accessibilityFocus = nil
        }
    }

    private func legalAcceptance(
        document: LegalDocumentRevision?,
        title: String,
        isAccepted: Bool,
        onAcceptedChange: @escaping (Bool) -> Void
    ) -> LegalAcceptanceRow {
        LegalAcceptanceRow(
            document: document,
            title: title,
            isAccepted: isAccepted,
            unavailableMessage: store.strings.registrationLegalUnavailable,
            onAcceptedChange: onAcceptedChange
        )
    }
}

private enum RegistrationAccessibilityFocus: Hashable {
    case email
    case otp
    case password
    case profile
}

private struct LegalAcceptanceRow: View {
    let document: LegalDocumentRevision?
    let title: String
    let isAccepted: Bool
    let unavailableMessage: String
    let onAcceptedChange: (Bool) -> Void

    @ViewBuilder
    var body: some View {
        if let document,
           let url = URL(string: document.url),
           url.scheme?.lowercased() == secureScheme {
            VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.md) {
                Link(destination: url) {
                    VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
                        Text(title)
                        Text(document.version)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Toggle(
                    title,
                    isOn: Binding(
                        get: { isAccepted },
                        set: onAcceptedChange
                    )
                )
            }
        } else {
            Text(unavailableMessage)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
        }
    }
}

private let availableCurrencies: [KwaborCurrency] = [.xof, .ngn, .usd, .eur]
private let otpCodeLength = 6
private let minimumPasswordLength = 8
private let secureScheme = "https"
