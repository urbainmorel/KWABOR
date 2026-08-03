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
