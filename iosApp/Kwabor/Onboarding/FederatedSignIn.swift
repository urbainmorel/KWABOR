import AuthenticationServices
import CryptoKit
import Foundation
import GoogleSignIn
import GoogleSignInSwift
import Security
import Shared
import SwiftUI
import UIKit

struct FederatedAuthCredential: CustomStringConvertible {
    let provider: SocialAuthProvider
    let idToken: String
    let rawNonce: String
    let suggestedFirstName: String?
    let suggestedLastName: String?

    var sharedRequest: SocialSignInRequest {
        SocialSignInRequest(
            provider: provider,
            idToken: idToken,
            rawNonce: rawNonce,
            suggestedFirstName: suggestedFirstName,
            suggestedLastName: suggestedLastName
        )
    }

    var description: String {
        "FederatedAuthCredential(provider: \(provider), idToken: <redacted>, " +
            "rawNonce: <redacted>, nameHints: <redacted>)"
    }
}

struct FederatedIdentityHints: Codable, Equatable {
    let firstName: String?
    let lastName: String?
}

enum FederatedIdentityProviderKey {
    case apple
    case google
}

protocol FederatedIdentityHintPersisting {
    func saveHints(
        _ hints: FederatedIdentityHints,
        provider: FederatedIdentityProviderKey,
        userIdentifier: String
    ) throws
    func hints(provider: FederatedIdentityProviderKey, userIdentifier: String) -> FederatedIdentityHints?
    func pendingHints() -> FederatedIdentityHints?
    @discardableResult
    func clearPendingHints() -> Bool

    @discardableResult
    func clearAllHints() -> Bool
}

final class KeychainFederatedIdentityHintStore: FederatedIdentityHintPersisting {
    private let service: String

    init(service: String = Bundle.main.bundleIdentifier ?? fallbackHintService) {
        self.service = service + federatedHintServiceSuffix
    }

    func saveHints(
        _ hints: FederatedIdentityHints,
        provider: FederatedIdentityProviderKey,
        userIdentifier: String
    ) throws {
        let account = hintAccount(provider: provider, userIdentifier: userIdentifier)
        if let pendingData = read(account: pendingHintAccount),
           let previousAccount = String(data: pendingData, encoding: .utf8),
           previousAccount != account {
            remove(account: previousAccount)
            remove(account: pendingHintAccount)
        }
        if let storedData = read(account: account),
           let storedHints = try? JSONDecoder().decode(FederatedIdentityHints.self, from: storedData),
           storedHints.firstName != nil || storedHints.lastName != nil {
            do {
                try write(Data(account.utf8), account: pendingHintAccount)
            } catch {
                remove(account: account)
                remove(account: pendingHintAccount)
                throw error
            }
            return
        }
        remove(account: account)
        guard hints.firstName != nil || hints.lastName != nil else {
            remove(account: pendingHintAccount)
            return
        }
        do {
            try write(JSONEncoder().encode(hints), account: account)
            try write(Data(account.utf8), account: pendingHintAccount)
        } catch {
            remove(account: account)
            remove(account: pendingHintAccount)
            throw error
        }
    }

    func hints(provider: FederatedIdentityProviderKey, userIdentifier: String) -> FederatedIdentityHints? {
        guard let data = read(account: hintAccount(provider: provider, userIdentifier: userIdentifier)) else {
            return nil
        }
        return try? JSONDecoder().decode(FederatedIdentityHints.self, from: data)
    }

    func pendingHints() -> FederatedIdentityHints? {
        guard let pendingData = read(account: pendingHintAccount),
              let pendingAccount = String(data: pendingData, encoding: .utf8),
              let hintData = read(account: pendingAccount) else {
            return nil
        }
        return try? JSONDecoder().decode(FederatedIdentityHints.self, from: hintData)
    }

    @discardableResult
    func clearPendingHints() -> Bool {
        guard let pendingData = read(account: pendingHintAccount),
              let pendingAccount = String(data: pendingData, encoding: .utf8) else {
            return remove(account: pendingHintAccount)
        }
        guard remove(account: pendingAccount) else { return false }
        return remove(account: pendingHintAccount)
    }

