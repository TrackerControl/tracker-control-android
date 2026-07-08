package net.kollnig.missioncontrol.wg

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Clock-injected unit tests for [WgConnectivityChecker], mirroring Mullvad's
 * talpid `check.rs` connectivity tests. We feed explicit `now` timestamps and
 * counter snapshots so the liveness state machine can be exercised without any
 * threads or the Android clock.
 *
 * Key constants (from [WgConnectivityChecker]):
 *  - BYTES_RX_TIMEOUT_MS = 5_000  (unanswered send before we prod)
 *  - PING_TIMEOUT_MS     = 15_000 (no rx after prodding → broken)
 */
class WgConnectivityCheckerTest {

    /** Counts how many times the checker decided to prod the tunnel. */
    private var prods = 0
    private fun newChecker() = WgConnectivityChecker { prods++ }

    private fun stats(rx: Long, tx: Long, freshHandshake: Boolean = false) =
        WgStats(rx, tx, 0L, freshHandshake)

    // --- baseline / connecting ------------------------------------------

    /** A freshly seeded tunnel with no demand stays healthy and is never prodded. */
    @Test
    fun idleConnectingTunnelStaysHealthy() {
        val c = newChecker()
        c.seed(0, stats(0, 0))
        // Poll for well past the rx timeout; no tx demand means no judgement.
        for (t in 1000L..30_000L step 1000L) {
            assertEquals(WgVerdict.HEALTHY, c.tick(t, stats(0, 0)))
        }
        assertEquals(0, prods)
    }

    /** First inbound byte promotes Connecting → Connected and reports healthy. */
    @Test
    fun firstReceiveMarksConnected() {
        val c = newChecker()
        c.seed(0, stats(0, 0))
        assertEquals(WgVerdict.HEALTHY, c.tick(1000, stats(100, 0)))
        assertEquals(0, prods)
    }

    /** Historical tx at seed time is baseline, not fresh demand. */
    @Test
    fun seededTxDoesNotCountAsUnansweredSend() {
        val c = newChecker()
        c.seed(0, stats(0, 100))

        for (t in 1000L..30_000L step 1000L) {
            assertEquals(WgVerdict.HEALTHY, c.tick(t, stats(0, 100)))
        }
        assertEquals(0, prods)

        // A new send after the seed starts the unanswered-send timer.
        assertEquals(WgVerdict.HEALTHY, c.tick(31_000, stats(0, 120)))
        assertEquals(WgVerdict.WAITING, c.tick(36_000, stats(0, 120)))
        assertEquals(1, prods)
    }

    // --- the failure path -----------------------------------------------

    /**
     * The user-visible failure: we keep sending (tx advances past the last rx)
     * but nothing comes back. After BYTES_RX_TIMEOUT_MS we prod (WAITING), and
     * after PING_TIMEOUT_MS without rx the tunnel is declared BROKEN.
     */
    @Test
    fun unansweredSendsEventuallyBreak() {
        val c = newChecker()
        c.seed(0, stats(0, 0))
        // Establish a connection (rx arrived once).
        assertEquals(WgVerdict.HEALTHY, c.tick(1000, stats(50, 10)))

        // Now we keep sending but receive nothing back.
        assertEquals(WgVerdict.HEALTHY, c.tick(2000, stats(50, 20)))  // <5s since last rx
        assertEquals(0, prods)

        // 6s since the last receive with an outstanding send → prod, WAITING.
        assertEquals(WgVerdict.WAITING, c.tick(7000, stats(50, 30)))
        assertEquals(1, prods)

        // Still nothing back after PING_TIMEOUT_MS past the first prod → BROKEN.
        assertEquals(WgVerdict.BROKEN, c.tick(7000 + WgConnectivityChecker.PING_TIMEOUT_MS + 1, stats(50, 40)))
    }

    /** Prods are rate-limited while waiting for rx. */
    @Test
    fun unansweredSendsProdAtCadence() {
        val c = newChecker()
        c.seed(0, stats(0, 0))
        assertEquals(WgVerdict.HEALTHY, c.tick(1000, stats(50, 10)))

        assertEquals(WgVerdict.WAITING, c.tick(7000, stats(50, 20)))
        assertEquals(1, prods)

        assertEquals(WgVerdict.WAITING, c.tick(8000, stats(50, 20)))
        assertEquals(WgVerdict.WAITING, c.tick(9000, stats(50, 20)))
        assertEquals(1, prods)

        assertEquals(WgVerdict.WAITING, c.tick(10_000, stats(50, 20)))
        assertEquals(2, prods)
    }

    /**
     * Regression test for the strict `>` in rxTimedOut: a CONNECTED tunnel that
     * simply goes idle (tx never advances past the last rx) must NEVER be
     * prodded or broken, even though at connect time tx and rx timestamps are
     * equal. With Mullvad's original `>=` this would prod a healthy idle tunnel.
     */
    @Test
    fun idleConnectedTunnelIsNeverBroken() {
        val c = newChecker()
        c.seed(0, stats(0, 0))
        assertEquals(WgVerdict.HEALTHY, c.tick(1000, stats(50, 50)))
        // Long idle stretch with no further traffic in either direction.
        for (t in 2000L..60_000L step 1000L) {
            assertEquals(WgVerdict.HEALTHY, c.tick(t, stats(50, 50)))
        }
        assertEquals(0, prods)
    }

