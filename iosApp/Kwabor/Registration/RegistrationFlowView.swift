import Foundation
import Shared
import SwiftUI

struct RegistrationFlowView: View {
    @ObservedObject private var coordinator: OnboardingCoordinator
    @StateObject private var store: RegistrationStore

    init(coordinator: OnboardingCoordinator) {
        self.coordinator = coordinator
        _store = StateObject(
            wrappedValue: RegistrationStore(
                controller: coordinator.registrationController,
                strings: coordinator.strings,
                locationProvider: coordinator.registrationLocationProvider,
                notificationPermissionRequester: coordinator.registrationNotificationPermissionRequester,
                notificationPrimingStore: coordinator.registrationNotificationPrimingStore,
                applyObservabilityConsent: coordinator.applyRegistrationObservabilityConsent,
                onCompleted: coordinator.completeRegistration,
                onCancel: coordinator.cancelRegistration
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
                        store: store
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
                            .disabled(store.state?.isLoading ?? false)
                    }
                }
                if store.canCancel {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(store.strings.guestCancel, action: store.requestCancellation)
                            .disabled(
                                (store.state?.isLoading ?? false) ||
                                    coordinator.isCancellingRegistration
                            )
                    }
                }
            }
        }
        .interactiveDismissDisabled(
            store.state?.currentSession != nil || store.state?.isLoading == true
        )
    }
}

struct RestoredSessionNotificationPrimingView: View {
    @ObservedObject var coordinator: OnboardingCoordinator

    var body: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.xxl) {
            Spacer()
            Image(systemName: "bell.badge.fill")
                .font(.system(size: notificationSymbolSize))
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                .accessibilityHidden(true)
            Text(coordinator.strings.registrationNotificationTitle)
                .font(.title2.bold())
                .multilineTextAlignment(.center)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
            Text(coordinator.strings.registrationNotificationSupport)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Spacer()
            Button(
                coordinator.strings.registrationNotificationEnable,
                action: coordinator.enableNotificationsAfterSessionRestore
            )
            .buttonStyle(.borderedProminent)
            .tint(KwaborDesignTokens.ColorToken.ink950)
            .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
            .disabled(coordinator.isRequestingNotificationsAfterSessionRestore)
            Button(
                coordinator.strings.registrationLater,
                action: coordinator.skipNotificationsAfterSessionRestore
            )
            .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
            .disabled(coordinator.isRequestingNotificationsAfterSessionRestore)
        }
        .padding(KwaborDesignTokens.Spacing.xxl)
        .background(KwaborDesignTokens.ColorToken.paper50)
        .overlay {
            if coordinator.isRequestingNotificationsAfterSessionRestore {
                ProgressView()
                    .padding(KwaborDesignTokens.Spacing.lg)
                    .background(
                        .regularMaterial,
                        in: RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control)
                    )
                    .accessibilityLabel(coordinator.strings.registrationNotificationEnable)
            }
        }
    }
}

private struct RegistrationStepContent: View {
    let state: RegistrationUiState
    let externalErrorMessage: String?
    @ObservedObject var store: RegistrationStore

