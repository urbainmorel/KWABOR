import Foundation

struct CatalogDetailDeepLinkDelivery: Equatable {
    let listingID: String
    let revision: UInt64
}

struct PendingInternalDeepLink: Equatable {
    private(set) var rootDestinationKey: String?
    private(set) var catalogDetailListingID: String?
    private var catalogDetailDeliveryRevision: UInt64 = 0

    init() {}

    var catalogDetailDelivery: CatalogDetailDeepLinkDelivery? {
        guard let catalogDetailListingID else { return nil }
        return CatalogDetailDeepLinkDelivery(
            listingID: catalogDetailListingID,
            revision: catalogDetailDeliveryRevision
        )
    }

    mutating func enqueueRoot(destinationKey: String) {
        rootDestinationKey = destinationKey
        catalogDetailListingID = nil
    }

    @discardableResult
    mutating func enqueueCatalogDetail(validatedListingID: String?) -> Bool {
        guard let validatedListingID else { return false }
        rootDestinationKey = nil
        guard catalogDetailListingID != validatedListingID else { return true }
        catalogDetailDeliveryRevision &+= 1
        catalogDetailListingID = validatedListingID
        return true
    }

    @discardableResult
    mutating func consumeRoot() -> Bool {
        guard rootDestinationKey != nil else { return false }
        rootDestinationKey = nil
        return true
    }

    func isCurrentCatalogDetail(delivery: CatalogDetailDeepLinkDelivery) -> Bool {
        catalogDetailDelivery == delivery
    }

    @discardableResult
    mutating func acknowledgeCatalogDetail(delivery: CatalogDetailDeepLinkDelivery) -> Bool {
        guard isCurrentCatalogDetail(delivery: delivery) else { return false }
        catalogDetailListingID = nil
        return true
    }

    mutating func clear() {
        rootDestinationKey = nil
        catalogDetailListingID = nil
    }
}

enum InternalDeepLinkIngressPolicy {
    static func shouldRetain(
        validatedDestinationExists: Bool,
        isSigningOut: Bool,
        isDeletingAccount: Bool
    ) -> Bool {
        validatedDestinationExists && !isSigningOut && !isDeletingAccount
    }
}

enum CatalogDetailDeepLinkPostBootstrapAction: Equatable {
    case wait
    case openWhenHome
}

enum CatalogDetailDeepLinkPostBootstrapPolicy {
    static func action(
        hasPendingListing: Bool,
        isIntroComplete: Bool,
        isSessionRestoreComplete: Bool,
        isBlockingFlowActive: Bool,
        hasAuthenticatedAccount: Bool,
        hasExplicitGuestAccess: Bool
    ) -> CatalogDetailDeepLinkPostBootstrapAction {
        guard hasPendingListing,
              isIntroComplete,
              isSessionRestoreComplete,
              !isBlockingFlowActive,
              hasAuthenticatedAccount || hasExplicitGuestAccess else {
            return .wait
        }
        return .openWhenHome
    }
}

enum CatalogDetailLayoutPolicy {
    static let mobileSheetHeightFraction: CGFloat = 0.92
    static let tabletSheetHeightFraction: CGFloat = 0.85
    static let tabletBreakpoint: CGFloat = 600
    static let heroHeightFraction: CGFloat = 0.58
    static let heroMinimumHeight: CGFloat = 320
    static let maximumContentWidth: CGFloat = 640

    static func sheetHeightFraction(forWidth availableWidth: CGFloat) -> CGFloat {
        guard !availableWidth.isNaN else { return mobileSheetHeightFraction }
        return availableWidth < tabletBreakpoint
            ? mobileSheetHeightFraction
            : tabletSheetHeightFraction
    }

    static func heroHeight(forSheetHeight sheetHeight: CGFloat) -> CGFloat {
        guard sheetHeight.isFinite, sheetHeight > 0 else { return heroMinimumHeight }
        return max(heroMinimumHeight, sheetHeight * heroHeightFraction)
    }

    static func sheetWidth(availableWidth: CGFloat) -> CGFloat {
        guard !availableWidth.isNaN else { return 0 }
        return min(max(availableWidth, 0), maximumContentWidth)
    }
}

enum CatalogDetailDescriptionPolicy {
    static let previewCharacterLimit = 150
    static let minimumWordBoundary = 120

    static func needsExpansion(_ description: String) -> Bool {
        description.count > previewCharacterLimit
    }

    static func preview(_ description: String) -> String {
        guard needsExpansion(description) else {
            return description
        }

        let candidate = String(description.prefix(previewCharacterLimit))
        let minimumIndex = candidate.index(
            candidate.startIndex,
            offsetBy: minimumWordBoundary
        )
        let wordBoundary = candidate[minimumIndex...]
            .lastIndex(where: { character in character.isWhitespace })
        let preview = wordBoundary.map { boundary in
            String(candidate[..<boundary])
        } ?? candidate

        return preview.trimmingTrailingWhitespace() + ellipsis
    }
}

enum CatalogDetailLabelPolicy {
    static func pluralizedLabel(
        count: Int,
        singular: String,
        plural: String
    ) -> String {
        count == 1 ? singular : plural
    }
}

private extension String {
    func trimmingTrailingWhitespace() -> String {
        var trimmedEnd = endIndex
        while trimmedEnd > startIndex {
            let previousIndex = index(before: trimmedEnd)
            guard self[previousIndex].isWhitespace else { break }
            trimmedEnd = previousIndex
        }
        return String(self[..<trimmedEnd])
    }
}

private let ellipsis = "…"
