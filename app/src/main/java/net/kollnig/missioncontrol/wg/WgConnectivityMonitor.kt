package net.kollnig.missioncontrol.wg

import android.os.SystemClock
import android.util.Log

/** Immutable transfer-counter snapshot read from the wgbridge tunnel. */
data class WgStats(
    val rxBytes: Long,
    val txBytes: Long,
    val latestHandshakeMillis: Long,
    val hasFreshHandshake: Boolean = false
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

    /**
     * True when the most recent [tick] observed the rx counter advancing —
     * decrypted return traffic, the only signal that proves the data path
     * works end to end. A completed handshake is NOT such proof (a path can
     * pass handshakes yet drop transport packets), so recovery backoff resets
     * key off this instead of handshake freshness.
     */
    var lastTickSawRx: Boolean = false
        private set

    /** Seed the baseline counters at loop start (or after a tunnel restart). */
    fun seed(now: Long, stats: WgStats) {
        state = ConnState.Connecting(now, false, stats.rxBytes, stats.txBytes)
        lastTickSawRx = false
        resetProd()
    }

    /** One poll. [stats] == null means the tunnel was torn down underneath us. */
    fun tick(now: Long, stats: WgStats?): WgVerdict {
        lastTickSawRx = false
        if (stats == null) return WgVerdict.GONE

        if (stats.hasFreshHandshake) {
            lastTickSawRx = update(now, stats.rxBytes, stats.txBytes)
            resetProd()
            return WgVerdict.HEALTHY
        }

        if (update(now, stats.rxBytes, stats.txBytes)) {
            lastTickSawRx = true
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
        } catch (e: Exception) {
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
 * Continuous WireGuard liveness watchdog. Owns the polling thread and the
 * Android clock, delegating the actual decision to [WgConnectivityChecker].
 *
 * The poll cadence adapts to screen state: 1s while the user is interactive,
 * 15s while it is off. The monitor is purely passive — it reads transfer
 * counters and never wakes the radio or holds a wakelock — but each poll still
 * costs a CPU wake, and its whole purpose is catching stalls *while traffic
 * flows*, which overwhelmingly happens with the screen on. Background sync at
 * night still gets stall detection, just at ~1/15th the wakeups; deep doze
 * suspends the thread entirely either way.
 *
 * Doze handling mirrors Mullvad's `SUSPEND_TIMEOUT` reset: if the loop notices
 * it was suspended longer than expected (the device dozed), the checker
 * rebases its timestamps instead of mistaking the gap for a stalled tunnel.
 * This replaces the previous generation-counter / rate-limit / screen-event
 * recovery, which was unreliable.
 */
internal class WgConnectivityMonitor(
    private val statsProvider: () -> WgStats?,
    private val prod: () -> Unit,
    private val onBroken: () -> Unit,
    // Fired each time this monitor observes a completed handshake after a
    // period without one (tunnel start, or re-handshake after session expiry),
    // so listeners (e.g. the settings status line) can move from
    // "Handshaking" to "Connected" without polling.
    private val onConnected: () -> Unit = {},
    // Fired on every poll that observed the rx counter advancing. This is the
    // only end-to-end proof the data path works (handshake success is not:
    // some paths pass handshakes but drop transport packets), so it is what
    // recovery backoff resets must key off.
    private val onRxAdvanced: () -> Unit = {},
    // Screen state, sampled every iteration so the cadence follows the screen
    // without restarting the thread. Backed by WgEgress.currentInteractive.
    private val isInteractive: () -> Boolean = { true },
    // A null stats sample is ambiguous at this layer: Tunnel.stats() can fail
    // transiently, but a callback for a replaced/stopped tunnel must not
    // recover the new tunnel. WgEgress supplies this callback from its tunnel
    // generation check.
    private val isCurrent: () -> Boolean = { true },
    // Injectable for lifecycle tests. The production default preserves the
    // screen-aware cadence and never busy-loops between stats failures.
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    // Test-only barrier between callback authorization and dispatch. Keeping
    // this seam here makes the stop/callback ordering deterministic without
    // adding any production scheduling.
    private val beforeCallback: () -> Unit = {}
) {
    internal companion object {
        /** How often the loop samples the tunnel counters while the screen is on. */
        const val INTERACTIVE_LOOP_SLEEP_MS = 1_000L

        /**
         * Sampling interval while the screen is off. Slow enough that the
         * wakeup cost is negligible, fast enough that a background-sync stall
         * is caught within one cycle and recovery still runs before the next
         * maintenance window.
         */
        const val IDLE_LOOP_SLEEP_MS = 15_000L

        /**
         * A wall-clock gap past one full cycle plus this margin means the
         * device dozed. Scales with the current interval, preserving the old
         * fixed 6s threshold at the 1s interactive cadence.
         */
        const val SUSPEND_MARGIN_MS = 5_000L

        /** Consecutive failed samples before entering the existing recovery path. */
        const val MAX_STATS_FAILURES = 3

        /** A blocked stats read is stale only after several idle cadence cycles. */
        const val STATS_READ_TIMEOUT_MS = IDLE_LOOP_SLEEP_MS * 4

        fun pollIntervalMs(interactive: Boolean): Long =
            if (interactive) INTERACTIVE_LOOP_SLEEP_MS else IDLE_LOOP_SLEEP_MS

        fun isSuspendGap(sleptMs: Long, intervalMs: Long): Boolean =
            sleptMs >= intervalMs + SUSPEND_MARGIN_MS
    }

    private val lifecycleLock = Any()
    private val callbackLock = Any()
    private var running = false
    private var thread: Thread? = null
    private var lifecycleGeneration = 0L
    // Non-null only while this monitor thread is inside statsProvider. It lets
    // isRunning() detect a native/JNI stats call that never returns, without a
    // timer, alarm, or extra polling thread.
    private var statsReadStartedAt: Long? = null

    fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            running = true
            val generation = ++lifecycleGeneration
            statsReadStartedAt = null
            val monitorThread = Thread({ runLoop(generation) }, "wg-conn-monitor").apply {
                isDaemon = true
            }
            thread = monitorThread
            monitorThread.start()
        }
    }

    fun stop() {
        val monitorThread = synchronized(lifecycleLock) {
            running = false
            lifecycleGeneration++
            val oldThread = thread
            thread = null
            statsReadStartedAt = null
            oldThread
        }
        // Interrupt outside the lock: a provider can synchronously call back
        // into stop(), and stopping must not wait for a stats read to return.
        // The callback gate is acquired after lifecycle invalidation so an
        // authorized callback either finishes before stop returns or is
        // rejected by the generation check.
        synchronized(callbackLock) { }
        monitorThread?.interrupt()
    }

    /** Whether this monitor currently owns a live polling thread. */
    fun isRunning(): Boolean {
        val now = clock()
        val staleThread = synchronized(lifecycleLock) {
            val startedAt = statsReadStartedAt
            if (running && startedAt != null && now - startedAt >= STATS_READ_TIMEOUT_MS) {
                running = false
                lifecycleGeneration++
                val oldThread = thread
                thread = null
                statsReadStartedAt = null
                oldThread
            } else {
                null
            }
        }
        // Interrupt outside the lock: native stats implementations may return
        // promptly on interruption, while lifecycle callers must not block.
        staleThread?.interrupt()
        return synchronized(lifecycleLock) { running }
    }

    private fun isActiveLocked(generation: Long): Boolean =
        running && lifecycleGeneration == generation && thread === Thread.currentThread()

    private fun isActive(generation: Long): Boolean = synchronized(lifecycleLock) {
        isActiveLocked(generation)
    }

    private fun waitForNextPoll(generation: Long, intervalMs: Long): Boolean {
        if (!isActive(generation)) return false
        return try {
            sleep(intervalMs)
            isActive(generation)
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun readStats(generation: Long): WgStats? {
        val beganRead = synchronized(lifecycleLock) {
            if (!isActiveLocked(generation)) {
                false
            } else {
                statsReadStartedAt = clock()
                true
            }
        }
        if (!beganRead) return null

        return try {
            try {
                statsProvider()
            } catch (e: Exception) {
                warn("stats read threw", e)
                null
            }
        } finally {
            synchronized(lifecycleLock) {
                if (lifecycleGeneration == generation && thread === Thread.currentThread())
                    statsReadStartedAt = null
            }
        }
    }

    private fun invokeCallback(generation: Long, label: String, callback: () -> Unit) {
        synchronized(callbackLock) {
            val authorized = synchronized(lifecycleLock) {
                isActiveLocked(generation)
            }
            if (!authorized) return

            try {
                beforeCallback()
            } catch (e: Exception) {
                warn("$label readiness hook threw", e)
            }

            // stop() may have invalidated this generation while the readiness
            // hook was waiting. Recheck while holding the callback gate so a
            // stop that returns can never be followed by an old callback.
            val stillAuthorized = synchronized(lifecycleLock) {
                isActiveLocked(generation)
            }
            if (!stillAuthorized) return

            try {
                callback()
            } catch (e: Exception) {
                warn("$label threw", e)
            }
        }
    }

    private fun reportBroken(generation: Long, reason: String) {
        warn(reason)
        synchronized(callbackLock) {
            // Invalidate before dispatching recovery. The callback gate keeps
            // stop() from returning until this already-authorized callback is
            // complete, while reentrant stop/start remain safe.
            val authorized = synchronized(lifecycleLock) {
                if (!isActiveLocked(generation)) {
                    false
                } else {
                    running = false
                    lifecycleGeneration++
                    thread = null
                    statsReadStartedAt = null
                    true
                }
            }
            if (!authorized) return

            try {
                onBroken()
            } catch (e: Exception) {
                warn("onBroken threw", e)
            }
        }
    }

    private fun warn(message: String, error: Throwable? = null) {
        try {
            if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
        } catch (_: Exception) {
            // Android's Log methods are not mocked by plain JVM tests. A log
            // failure must never change the monitor's lifecycle behavior.
        }
    }

    private fun runLoop(generation: Long) {
        // Keep checker state local to a run. If stop() races a blocked stats
        // call and start() launches a replacement, the old run cannot mutate
        // the new run's counters or prod cadence.
        val checker = WgConnectivityChecker {
            // Keep keepalives behind the same generation/callback gate as all
            // other monitor side effects. This prevents stop() from returning
            // between an active check and sendKeepalive().
            invokeCallback(generation, "prod", prod)
        }
        var announcedConnected = false

        try {
            var seeded = false
            var lastCheck = 0L
            var statsFailures = 0

            while (isActive(generation) && !Thread.currentThread().isInterrupted) {
                if (!seeded) {
                    val seed = readStats(generation)
                    if (!isActive(generation)) return
                    if (seed == null) {
                        // Startup races can make Tunnel.stats unavailable
                        // before the native tunnel has finished publishing its
                        // counters. Retry at the normal screen-aware cadence,
                        // while a stale tunnel exits without recovering a
                        // replacement.
                        if (!isCurrent()) return
                        statsFailures++
                        if (statsFailures >= MAX_STATS_FAILURES) {
                            if (!isInteractive()) {
                                // Defer active recovery while the screen is
                                // off. Reset the evidence and keep sampling at
                                // the idle cadence; the next interactive polls
                                // will reach the bounded recovery path.
                                statsFailures = 0
                            } else {
                                reportBroken(
                                    generation,
                                    "stats unavailable for $statsFailures polls; reporting broken state"
                                )
                                return
                            }
                        }
                        if (!waitForNextPoll(generation, pollIntervalMs(isInteractive()))) return
                        continue
                    }
                    if (!isCurrent()) return
                    lastCheck = clock()
                    checker.seed(lastCheck, seed)
                    seeded = true
                    statsFailures = 0
                }

                val intervalMs = pollIntervalMs(isInteractive())
                if (!waitForNextPoll(generation, intervalMs)) return

                val now = clock()
                val slept = now - lastCheck
                lastCheck = now

                // The device dozed; the elapsed gap is not evidence of a stall.
                if (isSuspendGap(slept, intervalMs)) {
                    statsFailures = 0
                    checker.onSuspended(now)
                    continue
                }

                val stats = readStats(generation)
                if (!isActive(generation)) return
                if (stats == null) {
                    // A replaced/stopped tunnel is an ordinary lifecycle exit,
                    // not evidence that the replacement should be recovered.
                    if (!isCurrent()) return
                    statsFailures++
                    if (statsFailures >= MAX_STATS_FAILURES) {
                        if (isInteractive()) {
                            reportBroken(
                                generation,
                                "stats unavailable for $statsFailures polls; reporting broken state"
                            )
                            return
                        }
                        // Do not rebind/prod an idle-screen tunnel solely on
                        // missing stats. Keep the existing idle cadence and
                        // defer recovery until the screen is interactive.
                        statsFailures = 0
                    }
                    // Keep the existing screen-aware cadence while retrying;
                    // never spin or add an extra wakeup/radio operation.
                    continue
                }
                statsFailures = 0
                if (!isCurrent()) return

                when (checker.tick(now, stats)) {
                    WgVerdict.HEALTHY, WgVerdict.WAITING -> {}
                    WgVerdict.BROKEN -> {
                        // Recover regardless of screen state. Unlike a null
                        // stats sample, BROKEN is direct evidence that the
                        // data path is dead: tx advanced, no rx followed, and
                        // the keepalive prod did not revive it. Deferring the
                        // restart until the screen comes on would strand every
                        // app without network (the hijack is fail-closed) while
                        // still prodding the radio each idle poll, so it costs
                        // battery without buying recovery.
                        reportBroken(generation, "tunnel unresponsive after prod; reporting broken state")
                        return
                    }
                    // Null samples are handled before checker.tick so GONE is
                    // reserved for the pure checker API and cannot kill this
                    // monitor on a transient Tunnel.stats exception.
                    WgVerdict.GONE -> return
                }

                if (checker.lastTickSawRx) {
                    invokeCallback(generation, "onRxAdvanced", onRxAdvanced)
                }

                // Announce on every no-session -> session transition, not just
                // the first: an expired session (idle screen-off tunnel past
                // REJECT_AFTER_TIME) drops latestHandshakeMillis back to 0, and the
                // lazy re-handshake on the next packet must re-notify listeners or
                // the settings status line stays frozen on "Handshaking".
                val hasHandshake = stats.latestHandshakeMillis > 0L
                if (!announcedConnected && hasHandshake) {
                    announcedConnected = true
                    invokeCallback(generation, "onConnected", onConnected)
                } else if (announcedConnected && !hasHandshake) {
                    announcedConnected = false
                }
            }
        } finally {
            // Every exit path, including a null seed, stale tunnel, interrupted
            // sleep, and callback failure, must release the lifecycle slot.
            synchronized(lifecycleLock) {
                if (lifecycleGeneration == generation && thread === Thread.currentThread()) {
                    running = false
                    thread = null
                }
            }
        }
    }
}
