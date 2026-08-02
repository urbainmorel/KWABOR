import Foundation
import UserNotifications

struct RegistrationCoordinate {
    let latitude: Double
    let longitude: Double
}

enum RegistrationLocationResult {
    case coordinate(RegistrationCoordinate)
    case permissionDenied
    case unavailable
}

@MainActor
protocol RegistrationLocationProviding {
    func requestCurrentLocation() async -> RegistrationLocationResult
}

@MainActor
final class CoreLocationRegistrationService: RegistrationLocationProviding {
    private let provider: ApproximateLocationProviding

    init(provider: ApproximateLocationProviding? = nil) {
        self.provider = provider ?? CoreLocationApproximateLocationProvider()
    }

    func requestCurrentLocation() async -> RegistrationLocationResult {
        switch await provider.requestCurrentLocation() {
        case let .coordinate(coordinate):
            return .coordinate(
                RegistrationCoordinate(
                    latitude: coordinate.latitude,
                    longitude: coordinate.longitude
                )
            )
        case .permissionDenied:
            return .permissionDenied
        case .disabled, .unavailable:
            return .unavailable
        }
    }
}

@MainActor
protocol RegistrationNotificationPermissionRequesting {
    func requestPermission() async -> RegistrationNotificationPermissionResult
}

enum RegistrationNotificationPermissionResult {
    case granted
    case denied
    case unavailable
}

protocol RegistrationNotificationPrimingPersisting {
    var isResolved: Bool { get }

    func markResolved()
}

struct UserDefaultsRegistrationNotificationPrimingStore:
    RegistrationNotificationPrimingPersisting {
    private let userDefaults: UserDefaults

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
    }

    var isResolved: Bool {
        userDefaults.bool(forKey: notificationPrimingResolvedKey)
    }

    func markResolved() {
        userDefaults.set(true, forKey: notificationPrimingResolvedKey)
    }
}

@MainActor
struct UserNotificationRegistrationService: RegistrationNotificationPermissionRequesting {
    private let center: UNUserNotificationCenter

    init(center: UNUserNotificationCenter = .current()) {
        self.center = center
    }

    func requestPermission() async -> RegistrationNotificationPermissionResult {
        do {
            let granted = try await center.requestAuthorization(options: [.alert, .badge, .sound])
            return granted ? .granted : .denied
        } catch {
            return .unavailable
        }
    }
}

private let notificationPrimingResolvedKey = "kwabor.registration.notification_priming_resolved_v1"