    var body: some View {
        VStack(spacing: 0) {
            if state.step != .notificationPriming && state.step != .completed {
                ProgressView(value: progress, total: registrationProgressTotal)
                    .tint(KwaborDesignTokens.ColorToken.ink950)
                    .padding(.horizontal, KwaborDesignTokens.Spacing.xxl)
                    .padding(.top, KwaborDesignTokens.Spacing.md)
                    .accessibilityHidden(true)
            }

            Form {
                Section {
                    Text(stepTitle)
                        .font(.title2.bold())
                        .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                }
                registrationStep
                feedback
            }
            .scrollDismissesKeyboard(.interactively)
            .disabled(state.isLoading || !store.isConfigured)

            if showsPrimaryAction {
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
        .alert(
            store.strings.registrationUseLocation,
            isPresented: $store.isLocationPrimerPresented
        ) {
            Button(store.strings.registrationLater, role: .cancel) {}
            Button(store.strings.registrationUseLocation, action: store.requestLocationAfterPrimer)
        } message: {
            Text(locationPrimerMessage)
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
        } else if state.step == .identity {
            identityStep
        } else if state.step == .city {
            cityStep
        } else if state.step == .currency {
            currencyStep
        } else if state.step == .legal {
            legalStep
        } else if state.step == .observability {
            observabilityStep
        } else if state.step == .notificationPriming {
            notificationStep
        } else {
            completedStep
        }
    }

    private var emailStep: some View {
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
        }
    }

    private var otpStep: some View {
        Section {
            ZStack {
                HStack(spacing: KwaborDesignTokens.Spacing.sm) {
                    ForEach(0..<otpCodeLength, id: \.self) { index in
                        Text(otpDigit(at: index))
                            .font(.title2.monospacedDigit())
                            .frame(
                                maxWidth: .infinity,
                                minHeight: KwaborDesignTokens.Sizing.touchTarget
                            )
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
            }
            Button(store.strings.authRequestOtp, action: store.resendOtp)
                .disabled(!store.canResendOtp || state.isLoading)
        }
    }

    private var passwordStep: some View {
        Section {
            SecureField(store.strings.registrationPassword, text: $store.password)
                .textContentType(.newPassword)
            SecureField(
                store.strings.registrationPasswordConfirmation,
                text: $store.passwordConfirmation
            )
            .textContentType(.newPassword)
            .submitLabel(.continue)
            .onSubmit(store.submitPrimaryAction)
            Text(store.strings.registrationPasswordTooShort)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var identityStep: some View {
        Section {
            TextField(
                store.strings.authFirstName,
                text: Binding(
                    get: { state.firstName },
                    set: store.updateFirstName
                )
            )
            .textContentType(.givenName)
            TextField(
                store.strings.authLastName,
                text: Binding(
                    get: { state.lastName },
                    set: store.updateLastName
                )
            )
            .textContentType(.familyName)
            .submitLabel(.continue)
            .onSubmit(store.submitPrimaryAction)
        }
    }

    private var cityStep: some View {
        Group {
            Section {
                Button(action: store.presentLocationPrimer) {
                    Label(
                        store.strings.registrationUseLocation,
                        systemImage: "location.fill"
                    )
                }
                .disabled(store.isRequestingLocation)
                if store.isRequestingLocation {
                    ProgressView()
                }
                if let locationMessage = store.locationMessage {
                    Text(locationMessage)
                        .foregroundStyle(.secondary)
                        .accessibilityLabel(locationMessage)
                }
            }
            Section {
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
            .disabled(store.isRequestingLocation)
        }
    }

    private var currencyStep: some View {
        Section {
            ForEach(availableCurrencies.indices, id: \.self) { index in
                let currency = availableCurrencies[index]
                Button {
                    store.selectCurrency(currency)
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: KwaborDesignTokens.Spacing.xs) {
                            Text(currency.name.uppercased())
                                .font(.headline)
                            Text(currency.symbol)
                                .font(.body.monospacedDigit())
                        }
                        .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                        Spacer()
                        if state.preferredCurrency == currency {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                                .accessibilityHidden(true)
                        }
                    }
                }
                .accessibilityAddTraits(state.preferredCurrency == currency ? .isSelected : [])
            }
        }
    }

    private var legalStep: some View {
        Section {
            legalAcceptance(
                document: state.termsDocument,
                title: store.strings.registrationTermsAcceptance,
                isAccepted: state.termsAccepted,
                type: .terms
            )
            legalAcceptance(
                document: state.privacyDocument,
                title: store.strings.registrationPrivacyAcceptance,
                isAccepted: state.privacyAccepted,
                type: .privacyPolicy
            )
            legalAcceptance(
                document: state.ugcDocument,
                title: store.strings.registrationUgcAcceptance,
                isAccepted: state.ugcAccepted,
                type: .ugcLicense
            )
        }
    }

    private var observabilityStep: some View {
        Section {
            Toggle(
                store.strings.registrationAnalyticsConsent,
                isOn: Binding(
                    get: { state.observabilityConsent.analyticsAllowed },
                    set: store.updateAnalyticsConsent
                )
            )
            Toggle(
                store.strings.registrationDiagnosticsConsent,
                isOn: Binding(
                    get: { state.observabilityConsent.diagnosticsAllowed },
                    set: store.updateDiagnosticsConsent
                )
            )
            Toggle(
                store.strings.registrationRemoteConfigConsent,
                isOn: Binding(
                    get: { state.observabilityConsent.remoteConfigurationAllowed },
                    set: store.updateRemoteConfigurationConsent
                )
            )
        }
    }

    private var notificationStep: some View {
        Section {
            Image(systemName: "bell.badge.fill")
                .font(.system(size: notificationSymbolSize))
                .frame(maxWidth: .infinity)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ink950)
                .accessibilityHidden(true)
            Text(store.strings.registrationNotificationSupport)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button(store.strings.registrationNotificationEnable, action: store.enableNotifications)
                .buttonStyle(.borderedProminent)
                .tint(KwaborDesignTokens.ColorToken.ink950)
                .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                .disabled(store.isRequestingNotifications)
            Button(store.strings.registrationLater, action: store.skipNotifications)
                .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                .disabled(store.isRequestingNotifications)
        }
    }

    private var completedStep: some View {
        Section {
            ProgressView()
                .frame(maxWidth: .infinity)
                .accessibilityLabel(store.strings.registrationComplete)
        }
    }

    @ViewBuilder
    private var feedback: some View {
        if !store.isConfigured {
            Section {
                Text(store.strings.authUnavailable)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
                    .accessibilityLabel(store.strings.authUnavailable)
            }
        }
        if let externalErrorMessage {
            Section {
                Text(externalErrorMessage)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
                    .accessibilityLabel(externalErrorMessage)
            }
        }
        if let notice = state.noticeMessage {
            Section {
                Text(notice)
                    .foregroundStyle(.secondary)
                    .accessibilityLabel(notice)
            }
        }
        if let error = state.errorMessage {
            Section {
                Text(error)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
                    .accessibilityLabel(error)
            }
        }
    }

    private var stepTitle: String {
        if state.step == .email {
            store.strings.registrationTitle
        } else if state.step == .otp {
            store.strings.authOtpCode
        } else if state.step == .password {
            store.strings.registrationPassword
        } else if state.step == .identity {
            store.strings.registrationIdentityTitle
        } else if state.step == .city {
            store.strings.registrationCityTitle
        } else if state.step == .currency {
            store.strings.registrationCurrencyTitle
        } else if state.step == .legal {
            store.strings.registrationLegalTitle
        } else if state.step == .observability {
            store.strings.registrationObservabilityTitle
        } else if state.step == .notificationPriming {
            store.strings.registrationNotificationTitle
        } else {
            store.strings.registrationComplete
        }
    }

    private var primaryActionTitle: String {
        if state.step == .email {
            store.strings.authRequestOtp
        } else if state.step == .otp {
            store.strings.authVerifyOtp
        } else {
            store.strings.registrationContinue
        }
    }

    private var showsPrimaryAction: Bool {
        state.step != .notificationPriming && state.step != .completed
    }

    private var primaryActionDisabled: Bool {
        if state.isLoading || !store.isConfigured { return true }
        if state.step == .email {
            return state.email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        if state.step == .otp { return store.otpCode.count != otpCodeLength }
        if state.step == .password {
            return store.password.count < minimumPasswordLength ||
                store.password != store.passwordConfirmation
        }
        if state.step == .identity {
            return state.firstName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                state.lastName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        if state.step == .city { return state.selectedCityId == nil }
        if state.step == .legal {
            return !state.termsAccepted ||
                !state.privacyAccepted ||
                !state.ugcAccepted ||
                state.termsDocument == nil ||
                state.privacyDocument == nil ||
                state.ugcDocument == nil
        }
        return false
    }

    private var progress: Double {
        if state.step == .email { return 1 }
        if state.step == .otp { return 2 }
        if state.step == .password { return 3 }
        if state.step == .identity { return 4 }
        if state.step == .city { return 5 }
        if state.step == .currency { return 6 }
        if state.step == .legal { return 7 }
        return registrationProgressTotal
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

    private var locationPrimerMessage: String {
        Bundle.main.object(forInfoDictionaryKey: locationUsageDescriptionKey) as? String
            ?? store.strings.registrationLocationUnavailable
    }

    @ViewBuilder
    private func legalAcceptance(
        document: LegalDocumentRevision?,
        title: String,
        isAccepted: Bool,
        type: LegalDocumentType
    ) -> some View {
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
                        set: { accepted in
                            store.updateLegalAcceptance(type, accepted: accepted)
                        }
                    )
                )
            }
        } else {
            Text(store.strings.registrationLegalUnavailable)
                .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
        }
    }
}

private let availableCurrencies: [KwaborCurrency] = [.xof, .ngn, .usd, .eur]
private let registrationProgressTotal = 8.0
private let otpCodeLength = 6
private let minimumPasswordLength = 8
private let notificationSymbolSize: CGFloat = 52
private let locationUsageDescriptionKey = "NSLocationWhenInUseUsageDescription"
private let secureScheme = "https"
