import CoreLocation
import Foundation

struct ApproximateCoordinate: Equatable {
    let latitude: Double
    let longitude: Double
}

enum ApproximateLocationResult: Equatable {
    case coordinate(ApproximateCoordinate)
    case permissionDenied
    case disabled
    case unavailable
}

@MainActor
protocol ApproximateLocationProviding: AnyObject {
    func requestCurrentLocation() async -> ApproximateLocationResult
}

@MainActor
final class CoreLocationApproximateLocationProvider: NSObject, ApproximateLocationProviding {
    private let manager: CLLocationManager
    private var continuation: CheckedContinuation<ApproximateLocationResult, Never>?
    private var timeoutTask: Task<Void, Never>?

    init(manager: CLLocationManager = CLLocationManager()) {
        self.manager = manager
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
    }

    func requestCurrentLocation() async -> ApproximateLocationResult {
        guard continuation == nil else { return .unavailable }
        guard CLLocationManager.locationServicesEnabled() else { return .disabled }

        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                self.continuation = continuation
                if Task.isCancelled {
                    finish(with: .unavailable)
                    return
                }
                timeoutTask = Task { [weak self] in
                    do {
                        try await Task.sleep(nanoseconds: locationTimeoutNanoseconds)
                    } catch {
                        return
                    }
                    guard !Task.isCancelled else { return }
                    self?.finish(with: .unavailable)
                }
                requestAuthorizationOrLocation()
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.finish(with: .unavailable)
            }
        }
    }

    private func requestAuthorizationOrLocation() {
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

    private func finish(with result: ApproximateLocationResult) {
        guard let continuation else { return }
        timeoutTask?.cancel()
        timeoutTask = nil
        self.continuation = nil
        continuation.resume(returning: result)
    }
}

extension CoreLocationApproximateLocationProvider: @preconcurrency CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard continuation != nil else { return }
        guard CLLocationManager.locationServicesEnabled() else {
            finish(with: .disabled)
            return
        }
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
                ApproximateCoordinate(
                    latitude: location.coordinate.latitude,
                    longitude: location.coordinate.longitude
                )
            )
        )
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError _: Error) {
        guard CLLocationManager.locationServicesEnabled() else {
            finish(with: .disabled)
            return
        }
        if manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted {
            finish(with: .permissionDenied)
        } else {
            finish(with: .unavailable)
        }
    }
}

private let locationTimeoutNanoseconds: UInt64 = 12_000_000_000
