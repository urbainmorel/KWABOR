import SwiftUI

enum KwaborDesignTokens {
    enum ColorToken {
        static let ink950 = Color(red: 0.055, green: 0.055, blue: 0.063)
        static let ink900 = Color(red: 0.102, green: 0.102, blue: 0.110)
        static let ink700 = Color(red: 0.235, green: 0.235, blue: 0.255)
        static let ink100 = Color(red: 0.945, green: 0.945, blue: 0.953)
        static let paper50 = Color(red: 0.980, green: 0.980, blue: 0.973)
        static let surface0 = Color.white
        static let sponsored = Color(red: 0.957, green: 0.706, blue: 0.000)
        static let ticket = Color(red: 0.773, green: 0.157, blue: 0.239)
    }

    enum Spacing {
        static let xs: CGFloat = 4
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 16
        static let xl: CGFloat = 20
        static let xxl: CGFloat = 24
        static let xxxl: CGFloat = 32
    }

    enum Radius {
        static let card: CGFloat = 16
        static let control: CGFloat = 14
        static let sheet: CGFloat = 28
        static let pill: CGFloat = 999
    }

    enum Sizing {
        static let touchTarget: CGFloat = 44
    }

    enum Alpha {
        static let scrimHigh = 0.72
    }
}
