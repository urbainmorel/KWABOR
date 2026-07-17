import CoreLocation
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
final class CoreLocationRegistrationService: NSObject, RegistrationLocationProviding {
    private let manager: CLLocationManager
    private var continuation: CheckedContinuation<RegistrationLocationResult, Never>?
    private var timeoutTask: Task<Void, Never>?

    init(manager: CLLocationManager = CLLocationManager()) {
        self.manager = manager
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
    }

    func requestCurrentLocation() async -> RegistrationLocationResult {
        guard continuation == nil else { return .unavailable }

        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            timeoutTask = Task { [weak self] in
                do {
                    try await Task.sleep(nanoseconds: locationTimeoutNanoseconds)
                } catch {
                    return
                }
                guard !Task.isCancelled else { return }
                self?.finish(with: .unavailable)
            }
            switch manager.authorizationStatus {
            case .authorizedAlways, .authorizedWhenInUse:
                manager.requestLocation()
            case .notDetermined:
                manager.requestWhenInUseAuthorization()
            case .denied, .restricted:
                finish(with: .permissionDenied)
            @unknown default:
                finish(with: .unavailable)
            }
        }
    }

    private func finish(with result: RegistrationLocationResult) {
        guard let continuation else { return }
        timeoutTask?.cancel()
        timeoutTask = nil
        self.continuation = nil
        continuation.resume(returning: result)
    }
}

extension CoreLocationRegistrationService: CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard continuation != nil else { return }
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            manager.requestLocation()
        case .denied, .restricted:
            finish(with: .permissionDenied)
        case .notDetermined:
            break
        @unknown default:
            finish(with: .unavailable)
        }
    }

    func locationManager(_: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else {
            finish(with: .unavailable)
            return
        }
        finish(
            with: .coordinate(
                RegistrationCoordinate(
                    latitude: location.coordinate.latitude,
                    longitude: location.coordinate.longitude
                )
            )
        )
    }

    func locationManager(_: CLLocationManager, didFailWithError _: Error) {
        finish(with: .unavailable)
    }
}

private let locationTimeoutNanoseconds: UInt64 = 12_000_000_000

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