    @discardableResult
    func clearAllHints() -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    private func hintAccount(provider: FederatedIdentityProviderKey, userIdentifier: String) -> String {
        let prefix: String
        switch provider {
        case .apple:
            prefix = appleHintAccountPrefix
        case .google:
            prefix = googleHintAccountPrefix
        }
        let identifierDigest = SHA256.hash(data: Data(userIdentifier.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        return prefix + identifierDigest
    }

    private func write(_ data: Data, account: String) throws {
        let query = baseQuery(account: account)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else {
            throw FederatedIdentityHintStoreError.keychain(status)
        }
        var insert = query
        attributes.forEach { insert[$0.key] = $0.value }
        let insertStatus = SecItemAdd(insert as CFDictionary, nil)
        guard insertStatus == errSecSuccess else {
            throw FederatedIdentityHintStoreError.keychain(insertStatus)
        }
    }

    private func read(account: String) -> Data? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess else {
            return nil
        }
        return item as? Data
    }

    private func remove(account: String) -> Bool {
        let status = SecItemDelete(baseQuery(account: account) as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}

private enum FederatedIdentityHintStoreError: Error {
    case keychain(OSStatus)
}

enum AccountDeletionPrivacyCleanupMarkerState: Equatable {
    case absent
    case pending
    case unavailable
}

protocol AccountDeletionPrivacyCleanupPersisting {
    var state: AccountDeletionPrivacyCleanupMarkerState { get }

    func persist() -> Bool
    func clear() -> Bool
}

final class KeychainAccountDeletionPrivacyCleanupStore: AccountDeletionPrivacyCleanupPersisting {
    private let service: String
    private let account = "account-deletion-provider-cleanup"

    init(service: String = "com.kwabor.ios.auth-deletion-privacy") {
        self.service = service
    }

    var state: AccountDeletionPrivacyCleanupMarkerState {
        var query = baseQuery
        query[kSecReturnData as String] = false
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        switch SecItemCopyMatching(query as CFDictionary, nil) {
        case errSecSuccess: return .pending
        case errSecItemNotFound: return .absent
        default: return .unavailable
        }
    }

    func persist() -> Bool {
        let payload = Data("pending:v1".utf8)
        let updateStatus = SecItemUpdate(
            baseQuery as CFDictionary,
            [kSecValueData as String: payload] as CFDictionary
        )
        if updateStatus == errSecItemNotFound {
            var insert = baseQuery
            insert[kSecValueData as String] = payload
            insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(insert as CFDictionary, nil) == errSecSuccess else { return false }
        } else if updateStatus != errSecSuccess {
            return false
        }
        return state == .pending
    }

    func clear() -> Bool {
        let status = SecItemDelete(baseQuery as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}

protocol PresentingViewControllerProviding {
    @MainActor
    func presentingViewController() -> UIViewController?

    @MainActor
    func bind(windowScene: UIWindowScene?)
}

extension PresentingViewControllerProviding {
    @MainActor
    func bind(windowScene: UIWindowScene?) {}
}

final class WindowScenePresentingViewControllerProvider: PresentingViewControllerProviding {
    private weak var windowScene: UIWindowScene?

    @MainActor
    func bind(windowScene: UIWindowScene?) {
        self.windowScene = windowScene
    }

    @MainActor
    func presentingViewController() -> UIViewController? {
        guard windowScene?.activationState == .foregroundActive else { return nil }
        let window = windowScene?.windows.first { $0.isKeyWindow }
        return topViewController(from: window?.rootViewController)
    }

    @MainActor
    private func topViewController(from viewController: UIViewController?) -> UIViewController? {
        if let navigationController = viewController as? UINavigationController {
            return topViewController(from: navigationController.visibleViewController)
        }
        if let tabBarController = viewController as? UITabBarController {
            return topViewController(from: tabBarController.selectedViewController)
        }
        if let presentedViewController = viewController?.presentedViewController {
            return topViewController(from: presentedViewController)
        }
        return viewController
    }
}

@MainActor
final class FederatedSignInStore: NSObject, ObservableObject {
    @Published private(set) var isLoading = false
    @Published private(set) var errorMessage: String?

    let strings: OnboardingStrings

    private let presenterProvider: PresentingViewControllerProviding
    private let identityHintStore: FederatedIdentityHintPersisting
    private let reportsSubmissionFailure: Bool
    private let attemptPreflight: () -> Bool
    private let attemptPreflightErrorMessage: String?
    private let attemptPreparation: ((@escaping (Bool) -> Void) -> Void)?
    private let attemptPreparationErrorMessage: () -> String?
    private let onPreparedAttemptAborted: ((@escaping () -> Void) -> Void)?
    private let onCredential: (FederatedAuthCredential, @escaping (Bool) -> Void) -> Void
    private var pendingAppleAttempt: NonceAttempt?
    private var appleAuthorizationContexts: [ObjectIdentifier: AppleAuthorizationContext] = [:]
    private let fallbackApplePresentationAnchor = UIWindow(frame: .zero)
    private var deferredAttemptPhase = DeferredAttemptPhase.idle
    private var deferredAttemptGeneration: UInt64 = 0

    init(
        strings: OnboardingStrings,
        presenterProvider: PresentingViewControllerProviding,
        identityHintStore: FederatedIdentityHintPersisting,
        reportsSubmissionFailure: Bool = true,
        attemptPreflight: @escaping () -> Bool = { true },
        attemptPreflightErrorMessage: String? = nil,
        attemptPreparation: ((@escaping (Bool) -> Void) -> Void)? = nil,
        attemptPreparationErrorMessage: @escaping () -> String? = { nil },
        onPreparedAttemptAborted: ((@escaping () -> Void) -> Void)? = nil,
        onCredential: @escaping (FederatedAuthCredential, @escaping (Bool) -> Void) -> Void
    ) {
        self.strings = strings
        self.presenterProvider = presenterProvider
        self.identityHintStore = identityHintStore
        self.reportsSubmissionFailure = reportsSubmissionFailure
        self.attemptPreflight = attemptPreflight
        self.attemptPreflightErrorMessage = attemptPreflightErrorMessage
        self.attemptPreparation = attemptPreparation
        self.attemptPreparationErrorMessage = attemptPreparationErrorMessage
        self.onPreparedAttemptAborted = onPreparedAttemptAborted
        self.onCredential = onCredential
        super.init()
        GoogleSignInBootstrap.configureIfPossible()
    }

    var isGoogleConfigured: Bool {
        GoogleOAuthConfiguration.current != nil
    }

    var requiresDeferredProviderLaunch: Bool {
        attemptPreparation != nil
    }

    func bindPresentationScene(_ windowScene: UIWindowScene?) {
        presenterProvider.bind(windowScene: windowScene)
    }

    func clearError() {
        guard !isLoading else { return }
        errorMessage = nil
    }

    func prepareAppleRequest(_ request: ASAuthorizationAppleIDRequest) {
        guard !isLoading, !requiresDeferredProviderLaunch else { return }
        guard prepareAttempt() else { return }
        do {
            let attempt = try NonceAttempt.make()
            pendingAppleAttempt = attempt
            errorMessage = nil
            isLoading = true
            request.requestedScopes = [.fullName, .email]
            request.nonce = attempt.hashedNonce
            request.state = attempt.state
        } catch {
            pendingAppleAttempt = nil
            errorMessage = strings.authFederatedUnavailable
        }
    }

    func startAppleSignIn() {
        guard !isLoading, requiresDeferredProviderLaunch else { return }
        prepareDeferredAttempt { [self] generation in
            guard let presentingViewController = presenterProvider.presentingViewController(),
                  let presentationAnchor = presentingViewController.view.window else {
                finishProviderFailure(errorMessage: strings.authFederatedUnavailable)
                return
            }
            launchAppleSignIn(presentationAnchor: presentationAnchor, deferredGeneration: generation)
        }
    }

    func completeAppleAuthorization(_ result: Result<ASAuthorization, Error>) {
        guard let attempt = pendingAppleAttempt else {
            if case let .failure(error) = result, !isCancellation(error) {
                finishProviderFailure(errorMessage: strings.authFederatedUnavailable)
            } else {
                finishProviderFailure(errorMessage: nil)
            }
            return
        }
        pendingAppleAttempt = nil
        completeAppleAuthorization(result, attempt: attempt, deferredGeneration: nil)
    }

    private func completeAppleAuthorization(
        _ result: Result<ASAuthorization, Error>,
        attempt: NonceAttempt,
        deferredGeneration: UInt64?
    ) {
        switch result {
        case let .success(authorization):
            guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
                  credential.state == attempt.state,
                  let identityToken = credential.identityToken,
                  let idToken = String(data: identityToken, encoding: .utf8),
                  !idToken.isEmpty else {
                finishProviderFailure(errorMessage: strings.authFederatedUnavailable)
                return
            }
            let receivedHints = FederatedIdentityHints(
                firstName: normalized(credential.fullName?.givenName),
                lastName: normalized(credential.fullName?.familyName)
            )
            let persistedHints: FederatedIdentityHints?
            do {
                try identityHintStore.saveHints(
                    receivedHints,
                    provider: .apple,
                    userIdentifier: credential.user
                )
                persistedHints = identityHintStore.hints(
                    provider: .apple,
                    userIdentifier: credential.user
                )
            } catch {
                identityHintStore.clearPendingHints()
                persistedHints = nil
            }
            submit(
                FederatedAuthCredential(
                    provider: .apple,
                    idToken: idToken,
                    rawNonce: attempt.rawNonce,
                    suggestedFirstName: persistedHints?.firstName ?? receivedHints.firstName,
                    suggestedLastName: persistedHints?.lastName ?? receivedHints.lastName
                ),
                deferredGeneration: deferredGeneration
            )
        case let .failure(error):
            finishProviderFailure(
                errorMessage: isCancellation(error) ? nil : strings.authFederatedUnavailable
            )
        }
    }

    func startGoogleSignIn() {
        guard !isLoading else { return }
        guard let configuration = GoogleOAuthConfiguration.current else {
            errorMessage = strings.authFederatedUnavailable
            return
        }
        if requiresDeferredProviderLaunch {
            prepareDeferredAttempt { [self] generation in
                guard let presenter = presenterProvider.presentingViewController() else {
                    finishProviderFailure(errorMessage: strings.authFederatedUnavailable)
                    return
                }
                launchGoogleSignIn(
                    configuration: configuration,
                    presenter: presenter,
                    deferredGeneration: generation
                )
            }
            return
        }
        guard let presenter = presenterProvider.presentingViewController() else {
            errorMessage = strings.authFederatedUnavailable
            return
        }
        guard prepareAttempt() else { return }
        launchGoogleSignIn(configuration: configuration, presenter: presenter, deferredGeneration: nil)
    }

    func abortDeferredAttemptForDisappearance() {
        guard let generation = deferredAttemptPhase.abortableGeneration else { return }
        deferredAttemptPhase = .aborting(generation)
        cancelAppleAuthorizationContexts(for: generation)
        finishDeferredAbort(generation: generation, message: nil)
    }

    private func launchAppleSignIn(
        presentationAnchor: ASPresentationAnchor,
        deferredGeneration: UInt64
    ) {
        guard deferredAttemptPhase == .providerActive(deferredGeneration) else { return }
        do {
            let attempt = try NonceAttempt.make()
            let request = ASAuthorizationAppleIDProvider().createRequest()
            request.requestedScopes = [.fullName, .email]
            request.nonce = attempt.hashedNonce
            request.state = attempt.state
            let authorizationController = ASAuthorizationController(authorizationRequests: [request])
            let context = AppleAuthorizationContext(
                controller: authorizationController,
                attempt: attempt,
                presentationAnchor: presentationAnchor,
                generation: deferredGeneration
            )
            appleAuthorizationContexts[ObjectIdentifier(authorizationController)] = context
            errorMessage = nil
            isLoading = true
            authorizationController.delegate = self
            authorizationController.presentationContextProvider = self
            authorizationController.performRequests()
        } catch {
            finishProviderFailure(errorMessage: strings.authFederatedUnavailable)
        }
    }

    private func launchGoogleSignIn(
        configuration: GoogleOAuthConfiguration,
        presenter: UIViewController,
        deferredGeneration: UInt64?
    ) {
        if let deferredGeneration,
           deferredAttemptPhase != .providerActive(deferredGeneration) {
            return
        }
        do {
            let attempt = try NonceAttempt.make()
            errorMessage = nil
            isLoading = true
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(
                clientID: configuration.iosClientId,
                serverClientID: configuration.serverClientId
            )
            GIDSignIn.sharedInstance.signIn(
                withPresenting: presenter,
                hint: nil,
                additionalScopes: nil,
                nonce: attempt.hashedNonce
            ) { [self] result, error in
                if let deferredGeneration,
                   deferredAttemptPhase != .providerActive(deferredGeneration) {
                    return
                }
                guard !isCancellation(error) else {
                    finishProviderFailure(errorMessage: nil)
                    return
                }
                guard error == nil,
                      let user = result?.user,
                      let userIdentifier = user.userID,
                      let idToken = user.idToken?.tokenString,
                      !idToken.isEmpty else {
                    finishProviderFailure(errorMessage: strings.authFederatedUnavailable)
                    return
                }
                let receivedHints = FederatedIdentityHints(
                    firstName: normalized(user.profile?.givenName),
                    lastName: normalized(user.profile?.familyName)
                )
                let persistedHints: FederatedIdentityHints?
                do {
                    try identityHintStore.saveHints(
                        receivedHints,
                        provider: .google,
                        userIdentifier: userIdentifier
                    )
                    persistedHints = identityHintStore.hints(
                        provider: .google,
                        userIdentifier: userIdentifier
                    )
                } catch {
                    identityHintStore.clearPendingHints()
                    persistedHints = nil
                }
                submit(
                    FederatedAuthCredential(
                        provider: .google,
                        idToken: idToken,
                        rawNonce: attempt.rawNonce,
                        suggestedFirstName: persistedHints?.firstName ?? receivedHints.firstName,
                        suggestedLastName: persistedHints?.lastName ?? receivedHints.lastName
                    ),
                    deferredGeneration: deferredGeneration
                )
            }
        } catch {
            finishProviderFailure(errorMessage: strings.authFederatedUnavailable)
        }
    }

    private func submit(
        _ credential: FederatedAuthCredential,
        deferredGeneration: UInt64? = nil
    ) {
        if let deferredGeneration {
            guard deferredAttemptPhase == .providerActive(deferredGeneration) else { return }
            deferredAttemptPhase = .submitting(deferredGeneration)
        }
        isLoading = true
        errorMessage = nil
        onCredential(credential) { [self] completed in
            if let deferredGeneration,
               deferredAttemptPhase == .submitting(deferredGeneration) {
                deferredAttemptPhase = .idle
            }
            isLoading = false
            if !completed, reportsSubmissionFailure {
                errorMessage = strings.authReauthenticationFailed
            }
        }
    }

    private func prepareDeferredAttempt(onPrepared: @escaping (UInt64) -> Void) {
        guard let attemptPreparation else {
            return
        }
        guard deferredAttemptPhase == .idle else { return }
        let generation = nextDeferredAttemptGeneration()
        deferredAttemptPhase = .preparing(generation)
        isLoading = true
        errorMessage = nil
        attemptPreparation { [self] prepared in
            guard deferredAttemptPhase == .preparing(generation) else { return }
            guard prepared else {
                deferredAttemptPhase = .idle
                isLoading = false
                errorMessage = attemptPreparationErrorMessage() ?? strings.authFederatedUnavailable
                return
            }
            deferredAttemptPhase = .providerActive(generation)
            onPrepared(generation)
        }
    }

    private func finishProviderFailure(errorMessage message: String?) {
        pendingAppleAttempt = nil
        guard let generation = deferredAttemptPhase.providerActiveGeneration else {
            isLoading = false
            errorMessage = message
            return
        }
        deferredAttemptPhase = .aborting(generation)
        finishDeferredAbort(generation: generation, message: message)
    }

    private func finishDeferredAbort(generation: UInt64, message: String?) {
        let finish = { [self] in
            guard deferredAttemptPhase == .aborting(generation) else { return }
            deferredAttemptPhase = .idle
            isLoading = false
            errorMessage = message
        }
        guard let onPreparedAttemptAborted else {
            finish()
            return
        }
        onPreparedAttemptAborted(finish)
    }

    private func cancelAppleAuthorizationContexts(for generation: UInt64) {
        appleAuthorizationContexts.values
            .filter { $0.generation == generation }
            .forEach { $0.controller.cancel() }
    }

    private func completeAppleAuthorization(
        controller: ASAuthorizationController,
        result: Result<ASAuthorization, Error>
    ) {
        let identifier = ObjectIdentifier(controller)
        guard let context = appleAuthorizationContexts.removeValue(forKey: identifier) else { return }
        guard deferredAttemptPhase == .providerActive(context.generation) else { return }
        completeAppleAuthorization(
            result,
            attempt: context.attempt,
            deferredGeneration: context.generation
        )
    }

    private func applePresentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        appleAuthorizationContexts[ObjectIdentifier(controller)]?.presentationAnchor ??
            fallbackApplePresentationAnchor
    }

    private func nextDeferredAttemptGeneration() -> UInt64 {
        deferredAttemptGeneration = deferredAttemptGeneration == UInt64.max ? 1 : deferredAttemptGeneration + 1
        return deferredAttemptGeneration
    }

    private func prepareAttempt() -> Bool {
        guard attemptPreflight() else {
            pendingAppleAttempt = nil
            isLoading = false
            errorMessage = attemptPreflightErrorMessage ?? strings.authFederatedUnavailable
            return false
        }
        errorMessage = nil
        return true
    }

    private func normalized(_ value: String?) -> String? {
        let candidate = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let containsControlCharacter = candidate.unicodeScalars.contains { scalar in
            CharacterSet.controlCharacters.contains(scalar)
        }
        guard !candidate.isEmpty, !containsControlCharacter else {
            return nil
        }
        return String(candidate.prefix(maximumIdentityHintLength))
    }

    private func isCancellation(_ error: Error?) -> Bool {
        guard let nsError = error as NSError? else { return false }
        if nsError.domain == ASAuthorizationError.errorDomain,
           nsError.code == ASAuthorizationError.canceled.rawValue {
            return true
        }
        return nsError.domain == kGIDSignInErrorDomain &&
            nsError.code == GIDSignInError.Code.canceled.rawValue
    }
}

extension FederatedSignInStore: ASAuthorizationControllerDelegate {
    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        completeAppleAuthorization(controller: controller, result: .success(authorization))
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        completeAppleAuthorization(controller: controller, result: .failure(error))
    }
}

extension FederatedSignInStore: ASAuthorizationControllerPresentationContextProviding {
    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        applePresentationAnchor(for: controller)
    }
}

private struct AppleAuthorizationContext {
    let controller: ASAuthorizationController
    let attempt: NonceAttempt
    let presentationAnchor: ASPresentationAnchor
    let generation: UInt64
}

private enum DeferredAttemptPhase: Equatable {
    case idle
    case preparing(UInt64)
    case providerActive(UInt64)
    case submitting(UInt64)
    case aborting(UInt64)

    var abortableGeneration: UInt64? {
        switch self {
        case let .preparing(generation), let .providerActive(generation): generation
        case .idle, .submitting, .aborting: nil
        }
    }

    var providerActiveGeneration: UInt64? {
        guard case let .providerActive(generation) = self else { return nil }
        return generation
    }
}

struct FederatedSignInButtons: View {
    @ObservedObject var store: FederatedSignInStore
    let isDisabled: Bool

    var body: some View {
        VStack(spacing: KwaborDesignTokens.Spacing.md) {
            HStack(spacing: KwaborDesignTokens.Spacing.md) {
                Rectangle()
                    .fill(KwaborDesignTokens.ColorToken.ink100)
                    .frame(height: separatorHeight)
                Text(store.strings.authOrSeparator)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                Rectangle()
                    .fill(KwaborDesignTokens.ColorToken.ink100)
                    .frame(height: separatorHeight)
            }
            ZStack {
                VStack(spacing: KwaborDesignTokens.Spacing.md) {
                    appleSignInButton

                    GoogleSignInButton(
                        scheme: .light,
                        style: .wide,
                        state: isDisabled || store.isLoading || !store.isGoogleConfigured ? .disabled : .normal,
                        action: store.startGoogleSignIn
                    )
                    .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                    .accessibilityLabel(store.strings.authSignInWithGoogle)
                }
                .opacity(store.isLoading ? federatedButtonsLoadingOpacity : 1)

                if store.isLoading {
                    ProgressView()
                        .padding(KwaborDesignTokens.Spacing.md)
                        .background(
                            .regularMaterial,
                            in: RoundedRectangle(cornerRadius: KwaborDesignTokens.Radius.control)
                        )
                        .accessibilityLabel(store.strings.loading)
                }
            }

            if !store.isGoogleConfigured {
                Text(store.strings.authFederatedUnavailable)
                    .font(.caption)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ink700)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if let errorMessage = store.errorMessage {
                Text(errorMessage)
                    .font(.callout)
                    .foregroundStyle(KwaborDesignTokens.ColorToken.ticket)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityLabel(errorMessage)
            }
        }
        .background(
            PresentationSceneReader(onSceneChanged: store.bindPresentationScene)
        )
    }

    @ViewBuilder
    private var appleSignInButton: some View {
        if store.requiresDeferredProviderLaunch {
            ZStack {
                SignInWithAppleButton(
                    .continue,
                    onRequest: { _ in },
                    onCompletion: { _ in }
                )
                .signInWithAppleButtonStyle(.black)
                .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
                Button(action: store.startAppleSignIn) {
                    Color.clear
                        .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(store.strings.authSignInWithApple)
            }
            .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
            .disabled(isDisabled || store.isLoading)
        } else {
            SignInWithAppleButton(
                .continue,
                onRequest: store.prepareAppleRequest,
                onCompletion: store.completeAppleAuthorization
            )
            .signInWithAppleButtonStyle(.black)
            .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
            .accessibilityLabel(store.strings.authSignInWithApple)
            .disabled(isDisabled || store.isLoading)
        }
    }
}

private struct PresentationSceneReader: UIViewRepresentable {
    let onSceneChanged: (UIWindowScene?) -> Void

    func makeUIView(context: Context) -> PresentationSceneView {
        let view = PresentationSceneView()
        view.isUserInteractionEnabled = false
        view.onSceneChanged = onSceneChanged
        return view
    }

    func updateUIView(_ uiView: PresentationSceneView, context: Context) {
        uiView.onSceneChanged = onSceneChanged
        uiView.reportCurrentScene()
    }
}

private final class PresentationSceneView: UIView {
    var onSceneChanged: ((UIWindowScene?) -> Void)?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        reportCurrentScene()
    }

    func reportCurrentScene() {
        onSceneChanged?(window?.windowScene)
    }
}

private struct GoogleOAuthConfiguration {
    let iosClientId: String
    let serverClientId: String

    static var current: GoogleOAuthConfiguration? {
        guard let iosClientId = Bundle.main.nonBlankString(for: googleIosClientIdKey),
              let serverClientId = Bundle.main.nonBlankString(for: googleServerClientIdKey),
              let configuredReversedClientId = Bundle.main.nonBlankString(for: googleReversedClientIdKey),
              isValidClientId(iosClientId),
              isValidClientId(serverClientId),
              iosClientId != serverClientId,
              configuredReversedClientId == reversedClientId(for: iosClientId),
              Bundle.main.registeredUrlSchemes.contains(configuredReversedClientId) else {
            return nil
        }
        return GoogleOAuthConfiguration(
            iosClientId: iosClientId,
            serverClientId: serverClientId
        )
    }

    private static func reversedClientId(for clientId: String) -> String {
        let identifier = clientId.dropLast(googleClientIdSuffix.count)
        return googleReversedClientIdPrefix + identifier
    }

    private static func isValidClientId(_ value: String) -> Bool {
        value.range(of: googleClientIdPattern, options: .regularExpression) != nil
    }
}

@MainActor
enum GoogleSignInBootstrap {
    private static var hasConfigured = false
    // GoogleSignIn 9.0.0 uses GTMKeychainStore(item: "auth"); GTMAppAuth 5 uses account "OAuth".
    private static let keychainService = "auth"
    private static let keychainAccount = "OAuth"

    static func configureIfPossible() {
        guard !hasConfigured, let configuration = GoogleOAuthConfiguration.current else { return }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(
            clientID: configuration.iosClientId,
            serverClientID: configuration.serverClientId
        )
        GIDSignIn.sharedInstance.configure(completion: nil)
        hasConfigured = true
    }

    @discardableResult
    static func clearLocalSession() -> Bool {
        guard UIApplication.shared.isProtectedDataAvailable else { return false }
        GIDSignIn.sharedInstance.signOut()
        let persistedSessionRemoved = removePersistedAuthSession()
        return GIDSignIn.sharedInstance.currentUser == nil && persistedSessionRemoved
    }

    private static func removePersistedAuthSession() -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
        ]
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }
}

