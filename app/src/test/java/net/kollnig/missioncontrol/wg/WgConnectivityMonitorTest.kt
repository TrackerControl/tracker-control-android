package net.kollnig.missioncontrol.wg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the [WgConnectivityMonitor] loop cadence: fast polling
 * while the screen is on, slow (battery-preserving) polling while it is off,
 * and a doze-detection threshold that scales with the active interval.
 */
class WgConnectivityMonitorTest {

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
}
