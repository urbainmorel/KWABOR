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
              !rawValue.isEmpty,
              rawValue.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
              let components = URLComponents(string: rawValue),
              components.scheme?.lowercased() == secureScheme,
              components.host?.isEmpty == false,
              components.user == nil,
              components.password == nil,
              components.fragment == nil,
              let url = components.url else {
            return nil
        }
        return url
    }
}

private let secureScheme = "https"
