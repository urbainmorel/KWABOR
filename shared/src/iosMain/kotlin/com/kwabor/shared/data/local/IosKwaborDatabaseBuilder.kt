package com.kwabor.shared.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSLog
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

private const val KWABOR_ROOM_DIRECTORY_NAME = "KwaborRoom"
private val IOS_DATABASE_FILE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

@OptIn(ExperimentalForeignApi::class)
internal fun createIosKwaborDatabaseBuilder(): KwaborDatabaseBuilderResult {
    val fileManager = NSFileManager.defaultManager
    val applicationSupportUrl = fileManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    return createIosKwaborDatabaseBuilder(
        applicationSupportUrl = applicationSupportUrl,
        fileManager = fileManager,
    )
}

@OptIn(ExperimentalForeignApi::class)
internal fun createIosKwaborDatabaseBuilder(
    applicationSupportUrl: NSURL?,
    fileManager: NSFileManager,
    protectionApplicator: IosRoomFileProtectionApplicator = fileManager.iosRoomFileProtectionApplicator(),
    onPolicyFailure: () -> Unit = ::reportIosRoomStoragePolicyFailure,
): KwaborDatabaseBuilderResult {
    val databasePath = try {
        val roomDirectoryUrl = prepareIosRoomDirectory(
            applicationSupportUrl = requireIosRoomPolicyValue(
                applicationSupportUrl,
                "The iOS application support directory is unavailable.",
            ),
            fileManager = fileManager,
            protectionApplicator = protectionApplicator,
        )
        val roomDirectoryPath = requireIosRoomPolicyValue(
            roomDirectoryUrl.path,
            "The protected iOS Room directory path is unavailable.",
        )
        "$roomDirectoryPath/$KWABOR_DATABASE_FILENAME"
    } catch (_: IosRoomStoragePolicyException) {
        onPolicyFailure()
        null
    }
    return if (databasePath == null) {
        KwaborDatabaseBuilderResult(
            storageMode = KwaborDatabaseStorageMode.MemoryOnly,
            builderFactory = {
                Room.inMemoryDatabaseBuilder<KwaborDatabase>(
                    factory = KwaborDatabaseConstructor::initialize,
                )
            },
        )
    } else {
        KwaborDatabaseBuilderResult(
            storageMode = KwaborDatabaseStorageMode.Durable,
            builderFactory = { createIosKwaborDatabaseBuilder(databasePath = databasePath) },
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun prepareIosRoomDirectory(
    applicationSupportUrl: NSURL,
    fileManager: NSFileManager = NSFileManager.defaultManager,
    protectionApplicator: IosRoomFileProtectionApplicator = fileManager.iosRoomFileProtectionApplicator(),
): NSURL {
    val applicationSupportPath = requireIosRoomPolicyValue(
        applicationSupportUrl.path,
        "The iOS application support directory path is unavailable.",
    )
    removeLegacyIosDatabaseFiles(
        applicationSupportPath = applicationSupportPath,
        fileManager = fileManager,
    )
    val roomDirectoryUrl = resolveIosRoomDirectoryUrl(applicationSupportUrl)
    val roomDirectoryPath = requireIosRoomPolicyValue(
        roomDirectoryUrl.path,
        "The protected iOS Room directory path is unavailable.",
    )
    val roomPolicy = resolveIosRoomPolicy()
    enforceIosRoomDirectoryPolicy(
        roomDirectoryUrl = roomDirectoryUrl,
        roomDirectoryPath = roomDirectoryPath,
        policy = roomPolicy,
        fileManager = fileManager,
        protectionApplicator = protectionApplicator,
    )
    protectExistingIosDatabaseFiles(
        roomDirectoryPath = roomDirectoryPath,
        protectionAttributes = roomPolicy.protectionAttributes,
        fileManager = fileManager,
        protectionApplicator = protectionApplicator,
    )
    return roomDirectoryUrl
}

private data class IosRoomPolicy(
    val backupKey: String,
    val protectionAttributes: Map<Any?, Any?>,
)

internal class IosRoomStoragePolicyException(message: String) : IllegalStateException(message)

internal fun interface IosRoomFileProtectionApplicator {
    fun apply(path: String, attributes: Map<Any?, Any?>): Boolean
}

@OptIn(ExperimentalForeignApi::class)
private fun NSFileManager.iosRoomFileProtectionApplicator(): IosRoomFileProtectionApplicator =
    IosRoomFileProtectionApplicator { path, attributes ->
        setAttributes(
            attributes = attributes,
            ofItemAtPath = path,
            error = null,
        )
    }

@OptIn(ExperimentalForeignApi::class)
private fun resolveIosRoomDirectoryUrl(applicationSupportUrl: NSURL): NSURL = requireIosRoomPolicyValue(
    applicationSupportUrl.URLByAppendingPathComponent(
        pathComponent = KWABOR_ROOM_DIRECTORY_NAME,
        isDirectory = true,
    ),
    "The protected iOS Room directory URL is unavailable.",
)

@OptIn(ExperimentalForeignApi::class)
private fun resolveIosRoomPolicy(): IosRoomPolicy {
    val protectionKey = requireIosRoomPolicyValue(
        NSFileProtectionKey,
        "The iOS file-protection key is unavailable.",
    )
    val protectionValue = requireIosRoomPolicyValue(
        NSFileProtectionCompleteUntilFirstUserAuthentication,
        "The required iOS file-protection value is unavailable.",
    )
    return IosRoomPolicy(
        backupKey = requireIosRoomPolicyValue(
            NSURLIsExcludedFromBackupKey,
            "The iOS backup-exclusion key is unavailable.",
        ),
        protectionAttributes = mapOf(protectionKey to protectionValue),
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun enforceIosRoomDirectoryPolicy(
    roomDirectoryUrl: NSURL,
    roomDirectoryPath: String,
    policy: IosRoomPolicy,
    fileManager: NSFileManager,
    protectionApplicator: IosRoomFileProtectionApplicator,
) {
    val directoryCreated = fileManager.createDirectoryAtURL(
        url = roomDirectoryUrl,
        withIntermediateDirectories = true,
        attributes = policy.protectionAttributes,
        error = null,
    )
    val directoryProtected = protectionApplicator.apply(roomDirectoryPath, policy.protectionAttributes)
    val directoryExcludedFromBackup = roomDirectoryUrl.setResourceValue(
        value = NSNumber(bool = true),
        forKey = policy.backupKey,
        error = null,
    )
    requireIosRoomPolicy(
        directoryCreated && directoryProtected && directoryExcludedFromBackup,
        "The protected, backup-excluded iOS Room directory could not be prepared.",
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun removeLegacyIosDatabaseFiles(applicationSupportPath: String, fileManager: NSFileManager) {
    var allFilesRemoved = true
    IOS_DATABASE_FILE_SUFFIXES.forEach { suffix ->
        val legacyPath = "$applicationSupportPath/$KWABOR_DATABASE_FILENAME$suffix"
        if (fileManager.fileExistsAtPath(legacyPath) && !fileManager.removeItemAtPath(legacyPath, error = null)) {
            allFilesRemoved = false
        }
    }
    requireIosRoomPolicy(
        allFilesRemoved,
        "The complete legacy iOS Room cache could not be removed.",
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun protectExistingIosDatabaseFiles(
    roomDirectoryPath: String,
    protectionAttributes: Map<Any?, Any?>,
    fileManager: NSFileManager,
    protectionApplicator: IosRoomFileProtectionApplicator,
) {
    var allFilesProtected = true
    IOS_DATABASE_FILE_SUFFIXES.forEach { suffix ->
        val databaseFilePath = "$roomDirectoryPath/$KWABOR_DATABASE_FILENAME$suffix"
        if (
            fileManager.fileExistsAtPath(databaseFilePath) &&
            !protectionApplicator.apply(databaseFilePath, protectionAttributes)
        ) {
            allFilesProtected = false
        }
    }
    requireIosRoomPolicy(
        allFilesProtected,
        "The complete iOS Room database family could not be protected.",
    )
}

private fun requireIosRoomPolicy(condition: Boolean, message: String) {
    if (!condition) {
        throw IosRoomStoragePolicyException(message)
    }
}

private fun <T : Any> requireIosRoomPolicyValue(value: T?, message: String): T =
    value ?: throw IosRoomStoragePolicyException(message)

@OptIn(ExperimentalForeignApi::class)
private fun reportIosRoomStoragePolicyFailure() {
    NSLog("Kwabor local persistence is unavailable; using memory-only storage.")
}

internal fun createIosKwaborDatabaseBuilder(databasePath: String): RoomDatabase.Builder<KwaborDatabase> {
    require(databasePath.isNotBlank()) { "The iOS database path must not be blank." }
    return Room.databaseBuilder<KwaborDatabase>(
        name = databasePath,
        factory = KwaborDatabaseConstructor::initialize,
    )
}
