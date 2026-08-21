package com.kwabor.shared.data.notification

import kotlinx.coroutines.sync.Mutex

internal class NotificationStoreLock {
    val mutex = Mutex()
}
