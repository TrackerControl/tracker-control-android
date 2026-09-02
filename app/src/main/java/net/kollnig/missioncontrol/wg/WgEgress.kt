package net.kollnig.missioncontrol.wg

import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import net.kollnig.missioncontrol.LocalNetworkAccess
import net.kollnig.missioncontrol.wgbridge.Logger as WgLogger
import net.kollnig.missioncontrol.wgbridge.Protector as WgProtector
import net.kollnig.missioncontrol.wgbridge.Tunnel as WgTunnel
import net.kollnig.missioncontrol.wgbridge.Wgbridge
import net.kollnig.missioncontrol.wgbridge.DnsRecorder as WgDnsRecorder

/**
 * Serializes monitor replacement without holding its lock while stopping an
 * old monitor. A monitor callback can synchronously request replacement, so
 * stopping under the lifecycle lock would deadlock against the monitor's
 * callback gate. The epoch cancels a replacement that races a stop or another
 * replacement.
 */
internal class WgMonitorLifecycle<T>(
    private val lock: Any,
    private val isRunning: (T) -> Boolean,
    private val stop: (T) -> Unit,
    private val start: (T) -> Unit
) {
    private var active: T? = null
    private var epoch = 0L

    /** Install and start [candidate], optionally only when no live monitor exists. */
    fun replace(candidate: T, isCurrent: () -> Boolean, onlyIfDead: Boolean = false): Boolean {
        if (!isCurrent()) {
            stop(candidate)
            return false
        }

        val old: T?
        val transaction: Long
        synchronized(lock) {
            if (onlyIfDead && active?.let(isRunning) == true) return false
            old = active
            active = null
            transaction = ++epoch
        }

        // Do not hold lock while stop() waits for callbacks to finish.
        old?.let(stop)

        if (!isCurrent()) {
            stop(candidate)
            return false
        }

        var installed = false
        synchronized(lock) {
            if (transaction == epoch) {
                active = candidate
                // Start while ownership is held, so a concurrent stop cannot
                // detach an unstarted candidate that would be started later.
                start(candidate)
                installed = true
            }
        }
        if (!installed) stop(candidate)
        return installed
    }

    /** Detach the current monitor, then stop it without holding [lock]. */
    fun stop() {
        val old = synchronized(lock) {
            ++epoch
            active.also { active = null }
        }
        old?.let(stop)
    }

    fun isRunning(): Boolean = synchronized(lock) { active?.let(isRunning) == true }
}

/**
 * Owns the gotatun (Rust WireGuard) tunnel that sits behind NetGuard's
 * IP-layer hijack.
 *
 * Lifecycle is driven by [startOrUpdate] from `ServiceSinkhole.startNative`
 * and [stop] from the actual VPN-shutdown path. Crucially, `stopNative` does
 * NOT call [stop] — when NetGuard does a "Native restart" reload (same
 * builder, same TUN fd) we want WG to keep running so we don't redo the
 * handshake on every DHCP/connectivity blip.
 *
 * The wgbridge classes used here are hand-written JNI bindings to the Rust
 * crate in `wgbridge-rs/`; build instructions live in `wgbridge-rs/README.md`.
 */
object WgEgress {
    private const val TAG = "WgEgress"
    private const val DEFAULT_MTU = 1420
    private const val POST_WAKE_VERIFY_DELAY_MS = 3_000L
    private const val HANDSHAKE_DEAD_AFTER_MS = 180_000L
    private const val RECOVERY_NOTIFY_AFTER_MS = 30_000L
    private const val ENDPOINT_CACHE_TTL_MS = 5 * 60 * 1000L
    private const val RESOLVE_TIMEOUT_MS = 4_000L
    // If the monitor declares the tunnel broken again this soon after a
    // rebind + re-resolve, the cheap path clearly didn't help — escalate to
    // a full restart.
    private const val CHEAP_RECOVERY_ESCALATION_MS = 90_000L
    // Exponential backoff for repeated full-restart cycles (captive portal,
    // blocked UDP path): first restart is immediate, subsequent ones back off
    // instead of looping every ~20s (BYTES_RX_TIMEOUT_MS + PING_TIMEOUT_MS)
    // indefinitely, each doing DNS + handshake + monitor polling under a wakelock.
    private const val RESTART_BACKOFF_BASE_MS = 20_000L
    private const val RESTART_BACKOFF_MAX_MS = 5 * 60_000L
    // A dead relay server looks identical to any other broken-tunnel cause
    // from in here (no rx, handshake stalls). After this many full-restart
    // cycles against the SAME endpoint have all failed to recover, and on
    // every further multiple of it, ask the provider-aware callback to move
    // the active profile to a different relay before continuing the restart
    // loop, instead of retrying the one dead server forever.
    private const val RELAY_FAILOVER_AFTER_ATTEMPTS = 3
    // Bounds the relay-list fetch + config rewrite so a stalled network call
    // can never withhold a restart for longer than the caller would have
    // waited anyway. forceRestartPending is already set by the time this
    // runs, which makes onMonitorBroken/onUnderlyingNetworkChanged no-op
    // until a restart is scheduled — without a bound, a hung call would
    // silently stall recovery instead of merely skipping the relay switch.
    private const val FAILOVER_TIMEOUT_MS = 15_000L

