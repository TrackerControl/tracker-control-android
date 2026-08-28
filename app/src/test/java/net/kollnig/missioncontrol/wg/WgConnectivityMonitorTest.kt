package net.kollnig.missioncontrol.wg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure unit tests for the [WgConnectivityMonitor] loop cadence: fast polling
 * while the screen is on, slow (battery-preserving) polling while it is off,
 * and a doze-detection threshold that scales with the active interval.
 */
class WgConnectivityMonitorTest {

    private class FakeMonitor {
        var started = false
        var stopped = false
    }

    private fun awaitStopped(monitor: WgConnectivityMonitor) {
        repeat(1_000) {
            if (!monitor.isRunning()) return
            Thread.sleep(1)
        }
        fail("monitor did not stop")
    }

    private fun stats() = WgStats(0, 0, 0)

    @Test
    fun interactiveCadenceIsOneSecond() {
        assertEquals(1_000L, WgConnectivityMonitor.INTERACTIVE_LOOP_SLEEP_MS)
        assertEquals(1_000L, WgConnectivityMonitor.pollIntervalMs(true))
    }

    @Test
    fun screenOffCadenceIsSlow() {
        val idle = WgConnectivityMonitor.pollIntervalMs(false)
        assertTrue(idle >= 10_000L)
        assertTrue(idle <= 15_000L)
    }

    /**
     * At the 1s interactive cadence the suspend threshold must stay at the
     * historical fixed value of 6s, so doze detection does not regress.
     */
    @Test
    fun suspendThresholdAtInteractiveCadenceIsSixSeconds() {
        assertFalse(WgConnectivityMonitor.isSuspendGap(6_000L - 1, 1_000L))
        assertTrue(WgConnectivityMonitor.isSuspendGap(6_000L, 1_000L))
    }

    /**
     * At the slow screen-off cadence a normal cycle must not be mistaken for
     * a doze gap (that would rebase timestamps every tick), while genuine
     * multi-second timer deferrals still are.
     */
    @Test
    fun suspendThresholdScalesWithIdleCadence() {
        val idle = WgConnectivityMonitor.pollIntervalMs(false)
        assertFalse(WgConnectivityMonitor.isSuspendGap(idle, idle))
        assertTrue(WgConnectivityMonitor.isSuspendGap(idle + WgConnectivityMonitor.SUSPEND_MARGIN_MS, idle))
    }

    @Test
    fun nullThenValidInitialSeedStartsMonitoring() {
        var seedReads = 0
        var sleeps = 0
        val delays = mutableListOf<Long>()
        var monitor: WgConnectivityMonitor? = null
        monitor = WgConnectivityMonitor(
            statsProvider = {
                seedReads++
                if (seedReads == 1) null else stats()
            },
            prod = {},
            onBroken = { fail("valid initial stats entered recovery") },
            sleep = { delay ->
                delays += delay
                sleeps++
                // One sleep retries the initial seed; the next is the first
                // normal poll after the valid seed is installed.
                if (sleeps == 2) monitor!!.stop()
            },
            clock = { 0L }
        )

        monitor.start()
        awaitStopped(monitor)
        assertEquals(2, seedReads)
        assertEquals(listOf(WgConnectivityMonitor.INTERACTIVE_LOOP_SLEEP_MS,
            WgConnectivityMonitor.INTERACTIVE_LOOP_SLEEP_MS), delays)
    }

    @Test
    fun allNullFromStartEntersRecoveryOnce() {
        var seedReads = 0
        var broken = 0
        val recovery = CountDownLatch(1)
        val monitor = WgConnectivityMonitor(
            statsProvider = { seedReads++; null },
            prod = {},
            onBroken = {
                broken++
                recovery.countDown()
            },
            sleep = {},
            clock = { 0L }
        )

        monitor.start()
        assertTrue(recovery.await(1, TimeUnit.SECONDS))
        awaitStopped(monitor)
        assertEquals(WgConnectivityMonitor.MAX_STATS_FAILURES, seedReads)
        assertEquals(1, broken)
        assertFalse(monitor.isRunning())
    }

