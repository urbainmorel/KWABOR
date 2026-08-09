import Foundation

struct ExplorePaginationGuard {
    private(set) var requestedCursor: String?

    mutating func shouldLoadNext(
        cursor: String?,
        canLoadMore: Bool,
        isNearEnd: Bool,
        hasAppendError: Bool
    ) -> Bool {
        guard canLoadMore,
              isNearEnd,
              !hasAppendError,
              let cursor,
              !cursor.isEmpty,
              cursor != requestedCursor else {
            return false
        }
        requestedCursor = cursor
        return true
    }

    mutating func shouldRetry(cursor: String?, canLoadMore: Bool) -> Bool {
        guard canLoadMore, let cursor, !cursor.isEmpty else { return false }
        requestedCursor = cursor
        return true
    }

    mutating func reset() {
        requestedCursor = nil
    }
}

struct SearchPaginationGuard {
    private(set) var requestedCursor: String?

    mutating func shouldLoadNext(
        cursor: String?,
        canLoadMore: Bool,
        isNearEnd: Bool,
        hasAppendError: Bool
    ) -> Bool {
        guard canLoadMore,
              isNearEnd,
              !hasAppendError,
              let cursor,
              !cursor.isEmpty,
              cursor != requestedCursor else {
            return false
        }
        requestedCursor = cursor
        return true
    }

    mutating func shouldRetry(cursor: String?, canLoadMore: Bool) -> Bool {
        guard canLoadMore, let cursor, !cursor.isEmpty else { return false }
        requestedCursor = cursor
        return true
    }

    mutating func reset() {
        requestedCursor = nil
    }
}

enum SearchPaginationPolicy {
    static func isNearEnd(index: Int, itemCount: Int, threshold: Int = 4) -> Bool {
        guard index >= 0, itemCount > 0, threshold > 0, index < itemCount else { return false }
        return index >= max(itemCount - threshold, 0)
    }
}

enum SearchGridPolicy {
    static func columnCount(
        availableWidth: Double,
        tabletBreakpoint: Double,
        usesAccessibilityLayout: Bool
    ) -> Int {
        if usesAccessibilityLayout {
            return 1
        }
        return availableWidth >= tabletBreakpoint ? 3 : 2
    }
}

struct ExploreCardDecorationPresentation: Equatable {
    let showsSponsoredBadge: Bool
    let ratingLabel: String?
    let eventDateLabel: String?
    let showsEndedRibbon: Bool
}

enum ExploreCardDecorationPolicy {
    static func presentation(
        isSponsoredPlacement: Bool,
        ratingLabel: String?,
        eventDateLabel: String?,
        isEventEnded: Bool
    ) -> ExploreCardDecorationPresentation {
        ExploreCardDecorationPresentation(
            showsSponsoredBadge: isSponsoredPlacement,
            ratingLabel: isSponsoredPlacement ? nil : normalizedLabel(ratingLabel),
            eventDateLabel: normalizedLabel(eventDateLabel),
            showsEndedRibbon: isEventEnded
        )
    }

    private static func normalizedLabel(_ rawValue: String?) -> String? {
        let normalized = rawValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return normalized.isEmpty ? nil : normalized
    }
}

enum ExploreCardImageAccessibilityPolicy {
    static func description(coverImageAlt: String?, fallbackTitle: String) -> String {
        let normalizedAlt = coverImageAlt?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return normalizedAlt.isEmpty ? fallbackTitle : normalizedAlt
    }
}

enum ContextualAuthenticationDismissalAction: Equatable {
    case cancel
    case keepForAuthenticatedReplay
    case keepForPresentedRegistration
    case presentRequestedRegistration
}

enum ContextualAuthenticationDismissalPolicy {
    static func action(
        hasCompleteAccount: Bool,
        isRegistrationPresented: Bool,
        registrationWasRequested: Bool
    ) -> ContextualAuthenticationDismissalAction {
        if isRegistrationPresented {
            return .keepForPresentedRegistration
        }
        if hasCompleteAccount {
            return .keepForAuthenticatedReplay
        }
        return registrationWasRequested ? .presentRequestedRegistration : .cancel
    }
}

enum ProtectedDestinationReplayAction: Equatable {
    case applyRootDeepLink(discardProtectedDestination: Bool)
    case transferRootDeepLinkToAuthentication
    case select(String)
    case wait
}

enum ProtectedDestinationReplayPolicy {
    static func action(
        isGuest: Bool,
        hasPendingRootDeepLink: Bool,
        isRootDeepLinkProtected: Bool,
        pendingDestinationKey: String?
    ) -> ProtectedDestinationReplayAction {
        if hasPendingRootDeepLink {
            if isGuest && isRootDeepLinkProtected {
                return .transferRootDeepLinkToAuthentication
            }
            return .applyRootDeepLink(discardProtectedDestination: pendingDestinationKey != nil)
        }
        guard let pendingDestinationKey else { return .wait }
        return isGuest ? .wait : .select(pendingDestinationKey)
    }
}

enum ExploreRemoteImageURLPolicy {
    static func acceptedURL(_ rawValue: String?) -> URL? {
        guard let rawValue,
              (minimumHTTPSURLBytes...maximumHTTPSURLBytes).contains(rawValue.utf8.count),
              rawValue.hasPrefix(securePrefix),
              rawValue.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
              !rawValue.contains("\\"),
              let components = URLComponents(string: rawValue),
              components.scheme?.lowercased() == secureScheme,
              let host = components.host,
              isCanonicalPublicDNSHost(host),
              rawAuthority(in: rawValue) == canonicalAuthority(host: host, port: components.port),
              components.port == nil || components.port == httpsPort,
              components.user == nil,
              components.password == nil,
              components.fragment == nil,
              let url = components.url else {
            return nil
        }
        return url
    }

    private static func rawAuthority(in value: String) -> String {
        let authorityStart = value.index(value.startIndex, offsetBy: securePrefix.count)
        let suffix = value[authorityStart...]
        let authorityEnd = suffix.firstIndex(where: { $0 == "/" || $0 == "?" }) ?? value.endIndex
        return String(value[authorityStart..<authorityEnd])
    }

    private static func canonicalAuthority(host: String, port: Int?) -> String {
        port == httpsPort ? "\(host):\(httpsPort)" : host
    }

    private static func isCanonicalPublicDNSHost(_ host: String) -> Bool {
        guard host == host.lowercased(),
              host.count <= maximumHostLength,
              host.contains("."),
              host.unicodeScalars.allSatisfy({ dnsHostScalars.contains($0) }),
              host.contains(where: { !$0.isNumber && $0 != "." }),
              !forbiddenHostSuffixes.contains(where: { host == $0 || host.hasSuffix(".\($0)") }) else {
            return false
        }
        return host.split(separator: ".", omittingEmptySubsequences: false).allSatisfy { label in
            guard (1...maximumHostLabelLength).contains(label.count),
                  let first = label.unicodeScalars.first,
                  let last = label.unicodeScalars.last else {
                return false
            }
            return asciiAlphanumericScalars.contains(first) && asciiAlphanumericScalars.contains(last)
        }
    }
}

private let secureScheme = "https"
private let securePrefix = "https://"
private let httpsPort = 443
private let minimumHTTPSURLBytes = 9
private let maximumHTTPSURLBytes = 2_048
private let maximumHostLength = 253
private let maximumHostLabelLength = 63
private let asciiAlphanumericScalars = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789")
private let dnsHostScalars = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789-.")
private let forbiddenHostSuffixes = ["localhost", "local", "internal", "lan", "home.arpa"]