    @Volatile private var tunnel: WgTunnel? = null
    // Monotonically identifies the tunnel instance published in [tunnel].
    // Recovery runs off-thread and may finish after startOrUpdate has replaced
    // that instance, so object identity alone is not enough to associate its
    // result with the lifecycle that launched it. AtomicLong also makes the
    // read/modify/write safe across the VPN, monitor, and rebind threads.
    private val tunnelGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val tunnelLifecycleLock = Any()
    @Volatile private var lastError: String? = null
    // A provider's own explanation for why the tunnel cannot come up — an
    // exhausted device limit, a lapsed account. It outlives the failover
    // attempt that learned it, because the generic recovery check runs
    // afterwards and would otherwise overwrite it with "unresponsive". Cleared
    // once a handshake proves the tunnel works, or when it is torn down.
    @Volatile private var providerFailureReason: String? = null
    // What the in-flight failover has learned so far, not yet applied. See
    // [reportProviderFailure].
    @Volatile private var pendingProviderFailure: String? = null
    @Volatile private var verificationGeneration: Long = 0
    @Volatile private var currentConfig: String? = null
    private var currentTunFd: Int = -1
    // The exact ParcelFileDescriptor the running tunnel was started with. A
    // re-established VPN can be handed a new pfd that reuses the previous
    // integer fd number, so comparing fd ints alone would wrongly no-op and
    // leave the Rust side writing into a dup of the dead TUN. Identity of the
    // pfd object distinguishes "same TUN reused across a Native restart"
    // (same object) from "new TUN that happens to alias the fd number".
    private var currentTunPfd: ParcelFileDescriptor? = null
    // Volatile: read on every poll by the wg-conn-monitor thread, written from
    // the vpn handler thread (startOrUpdate / reapplyConfig).
    @Volatile private var currentInteractive: Boolean = true
    private var currentKeepaliveAlwaysOn: Boolean = false
    @Volatile private var forceRestartPending: Boolean = false
    @Volatile private var lastCheapRecoveryMs: Long = 0
    @Volatile private var recoveryNotificationGeneration: Long = 0
    @Volatile private var restartAttempts: Int = 0
    // Tracks whether a delayed (backed-off) restart is currently posted, so
    // clearRecoveryState() can cancel it without forcing verifyHandler's lazy
    // init on paths that never scheduled anything (e.g. plain-JUnit-tested
    // no-tunnel callers that never touch the main-thread Looper).
    @Volatile private var restartScheduled: Boolean = false
    @Volatile private var pendingRestartTunnel: WgTunnel? = null
    @Volatile private var pendingRestartTunnelGeneration: Long = -1
    private val pendingRestartRunnable = Runnable {
        restartScheduled = false
        // If forceRestartPending was cleared while this was queued, the tunnel
        // was already (re)started by another path (e.g. a network-change
        // reload consumed the pending restart in startOrUpdate) — firing now
        // would kick a redundant restart of an already-healthy tunnel.
        synchronized(tunnelLifecycleLock) {
            val expected = TunnelSnapshot(
                pendingRestartTunnel ?: return@synchronized,
                pendingRestartTunnelGeneration
            )
            // Keep validation and callback dispatch atomic with publication.
            // The callback only posts the service reload; if that ever changes
            // to synchronously start the tunnel, JVM monitors are re-entrant.
            if (forceRestartPending && isCurrentLocked(expected)) {
                pendingRestartTunnel = null
                pendingRestartTunnelGeneration = -1
                requestReloadCb?.run()
            }
        }
    }

