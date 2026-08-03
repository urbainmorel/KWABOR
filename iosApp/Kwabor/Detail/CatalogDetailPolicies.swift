import Foundation

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
