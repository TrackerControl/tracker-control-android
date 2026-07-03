package net.kollnig.missioncontrol.wg

import android.os.SystemClock
import android.util.Log

/** Immutable transfer-counter snapshot read from the wgbridge tunnel. */
data class WgStats(
    val rxBytes: Long,
    val txBytes: Long,
    val latestHandshakeMillis: Long
)

/** Outcome of a single connectivity poll. */
internal enum class WgVerdict { HEALTHY, WAITING, BROKEN, GONE }

/**
 * Pure, clock-injected liveness state machine, adapted from Mullvad's talpid
 * connectivity monitor (talpid-wireguard/src/connectivity, MPL-2.0).
 *
 * This holds no threads or Android clock so it can be unit-tested by feeding
 * explicit `now` timestamps and counter snapshots — mirroring Mullvad's
 * `check.rs` tests, where `ConnState`/`check_connectivity_interval` are driven
 * with a controlled clock. [WgConnectivityMonitor] owns the actual loop.
 *
 * Mullvad polls per-peer rx/tx counters every second and, when it has sent
 * traffic without seeing a reply, sends ICMP pings to prod the tunnel and
 * declares it broken if no rx arrives within a timeout. We adapt that to this
 * app's outbound-only, battery-sensitive egress in two ways:
 *
 *  - We do NOT actively ping an idle tunnel. The user-visible failure is "I'm
 *    using the network but nothing loads" — which shows up as tx advancing
 *    past rx ([rxTimedOut]). An idle tunnel with no demand is left alone, so
 *    we never wake the radio (or churn a healthy tunnel) for nothing. A tunnel
 *    that died while idle is caught the instant real traffic resumes.
 *  - The "prod" is a cheap WireGuard keepalive (NAT refresh), not an injected
 *    ICMP packet.
 */
internal class WgConnectivityChecker(private val prod: () -> Unit) {
    companion object {
        /** After sending, how long to wait for a reply before prodding. */
        const val BYTES_RX_TIMEOUT_MS = 5_000L

        /** Once prodding, how long without rx before the tunnel is broken. */
        const val PING_TIMEOUT_MS = 15_000L

        /** Minimum spacing between prods. */
        const val SECONDS_PER_PING_MS = 3_000L
    }

    private sealed class ConnState {
        abstract val rx: Long
        abstract val tx: Long

        data class Connecting(
            val start: Long,
            val awaitingRx: Boolean,
            override val rx: Long,
            override val tx: Long
        ) : ConnState()

        data class Connected(
            val rxTimestamp: Long,
            val txTimestamp: Long,
            override val rx: Long,
            override val tx: Long
        ) : ConnState()
    }

    private var state: ConnState = ConnState.Connecting(0, false, 0, 0)
    private var initialProdTs: Long? = null
    private var numProds: Int = 0

    /** Seed the baseline counters at loop start (or after a tunnel restart). */
    fun seed(now: Long, stats: WgStats) {
        state = ConnState.Connecting(now, false, stats.rxBytes, stats.txBytes)
        resetProd()
    }

    /** One poll. [stats] == null means the tunnel was torn down underneath us. */
    fun tick(now: Long, stats: WgStats?): WgVerdict {
        if (stats == null) return WgVerdict.GONE

        if (update(now, stats.rxBytes, stats.txBytes)) {
            resetProd()
            return WgVerdict.HEALTHY
        }

        // Only judge the tunnel when we have outbound traffic that isn't being
        // answered. An idle tunnel (no demand) is never restarted.
        if (!rxTimedOut(now)) {
            resetProd()
            return WgVerdict.HEALTHY
        }

        maybeProd(now)
        return if (prodTimedOut(now)) WgVerdict.BROKEN else WgVerdict.WAITING
    }

    /**
     * The device dozed: rebase timestamps so the elapsed gap is not mistaken
     * for a stall (Mullvad's `reset_after_suspension`).
     */
    fun onSuspended(now: Long) {
        resetProd()
        when (val s = state) {
            is ConnState.Connected -> state = s.copy(rxTimestamp = now, txTimestamp = now)
            is ConnState.Connecting -> state = s.copy(start = now)
        }
    }

