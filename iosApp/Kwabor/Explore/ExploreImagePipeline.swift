import Foundation
import ImageIO
import SwiftUI
import UIKit

actor ExploreImagePipeline {
    static let shared = ExploreImagePipeline()

    private struct InFlightRequest {
        let id: UUID
        let task: Task<UIImage?, Never>
        var waiters: Set<UUID>
    }

    private let imageCache = NSCache<NSString, UIImage>()
    private let session: URLSession
    private let downloadLimiter = ExploreImageDownloadLimiter(maxConcurrent: maximumConcurrentImageDownloads)
    private var inFlightRequests: [String: InFlightRequest] = [:]

    init() {
        let urlCache = URLCache(
            memoryCapacity: imageURLCacheMemoryBytes,
            diskCapacity: imageURLCacheDiskBytes
        )
        let configuration = URLSessionConfiguration.default
        configuration.urlCache = urlCache
        configuration.requestCachePolicy = .returnCacheDataElseLoad
        configuration.timeoutIntervalForRequest = imageRequestTimeoutSeconds
        configuration.timeoutIntervalForResource = imageResourceTimeoutSeconds
        configuration.httpMaximumConnectionsPerHost = maximumConcurrentImageDownloads
        session = URLSession(configuration: configuration)
        imageCache.countLimit = imageMemoryCacheCount
        imageCache.totalCostLimit = imageMemoryCacheCostBytes
    }

    func image(for url: URL, maxPixelSize: Int) async -> UIImage? {
        let boundedPixelSize = min(max(maxPixelSize, minimumImagePixelSize), maximumImagePixelSize)
        let key = "\(url.absoluteString)#\(boundedPixelSize)"
        let cacheKey = key as NSString
        if let cached = imageCache.object(forKey: cacheKey) {
            return cached
        }
        let waiterID = UUID()
        let requestID: UUID
        let task: Task<UIImage?, Never>
        if var inFlight = inFlightRequests[key] {
            inFlight.waiters.insert(waiterID)
            inFlightRequests[key] = inFlight
            requestID = inFlight.id
            task = inFlight.task
        } else {
            requestID = UUID()
            let session = session
            let limiter = downloadLimiter
            task = Task(priority: .utility) {
                guard await limiter.acquire() else { return nil }
                let image = await Self.downloadAndDownsample(
                    url: url,
                    maxPixelSize: boundedPixelSize,
                    session: session
                )
                await limiter.release()
                return image
            }
            inFlightRequests[key] = InFlightRequest(
                id: requestID,
                task: task,
                waiters: [waiterID]
            )
        }

        return await withTaskCancellationHandler(
            operation: {
                let image = await task.value
                let cancelled = Task.isCancelled
                await self.completeWaiter(
                    key: key,
                    cacheKey: cacheKey,
                    requestID: requestID,
                    waiterID: waiterID,
                    image: cancelled ? nil : image
                )
                return cancelled ? nil : image
            },
            onCancel: {
                Task {
                    await self.cancelWaiter(key: key, requestID: requestID, waiterID: waiterID)
                }
            }
        )
    }

    private func completeWaiter(
        key: String,
        cacheKey: NSString,
        requestID: UUID,
        waiterID: UUID,
        image: UIImage?
    ) {
        guard var inFlight = inFlightRequests[key],
              inFlight.id == requestID,
              inFlight.waiters.remove(waiterID) != nil else {
            return
        }
        if let image {
            imageCache.setObject(image, forKey: cacheKey, cost: image.memoryCost)
        }
        if inFlight.waiters.isEmpty {
            inFlightRequests[key] = nil
        } else {
            inFlightRequests[key] = inFlight
        }
    }

    private func cancelWaiter(key: String, requestID: UUID, waiterID: UUID) {
        guard var inFlight = inFlightRequests[key],
              inFlight.id == requestID,
              inFlight.waiters.remove(waiterID) != nil else {
            return
        }
        if inFlight.waiters.isEmpty {
            inFlight.task.cancel()
            inFlightRequests[key] = nil
        } else {
            inFlightRequests[key] = inFlight
        }
    }

    private static func downloadAndDownsample(
        url: URL,
        maxPixelSize: Int,
        session: URLSession
    ) async -> UIImage? {
        do {
            guard !Task.isCancelled else { return nil }
            let (bytes, response) = try await session.bytes(from: url)
            var didConsumeResponse = false
            defer {
                if !didConsumeResponse {
                    bytes.task.cancel()
                }
            }
            guard let response = response as? HTTPURLResponse,
                  (200..<300).contains(response.statusCode),
                  response.mimeType?.lowercased().hasPrefix(imageMimePrefix) == true else {
                return nil
            }
            let expectedLength = response.expectedContentLength
            guard expectedLength == NSURLSessionTransferSizeUnknown ||
                    expectedLength <= maximumImageResponseBytes else {
                return nil
            }
            var data = Data()
            if expectedLength > 0 {
                data.reserveCapacity(Int(expectedLength))
            }
            for try await byte in bytes {
                guard !Task.isCancelled, data.count < maximumImageResponseBytes else {
                    return nil
                }
                data.append(byte)
            }
            didConsumeResponse = true
            guard !data.isEmpty else { return nil }
            return downsample(data: data, maxPixelSize: maxPixelSize)
        } catch {
            return nil
        }
    }

    private static func downsample(data: Data, maxPixelSize: Int) -> UIImage? {
        autoreleasepool {
            let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
            guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions) else {
                return nil
            }
            let options = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
            ] as CFDictionary
            guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, options) else {
                return nil
            }
            return UIImage(cgImage: thumbnail)
        }
    }
}