    @Test
    fun screenOffPersistentNullDefersRecoveryAtIdleCadence() {
        var reads = 0
        var sleeps = 0
        var broken = 0
        var monitor: WgConnectivityMonitor? = null
        monitor = WgConnectivityMonitor(
            statsProvider = {
                reads++
                null
            },
            prod = {},
            onBroken = { broken++ },
            isInteractive = { false },
            sleep = { delay ->
                assertEquals(WgConnectivityMonitor.IDLE_LOOP_SLEEP_MS, delay)
                if (++sleeps == WgConnectivityMonitor.MAX_STATS_FAILURES + 1) monitor!!.stop()
            },
            clock = { 0L }
        )

        monitor.start()
        awaitStopped(monitor)
        assertEquals(0, broken)
        assertEquals(WgConnectivityMonitor.MAX_STATS_FAILURES + 1, reads)
        assertEquals(WgConnectivityMonitor.MAX_STATS_FAILURES + 1, sleeps)
    }

    @Test
    fun transientMidLoopNullRetriesAtScreenAwareCadence() {
        var reads = 0
        var sleeps = 0
        val delays = mutableListOf<Long>()
        var monitor: WgConnectivityMonitor? = null
        monitor = WgConnectivityMonitor(
            statsProvider = {
                reads++
                if (reads == 2) null else stats()
            },
            prod = {},
            onBroken = { fail("transient stats failure entered recovery") },
            sleep = { delay ->
                delays += delay
                sleeps++
                if (sleeps == 3) monitor!!.stop()
            },
            clock = { 0L }
        )

        monitor.start()
        awaitStopped(monitor)
        assertEquals(3, reads)
        assertEquals(listOf(WgConnectivityMonitor.INTERACTIVE_LOOP_SLEEP_MS,
            WgConnectivityMonitor.INTERACTIVE_LOOP_SLEEP_MS,
            WgConnectivityMonitor.INTERACTIVE_LOOP_SLEEP_MS), delays)
    }

    @Test
    fun persistentNullLeadsToRecoveryAfterBoundedRetries() {
        var reads = 0
        var broken = 0
        val recovery = CountDownLatch(1)
        val monitor = WgConnectivityMonitor(
            statsProvider = {
                reads++
                if (reads == 1) stats() else null
            },
            prod = {},
            onBroken = {
                broken++
                recovery.countDown()
            },
            sleep = {},
            clock = { 0L }
        )

        monitor.start()
        assertTrue(recovery.await(1, TimeUnit.SECONDS))
        awaitStopped(monitor)
        assertEquals(1, broken)
        assertEquals(1 + WgConnectivityMonitor.MAX_STATS_FAILURES, reads)
        assertFalse(monitor.isRunning())
    }

    @Test
    fun stopDuringStatsRetryIsPromptAndDoesNotRecover() {
        var reads = 0
        var sleeps = 0
        var broken = 0
        var monitor: WgConnectivityMonitor? = null
        monitor = WgConnectivityMonitor(
            statsProvider = {
                reads++
                if (reads == 1) stats() else null
            },
            prod = {},
            onBroken = { broken++ },
            sleep = {
                sleeps++
                if (sleeps == 2) monitor!!.stop()
            },
            clock = { 0L }
        )

        monitor.start()
        awaitStopped(monitor)
        assertEquals(2, reads)
        assertEquals(0, broken)
        assertFalse(monitor.isRunning())
    }

    @Test
    fun restartAfterInitialExitStartsAFreshRun() {
        var reads = 0
        var sleeps = 0
        var broken = 0
        var monitor: WgConnectivityMonitor? = null
        monitor = WgConnectivityMonitor(
            statsProvider = {
                reads++
                if (reads <= WgConnectivityMonitor.MAX_STATS_FAILURES) null else stats()
            },
            prod = {},
            onBroken = { broken++ },
            sleep = {
                sleeps++
                if (broken > 0) monitor!!.stop()
            },
            clock = { 0L }
        )

        monitor.start()
        awaitStopped(monitor)
        assertEquals(1, broken)
        assertEquals(WgConnectivityMonitor.MAX_STATS_FAILURES, reads)

        monitor.start()
        awaitStopped(monitor)
        assertEquals(WgConnectivityMonitor.MAX_STATS_FAILURES + 1, reads)
        assertEquals(WgConnectivityMonitor.MAX_STATS_FAILURES, sleeps)
    }

