import Foundation

enum CatalogDetailExternalURLTarget: Equatable {
    case directions(latitude: Double, longitude: Double, label: String)
    case phone(String)
    case whatsapp(String)
    case email(String)
    case https(String)
}

enum CatalogDetailExternalURLPolicy {
    static func url(for target: CatalogDetailExternalURLTarget) -> URL? {
        switch target {
        case let .directions(latitude, longitude, label):
            return directionsURL(latitude: latitude, longitude: longitude, label: label)
        case let .phone(value):
            return phoneURL(value)
        case let .whatsapp(value):
            return whatsappURL(value)
        case let .email(value):
            return emailURL(value)
        case let .https(value):
            return acceptedHTTPSURL(value)
        }
    }

    static func directionsURL(latitude: Double, longitude: Double, label: String) -> URL? {
        guard latitude.isFinite,
              longitude.isFinite,
              (-90...90).contains(latitude),
              (-180...180).contains(longitude),
              isValidDirectionsLabel(label) else {
            return nil
        }

        var components = URLComponents()
        components.scheme = secureScheme
        components.host = googleMapsHost
        components.path = "/maps/dir/"
        components.queryItems = [
            URLQueryItem(name: "api", value: "1"),
            URLQueryItem(name: "destination", value: "\(latitude),\(longitude)"),
        ]

        guard let rawValue = components.string else { return nil }
        return acceptedHTTPSURL(rawValue)
    }

    static func phoneURL(_ rawValue: String?) -> URL? {
        guard let rawValue, isValidBeninPhone(rawValue) else { return nil }
        return URL(string: "tel:\(rawValue)")
    }

    static func whatsappURL(_ rawValue: String?) -> URL? {
        guard let rawValue, isValidBeninPhone(rawValue) else { return nil }
        let digits = rawValue.dropFirst()
        return acceptedHTTPSURL("https://wa.me/\(digits)")
    }

    static func emailURL(_ rawValue: String?) -> URL? {
        guard let rawValue,
              (minimumEmailBytes...maximumEmailBytes).contains(rawValue.utf8.count),
              rawValue.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
              rawValue.rangeOfCharacter(from: .controlCharacters) == nil else {
            return nil
        }

        let parts = rawValue.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return nil }

        let localPart = String(parts[0])
        let domain = String(parts[1]).lowercased()
        guard (1...maximumEmailLocalPartBytes).contains(localPart.utf8.count),
              localPart.unicodeScalars.allSatisfy({ emailLocalPartScalars.contains($0) }),
              localPart.first != ".",
              localPart.last != ".",
              !localPart.contains(".."),
              isCanonicalPublicDNSHost(domain) else {
            return nil
        }

        var components = URLComponents()
        components.scheme = mailtoScheme
        components.path = "\(localPart)@\(domain)"
        return components.url
    }

    static func acceptedHTTPSURL(_ rawValue: String?) -> URL? {
        guard let rawValue,
              (minimumHTTPSURLBytes...maximumHTTPSURLBytes).contains(rawValue.utf8.count),
              rawValue.hasPrefix(securePrefix),
              rawValue.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
              rawValue.rangeOfCharacter(from: .controlCharacters) == nil,
              !rawValue.contains("\\"),
              hasSafePercentEncoding(rawValue),
              let components = URLComponents(string: rawValue),
              components.scheme == secureScheme,
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

    private static func isValidDirectionsLabel(_ value: String) -> Bool {
        guard (minimumDirectionsLabelCharacters...maximumDirectionsLabelCharacters).contains(value.unicodeScalars.count),
              value == value.trimmingCharacters(in: .whitespacesAndNewlines),
              value.rangeOfCharacter(from: .controlCharacters) == nil else {
            return false
        }
        return value.unicodeScalars.contains { !CharacterSet.whitespacesAndNewlines.contains($0) }
    }

    private static func isValidBeninPhone(_ value: String) -> Bool {
        guard value.hasPrefix(beninCallingCode) else { return false }
        let nationalNumber = value.dropFirst(beninCallingCode.count)
        guard (minimumNationalNumberDigits...maximumNationalNumberDigits).contains(
            nationalNumber.count
        ) else {
            return false
        }
        return nationalNumber.unicodeScalars.allSatisfy { asciiDigitScalars.contains($0) }
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
              !isIPv4LikeHost(host),
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

    private static func isIPv4LikeHost(_ host: String) -> Bool {
        host.split(separator: ".", omittingEmptySubsequences: false).allSatisfy { label in
            if label.unicodeScalars.allSatisfy({ asciiDigitScalars.contains($0) }) {
                return true
            }
            guard label.hasPrefix("0x"), label.count > 2 else { return false }
            return label.dropFirst(2).unicodeScalars.allSatisfy { hexadecimalScalars.contains($0) }
        }
    }

    private static func hasSafePercentEncoding(_ value: String) -> Bool {
        let scalars = Array(value.unicodeScalars)
        var index = 0
        while index < scalars.count {
            guard scalars[index] == "%" else {
                index += 1
                continue
            }
            guard index + 2 < scalars.count,
                  let high = hexadecimalValue(scalars[index + 1]),
                  let low = hexadecimalValue(scalars[index + 2]) else {
                return false
            }
            let decodedByte = high * 16 + low
            guard decodedByte >= 0x20,
                  decodedByte != 0x7F,
                  decodedByte != 0x5C else {
                return false
            }
            index += 3
        }
        return true
    }

    private static func hexadecimalValue(_ scalar: Unicode.Scalar) -> UInt8? {
        switch scalar.value {
        case 48...57:
            return UInt8(scalar.value - 48)
        case 65...70:
            return UInt8(scalar.value - 55)
        case 97...102:
            return UInt8(scalar.value - 87)
        default:
            return nil
        }
    }

    private static let secureScheme = "https"
    private static let securePrefix = "https://"
    private static let mailtoScheme = "mailto"
    private static let googleMapsHost = "www.google.com"
    private static let beninCallingCode = "+229"
    private static let httpsPort = 443
    private static let minimumHTTPSURLBytes = 9
    private static let maximumHTTPSURLBytes = 2_048
    private static let minimumEmailBytes = 3
    private static let maximumEmailBytes = 254
    private static let maximumEmailLocalPartBytes = 64
    private static let minimumDirectionsLabelCharacters = 1
    private static let maximumDirectionsLabelCharacters = 80
    private static let minimumNationalNumberDigits = 5
    private static let maximumNationalNumberDigits = 12
    private static let maximumHostLength = 253
    private static let maximumHostLabelLength = 63
    private static let asciiDigitScalars = CharacterSet(charactersIn: "0123456789")
    private static let asciiAlphanumericScalars = CharacterSet(
        charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789"
    )
    private static let dnsHostScalars = CharacterSet(
        charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789-."
    )
    private static let emailLocalPartScalars = CharacterSet(
        charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.!#$%&'*+/=?^_`{|}~-"
    )
    private static let hexadecimalScalars = CharacterSet(charactersIn: "0123456789abcdefABCDEF")
    private static let forbiddenHostSuffixes = ["localhost", "local", "internal", "lan", "home.arpa"]
}