    /** A receive after an outstanding send clears the timeout and recovers. */
    @Test
    fun lateReplyRecoversBeforeBreak() {
        val c = newChecker()
        c.seed(0, stats(0, 0))
        assertEquals(WgVerdict.HEALTHY, c.tick(1000, stats(50, 10)))
        assertEquals(WgVerdict.WAITING, c.tick(7000, stats(50, 20)))  // prodded
        assertEquals(1, prods)
        // The prod (or the network) worked: rx advances → healthy again.
        assertEquals(WgVerdict.HEALTHY, c.tick(8000, stats(60, 20)))
        // Subsequent idle does not re-prod.
        assertEquals(WgVerdict.HEALTHY, c.tick(9000, stats(60, 20)))
        assertEquals(1, prods)
    }

    /** Fresh WireGuard handshakes count as tunnel liveness even without payload rx. */
    @Test
    fun freshHandshakeSuppressesPayloadRxTimeout() {
        val c = newChecker()
        c.seed(0, stats(0, 0))
        assertEquals(WgVerdict.HEALTHY, c.tick(1000, stats(50, 10)))

        for (t in 2000L..60_000L step 1000L) {
            assertEquals(WgVerdict.HEALTHY, c.tick(t, stats(50, t, freshHandshake = true)))
        }
        assertEquals(0, prods)
    }

    /** Continuous two-way traffic never trips the watchdog. */
    @Test
    fun continuousTrafficStaysHealthy() {
        val c = newChecker()
        c.seed(0, stats(0, 0))
        var rx = 0L
        var tx = 0L
        for (t in 1000L..60_000L step 1000L) {
            rx += 100
            tx += 100
            assertEquals(WgVerdict.HEALTHY, c.tick(t, stats(rx, tx)))
        }
        assertEquals(0, prods)
    }

    // --- rx-advance signal (drives recovery backoff reset) ----------------

    /**
     * lastTickSawRx must be true exactly when decrypted return traffic was
     * observed — not on idle ticks, not on unanswered sends, and not on a
     * fresh handshake alone. The restart backoff in WgEgress resets on this
     * signal; if handshake freshness or plain HEALTHY verdicts set it, a
     * handshake-passes-but-data-drops loop would restart at full tilt forever.
     */
    @Test
    fun rxAdvanceIsOnlySignaledForReturnTraffic() {
        val c = newChecker()
        c.seed(0, stats(0, 0))
        assertEquals(false, c.lastTickSawRx)

        // Idle tick: healthy, but no rx observed.
        c.tick(1000, stats(0, 0))
        assertEquals(false, c.lastTickSawRx)

        // Unanswered send: tx advances, still no rx.
        c.tick(2000, stats(0, 10))
        assertEquals(false, c.lastTickSawRx)

        // Fresh handshake without payload rx: liveness, but not path proof.
        c.tick(3000, stats(0, 20, freshHandshake = true))
        assertEquals(false, c.lastTickSawRx)

        // Return traffic arrives.
        c.tick(4000, stats(50, 20))
        assertEquals(true, c.lastTickSawRx)

        // Back to idle: the flag reflects the latest tick only.
        c.tick(5000, stats(50, 20))
        assertEquals(false, c.lastTickSawRx)

        // rx advancing during a fresh-handshake tick still counts.
        c.tick(6000, stats(80, 30, freshHandshake = true))
        assertEquals(true, c.lastTickSawRx)
    }

    /** A torn-down tunnel (null stats) never reports rx. */
    @Test
    fun rxAdvanceIsClearedOnGone() {
        val c = newChecker()
        c.seed(0, stats(0, 0))
        c.tick(1000, stats(50, 10))
        assertEquals(true, c.lastTickSawRx)
        c.tick(2000, null)
        assertEquals(false, c.lastTickSawRx)
    }

    // --- teardown / doze -------------------------------------------------

    /** A torn-down tunnel (null stats) reports GONE. */
    @Test
    fun nullStatsReportsGone() {
        val c = newChecker()
        c.seed(0, stats(0, 0))
        assertEquals(WgVerdict.GONE, c.tick(1000, null))
    }

    /**
     * After a Doze gap the loop calls onSuspended to rebase timestamps so the
     * elapsed wall-clock gap is not mistaken for a stalled tunnel. An
     * outstanding send from before the gap must not immediately break.
     */
    @Test
    fun suspensionDoesNotCauseFalseBreak() {
        val c = newChecker()
        c.seed(0, stats(0, 0))
        c.tick(1000, stats(50, 10))   // Connected
        c.tick(2000, stats(50, 20))   // outstanding send (tx advanced past rx)

        // Device dozes for ~98s, then wakes.
        val wake = 100_000L
        c.onSuspended(wake)

        // Immediately after wake the same (still unanswered) counters must not
        // be treated as a 98s stall.
        assertEquals(WgVerdict.HEALTHY, c.tick(wake + 1000, stats(50, 20)))
        assertEquals(0, prods)
    }

    /** onSuspended also rebases the Connecting state's start clock. */
    @Test
    fun suspensionRebasesConnectingState() {
        val c = newChecker()
        c.seed(0, stats(0, 0))
        c.tick(1000, stats(0, 100))   // sent without ever receiving → still Connecting

        val wake = 100_000L
        c.onSuspended(wake)

        // The pre-doze elapsed time must not count toward the rx timeout.
        assertEquals(WgVerdict.HEALTHY, c.tick(wake + 1000, stats(0, 100)))
        assertEquals(0, prods)
    }
}