    @Test
    fun staleExpectedTunnelDoesNotTriggerRecovery() {
        var reads = 0
        var sleeps = 0
        var current = true
        var broken = 0
        val staleRead = CountDownLatch(1)
        var monitor: WgConnectivityMonitor? = null
        monitor = WgConnectivityMonitor(
            statsProvider = {
                reads++
                if (reads == 1) stats() else null.also { staleRead.countDown() }
            },
            prod = {},
            onBroken = { broken++ },
            isCurrent = { current },
            sleep = {
                if (++sleeps == 1) current = false
            },
            clock = { 0L }
        )

        monitor.start()
        assertTrue(staleRead.await(1, TimeUnit.SECONDS))
        awaitStopped(monitor)
        assertEquals(2, reads)
        assertEquals(0, broken)
        assertFalse(monitor.isRunning())
    }

    @Test
    fun stopBetweenCallbackAuthorizationAndDispatchSuppressesOldCallback() {
        val callbackReady = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val callbacks = AtomicInteger()
        var reads = 0
        var monitor: WgConnectivityMonitor? = null
        monitor = WgConnectivityMonitor(
            statsProvider = {
                reads++
                if (reads == 1) stats() else WgStats(0, 0, 1)
            },
            prod = {},
            onBroken = {},
            onConnected = { callbacks.incrementAndGet() },
            sleep = {},
            clock = { 0L },
            beforeCallback = {
                callbackReady.countDown()
                assertTrue(releaseCallback.await(1, TimeUnit.SECONDS))
            }
        )

        monitor.start()
        assertTrue(callbackReady.await(1, TimeUnit.SECONDS))
        Thread {
            monitor!!.stop()
            stopReturned.countDown()
        }.start()
        // stop invalidates the generation before waiting for the callback gate.
        repeat(1_000) {
            if (!monitor.isRunning()) return@repeat
            Thread.yield()
        }
        assertFalse(monitor.isRunning())
        releaseCallback.countDown()
        assertTrue(stopReturned.await(1, TimeUnit.SECONDS))
        assertEquals(0, callbacks.get())
    }

    @Test
    fun stopBetweenProdAuthorizationAndDispatchSuppressesKeepalive() {
        val now = AtomicLong(0)
        val prodReady = CountDownLatch(1)
        val releaseProd = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val prods = AtomicInteger()
        var reads = 0
        var sleeps = 0
        var monitor: WgConnectivityMonitor? = null
        monitor = WgConnectivityMonitor(
            statsProvider = {
                reads++
                if (reads == 1) WgStats(0, 0, 0) else WgStats(0, 1, 0)
            },
            prod = { prods.incrementAndGet() },
            onBroken = {},
            sleep = {
                when (++sleeps) {
                    1 -> now.set(1_000L)
                    2 -> now.set(6_000L)
                }
            },
            clock = { now.get() },
            beforeCallback = {
                prodReady.countDown()
                assertTrue(releaseProd.await(1, TimeUnit.SECONDS))
            }
        )

        monitor.start()
        assertTrue(prodReady.await(1, TimeUnit.SECONDS))
        Thread {
            monitor!!.stop()
            stopReturned.countDown()
        }.start()
        // stop invalidates the generation first, then waits for the callback
        // gate held by beforeCallback. It cannot return while that gate is
        // unresolved.
        repeat(1_000) {
            if (!monitor.isRunning()) return@repeat
            Thread.yield()
        }
        assertFalse(monitor.isRunning())
        assertFalse(stopReturned.await(100, TimeUnit.MILLISECONDS))
        releaseProd.countDown()
        assertTrue(stopReturned.await(1, TimeUnit.SECONDS))
        awaitStopped(monitor)
        assertEquals(0, prods.get())
    }

