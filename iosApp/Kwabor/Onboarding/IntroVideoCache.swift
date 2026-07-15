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
            try Task.checkCancellation()
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            let destination = cachedURL(revision: source.revision, sha256: source.sha256)
            if fileManager.fileExists(atPath: destination.path),
               try hasSupportedSize(fileURL: destination),
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

    func resolveCached(revision: Int64, sha256: String) async -> URL? {
        guard revision > 0, sha256.range(of: sha256Pattern, options: .regularExpression) != nil else {
            return nil
        }
        let destination = cachedURL(revision: revision, sha256: sha256)
        do {
            guard fileManager.fileExists(atPath: destination.path),
                  try hasSupportedSize(fileURL: destination),
                  try hasExpectedHash(fileURL: destination, expectedHash: sha256),
                  try await isSupportedIntroVideo(fileURL: destination) else {
                try? fileManager.removeItem(at: destination)
                return nil
            }
            return destination
        } catch {
            try? fileManager.removeItem(at: destination)
            return nil
        }
    }

    func clear() {
        guard !Task.isCancelled else { return }
        try? fileManager.removeItem(at: directory)
    }

    private func downloadAndValidate(
        source: FirebaseRemoteIntroVideo,
        destination: URL
    ) async throws -> URL? {
        let stagingURL = directory.appending(path: "\(destination.lastPathComponent).part")
        try? fileManager.removeItem(at: stagingURL)
        defer { try? fileManager.removeItem(at: stagingURL) }

        var request = URLRequest(url: source.url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = requestTimeout
        let requestDelegate = IntroVideoRequestDelegate()
        let (bytes, response) = try await session.bytes(for: request, delegate: requestDelegate)
        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode),
              httpResponse.url?.scheme?.lowercased() == "https",
              httpResponse.mimeType?.lowercased() == mp4MimeType,
              httpResponse.expectedContentLength >= unknownContentLength,
              httpResponse.expectedContentLength <= maximumVideoBytes else {
            return nil
        }
        try await write(bytes: bytes, to: stagingURL)
        guard try hasExpectedHash(fileURL: stagingURL, expectedHash: source.sha256),
              try await isSupportedIntroVideo(fileURL: stagingURL) else {
            return nil
        }
        try Task.checkCancellation()

        if fileManager.fileExists(atPath: destination.path) {
            _ = try fileManager.replaceItemAt(destination, withItemAt: stagingURL)
        } else {
            try fileManager.moveItem(at: stagingURL, to: destination)
        }
        removeOldVersions(except: destination)
        return destination
    }

    private func write(bytes: URLSession.AsyncBytes, to fileURL: URL) async throws {
        guard fileManager.createFile(atPath: fileURL.path, contents: nil) else {
            throw IntroVideoDownloadError.cannotCreateStagingFile
        }
        let fileHandle = try FileHandle(forWritingTo: fileURL)
        defer { try? fileHandle.close() }

        var buffer = Data()
        buffer.reserveCapacity(downloadBufferSize)
        var totalBytes: Int64 = 0
        for try await byte in bytes {
            try Task.checkCancellation()
            totalBytes += 1
            guard totalBytes <= maximumVideoBytes else {
                throw IntroVideoDownloadError.maximumSizeExceeded
            }
            buffer.append(byte)
            if buffer.count == downloadBufferSize {
                try fileHandle.write(contentsOf: buffer)
                buffer.removeAll(keepingCapacity: true)
            }
        }
        guard totalBytes > 0 else {
            throw IntroVideoDownloadError.emptyBody
        }
        if !buffer.isEmpty {
            try fileHandle.write(contentsOf: buffer)
        }
        try fileHandle.synchronize()
    }

    private func cachedURL(revision: Int64, sha256: String) -> URL {
        let hashPrefix = String(sha256.prefix(hashFilePrefixLength))
        return directory.appending(path: "intro-\(revision)-\(hashPrefix).mp4")
    }

    private func hasExpectedHash(fileURL: URL, expectedHash: String) throws -> Bool {
        let data = try Data(contentsOf: fileURL, options: .mappedIfSafe)
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        return digest == expectedHash
    }

    private func hasSupportedSize(fileURL: URL) throws -> Bool {
        let size = Int64(try fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
        return size > 0 && size <= maximumVideoBytes
    }

    private func isSupportedIntroVideo(fileURL: URL) async throws -> Bool {
        let asset = AVURLAsset(url: fileURL)
        let duration = try await asset.load(.duration).seconds
        guard duration.isFinite,
              minimumVideoDuration...maximumVideoDuration ~= duration else {
            return false
        }
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        guard audioTracks.isEmpty else {
            return false
        }
        let tracks = try await asset.loadTracks(withMediaType: .video)
        guard tracks.count == 1, let track = tracks.first else {
            return false
        }
        let descriptions = try await track.load(.formatDescriptions)
        let size = try await track.load(.naturalSize)
        let transform = try await track.load(.preferredTransform)
        let displayedSize = size.applying(transform)
        let isPortrait = abs(displayedSize.height) > abs(displayedSize.width)
        let isH264 = descriptions.contains { description in
            CMFormatDescriptionGetMediaSubType(description) == kCMVideoCodecType_H264
        }
        return isPortrait && isH264
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

private final class IntroVideoRequestDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

private enum IntroVideoDownloadError: Error {
    case cannotCreateStagingFile
    case emptyBody
    case maximumSizeExceeded
}

private let directoryName = "intro-media"
private let mp4Extension = "mp4"
private let mp4MimeType = "video/mp4"
private let maximumVideoBytes: Int64 = 3 * 1_024 * 1_024
private let minimumVideoDuration = 15.0
private let maximumVideoDuration = 25.5
private let requestTimeout: TimeInterval = 30
private let hashFilePrefixLength = 12
private let downloadBufferSize = 8_192
private let unknownContentLength: Int64 = -1
private let sha256Pattern = "^[a-f0-9]{64}$"
