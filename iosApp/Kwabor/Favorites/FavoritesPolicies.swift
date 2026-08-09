import Foundation

struct FavoritesPaginationGuard {
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

enum FavoritesPaginationPolicy {
    static func isNearEnd(index: Int, itemCount: Int, threshold: Int = 4) -> Bool {
        guard index >= 0, itemCount > 0, threshold > 0, index < itemCount else { return false }
        return index >= max(itemCount - threshold, 0)
    }
}

enum FavoritesGridPolicy {
    static func columnCount(
        availableWidth: Double,
        tabletBreakpoint: Double,
        usesAccessibilityLayout: Bool
    ) -> Int {
        guard availableWidth.isFinite,
              availableWidth >= 0,
              tabletBreakpoint.isFinite,
              tabletBreakpoint > 0 else {
            return 1
        }
        if usesAccessibilityLayout {
            return 1
        }
        return availableWidth >= tabletBreakpoint ? 3 : 2
    }
}

enum FavoritesViewerTransitionPolicy {
    static func normalizedAccountID(_ rawValue: String?) -> String? {
        let candidate = rawValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return candidate.isEmpty ? nil : candidate
    }

    static func shouldHidePrivateContent(
        currentAccountID: String?,
        nextAccountID: String?
    ) -> Bool {
        normalizedAccountID(currentAccountID) != normalizedAccountID(nextAccountID)
    }
}

struct FavoritesCardDecorationVisibility: Equatable {
    let showsEndedRibbon: Bool
    let showsRating: Bool
}

enum FavoritesCardDecorationPolicy {
    static func visibility(
        isEventEnded: Bool,
        ratingLabel: String?
    ) -> FavoritesCardDecorationVisibility {
        let normalizedRating = ratingLabel?.trimmingCharacters(in: .whitespacesAndNewlines)
        return FavoritesCardDecorationVisibility(
            showsEndedRibbon: isEventEnded,
            showsRating: normalizedRating?.isEmpty == false
        )
    }
}