    /** Folds a new counter sample into [state]. Returns true if rx advanced. */
    private fun update(now: Long, newRx: Long, newTx: Long): Boolean {
        return when (val s = state) {
            is ConnState.Connecting -> {
                if (newRx > s.rx) {
                    state = ConnState.Connected(now, now, newRx, newTx)
                    true
                } else {
                    val txInc = newTx > s.tx
                    state = ConnState.Connecting(
                        start = if (!s.awaitingRx && txInc) now else s.start,
                        awaitingRx = s.awaitingRx || txInc,
                        rx = newRx,
                        tx = newTx
                    )
                    false
                }
            }
            is ConnState.Connected -> {
                val rxInc = newRx > s.rx
                val rxTs = if (rxInc) now else s.rxTimestamp
                val txTs = if (newTx > s.tx) now else s.txTimestamp
                state = ConnState.Connected(rxTs, txTs, newRx, newTx)
                rxInc
            }
        }
    }

    /**
     * True once we have an *unanswered* send: tx advanced strictly after the
     * last receive and no reply has arrived for [BYTES_RX_TIMEOUT_MS].
     *
     * Note the strict `>`. Mullvad uses `>=` here, but their monitor keeps the
     * tunnel busy with active pings, so a connected-but-idle tunnel never
     * lingers in the "tx == rx timestamp" state. We ping passively, so a strict
     * comparison is required: at connect we set tx==rx, and a genuinely idle
     * tunnel (no new sends) must NOT be treated as awaiting a reply — otherwise
     * we'd prod and restart a perfectly healthy idle tunnel every ~20s.
     */
    private fun rxTimedOut(now: Long): Boolean = when (val s = state) {
        is ConnState.Connecting -> s.awaitingRx && now - s.start >= BYTES_RX_TIMEOUT_MS
        is ConnState.Connected ->
            s.txTimestamp > s.rxTimestamp && now - s.rxTimestamp >= BYTES_RX_TIMEOUT_MS
    }

    private fun maybeProd(now: Long) {
        val cadenceOk = initialProdTs?.let { (now - it) / numProds >= SECONDS_PER_PING_MS } ?: true
        if (!cadenceOk) return
        try {
            prod()
        } catch (e: Throwable) {
            Log.w(TAG, "prod threw", e)
        }
        if (initialProdTs == null) initialProdTs = now
        numProds++
    }

    private fun prodTimedOut(now: Long): Boolean =
        initialProdTs?.let { now - it > PING_TIMEOUT_MS } ?: false

    private fun resetProd() {
        initialProdTs = null
        numProds = 0
    }
}

private const val TAG = "WgConnMonitor"

/**
 * Continuous WireGuard liveness watchdog. Owns the 1s polling thread and the
 * Android clock, delegating the actual decision to [WgConnectivityChecker].
 *
 * Doze handling mirrors Mullvad's `SUSPEND_TIMEOUT` reset: if the loop notices
 * it was suspended longer than expected (the device dozed), the checker
 * rebases its timestamps instead of mistaking the gap for a stalled tunnel.
 * This replaces the previous generation-counter / rate-limit / screen-event
 * recovery, which was unreliable.
 */
internal class WgConnectivityMonitor(
    private val statsProvider: () -> WgStats?,
    prod: () -> Unit,
    private val onBroken: () -> Unit
) {
    private companion object {
        /** How often the loop samples the tunnel counters. */
        const val LOOP_SLEEP_MS = 1_000L

        /** A wall-clock gap larger than this means the device dozed. */
        const val SUSPEND_TIMEOUT_MS = 6_000L
    }

    private val checker = WgConnectivityChecker(prod)

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::runLoop, "wg-conn-monitor").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun runLoop() {
        val seed = statsProvider() ?: return
        var lastCheck = SystemClock.elapsedRealtime()
        checker.seed(lastCheck, seed)

        while (running && !Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(LOOP_SLEEP_MS)
            } catch (e: InterruptedException) {
                break
            }

            val now = SystemClock.elapsedRealtime()
            val slept = now - lastCheck
            lastCheck = now

            // The device dozed; the elapsed gap is not evidence of a stall.
            if (slept >= SUSPEND_TIMEOUT_MS) {
                checker.onSuspended(now)
                continue
            }

            when (checker.tick(now, statsProvider())) {
                WgVerdict.HEALTHY, WgVerdict.WAITING -> {}
                WgVerdict.BROKEN -> {
                    Log.w(TAG, "tunnel unresponsive after prod; reporting broken state")
                    running = false
                    onBroken()
                    return
                }
                WgVerdict.GONE -> return
            }
        }
    }
}
