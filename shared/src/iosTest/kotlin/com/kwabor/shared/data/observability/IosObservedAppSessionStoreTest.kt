package com.kwabor.shared.data.observability

import com.kwabor.shared.domain.observability.ObservedAppSessionCheckpointRead
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class IosObservedAppSessionStoreTest {
    @Test
    fun failedAtomicReplacementIsNeverAcknowledged() {
        val file = FakeIosObservedAppSessionFile(replacementSucceeds = false)
        val store = IosObservedAppSessionStore(file)

        assertFalse(store.writeForeground())
        assertEquals(IosObservedAppSessionFileRead.Missing, file.read())
    }

    @Test
    fun failedDurableRemovalIsNeverAcknowledged() {
        val file = FakeIosObservedAppSessionFile(
            initialValue = ObservedAppSessionCheckpointCodec.encodeForeground(),
            removalSucceeds = false,
        )
        val store = IosObservedAppSessionStore(file)

        assertFalse(store.clear())
        assertEquals(ObservedAppSessionCheckpointRead.Foreground, store.read())
    }

    @Test
    fun fileReadFailureFailsClosed() {
        val store = IosObservedAppSessionStore(FailingIosObservedAppSessionFile)

        assertEquals(ObservedAppSessionCheckpointRead.Failure, store.read())
    }

    @Test
    fun thrownFileReadFailureFailsClosed() {
        val store = IosObservedAppSessionStore(ThrowingIosObservedAppSessionFile)

        assertEquals(ObservedAppSessionCheckpointRead.Failure, store.read())
    }
}

private class FakeIosObservedAppSessionFile(
    initialValue: String? = null,
    private val replacementSucceeds: Boolean = true,
    private val removalSucceeds: Boolean = true,
) : IosObservedAppSessionFile {
    private var value = initialValue

    override fun read(): IosObservedAppSessionFileRead = value
        ?.let(IosObservedAppSessionFileRead::Value)
        ?: IosObservedAppSessionFileRead.Missing

    override fun replaceAtomically(value: String): Boolean {
        if (!replacementSucceeds) return false
        this.value = value
        return true
    }

    override fun removeDurably(): Boolean {
        if (!removalSucceeds) return false
        value = null
        return true
    }
}

private data object FailingIosObservedAppSessionFile : IosObservedAppSessionFile {
    override fun read(): IosObservedAppSessionFileRead = IosObservedAppSessionFileRead.Failure

    override fun replaceAtomically(value: String): Boolean = false

    override fun removeDurably(): Boolean = false
}

private data object ThrowingIosObservedAppSessionFile : IosObservedAppSessionFile {
    override fun read(): IosObservedAppSessionFileRead = throw IOException("expected test failure")

    override fun replaceAtomically(value: String): Boolean = false

    override fun removeDurably(): Boolean = false
}
