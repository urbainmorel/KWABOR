import Foundation
import OSLog

actor LegacyRemoteIntroCleaner {
    private let fileManager: FileManager
    private let userDefaults: UserDefaults
    private let cacheDirectory: URL?
    private let logger: Logger

    init(
        fileManager: FileManager = .default,
        userDefaults: UserDefaults = .standard,
        cacheRoot: URL? = nil
    ) {
        self.fileManager = fileManager
        self.userDefaults = userDefaults
        let resolvedCacheRoot = cacheRoot ?? fileManager.urls(
            for: .cachesDirectory,
            in: .userDomainMask
        ).first
        cacheDirectory = resolvedCacheRoot?.appending(
            path: legacyRemoteIntroCacheDirectoryName,
            directoryHint: .isDirectory
        )
        logger = Logger(subsystem: legacyCleanupLoggerSubsystem, category: legacyCleanupLoggerCategory)
    }

    func cleanIfNeeded() {
        guard cleanLegacyRemoteIntroStorage(
            fileManager: fileManager,
            userDefaults: userDefaults,
            cacheDirectory: cacheDirectory
        ) else {
            logger.error("Legacy remote intro cleanup is incomplete and will be retried.")
            return
        }
    }
}

@discardableResult
func cleanLegacyRemoteIntroStorage(
    fileManager: FileManager,
    userDefaults: UserDefaults,
    cacheDirectory: URL?
) -> Bool {
    guard !userDefaults.bool(forKey: legacyRemoteIntroCleanupCompletedKey) else { return true }

    legacyRemoteIntroPreferenceKeys.forEach { key in
        userDefaults.removeObject(forKey: key)
    }
    let cacheWasRemoved: Bool
    if let cacheDirectory, fileManager.fileExists(atPath: cacheDirectory.path) {
        do {
            try fileManager.removeItem(at: cacheDirectory)
            cacheWasRemoved = !fileManager.fileExists(atPath: cacheDirectory.path)
        } catch {
            cacheWasRemoved = false
        }
    } else {
        cacheWasRemoved = true
    }
    let preferencesWereRemoved = legacyRemoteIntroPreferenceKeys.allSatisfy { key in
        userDefaults.object(forKey: key) == nil
    }
    guard cacheWasRemoved, preferencesWereRemoved else { return false }

    userDefaults.set(true, forKey: legacyRemoteIntroCleanupCompletedKey)
    return userDefaults.bool(forKey: legacyRemoteIntroCleanupCompletedKey)
}

let legacyRemoteIntroPreferenceKeys = [
    "kwabor.intro.pending_remote_video_v2",
    "kwabor.intro.pending_remote_revision",
    "kwabor.intro.pending_remote_sha256",
    "kwabor.intro.last_presented_remote_revision",
    "kwabor.intro.remote_media_purge_state_v1",
]
let legacyRemoteIntroCleanupCompletedKey = "kwabor.migration.remote_intro_store_release_only_v1"
let legacyRemoteIntroCacheDirectoryName = "intro-media"

private let legacyCleanupLoggerSubsystem = "com.kwabor.app"
private let legacyCleanupLoggerCategory = "migration"