    @Test
    fun blockedStatsReadExpiresAndOldRunCannotCallbackAfterRestart() {
        val now = AtomicLong(0)
        val oldReadStarted = CountDownLatch(1)
        val oldReadFinished = CountDownLatch(1)
        val releaseOldRead = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val reads = AtomicInteger()
        var staleGeneration = false
        var monitor: WgConnectivityMonitor? = null
        monitor = WgConnectivityMonitor(
            statsProvider = {
                when (reads.incrementAndGet()) {
                    1 -> stats()
                    2 -> {
                        oldReadStarted.countDown()
                        try {
                            releaseOldRead.await(1, TimeUnit.SECONDS)
                        } catch (_: InterruptedException) {
                            // isRunning() interrupts the expired old run.
                        } finally {
                            oldReadFinished.countDown()
                        }
                        WgStats(0, 0, 1)
                    }
                    else -> stats()
                }
            },
            prod = {},
            onBroken = {},
            onConnected = { callbacks.incrementAndGet() },
            sleep = {
                if (staleGeneration) monitor!!.stop()
            },
            clock = { now.get() }
        )

        monitor.start()
        assertTrue(oldReadStarted.await(1, TimeUnit.SECONDS))
        now.set(WgConnectivityMonitor.STATS_READ_TIMEOUT_MS)
        assertFalse(monitor.isRunning())

        // A new run may start while the old native stats call is still unwinding.
        staleGeneration = true
        monitor.start()
        awaitStopped(monitor)
        releaseOldRead.countDown()
        assertTrue(oldReadFinished.await(1, TimeUnit.SECONDS))
        assertEquals(0, callbacks.get())
    }

    @Test
    fun callbackReplacementRacingStopDoesNotDeadlockOrLeakCandidate() {
        val callbackGate = Any()
        val oldStopEntered = CountDownLatch(1)
        val releaseOldStop = CountDownLatch(1)
        val replacementReturned = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val old = FakeMonitor()
        val candidate = FakeMonitor()
        val lifecycle = WgMonitorLifecycle<FakeMonitor>(
            lock = Any(),
            isRunning = { it.started },
            stop = {
                if (it === old) {
                    oldStopEntered.countDown()
                    releaseOldStop.await(1, TimeUnit.SECONDS)
                }
                it.stopped = true
            },
            start = { it.started = true }
        )
        assertTrue(lifecycle.replace(old, isCurrent = { true }))

        val callback = Thread {
            synchronized(callbackGate) {
                lifecycle.replace(candidate, isCurrent = { true })
                replacementReturned.countDown()
            }
        }
        callback.start()
        assertTrue(oldStopEntered.await(1, TimeUnit.SECONDS))

        // stopMonitor must acquire the lifecycle lock while the callback-side
        // replacement is waiting in old.stop(); it must not wait on the
        // callback gate or leave the candidate to be started later.
        Thread {
            lifecycle.stop()
            stopReturned.countDown()
        }.start()
        assertTrue(stopReturned.await(1, TimeUnit.SECONDS))
        releaseOldStop.countDown()
        assertTrue(replacementReturned.await(1, TimeUnit.SECONDS))
        callback.join(1_000)
        assertFalse(callback.isAlive)
        assertTrue(old.stopped)
        assertFalse(candidate.started)
        assertTrue(candidate.stopped)
        assertFalse(lifecycle.isRunning())
    }

    @Test
    fun stopCancelsInProgressReplacementBeforeCandidateStarts() {
        val oldStopEntered = CountDownLatch(1)
        val releaseOldStop = CountDownLatch(1)
        val replacementReturned = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val old = FakeMonitor()
        val candidate = FakeMonitor()
        val lifecycle = WgMonitorLifecycle<FakeMonitor>(
            lock = Any(),
            isRunning = { it.started },
            stop = {
                if (it === old) {
                    oldStopEntered.countDown()
                    releaseOldStop.await(1, TimeUnit.SECONDS)
                }
                it.stopped = true
            },
            start = { it.started = true }
        )
        assertTrue(lifecycle.replace(old, isCurrent = { true }))

        Thread {
            lifecycle.replace(candidate, isCurrent = { true })
            replacementReturned.countDown()
        }.start()
        assertTrue(oldStopEntered.await(1, TimeUnit.SECONDS))

        Thread {
            lifecycle.stop()
            stopReturned.countDown()
        }.start()
        assertTrue(stopReturned.await(1, TimeUnit.SECONDS))
        releaseOldStop.countDown()
        assertTrue(replacementReturned.await(1, TimeUnit.SECONDS))
        assertTrue(old.stopped)
        assertFalse(candidate.started)
        assertTrue(candidate.stopped)
        assertFalse(lifecycle.isRunning())
    }
}