private actor ExploreImageDownloadLimiter {
    private struct Waiter {
        let id: UUID
        let continuation: CheckedContinuation<Bool, Never>
    }

    private var availablePermits: Int
    private var waiters: [Waiter] = []

    init(maxConcurrent: Int) {
        availablePermits = max(1, maxConcurrent)
    }

    func acquire() async -> Bool {
        guard !Task.isCancelled else { return false }
        if availablePermits > 0 {
            availablePermits -= 1
            return true
        }

        let waiterID = UUID()
        return await withTaskCancellationHandler(
            operation: {
                await withCheckedContinuation { continuation in
                    if Task.isCancelled {
                        continuation.resume(returning: false)
                    } else {
                        waiters.append(Waiter(id: waiterID, continuation: continuation))
                    }
                }
            },
            onCancel: {
                Task {
                    await self.cancel(waiterID: waiterID)
                }
            }
        )
    }

    func release() {
        if waiters.isEmpty {
            availablePermits += 1
        } else {
            let waiter = waiters.removeFirst()
            waiter.continuation.resume(returning: true)
        }
    }

    private func cancel(waiterID: UUID) {
        guard let index = waiters.firstIndex(where: { waiter -> Bool in waiter.id == waiterID }) else {
            return
        }
        let waiter = waiters.remove(at: index)
        waiter.continuation.resume(returning: false)
    }
}

struct ExploreRemoteImage: View {
    let rawURL: String?

    @Environment(\.displayScale) private var displayScale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                LinearGradient(
                    colors: [KwaborDesignTokens.ColorToken.ink700, KwaborDesignTokens.ColorToken.ink950],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .transition(reduceMotion ? .identity : .opacity)
                } else {
                    Image(systemName: "photo")
                        .font(.title2)
                        .foregroundStyle(KwaborDesignTokens.ColorToken.ink100.opacity(0.72))
                }
            }
            .clipped()
            .task(id: requestKey(size: proxy.size)) {
                image = nil
                guard let request = requestKey(size: proxy.size) else { return }
                image = await ExploreImagePipeline.shared.image(
                    for: request.url,
                    maxPixelSize: request.maxPixelSize
                )
            }
        }
        .accessibilityHidden(true)
    }

    private func requestKey(size: CGSize) -> ExploreImageRequestKey? {
        guard let url = ExploreRemoteImageURLPolicy.acceptedURL(rawURL) else { return nil }
        let longestSide = max(size.width, size.height)
        guard longestSide.isFinite, longestSide > 0 else { return nil }
        return ExploreImageRequestKey(
            url: url,
            maxPixelSize: Int(ceil(longestSide * displayScale))
        )
    }
}

private struct ExploreImageRequestKey: Hashable {
    let url: URL
    let maxPixelSize: Int
}

private extension UIImage {
    var memoryCost: Int {
        guard let cgImage else { return 0 }
        return cgImage.bytesPerRow * cgImage.height
    }
}

private let imageURLCacheMemoryBytes = 16 * 1_024 * 1_024
private let imageURLCacheDiskBytes = 64 * 1_024 * 1_024
private let imageMemoryCacheCount = 48
private let imageMemoryCacheCostBytes = 32 * 1_024 * 1_024
private let maximumImageResponseBytes = 8 * 1_024 * 1_024
private let maximumConcurrentImageDownloads = 4
private let minimumImagePixelSize = 64
private let maximumImagePixelSize = 2_048
private let imageRequestTimeoutSeconds: TimeInterval = 15
private let imageResourceTimeoutSeconds: TimeInterval = 30
private let imageMimePrefix = "image/"
