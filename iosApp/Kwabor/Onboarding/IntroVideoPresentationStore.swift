import Foundation

private let bundledIntroRevision: Int64 = 1

struct IntroVideoPresentationStore {
    let userDefaults: UserDefaults

    init(userDefaults: UserDefaults) {
        self.userDefaults = userDefaults
        migrateLegacyPresentationStateIfNeeded()
    }

    var firstLaunchCompleted: Bool {
        get { userDefaults.bool(forKey: legacyIntroSeenKey) }
        nonmutating set { userDefaults.set(newValue, forKey: legacyIntroSeenKey) }
    }

    var lastPresentedBundledRevision: Int64 {
        normalizedStoredRevision
    }

    func pendingBundledRevision() -> Int64? {
        pendingBundledRevision(currentRevision: bundledIntroRevision)
    }

    func pendingBundledRevision(currentRevision: Int64) -> Int64? {
        guard currentRevision > noBundledIntroRevision,
              currentRevision > lastPresentedBundledRevision else {
            return nil
        }
        return currentRevision
    }

    func markBundledVideoPresented(revision: Int64) {
        guard revision > noBundledIntroRevision else { return }
        let presentedRevision = max(lastPresentedBundledRevision, revision)
        userDefaults.set(presentedRevision, forKey: presentedBundledRevisionKey)
    }

    private var normalizedStoredRevision: Int64 {
        guard userDefaults.object(forKey: presentedBundledRevisionKey) != nil else {
            return noBundledIntroRevision
        }
        return max(
            Int64(userDefaults.integer(forKey: presentedBundledRevisionKey)),
            noBundledIntroRevision
        )
    }

    private func migrateLegacyPresentationStateIfNeeded() {
        let storedRevision = normalizedStoredRevision
        let migratedRevision = if firstLaunchCompleted {
            max(storedRevision, legacyBundledIntroBaselineRevision)
        } else {
            storedRevision
        }
        if userDefaults.object(forKey: presentedBundledRevisionKey) == nil || migratedRevision != storedRevision {
            userDefaults.set(migratedRevision, forKey: presentedBundledRevisionKey)
        }
        if migratedRevision > noBundledIntroRevision, !firstLaunchCompleted {
            firstLaunchCompleted = true
        }
    }
}

private let legacyIntroSeenKey = "kwabor.first_launch.intro_seen_v1"
private let presentedBundledRevisionKey = "kwabor.intro.presented_bundled_revision_v1"
private let legacyBundledIntroBaselineRevision: Int64 = 1
private let noBundledIntroRevision: Int64 = 0
