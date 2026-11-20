package com.kwabor.shared.data.observability

import com.kwabor.shared.domain.observability.ObservedAppSessionCheckpointRead
import com.kwabor.shared.domain.observability.ObservedAppSessionStore
import com.kwabor.shared.domain.observability.ObservedAppSessionTimeMark
import com.kwabor.shared.domain.observability.ObservedAppSessionTimeRead
import com.kwabor.shared.domain.observability.ObservedAppSessionTimeSource

internal class InMemoryObservedAppSessionStore : ObservedAppSessionStore {
    private var checkpoint: ObservedAppSessionCheckpointRead = ObservedAppSessionCheckpointRead.Missing

    override fun read(): ObservedAppSessionCheckpointRead = checkpoint

    override fun writeForeground(): Boolean {
        checkpoint = ObservedAppSessionCheckpointRead.Foreground
        return true
    }

    override fun writeBackgroundedAt(timeMark: ObservedAppSessionTimeMark): Boolean {
        checkpoint = ObservedAppSessionCheckpointRead.BackgroundedAt(timeMark)
        return true
    }

    override fun clear(): Boolean {
        checkpoint = ObservedAppSessionCheckpointRead.Missing
        return true
    }
}

internal data object UnavailableObservedAppSessionTimeSource : ObservedAppSessionTimeSource {
    override fun read(): ObservedAppSessionTimeRead = ObservedAppSessionTimeRead.Failure
}