    // Single-thread executor for network-change rebinds: bounds the thread
    // count on a flapping network (instead of one raw Thread per event).
    // rebindInFlight means a rebind task is running; a network change arriving
    // during that window sets rebindDirty so the task re-runs once with the
    // latest network instead of being dropped — otherwise the sockets could
    // stay bound to a network that has already gone away.
    private val rebindExecutor = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "wg-rebind").apply { isDaemon = true }
    }
    private val rebindLock = Any()
    private var rebindInFlight: Boolean = false
    private var rebindDirty: Boolean = false

    @Volatile private var requestReloadCb: Runnable? = null
    @Volatile private var notifyBrokenCb: Runnable? = null
    // Provider-aware hook: tries to move the active profile to a different
    // relay server (same provider/account/country, reused key material) and
    // returns whether it actually switched. Null, or a profile with no
    // provider (self-hosted / manually imported single-server configs), means
    // there is nothing to fail over to.
    @Volatile private var regenerateEndpointCb: (() -> Boolean)? = null
    private val failoverInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val failoverExecutor = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "wg-failover").apply { isDaemon = true }
    }
    // Guarded by monitorLock: started/stopped from the vpn handler thread,
    // the wg-rebind thread, and the dying monitor thread itself. Unsynchronized
    // access could leak a second polling thread (double prods, double restarts).
    private val monitorLock = Any()
    private val monitorLifecycle = WgMonitorLifecycle<WgConnectivityMonitor>(
        lock = monitorLock,
        isRunning = { it.isRunning() },
        stop = { it.stop() },
        start = { it.start() }
    )

    private val verifyHandler by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Runnable>()
    private val endpointCache = mutableMapOf<String, EndpointCacheEntry>()
    // Last successful resolution per host:port, without TTL. Recovery paths
    // fall back to this when DNS fails or times out — during a broken-tunnel
    // window the resolver itself runs over the (dead or dropped) VPN network,
    // so fresh resolution is often impossible exactly when we need it most.
    private val lastKnownEndpoints = mutableMapOf<String, String>()
    private val endpointCacheLock = Any()

    /**
     * Recovery hooks supplied by the VPN service: [requestReload] kicks a
     * reload (which restarts WG because [forceRestartPending] is set), and
     * [notifyBroken] surfaces a user-facing notification. Registered once
     * when the service starts the egress.
     */
    fun setRecoveryCallbacks(
        requestReload: Runnable,
        notifyBroken: Runnable,
        regenerateEndpoint: (() -> Boolean)? = null
    ) {
        requestReloadCb = requestReload
        notifyBrokenCb = notifyBroken
        regenerateEndpointCb = regenerateEndpoint
    }

    fun addStateListener(l: Runnable) { listeners.add(l) }
    fun removeStateListener(l: Runnable) { listeners.remove(l) }
    private fun notifyStateChanged() {
        for (l in listeners) try { l.run() } catch (e: Throwable) { Log.w(TAG, "listener threw", e) }
    }

    /**
     * Bring the tunnel up, take it down, or leave it alone — whichever the
     * desired state requires. Idempotent: same config + same TUN fd is a
     * no-op so reload-induced restarts don't re-handshake.
     *
     * Returns true on success or already-correct state. Returns false if
     * WG was supposed to start but failed; in that case the caller must keep
     * the VPN from forwarding direct traffic so the user remains fail-closed.
     */
    fun startOrUpdate(
        wgEnabled: Boolean,
        configText: String?,
        vpnService: VpnService,
        vpnFd: ParcelFileDescriptor,
        interactive: Boolean,
        keepaliveAlwaysOn: Boolean,
        startSocketpair: () -> Int,
        stopSocketpair: () -> Unit
    ): Boolean {
        verificationGeneration++
        val wantRunning = wgEnabled && !configText.isNullOrEmpty()
        val desiredFd = vpnFd.fd
        lastError = null

        if (!wantRunning) {
            clearRecoveryState()
            clearAllEndpointState()
            if (tunnel != null) {
                Log.i(TAG, "WG disabled — tearing down tunnel")
                stopInternal(stopSocketpair)
                notifyStateChanged()
            }
            return true
        }

        if (tunnel != null && currentConfig == configText && currentTunPfd === vpnFd && !forceRestartPending) {
            val oldKeepaliveEnabled = currentInteractive || currentKeepaliveAlwaysOn
            val newKeepaliveEnabled = interactive || keepaliveAlwaysOn
            if (oldKeepaliveEnabled != newKeepaliveEnabled &&
                !reapplyConfigOrError(configText!!, newKeepaliveEnabled, interactive, keepaliveAlwaysOn))
                return false
            // The tunnel can outlive a monitor whose initial stats read raced
            // tunnel startup (or which exited after a stale sample). Keep the
            // idempotent tunnel path, but recreate a dead watchdog so a
            // same-config start does not silently leave connectivity unwatched.
            startMonitorIfDead()
            Log.v(TAG, "startOrUpdate: same config + same TUN pfd, no-op")
            return true
        }

        if (tunnel != null) {
            Log.i(TAG, "WG config, TUN fd, or recovery state changed — restarting")
            stopInternal(stopSocketpair)
        }
        forceRestartPending = false
        val keepaliveEnabled = interactive || keepaliveAlwaysOn

        val parsed = try {
            WgConfigParser.parse(configText!!)
        } catch (e: Exception) {
            lastError = "Invalid WireGuard config: ${e.message}"
            Log.e(TAG, "config parse: ${e.message}")
            notifyStateChanged()
            return false
        }

        val resolved = try {
            withResolvedEndpoints(parsed)
        } catch (e: Exception) {
            lastError = "WireGuard endpoint resolution failed: ${e.message}"
            Log.e(TAG, "endpoint resolve: ${e.message}")
            notifyStateChanged()
            return false
        }

        val rxFd = startSocketpair()
        if (rxFd < 0) {
            lastError = "Could not create WireGuard packet socket"
            Log.e(TAG, "jni_wireguard_start failed")
            notifyStateChanged()
            return false
        }

        val mtu = resolved.mtu ?: DEFAULT_MTU
        val protector = object : WgProtector {
            override fun protect(fd: Int): Boolean = vpnService.protect(fd)
        }
        val logger = object : WgLogger {
            override fun verbosef(s: String) { Log.v(TAG, s) }
            override fun errorf(s: String)   { Log.e(TAG, s) }
        }
        val dnsRecorder = object : WgDnsRecorder {
            override fun recordDns(qname: String, aname: String, resource: String, ttl: Int) {
                if (vpnService is eu.faircode.netguard.ServiceSinkhole)
                    vpnService.wireGuardDnsResolved(qname, aname, resource, ttl)
            }
        }

        val startedTunnel = try {
            Wgbridge.startTunnel(
                resolved.toUapi(keepaliveEnabled), rxFd, desiredFd, mtu, protector, logger, dnsRecorder
            )
        } catch (e: Throwable) {
            lastError = "WireGuard tunnel failed to start: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, "Wgbridge.startTunnel failed", e)
            stopSocketpair()
            notifyStateChanged()
            return false
        } finally {
            closeRawFd(rxFd)
        }

        currentConfig = configText
        currentTunFd = desiredFd
        currentTunPfd = vpnFd
        currentInteractive = interactive
        currentKeepaliveAlwaysOn = keepaliveAlwaysOn
        synchronized(tunnelLifecycleLock) {
            tunnelGeneration.incrementAndGet()
            // Volatile publication happens only after all companion state has
            // been installed above.
            tunnel = startedTunnel
        }
        Log.i(TAG, "WG up: tunFd=$desiredFd mtu=$mtu peers=${resolved.peers.size}")
        notifyStateChanged()
        scheduleFreshHandshakeNotificationCheck()
        startMonitor()
        return true
    }

    private fun startMonitor() {
        val expected = captureTunnel() ?: return
        startMonitor(expected)
    }

    private fun startMonitorIfDead() {
        val expected = captureTunnel() ?: return
        startMonitor(expected, onlyIfDead = true)
    }

    private fun startMonitor(expected: TunnelSnapshot, onlyIfDead: Boolean = false) {
        // Build the candidate before entering the lifecycle transaction. The
        // transaction itself never calls isCurrent while holding monitorLock:
        // monitor callbacks call back into isCurrent (tunnelLifecycleLock), so
        // nesting those locks would recreate the callback/replacement cycle.
        val candidate = WgConnectivityMonitor(
            statsProvider = { statsOrNull(expected) },
            prod = {
                if (isCurrent(expected)) try {
                    expected.tunnel.sendKeepalive()
                } catch (e: Throwable) {
                    Log.w(TAG, "prod keepalive threw", e)
                }
            },
            onBroken = { if (isCurrent(expected)) onMonitorBroken(expected) },
            onConnected = { if (isCurrent(expected)) notifyStateChanged() },
            // Reset the restart backoff only on observed return traffic:
            // a fresh handshake shortly after a restart is exactly the
            // signal a handshake-passes-but-data-drops failure loop also
            // produces, so resetting on it would defeat the backoff.
            onRxAdvanced = { if (isCurrent(expected)) restartAttempts = 0 },
            isInteractive = { currentInteractive },
            isCurrent = { isCurrent(expected) }
        )
        monitorLifecycle.replace(
            candidate,
            isCurrent = { isCurrent(expected) },
            onlyIfDead = onlyIfDead
        )
    }

    private fun stopMonitor() {
        monitorLifecycle.stop()
    }

    private fun onMonitorBroken(expected: TunnelSnapshot) {
        if (!isCurrent(expected)) return
        // A full restart is already queued; it will rebind everything anyway.
        if (forceRestartPending) return

        // First recourse: rebind the UDP sockets and re-resolve the endpoint.
        // That covers the common roaming failures (network switch, endpoint
        // IP change) without redoing the handshake or dropping packets.
        // Escalate to a full restart if a recent cheap recovery didn't stick.
        val now = now()
        if (now - lastCheapRecoveryMs > CHEAP_RECOVERY_ESCALATION_MS) {
            when (tryCheapRecovery(expected)) {
                RecoveryResult.STALE -> return
                RecoveryResult.SUCCEEDED -> {
                    if (!isCurrent(expected)) return
                    lastCheapRecoveryMs = now
                    Log.w(TAG, "connectivity monitor: tunnel broken; rebound sockets, watching")
                    startMonitor(expected)
                    return
                }
                RecoveryResult.FAILED -> Unit
            }
        }

        requestFullRestart(
            "connectivity monitor: tunnel still broken after cheap recovery",
            notify = true,
            expected = expected,
            // Only a monitor-observed break (no rx/handshake) is evidence the
            // relay itself is unreachable; a rebind failure below is not.
            eligibleForFailover = true
        )
    }

    private fun requestFullRestart(
        reason: String,
        notify: Boolean,
        expected: TunnelSnapshot,
        eligibleForFailover: Boolean
    ) {
        val attempt: Int
        synchronized(tunnelLifecycleLock) {
            // This check and installation of pending state must be atomic with
            // tunnel replacement. Otherwise a replacement can land between
            // them and inherit forceRestartPending from an obsolete failure.
            if (!isCurrentLocked(expected)) return
            clearEndpointCache()
            attempt = restartAttempts++
            forceRestartPending = true
            pendingRestartTunnel = expected.tunnel
            pendingRestartTunnelGeneration = expected.generation
        }
        Log.w(TAG, "$reason; forcing restart (attempt=${attempt + 1})")
        if (notify) scheduleRecoveryNotificationCheck()

        val regenerate = regenerateEndpointCb
        if (eligibleForFailover && attempt > 0 && attempt % RELAY_FAILOVER_AFTER_ATTEMPTS == 0 && regenerate != null) {
            tryRelayFailover(regenerate, expected, attempt)
            return
        }
        scheduleRestart(attempt)
    }

    private fun scheduleRestart(attempt: Int) {
        val delay = if (attempt == 0) 0L
        else minOf(RESTART_BACKOFF_MAX_MS, RESTART_BACKOFF_BASE_MS shl (attempt - 1).coerceAtMost(10))
        verifyHandler.removeCallbacks(pendingRestartRunnable)
        if (delay <= 0L) {
            restartScheduled = false
            pendingRestartRunnable.run()
        } else {
            restartScheduled = true
            verifyHandler.postDelayed(pendingRestartRunnable, delay)
        }
    }

    /**
     * The same relay has now failed to recover across several full-restart
     * cycles — likely the server itself is down, not a local network blip.
     * Runs the (network-bound) [regenerate] callback off-thread; on success
     * it has already rewritten the active profile's config, so the queued
     * restart picks up the new peer and the backoff resets, since the new
     * relay hasn't earned any of the old one's penalty. On failure or
     * [FAILOVER_TIMEOUT_MS] timeout, falls back to the normal same-config
     * backoff so we never restart less often than before this existed.
     *
     * [resolved] guards against the timeout and the completed callback both
     * trying to schedule a restart for the same attempt: whichever of the
     * two runs first (main thread for the timeout, [failoverExecutor] for
     * the callback) wins the race, the other is a no-op.
     */
    private fun tryRelayFailover(regenerate: () -> Boolean, expected: TunnelSnapshot, attempt: Int) {
        if (!failoverInFlight.compareAndSet(false, true)) {
            scheduleRestart(attempt)
            return
        }
        val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
        pendingProviderFailure = null
        val timeoutRunnable = Runnable {
            if (resolved.compareAndSet(false, true)) {
                failoverInFlight.set(false)
                pendingProviderFailure = null
                if (isCurrent(expected)) {
                    Log.w(TAG, "relay failover timed out after ${FAILOVER_TIMEOUT_MS}ms; falling back to backoff")
                    scheduleRestart(attempt)
                }
            }
        }
        verifyHandler.postDelayed(timeoutRunnable, FAILOVER_TIMEOUT_MS)
        try {
            failoverExecutor.execute {
                val switched = try {
                    regenerate()
                } catch (e: Throwable) {
                    Log.w(TAG, "relay failover callback threw", e)
                    false
                } finally {
                    failoverInFlight.set(false)
                }
                verifyHandler.removeCallbacks(timeoutRunnable)
                val refusal = pendingProviderFailure
                pendingProviderFailure = null
                if (!resolved.compareAndSet(false, true)) return@execute
                if (!isCurrent(expected)) return@execute
                if (refusal != null) {
                    // The provider explained why this tunnel cannot come up.
                    // Publishing it here, on the tunnel it belongs to, both
                    // notifies now and outlives the generic recovery check.
                    providerFailureReason = refusal
                    lastError = refusal
                    notifyStateChanged()
                }
                if (switched) {
                    Log.w(TAG, "relay failover: switched the active profile to a different server")
                    restartAttempts = 0
                    scheduleRestart(0)
                } else {
                    scheduleRestart(attempt)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            verifyHandler.removeCallbacks(timeoutRunnable)
            failoverInFlight.set(false)
            pendingProviderFailure = null
            if (resolved.compareAndSet(false, true)) scheduleRestart(attempt)
        }
    }

    private fun scheduleRecoveryNotificationCheck() {
        val gen = ++recoveryNotificationGeneration
        // Pin the tunnel this check is about: a reload can swap in a
        // different tunnel before the delayed callback fires, and without
        // this the callback would read the new tunnel's handshake state and
        // report it as the still-broken old one.
        val expected = captureTunnel()
        verifyHandler.postDelayed({
            if (gen != recoveryNotificationGeneration) return@postDelayed
            if (expected == null || !isCurrent(expected)) return@postDelayed
            val latest = latestHandshakeMillisOrNull() ?: 0L
            if (latest > 0 && now() - latest < HANDSHAKE_DEAD_AFTER_MS) {
                lastError = null
                providerFailureReason = null
                notifyStateChanged()
                return@postDelayed
            }
            lastError = providerFailureReason ?: "WireGuard tunnel unresponsive"
            notifyStateChanged()
            notifyBrokenCb?.run()
        }, RECOVERY_NOTIFY_AFTER_MS)
    }

    /**
     * Rebinds the outer UDP sockets on the current default network,
     * re-resolves peer hostnames (bypassing the endpoint cache), and prods
     * the tunnel. Runs synchronously; callers must be off the main thread.
     */
    private fun tryCheapRecovery(expected: TunnelSnapshot): RecoveryResult {
        synchronized(tunnelLifecycleLock) {
            if (!isCurrentLocked(expected)) return RecoveryResult.STALE
            clearEndpointCache()
        }
        val configText = currentConfig ?: return RecoveryResult.STALE
        return try {
            if (!isCurrent(expected)) return RecoveryResult.STALE
            expected.tunnel.rebind()
            if (!isCurrent(expected)) return RecoveryResult.STALE
            if (!refreshPeerEndpoints(expected, configText)) return RecoveryResult.STALE
            if (!isCurrent(expected)) return RecoveryResult.STALE
            expected.tunnel.sendKeepalive()
            if (isCurrent(expected)) RecoveryResult.SUCCEEDED else RecoveryResult.STALE
        } catch (e: Throwable) {
            Log.w(TAG, "WG rebind/re-resolve failed", e)
            if (isCurrent(expected)) RecoveryResult.FAILED else RecoveryResult.STALE
        }
    }

    /**
     * Re-resolves each peer's configured endpoint hostname and moves the peer
     * if the address changed. The config keeps the original hostnames, so
     * roaming across resolver views (e.g. after crossing a border) picks up
     * the new server IP.
     */
    private fun refreshPeerEndpoints(expected: TunnelSnapshot, configText: String): Boolean {
        val parsed = WgConfigParser.parse(configText)
        for (peer in parsed.peers) {
            val endpoint = peer.endpoint ?: continue
            try {
                val resolved = resolveEndpoint(endpoint)
                if (!isCurrent(expected)) return false
                expected.tunnel.updateEndpoint(peer.publicKey, resolved)
            } catch (e: Throwable) {
                Log.w(TAG, "endpoint refresh for $endpoint failed", e)
            }
        }
        return isCurrent(expected)
    }

    private fun statsOrNull(expected: TunnelSnapshot? = captureTunnel()): WgStats? = try {
        if (expected == null || !isCurrent(expected)) null
        else expected.tunnel.stats().let {
            // hasFreshHandshake deliberately uses WireGuard's REJECT_AFTER_TIME
            // (180s): while a handshake is younger than the session lifetime,
            // "tx without rx" is not proof of breakage — an idle tunnel whose
            // keepalives are (by protocol) unanswered looks exactly like that.
            // Once the session expires, a prod forces a re-handshake, which
            // either advances rx (alive) or fails (genuinely broken). The cost
            // is a detection floor of up to ~3 min for paths that break right
            // after a successful handshake; the benefit is zero restart churn
            // on healthy idle tunnels. Shorten this only together with a real
            // in-tunnel probe (Mullvad uses ICMP pings for this).
            WgStats(
                it.rxBytes,
                it.txBytes,
                it.latestHandshakeMillis,
                it.latestHandshakeMillis > 0 && now() - it.latestHandshakeMillis < HANDSHAKE_DEAD_AFTER_MS
            )
        }
    } catch (e: Throwable) {
        // Tunnel.stats() crosses JNI. A missing sample is ambiguous by
        // design and the monitor already bounds how long it tolerates one,
        // so an Error here must not escape into the polling loop.
        null
    }

    /** Tear down the tunnel completely. Called on actual service stop. */
    fun stop(stopSocketpair: () -> Unit) {
        clearRecoveryState()
        clearAllEndpointState()
        if (tunnel != null) {
            stopInternal(stopSocketpair)
            notifyStateChanged()
        }
    }

    fun isRunning(): Boolean = tunnel != null

    fun getLastError(): String? = lastError

    /**
     * Records why the provider refused to bring this tunnel up, so the blocked
     * state names the actual problem instead of the generic "unresponsive"
     * message the recovery check would otherwise post over it.
     */
    fun reportProviderFailure(reason: String?) {
        // Staged rather than applied: the failover that learned this runs
        // off-thread and can finish after its tunnel was replaced — the user
        // switched profiles, or turned WireGuard off. [tryRelayFailover]
        // commits it only while that tunnel is still current, so a refusal
        // from an abandoned attempt cannot surface against the profile the
        // user moved to.
        pendingProviderFailure = reason?.takeIf { it.isNotBlank() }
    }

    fun latestHandshakeMillisOrNull(): Long? =
        try { tunnel?.latestHandshakeMillis() } catch (_: Throwable) { null }

    fun onUnderlyingNetworkChanged() {
        verificationGeneration++
        clearEndpointCache()
        if (tunnel == null) return
        // A full restart is already queued (and the accompanying reload() is
        // in flight); rebinding concurrently would just race it.
        if (forceRestartPending) return

        // Rebind the protected UDP sockets onto the new default network and
        // re-resolve the endpoint instead of tearing the tunnel down: the
        // WireGuard session survives outer-address changes, so this recovers
        // roaming (Wi-Fi <-> cellular, crossing borders) without a
        // re-handshake. Runs off-thread because endpoint re-resolution does
        // blocking DNS. Falls back to a full restart if the rebind fails.
        synchronized(rebindLock) {
            if (rebindInFlight) {
                // A rebind is already running; the network changed again, so
                // mark it for a re-run rather than dropping this event.
                rebindDirty = true
                Log.i(TAG, "underlying network changed; rebind in flight, scheduling re-run")
                return
            }
            rebindInFlight = true
            rebindDirty = false
        }

        Log.i(TAG, "underlying network changed; rebinding WG sockets")
        rebindExecutor.execute {
            try {
                while (true) {
                    val expected = captureTunnel()
                    if (expected == null) {
                        synchronized(rebindLock) {
                            rebindInFlight = false
                            rebindDirty = false
                        }
                        return@execute
                    }
                    when (tryCheapRecovery(expected)) {
                        RecoveryResult.SUCCEEDED -> {
                            if (isCurrent(expected)) lastCheapRecoveryMs = now()
                        }
                        RecoveryResult.FAILED -> {
                            if (!forceRestartPending) {
                                requestFullRestart(
                                    "WG rebind failed after network change",
                                    notify = false,
                                    expected = expected,
                                    // A rebind failure means the local network
                                    // changed under us, not that the relay is
                                    // dead — don't let it count toward
                                    // switching relays.
                                    eligibleForFailover = false
                                )
                            }
                        }
                        RecoveryResult.STALE -> Unit
                    }
                    synchronized(rebindLock) {
                        if (!rebindDirty) {
                            rebindInFlight = false
                            return@execute
                        }
                        // Another network change landed mid-rebind; loop once
                        // more with the now-current default network.
                        rebindDirty = false
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "WG rebind task failed", e)
                synchronized(rebindLock) {
                    rebindInFlight = false
                    rebindDirty = false
                }
            }
        }
    }

    /**
     * Apply the screen-state keepalive policy (PersistentKeepalive is dropped
     * while the screen is off to save battery). Tunnel liveness and recovery
     * are owned by [WgConnectivityMonitor], not this screen event.
     */
    fun onInteractiveStateChanged(
        wgEnabled: Boolean,
        configText: String?,
        interactive: Boolean,
        keepaliveAlwaysOn: Boolean
    ) {
        if (!hasRunningTunnel(wgEnabled, configText)) {
            clearRecoveryState()
            return
        }

        val oldKeepaliveEnabled = currentInteractive || currentKeepaliveAlwaysOn
        val newKeepaliveEnabled = interactive || keepaliveAlwaysOn
        if (oldKeepaliveEnabled == newKeepaliveEnabled) {
            currentInteractive = interactive
            currentKeepaliveAlwaysOn = keepaliveAlwaysOn
            return
        }

        try {
            reapplyConfig(configText!!, newKeepaliveEnabled, interactive, keepaliveAlwaysOn)
        } catch (e: Throwable) {
            Log.w(TAG, "could not update WG keepalive for screen state", e)
        }
    }

    private fun stopInternal(stopSocketpair: () -> Unit) {
        val t = synchronized(tunnelLifecycleLock) {
            val old = tunnel
            // Invalidate in-flight recovery before stopping the old object.
            // Any later success/failure result is now harmlessly stale.
            tunnel = null
            tunnelGeneration.incrementAndGet()
            // Also covers the plain restart path (config/fd/recovery-state
            // change with a tunnel already up), which replaces the tunnel
            // without going through stop()/clearRecoveryState first – a
            // scheduled recovery-notification check must not survive it.
            recoveryNotificationGeneration++
            // stop() calls clearRecoveryState first, but a recovery failure may
            // land between that call and this invalidation. Clear again while
            // holding the same lock used to install pending restart state.
            forceRestartPending = false
            pendingRestartTunnel = null
            pendingRestartTunnelGeneration = -1
            old
        }
        // Invalidate the tunnel before cancelling the monitor. An in-progress
        // monitor replacement can then observe the stale epoch and cannot
        // install a watcher for the tunnel being torn down.
        stopMonitor()
        currentConfig = null
        currentTunFd = -1
        currentTunPfd = null
        currentKeepaliveAlwaysOn = false
        lastCheapRecoveryMs = 0
        verificationGeneration++
        // An error describes a tunnel that no longer exists. Listeners check
        // lastError before isRunning — deliberately, so a start that fails
        // without ever producing a tunnel still reports — so leaving it set
        // here kept a stopped tunnel reporting its final error forever.
        lastError = null
        providerFailureReason = null
        pendingProviderFailure = null
        if (t != null) {
            try {
                t.stop()
            } catch (e: Throwable) {
                Log.w(TAG, "tunnel.stop threw", e)
            }
        }
        stopSocketpair()
    }

    private fun hasRunningTunnel(wgEnabled: Boolean, configText: String?): Boolean {
        return wgEnabled && !configText.isNullOrEmpty() && tunnel != null
    }

    private fun reapplyConfigOrError(
        configText: String,
        keepaliveEnabled: Boolean,
        interactive: Boolean,
        keepaliveAlwaysOn: Boolean
    ): Boolean {
        return try {
            reapplyConfig(configText, keepaliveEnabled, interactive, keepaliveAlwaysOn)
            true
        } catch (e: Throwable) {
            lastError = "WireGuard tunnel failed to update: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, "Wgbridge.setConfig failed", e)
            notifyStateChanged()
            false
        }
    }

    private fun reapplyConfig(
        configText: String,
        keepaliveEnabled: Boolean,
        interactive: Boolean,
        keepaliveAlwaysOn: Boolean
    ) {
        val t = tunnel ?: return
        val resolved = withResolvedEndpoints(WgConfigParser.parse(configText))
        t.setConfig(resolved.toUapi(keepaliveEnabled))
        currentInteractive = interactive
        currentKeepaliveAlwaysOn = keepaliveAlwaysOn
        lastError = null
        notifyStateChanged()
    }

    private fun clearRecoveryState() {
        verificationGeneration++
        recoveryNotificationGeneration++
        providerFailureReason = null
        pendingProviderFailure = null
        forceRestartPending = false
        pendingRestartTunnel = null
        pendingRestartTunnelGeneration = -1
        restartAttempts = 0
        // restartScheduled guards the lazy init: this runs on every no-tunnel
        // /disable path, most of which never scheduled a delayed restart and
        // so never touched verifyHandler in the first place.
        if (restartScheduled) {
            restartScheduled = false
            verifyHandler.removeCallbacks(pendingRestartRunnable)
        }
    }

    private fun scheduleFreshHandshakeNotificationCheck() {
        val gen = verificationGeneration
        verifyHandler.postDelayed({
            val latest = latestHandshakeMillisOrNull() ?: 0L
            val fresh = latest > 0 && now() - latest < HANDSHAKE_DEAD_AFTER_MS
            // Note: a fresh handshake deliberately does NOT reset the restart
            // backoff. Handshake success is not proof the data path works
            // (some paths pass handshakes but drop transport packets), and a
            // failing restart loop produces this exact signal 3s after every
            // restart, which used to defeat the backoff entirely. The monitor
            // resets the backoff when it observes rx advancing instead.
            if (gen != verificationGeneration) return@postDelayed
            if (fresh) {
                lastError = null
                providerFailureReason = null
                recoveryNotificationGeneration++
                notifyStateChanged()
            }
        }, POST_WAKE_VERIFY_DELAY_MS)
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun withResolvedEndpoints(config: WgConfig): WgConfig {
        return config.copy(peers = config.peers.map { peer ->
            val ep = peer.endpoint
            if (ep == null) peer else peer.copy(endpoint = resolveEndpoint(ep))
        })
    }

    /**
     * "host:port" or "[v6-host]:port" -> "ip:port" or "[ip]:port".
     * Hostname is resolved synchronously; caller must run off the main thread.
     */
    private fun resolveEndpoint(endpoint: String): String {
        val (host, port) = if (endpoint.startsWith("[")) {
            val close = endpoint.indexOf(']')
            require(close > 0 && endpoint.length > close + 2 && endpoint[close + 1] == ':') {
                "malformed IPv6 endpoint: $endpoint"
            }
            endpoint.substring(1, close) to endpoint.substring(close + 2)
        } else {
            val colon = endpoint.lastIndexOf(':')
            require(colon > 0 && colon < endpoint.length - 1) {
                "missing :port in endpoint: $endpoint"
            }
            endpoint.substring(0, colon) to endpoint.substring(colon + 1)
        }
        val cacheKey = "$host:$port"
        val cached = cachedEndpoint(cacheKey)
        if (cached != null)
            return cached

        val resolved = try {
            val addr = resolveHostBounded(host)
            val ip = addr.hostAddress ?: throw IllegalStateException("getHostAddress null for $host")
            // A peer named by host name can still sit on the user's own network,
            // which Android 17 blocks without ACCESS_LOCAL_NETWORK (#701). The
            // address is in hand here, so no extra lookup is needed to notice.
            LocalNetworkAccess.reportDestination(ip)
            if (addr is java.net.Inet6Address) "[$ip]:$port" else "$ip:$port"
        } catch (e: Exception) {
            // DNS often fails exactly when we resolve: the resolver runs over
            // the VPN network, which is dead (broken tunnel) or dropped
            // (fail-closed restart window). Reuse the last IP that worked —
            // WG endpoints rarely move, and a wrong guess just leads the
            // monitor to escalate again.
            val fallback = synchronized(endpointCacheLock) { lastKnownEndpoints[cacheKey] }
            if (fallback == null) throw e
            Log.w(TAG, "endpoint resolution for $host failed (${e.message}); using last known $fallback")
            return fallback
        }
        synchronized(endpointCacheLock) {
            endpointCache[cacheKey] = EndpointCacheEntry(resolved, now())
            lastKnownEndpoints[cacheKey] = resolved
        }
        return resolved
    }

    /**
     * [java.net.InetAddress.getByName] with a deadline: netd can block for
     * ~30s when DNS is unreachable, which would stall the recovery threads
     * for far longer than the fallback path needs.
     */
    private fun resolveHostBounded(host: String): java.net.InetAddress {
        val task = java.util.concurrent.FutureTask { java.net.InetAddress.getByName(host) }
        Thread(task, "wg-resolve").apply { isDaemon = true }.start()
        return try {
            task.get(RESOLVE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            task.cancel(true)
            throw java.net.UnknownHostException("DNS timeout for $host")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    private fun cachedEndpoint(cacheKey: String): String? {
        val now = now()
        synchronized(endpointCacheLock) {
            val cached = endpointCache[cacheKey] ?: return null
            if (now - cached.resolvedAtMillis <= ENDPOINT_CACHE_TTL_MS)
                return cached.endpoint
            endpointCache.remove(cacheKey)
            return null
        }
    }

    /** Drops the TTL cache so the next resolution is fresh; keeps the
     *  last-known fallback, which recovery relies on when DNS is down. */
    private fun clearEndpointCache() {
        synchronized(endpointCacheLock) {
            endpointCache.clear()
        }
    }

    /** Full reset, including the last-known fallback. For stop/disable only. */
    private fun clearAllEndpointState() {
        synchronized(endpointCacheLock) {
            endpointCache.clear()
            lastKnownEndpoints.clear()
        }
    }

    private fun closeRawFd(fd: Int) {
        try {
            ParcelFileDescriptor.adoptFd(fd).close()
        } catch (e: Throwable) {
            Log.w(TAG, "close fd $fd failed", e)
        }
    }

    private data class EndpointCacheEntry(
        val endpoint: String,
        val resolvedAtMillis: Long
    )

    private data class TunnelSnapshot(
        val tunnel: WgTunnel,
        val generation: Long
    )

    private enum class RecoveryResult { SUCCEEDED, FAILED, STALE }

    private fun captureTunnel(): TunnelSnapshot? {
        synchronized(tunnelLifecycleLock) {
            val generation = tunnelGeneration.get()
            val current = tunnel ?: return null
            return TunnelSnapshot(current, generation)
        }
    }

    private fun isCurrent(expected: TunnelSnapshot): Boolean =
        synchronized(tunnelLifecycleLock) { isCurrentLocked(expected) }

    private fun isCurrentLocked(expected: TunnelSnapshot): Boolean =
        expected.generation == tunnelGeneration.get() && tunnel === expected.tunnel
}
