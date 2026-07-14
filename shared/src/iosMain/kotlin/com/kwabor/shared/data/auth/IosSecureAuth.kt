package com.kwabor.shared.data.auth

import cnames.structs.__CFData
import io.github.jan.supabase.auth.SessionManager
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

fun createIosSecureAuthSessionManager(): SessionManager = KwaborSessionManager(
    store = IosKeychainSecureStringStore(),
)

@OptIn(ExperimentalForeignApi::class)
private class IosKeychainSecureStringStore(
    private val service: String = "com.kwabor.auth",
) : SecureStringStore {
    override suspend fun putString(key: String, value: String) {
        remove(key)
        val data = value.toCFData()
        val query = baseQuery(key).apply {
            put(kSecValueData, data)
            put(kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        }

        val status = SecItemAdd(query.ref, null)
        CFRelease(data)
        query.release()
        check(status == errSecSuccess) { "Unable to save secure item" }
    }

    override suspend fun getStringOrNull(key: String): String? = memScoped {
        val query = baseQuery(key).apply {
            put(kSecReturnData, kCFBooleanTrue)
            put(kSecMatchLimit, kSecMatchLimitOne)
        }
        val result = alloc<CPointerVarOf<CPointer<out CPointed>>>()
        val status = SecItemCopyMatching(query.ref, result.ptr)
        query.release()

        when (status) {
            errSecSuccess -> result.value?.reinterpret<__CFData>()?.toUtf8String()
            errSecItemNotFound -> null
            else -> error("Unable to read secure item")
        }
    }

    override suspend fun remove(key: String) {
        val query = baseQuery(key)
        val status = SecItemDelete(query.ref)
        query.release()
        check(status == errSecSuccess || status == errSecItemNotFound) { "Unable to delete secure item" }
    }

    private fun baseQuery(key: String): KeychainQuery {
        val serviceValue = service.toCFString()
        val accountValue = key.toCFString()
        val ref = CFDictionaryCreateMutable(
            allocator = null,
            capacity = 0,
            keyCallBacks = kCFTypeDictionaryKeyCallBacks.ptr,
            valueCallBacks = kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: error("Unable to create keychain query")

        val query = KeychainQuery(
            ref = ref,
            retainedValues = mutableListOf(serviceValue, accountValue),
        )
        query.put(kSecClass, kSecClassGenericPassword)
        query.put(kSecAttrService, serviceValue)
        query.put(kSecAttrAccount, accountValue)
        return query
    }
}

@OptIn(ExperimentalForeignApi::class)
private class KeychainQuery(
    val ref: CFDictionaryRef,
    private val retainedValues: MutableList<CPointer<out CPointed>>,
) {
    fun put(key: CPointer<out CPointed>?, value: CPointer<out CPointed>?) {
        CFDictionarySetValue(ref, key, value)
    }

    fun release() {
        retainedValues.forEach(::CFRelease)
        CFRelease(ref)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun String.toCFString(): CPointer<out CPointed> = CFStringCreateWithCString(
    alloc = null,
    cStr = this,
    encoding = kCFStringEncodingUTF8,
) ?: error("Unable to encode secure key")

@OptIn(ExperimentalForeignApi::class)
private fun String.toCFData(): CPointer<out CPointed> {
    val bytes = encodeToByteArray()
    return bytes.usePinned { pinned ->
        CFDataCreate(
            allocator = null,
            bytes = pinned.addressOf(0).reinterpret<UByteVar>(),
            length = bytes.size.convert(),
        ) ?: error("Unable to encode secure item")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CPointer<__CFData>.toUtf8String(): String {
    val length = CFDataGetLength(this).toInt()
    val bytes = CFDataGetBytePtr(this) ?: error("Unable to read secure item")
    return ByteArray(length) { index -> bytes[index].toByte() }.decodeToString()
}
