package net.kollnig.missioncontrol.wg

import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import net.kollnig.missioncontrol.wgbridge.Logger as WgLogger
import net.kollnig.missioncontrol.wgbridge.Protector as WgProtector
import net.kollnig.missioncontrol.wgbridge.Tunnel as WgTunnel
import net.kollnig.missioncontrol.wgbridge.Wgbridge
import net.kollnig.missioncontrol.wgbridge.DnsRecorder as WgDnsRecorder

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

    @Volatile private var tunnel: WgTunnel? = null
    @Volatile private var lastError: String? = null
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
    private var currentInteractive: Boolean = true
    private var currentKeepaliveAlwaysOn: Boolean = false
    @Volatile private var forceRestartPending: Boolean = false
    @Volatile private var lastCheapRecoveryMs: Long = 0
    @Volatile private var recoveryNotificationGeneration: Long = 0
    @Volatile private var restartAttempts: Int = 0

    // Single-thread executor for network-change rebinds: bounds the thread
    // count on a flapping network (instead of one raw Thread per event) and
    // rebindQueued dedups bursts so only one rebind is in flight/queued at a time.
    private val rebindExecutor = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "wg-rebind").apply { isDaemon = true }
    }
    private val rebindLock = Any()
    @Volatile private var rebindQueued: Boolean = false

    @Volatile private var requestReloadCb: Runnable? = null
    @Volatile private var notifyBrokenCb: Runnable? = null
    // Guarded by monitorLock: started/stopped from the vpn handler thread,
    // the wg-rebind thread, and the dying monitor thread itself. Unsynchronized
    // access could leak a second polling thread (double prods, double restarts).
    private var monitor: WgConnectivityMonitor? = null
    private val monitorLock = Any()

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
    fun setRecoveryCallbacks(requestReload: Runnable, notifyBroken: Runnable) {
        requestReloadCb = requestReload
        notifyBrokenCb = notifyBroken
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

        try {
            tunnel = Wgbridge.startTunnel(
                resolved.toUapi(keepaliveEnabled), rxFd, desiredFd, mtu, protector, logger, dnsRecorder
            )
        } catch (e: Throwable) {
            lastError = "WireGuard tunnel failed to start: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, "Wgbridge.startTunnel failed", e)
            closeRawFd(rxFd)
            stopSocketpair()
            notifyStateChanged()
            return false
        } finally {
            if (tunnel != null) closeRawFd(rxFd)
        }

        currentConfig = configText
        currentTunFd = desiredFd
        currentTunPfd = vpnFd
        currentInteractive = interactive
        currentKeepaliveAlwaysOn = keepaliveAlwaysOn
        Log.i(TAG, "WG up: tunFd=$desiredFd mtu=$mtu peers=${resolved.peers.size}")
        notifyStateChanged()
        scheduleFreshHandshakeNotificationCheck()
        startMonitor()
        return true
    }

    private fun startMonitor() {
        synchronized(monitorLock) {
            monitor?.stop()
            monitor = WgConnectivityMonitor(
                statsProvider = { statsOrNull() },
                prod = { try { tunnel?.sendKeepalive() } catch (e: Throwable) { Log.w(TAG, "prod keepalive threw", e) } },
                onBroken = { onMonitorBroken() }
            ).also { it.start() }
        }
    }

    private fun stopMonitor() {
        synchronized(monitorLock) {
            monitor?.stop()
            monitor = null
        }
    }

    private fun onMonitorBroken() {
        if (tunnel == null) return
        // A full restart is already queued; it will rebind everything anyway.
        if (forceRestartPending) return

        // First recourse: rebind the UDP sockets and re-resolve the endpoint.
        // That covers the common roaming failures (network switch, endpoint
        // IP change) without redoing the handshake or dropping packets.
        // Escalate to a full restart if a recent cheap recovery didn't stick.
        val now = now()
        if (now - lastCheapRecoveryMs > CHEAP_RECOVERY_ESCALATION_MS && tryCheapRecovery()) {
            lastCheapRecoveryMs = now
            Log.w(TAG, "connectivity monitor: tunnel broken; rebound sockets, watching")
            startMonitor()
            return
        }

        requestFullRestart("connectivity monitor: tunnel still broken after cheap recovery", notify = true)
    }

    private fun requestFullRestart(reason: String, notify: Boolean) {
        val attempt = restartAttempts++
        val delay = if (attempt == 0) 0L
        else minOf(RESTART_BACKOFF_MAX_MS, RESTART_BACKOFF_BASE_MS shl (attempt - 1).coerceAtMost(10))
        Log.w(TAG, "$reason; forcing restart (attempt=${attempt + 1}, delay=${delay}ms)")
        clearEndpointCache()
        forceRestartPending = true
        if (notify) scheduleRecoveryNotificationCheck()
        if (delay <= 0L) {
            requestReloadCb?.run()
        } else {
            verifyHandler.postDelayed({ requestReloadCb?.run() }, delay)
        }
    }

    private fun scheduleRecoveryNotificationCheck() {
        val gen = ++recoveryNotificationGeneration
        verifyHandler.postDelayed({
            if (gen != recoveryNotificationGeneration) return@postDelayed
            if (tunnel == null) return@postDelayed
            val latest = latestHandshakeMillisOrNull() ?: 0L
            if (latest > 0 && now() - latest < HANDSHAKE_DEAD_AFTER_MS) {
                lastError = null
                restartAttempts = 0
                notifyStateChanged()
                return@postDelayed
            }
            lastError = "WireGuard tunnel unresponsive"
            notifyStateChanged()
            notifyBrokenCb?.run()
        }, RECOVERY_NOTIFY_AFTER_MS)
    }

    /**
     * Rebinds the outer UDP sockets on the current default network,
     * re-resolves peer hostnames (bypassing the endpoint cache), and prods
     * the tunnel. Runs synchronously; callers must be off the main thread.
     */
    private fun tryCheapRecovery(): Boolean {
        val t = tunnel ?: return false
        val configText = currentConfig ?: return false
        return try {
            clearEndpointCache()
            t.rebind()
            refreshPeerEndpoints(t, configText)
            t.sendKeepalive()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "WG rebind/re-resolve failed", e)
            false
        }
    }

    /**
     * Re-resolves each peer's configured endpoint hostname and moves the peer
     * if the address changed. The config keeps the original hostnames, so
     * roaming across resolver views (e.g. after crossing a border) picks up
     * the new server IP.
     */
    private fun refreshPeerEndpoints(t: WgTunnel, configText: String) {
        val parsed = WgConfigParser.parse(configText)
        for (peer in parsed.peers) {
            val endpoint = peer.endpoint ?: continue
            try {
                t.updateEndpoint(peer.publicKey, resolveEndpoint(endpoint))
            } catch (e: Throwable) {
                Log.w(TAG, "endpoint refresh for $endpoint failed", e)
            }
        }
    }

    private fun statsOrNull(): WgStats? = try {
        tunnel?.stats()?.let {
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
            if (rebindQueued) {
                Log.i(TAG, "underlying network changed; rebind already queued, skipping duplicate")
                return
            }
            rebindQueued = true
        }

        Log.i(TAG, "underlying network changed; rebinding WG sockets")
        rebindExecutor.execute {
            try {
                if (tryCheapRecovery()) {
                    lastCheapRecoveryMs = now()
                } else if (tunnel != null && !forceRestartPending) {
                    requestFullRestart("WG rebind failed after network change", notify = false)
                }
            } finally {
                synchronized(rebindLock) { rebindQueued = false }
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

        try {
            reapplyConfig(configText!!, interactive || keepaliveAlwaysOn, interactive, keepaliveAlwaysOn)
        } catch (e: Throwable) {
            Log.w(TAG, "could not update WG keepalive for screen state", e)
        }
    }

    private fun stopInternal(stopSocketpair: () -> Unit) {
        stopMonitor()
        val t = tunnel
        tunnel = null
        currentConfig = null
        currentTunFd = -1
        currentTunPfd = null
        currentKeepaliveAlwaysOn = false
        lastCheapRecoveryMs = 0
        verificationGeneration++
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
        forceRestartPending = false
        restartAttempts = 0
    }

    private fun scheduleFreshHandshakeNotificationCheck() {
        val gen = verificationGeneration
        verifyHandler.postDelayed({
            if (gen != verificationGeneration) return@postDelayed
            val latest = latestHandshakeMillisOrNull() ?: 0L
            if (latest > 0 && now() - latest < HANDSHAKE_DEAD_AFTER_MS) {
                lastError = null
                recoveryNotificationGeneration++
                restartAttempts = 0
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
}
