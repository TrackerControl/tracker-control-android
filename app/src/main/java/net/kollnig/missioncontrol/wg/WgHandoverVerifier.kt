package net.kollnig.missioncontrol.wg

import java.net.InetAddress

/** A numeric source/resolver pair for an in-tunnel DNS probe. */
internal data class WgProbeTarget(val sourceIp: String, val resolverIp: String)

/**
 * Bounded, generation-scoped verification after an underlying-network change.
 * It is deliberately clock-injected and side-effect free: the owner dispatches
 * [Action.Probe] away from the main thread and decides how to restart.
 */
internal class WgHandoverVerifier(
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    companion object {
        const val PROBE_INTERVAL_MS = 5_000L
        const val VERIFY_TIMEOUT_MS = 15_000L
        const val MAX_PROBES = 3
    }

    sealed class Action {
        object None : Action()
        data class Probe(val generation: Long, val target: WgProbeTarget, val token: Long) : Action()
        data class Restart(val generation: Long) : Action()
    }

    private data class Session(
        val generation: Long,
        val targets: List<WgProbeTarget>,
        var baselineDelivered: Long,
        var currentToken: Long,
        var probesSent: Int,
        var nextProbeAt: Long,
        var deadline: Long,
        var paused: Boolean
    )

    private var session: Session? = null
    private var nextToken = 0L

    @Synchronized fun begin(
        generation: Long,
        baseline: WgStats,
        targets: List<WgProbeTarget>,
        interactive: Boolean,
        now: Long = clock()
    ): Action {
        session = null
        if (targets.isEmpty()) return Action.None
        val next = Session(
            generation = generation,
            targets = targets.toList(),
            baselineDelivered = baseline.deliveredRxBytes,
            currentToken = 0L,
            probesSent = 0,
            nextProbeAt = now,
            deadline = now + VERIFY_TIMEOUT_MS,
            paused = !interactive
        )
        session = next
        return if (interactive) issueProbe(next, now) else Action.None
    }

    @Synchronized fun onSample(
        generation: Long,
        stats: WgStats,
        interactive: Boolean,
        now: Long = clock()
    ): Action {
        val current = session ?: return Action.None
        if (current.generation != generation) return Action.None

        if (!interactive) {
            // Screen-off verification is deferred and rebased. This avoids
            // probes and deadlines being driven by the idle monitor cadence.
            current.paused = true
            current.baselineDelivered = stats.deliveredRxBytes
            current.currentToken = 0L
            current.probesSent = 0
            return Action.None
        }

        if (current.paused) {
            current.paused = false
            current.baselineDelivered = stats.deliveredRxBytes
            current.currentToken = 0L
            current.probesSent = 0
            current.nextProbeAt = now
            current.deadline = now + VERIFY_TIMEOUT_MS
            return issueProbe(current, now)
        }

        if (stats.deliveredRxBytes > current.baselineDelivered ||
            (current.currentToken != 0L && stats.probeReplyToken == current.currentToken)) {
            session = null
            return Action.None
        }

        if (now >= current.deadline) {
            session = null
            return Action.Restart(current.generation)
        }

        return if (current.probesSent < MAX_PROBES && now >= current.nextProbeAt)
            issueProbe(current, now)
        else
            Action.None
    }

    @Synchronized fun isCurrent(generation: Long, token: Long): Boolean =
        session?.let { it.generation == generation && it.currentToken == token } == true

    @Synchronized fun isPending(): Boolean = session != null

    @Synchronized fun cancel() {
        session = null
    }

    @Synchronized fun setInteractive(interactive: Boolean) {
        if (!interactive) {
            session?.apply {
                paused = true
                currentToken = 0L
                probesSent = 0
            }
        }
    }

    @Synchronized fun onSuspended() {
        setInteractive(false)
    }

    @Synchronized fun cancelIfCurrent(generation: Long, token: Long) {
        if (isCurrent(generation, token)) session = null
    }

    private fun issueProbe(session: Session, now: Long): Action {
        val token = ++nextToken
        session.currentToken = token
        session.probesSent++
        session.nextProbeAt = now + PROBE_INTERVAL_MS
        val target = session.targets[(session.probesSent - 1) % session.targets.size]
        return Action.Probe(session.generation, target, token)
    }
}

/** Select only numeric, same-family resolver targets covered by WireGuard routes. */
internal object WgProbeTargetSelector {
    private val ipv4 = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")

    fun select(
        sourceAddresses: List<String>,
        resolverAddresses: List<String>,
        allowedIps: List<String>
    ): List<WgProbeTarget> {
        val sources = sourceAddresses.mapNotNull { numericAddress(it.substringBefore('/')) }
        val resolvers = resolverAddresses.mapNotNull { numericAddress(it) }
        val targets = ArrayList<WgProbeTarget>()
        for ((sourceText, source) in sources) {
            for ((resolverText, resolver) in resolvers) {
                if (source.size != resolver.size || !allowedIps.any { contains(it, resolver) })
                    continue
                val target = WgProbeTarget(sourceText, resolverText)
                if (!targets.contains(target)) targets += target
            }
        }
        return targets
    }

    private fun numericAddress(text: String, rejectSpecial: Boolean = true): Pair<String, ByteArray>? {
        if (text.isEmpty()) return null
        if (!text.contains(':') && !ipv4.matches(text)) return null
        if (text.contains(':') && !WgConfigParser.isIpv6Literal(text)) return null
        val bytes = try { InetAddress.getByName(text).address } catch (_: Exception) { return null }
        val address = try { InetAddress.getByAddress(bytes) } catch (_: Exception) { return null }
        if (rejectSpecial &&
            (address.isAnyLocalAddress || address.isMulticastAddress || address.isLoopbackAddress))
            return null
        return if ((text.contains(':') && bytes.size == 16) ||
            (!text.contains(':') && bytes.size == 4)) text to bytes else null
    }

    private fun contains(entry: String, address: ByteArray): Boolean {
        val slash = entry.indexOf('/')
        val prefixText = if (slash < 0) null else entry.substring(slash + 1)
        val parsed = numericAddress(
            if (slash < 0) entry else entry.substring(0, slash),
            rejectSpecial = false
        ) ?: return false
        if (parsed.second.size != address.size) return false
        val prefix = if (prefixText == null) parsed.second.size * 8
        else prefixText.toIntOrNull() ?: return false
        if (prefix !in 0..parsed.second.size * 8) return false
        var remaining = prefix
        for (i in parsed.second.indices) {
            if (remaining >= 8) {
                if (parsed.second[i] != address[i]) return false
                remaining -= 8
            } else if (remaining > 0) {
                val mask = (0xff shl (8 - remaining)) and 0xff
                if ((parsed.second[i].toInt() and mask) != (address[i].toInt() and mask)) return false
                break
            } else break
        }
        return true
    }
}
