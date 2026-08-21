package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.notification.NotificationAccountScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotificationRuntimeEffectQueueTest {
    @Test
    fun clearingSaturatedAccountAReleasesCapacityWithoutDiscardingAccountB() =
        runTest {
            val queue = NotificationRuntimeEffectQueue()
            val scopeA = NotificationAccountScope("account-a", epoch = 1L)
            val scopeB = NotificationAccountScope("account-b", epoch = 2L)
            repeat(NOTIFICATION_EFFECT_CAPACITY) {
                assertTrue(queue.offer(NotificationEffect.OpenNotificationPreferences(scopeA, 3L)))
            }

            queue.clearAccount(scopeA.accountId)
            assertTrue(queue.offer(NotificationEffect.OpenNotificationPreferences(scopeB, 4L)))

            val retained = async { queue.asFlow().first() }
            val effect = assertIs<NotificationEffect.OpenNotificationPreferences>(retained.await())
            assertEquals(scopeB, effect.scope)
            assertEquals(0, queue.pendingCount)
            queue.close()
        }

    @Test
    fun clearingAccountAPreservesAlreadyBufferedAccountBEffect() =
        runTest {
            val queue = NotificationRuntimeEffectQueue()
            val scopeA = NotificationAccountScope("account-a", epoch = 1L)
            val scopeB = NotificationAccountScope("account-b", epoch = 2L)
            assertTrue(queue.offer(NotificationEffect.OpenNotificationPreferences(scopeA, 3L)))
            assertTrue(queue.offer(NotificationEffect.OpenNotificationPreferences(scopeB, 4L)))

            queue.clearAccount(scopeA.accountId)

            val retained = async { queue.asFlow().first() }
            val effect = assertIs<NotificationEffect.OpenNotificationPreferences>(retained.await())
            assertEquals(scopeB, effect.scope)
            queue.close()
        }
}
