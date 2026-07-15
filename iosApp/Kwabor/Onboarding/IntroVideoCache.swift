import AVFoundation
import CoreMedia
import CryptoKit
import Foundation

actor IntroVideoCache {
    private let fileManager: FileManager
    private let session: URLSession
    private let directory: URL

    init(fileManager: FileManager = .default, session: URLSession = .shared) {
        self.fileManager = fileManager
        self.session = session
        let root = fileManager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        directory = root.appending(path: directoryName, directoryHint: .isDirectory)
    }

    func resolve(source: FirebaseRemoteIntroVideo) async -> URL? {
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            let destination = cachedURL(for: source)
            if fileManager.fileExists(atPath: destination.path),
               try hasExpectedHash(fileURL: destination, expectedHash: source.sha256),
               try await isSupportedIntroVideo(fileURL: destination) {
                return destination
            }
            try? fileManager.removeItem(at: destination)
            return try await downloadAndValidate(source: source, destination: destination)
        } catch {
            return nil
        }
    }

    func clear() {
        try? fileManager.removeItem(at: directory)
    }

    private func downloadAndValidate(
        source: FirebaseRemoteIntroVideo,
        destination: URL
    ) async throws -> URL? {
        var request = URLRequest(url: source.url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = requestTimeout
        let (downloadedURL, response) = try await session.download(for: request)
        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode),
              httpResponse.url?.scheme?.lowercased() == "https",
              httpResponse.mimeType?.lowercased() == mp4MimeType,
              httpResponse.expectedContentLength <= maximumVideoBytes else {
            return nil
        }
        let size = try downloadedURL.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= maximumVideoBytes,
              try hasExpectedHash(fileURL: downloadedURL, expectedHash: source.sha256),
              try await isSupportedIntroVideo(fileURL: downloadedURL) else {
            return nil
        }
        try Task.checkCancellation()

        let stagingURL = directory.appending(path: "\(destination.lastPathComponent).part")
        try? fileManager.removeItem(at: stagingURL)
        try fileManager.moveItem(at: downloadedURL, to: stagingURL)
        if fileManager.fileExists(atPath: destination.path) {
            _ = try fileManager.replaceItemAt(destination, withItemAt: stagingURL)
        } else {
            try fileManager.moveItem(at: stagingURL, to: destination)
        }
        removeOldVersions(except: destination)
        return destination
    }

    private func cachedURL(for source: FirebaseRemoteIntroVideo) -> URL {
        let hashPrefix = String(source.sha256.prefix(hashFilePrefixLength))
        return directory.appending(path: "intro-\(source.revision)-\(hashPrefix).mp4")
    }

    private func hasExpectedHash(fileURL: URL, expectedHash: String) throws -> Bool {
        let data = try Data(contentsOf: fileURL, options: .mappedIfSafe)
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        return digest == expectedHash
    }

    private func isSupportedIntroVideo(fileURL: URL) async throws -> Bool {
        let asset = AVURLAsset(url: fileURL)
        let duration = try await asset.load(.duration).seconds
        guard duration.isFinite,
              minimumVideoDuration...maximumVideoDuration ~= duration else {
            return false
        }
        let tracks = try await asset.loadTracks(withMediaType: .video)
        for track in tracks {
            let descriptions = try await track.load(.formatDescriptions)
            let size = try await track.load(.naturalSize)
            let transform = try await track.load(.preferredTransform)
            let displayedSize = size.applying(transform)
            let isPortrait = abs(displayedSize.height) > abs(displayedSize.width)
            let isH264 = descriptions.contains { description in
                CMFormatDescriptionGetMediaSubType(description) == kCMVideoCodecType_H264
            }
            if isPortrait && isH264 {
                return true
            }
        }
        return false
    }

    private func removeOldVersions(except destination: URL) {
        guard let files = try? fileManager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil
        ) else {
            return
        }
        for fileURL in files where fileURL != destination && fileURL.pathExtension == mp4Extension {
            try? fileManager.removeItem(at: fileURL)
        }
    }
}

private let directoryName = "intro-media"
private let mp4Extension = "mp4"
private let mp4MimeType = "video/mp4"
private let maximumVideoBytes: Int64 = 5 * 1_024 * 1_024
private let minimumVideoDuration = 15.0
private let maximumVideoDuration = 25.5
private let requestTimeout: TimeInterval = 30
private let hashFilePrefixLength = 12
