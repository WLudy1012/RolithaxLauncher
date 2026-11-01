package com.wludy.rolithax.launcher.shared

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MslRequestLimiterTest {
    @Test
    fun concurrentCallsAreSpacedAcrossClients() = runTest {
        val starts = mutableListOf<Long>()
        val limiter = MslRequestLimiter({ testScheduler.currentTime }, { delay(it) })
        repeat(3) { launch { limiter.awaitSlot(); starts += testScheduler.currentTime } }
        advanceUntilIdle()
        assertEquals(listOf(0L, 150L, 300L), starts)
    }

    @Test
    fun cancelledWaitDoesNotConsumeSlotOrBlockNextCaller() = runTest {
        val starts = mutableListOf<Long>()
        val limiter = MslRequestLimiter({ testScheduler.currentTime }, { delay(it) })
        limiter.awaitSlot()
        val cancelled = launch { limiter.awaitSlot(); starts += testScheduler.currentTime }
        runCurrent()
        advanceTimeBy(50)
        cancelled.cancelAndJoin()
        launch { limiter.awaitSlot(); starts += testScheduler.currentTime }
        advanceUntilIdle()
        assertEquals(listOf(150L), starts)
    }
}