private struct NonceAttempt {
    let rawNonce: String
    let hashedNonce: String
    let state: String

    static func make() throws -> NonceAttempt {
        let rawNonce = try SecureRandom.string(byteCount: nonceByteCount)
        return NonceAttempt(
            rawNonce: rawNonce,
            hashedNonce: SHA256.hash(data: Data(rawNonce.utf8)).map { String(format: "%02x", $0) }.joined(),
            state: try SecureRandom.string(byteCount: stateByteCount)
        )
    }
}

private enum SecureRandom {
    static func string(byteCount: Int) throws -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw SecureRandomError.generationFailed
        }
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

private enum SecureRandomError: Error {
    case generationFailed
}

private extension Bundle {
    func nonBlankString(for key: String) -> String? {
        let candidate = (object(forInfoDictionaryKey: key) as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return candidate.isEmpty ? nil : candidate
    }

    var registeredUrlSchemes: Set<String> {
        guard let urlTypes = object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] else {
            return []
        }
        return Set(urlTypes.flatMap { $0["CFBundleURLSchemes"] as? [String] ?? [] })
    }
}

private let googleIosClientIdKey = "KWABOR_GOOGLE_IOS_CLIENT_ID"
private let googleServerClientIdKey = "KWABOR_GOOGLE_SERVER_CLIENT_ID"
private let googleReversedClientIdKey = "KWABOR_GOOGLE_REVERSED_CLIENT_ID"
private let nonceByteCount = 32
private let stateByteCount = 32
private let maximumIdentityHintLength = 80
private let separatorHeight: CGFloat = 1
private let federatedButtonsLoadingOpacity = 0.55
private let fallbackHintService = "com.kwabor.ios"
private let federatedHintServiceSuffix = ".federated-identity-hints"
private let pendingHintAccount = "pending"
private let appleHintAccountPrefix = "apple:"
private let googleHintAccountPrefix = "google:"
private let googleClientIdSuffix = ".apps.googleusercontent.com"
private let googleReversedClientIdPrefix = "com.googleusercontent.apps."
private let googleClientIdPattern = "^[A-Za-z0-9-]+\\.apps\\.googleusercontent\\.com$"
