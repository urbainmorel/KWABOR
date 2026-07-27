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
    func clearPendingHints()
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

    func clearPendingHints() {
        guard let pendingData = read(account: pendingHintAccount),
              let pendingAccount = String(data: pendingData, encoding: .utf8) else {
            remove(account: pendingHintAccount)
            return
        }
        remove(account: pendingAccount)
        remove(account: pendingHintAccount)
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

    private func remove(account: String) {
        SecItemDelete(baseQuery(account: account) as CFDictionary)
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

protocol PresentingViewControllerProviding {
    @MainActor
    func presentingViewController() -> UIViewController?
}

struct WindowScenePresentingViewControllerProvider: PresentingViewControllerProviding {
    @MainActor
    func presentingViewController() -> UIViewController? {
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }
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
final class FederatedSignInStore: ObservableObject {
    @Published private(set) var isLoading = false
    @Published private(set) var errorMessage: String?

    let strings: OnboardingStrings

    private let presenterProvider: PresentingViewControllerProviding
    private let identityHintStore: FederatedIdentityHintPersisting
    private let reportsSubmissionFailure: Bool
    private let onCredential: (FederatedAuthCredential, @escaping (Bool) -> Void) -> Void
    private var pendingAppleAttempt: NonceAttempt?

    init(
        strings: OnboardingStrings,
        presenterProvider: PresentingViewControllerProviding,
        identityHintStore: FederatedIdentityHintPersisting,
        reportsSubmissionFailure: Bool = true,
        onCredential: @escaping (FederatedAuthCredential, @escaping (Bool) -> Void) -> Void
    ) {
        self.strings = strings
        self.presenterProvider = presenterProvider
        self.identityHintStore = identityHintStore
        self.reportsSubmissionFailure = reportsSubmissionFailure
        self.onCredential = onCredential
        GoogleSignInBootstrap.configureIfPossible()
    }

    var isGoogleConfigured: Bool {
        GoogleOAuthConfiguration.current != nil
    }

    func prepareAppleRequest(_ request: ASAuthorizationAppleIDRequest) {
        guard !isLoading else { return }
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

    func completeAppleAuthorization(_ result: Result<ASAuthorization, Error>) {
        guard let attempt = pendingAppleAttempt else {
            if case let .failure(error) = result, !isCancellation(error) {
                errorMessage = strings.authFederatedUnavailable
            }
            isLoading = false
            return
        }
        pendingAppleAttempt = nil
        switch result {
        case let .success(authorization):
            guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
                  credential.state == attempt.state,
                  let identityToken = credential.identityToken,
                  let idToken = String(data: identityToken, encoding: .utf8),
                  !idToken.isEmpty else {
                isLoading = false
                errorMessage = strings.authFederatedUnavailable
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
                )
            )
        case let .failure(error):
            isLoading = false
            guard !isCancellation(error) else { return }
            errorMessage = strings.authFederatedUnavailable
        }
    }

    func startGoogleSignIn() {
        guard !isLoading else { return }
        guard let configuration = GoogleOAuthConfiguration.current,
              let presenter = presenterProvider.presentingViewController() else {
            errorMessage = strings.authFederatedUnavailable
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
            ) { [weak self] result, error in
                guard let self else { return }
                guard !isCancellation(error) else {
                    isLoading = false
                    return
                }
                guard error == nil,
                      let user = result?.user,
                      let userIdentifier = user.userID,
                      let idToken = user.idToken?.tokenString,
                      !idToken.isEmpty else {
                    isLoading = false
                    errorMessage = strings.authFederatedUnavailable
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
                    )
                )
            }
        } catch {
            isLoading = false
            errorMessage = strings.authFederatedUnavailable
        }
    }

    private func submit(_ credential: FederatedAuthCredential) {
        isLoading = true
        errorMessage = nil
        onCredential(credential) { [weak self] completed in
            guard let self else { return }
            isLoading = false
            if !completed, reportsSubmissionFailure {
                errorMessage = strings.authReauthenticationFailed
            }
        }
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
            nsError.code == GIDSignInErrorCode.canceled.rawValue
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
                    SignInWithAppleButton(
                        .continue,
                        onRequest: store.prepareAppleRequest,
                        onCompletion: store.completeAppleAuthorization
                    )
                    .signInWithAppleButtonStyle(.black)
                    .frame(maxWidth: .infinity, minHeight: KwaborDesignTokens.Sizing.touchTarget)
                    .accessibilityLabel(store.strings.authSignInWithApple)
                    .disabled(isDisabled || store.isLoading)

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

    static func configureIfPossible() {
        guard !hasConfigured, let configuration = GoogleOAuthConfiguration.current else { return }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(
            clientID: configuration.iosClientId,
            serverClientID: configuration.serverClientId
        )
        GIDSignIn.sharedInstance.configure(completion: nil)
        hasConfigured = true
    }

    static func clearLocalSession() {
        GIDSignIn.sharedInstance.signOut()
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
