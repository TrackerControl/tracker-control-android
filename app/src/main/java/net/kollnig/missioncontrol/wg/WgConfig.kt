package net.kollnig.missioncontrol.wg

import net.kollnig.missioncontrol.wg.WgConfigParser.base64ToHex

/**
 * Minimal parser for the WireGuard `.conf` format (a subset of the
 * `wg-quick` syntax). We deliberately do not pull in
 * `com.wireguard.android:tunnel` to avoid bundling its libwg-go.so
 * alongside our own WireGuard engine.
 *
 * Returned [WgConfig] also exposes a [toUapi] string in WireGuard's UAPI
 * configuration format — that's what `WgEgress` hands to the Rust bridge
 * (`wgbridge-rs/`, which embeds gotatun) at startup.
 */
data class WgConfig(
    val privateKey: String,
    val address: List<String>,   // CIDR strings, e.g. "10.0.0.2/32"
    val dns: List<String>,       // resolver IPs/search entries from wg-quick DNS
    val mtu: Int?,               // optional override
    val peers: List<WgPeer>
) {
    fun toUapi(keepaliveEnabled: Boolean = true): String {
        val sb = StringBuilder()
        sb.append("private_key=").append(base64ToHex(privateKey)).append('\n')
        for (peer in peers) {
            sb.append("public_key=").append(base64ToHex(peer.publicKey)).append('\n')
            peer.presharedKey?.let {
                sb.append("preshared_key=").append(base64ToHex(it)).append('\n')
            }
            peer.endpoint?.let { sb.append("endpoint=").append(it).append('\n') }
            peer.persistentKeepalive?.let {
                sb.append("persistent_keepalive_interval=")
                    .append(if (keepaliveEnabled) it else 0)
                    .append('\n')
            }
            sb.append("replace_allowed_ips=true\n")
            for (ip in peer.allowedIPs) sb.append("allowed_ip=").append(ip).append('\n')
        }
        return sb.toString()
    }
}

data class WgPeer(
    val publicKey: String,
    val presharedKey: String?,
    val allowedIPs: List<String>,
    val endpoint: String?,        // host:port (host may need DNS resolution)
    val persistentKeepalive: Int?
)

class WgConfigException(message: String) : Exception(message)

object WgConfigParser {

    fun parse(text: String): WgConfig {
        var section: String? = null
        var ifPrivKey: String? = null
        val ifAddress = mutableListOf<String>()
        val ifDns = mutableListOf<String>()
        var ifMtu: Int? = null

        var peerPub: String? = null
        var peerPsk: String? = null
        val peerAllowed = mutableListOf<String>()
        var peerEndpoint: String? = null
        var peerPersistentKeepalive: Int? = null
        val peers = mutableListOf<WgPeer>()

        fun flushPeer() {
            if (peerPub == null) return
            peers.add(
                WgPeer(
                    publicKey = peerPub!!,
                    presharedKey = peerPsk,
                    allowedIPs = peerAllowed.toList(),
                    endpoint = peerEndpoint,
                    persistentKeepalive = peerPersistentKeepalive
                )
            )
            peerPub = null
            peerPsk = null
            peerAllowed.clear()
            peerEndpoint = null
            peerPersistentKeepalive = null
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                if (section == "Peer") flushPeer()
                section = line.substring(1, line.length - 1).trim()
                continue
            }
            val eq = line.indexOf('=')
            if (eq < 0) throw WgConfigException("malformed line: $rawLine")
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            when (section) {
                "Interface" -> when (key) {
                    "privatekey" -> ifPrivKey = requireBase64Key(value)
                    "address" -> ifAddress += value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    "dns" -> ifDns += value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    "mtu" -> ifMtu = value.toIntOrNull() ?: throw WgConfigException("invalid MTU: $value")
                    "listenport", "table", "preup", "predown", "postup", "postdown", "fwmark", "saveconfig" -> {
                        // wg-quick directives we intentionally ignore
                    }
                    else -> throw WgConfigException("unknown Interface key: $key")
                }
                "Peer" -> when (key) {
                    "publickey" -> peerPub = requireBase64Key(value)
                    "presharedkey" -> peerPsk = requireBase64Key(value)
                    "allowedips" -> peerAllowed += value.split(',').map { it.trim() }
                        .filter { it.isNotEmpty() }.map { requireAllowedIp(it) }
                    "endpoint" -> peerEndpoint = value
                    "persistentkeepalive" -> peerPersistentKeepalive = parseKeepalive(value)
                    else -> throw WgConfigException("unknown Peer key: $key")
                }
                else -> throw WgConfigException("data outside [Interface]/[Peer]")
            }
        }
        if (section == "Peer") flushPeer()

        if (ifPrivKey == null) throw WgConfigException("Interface.PrivateKey is required")
        if (peers.isEmpty()) throw WgConfigException("at least one [Peer] section is required")

        return WgConfig(
            privateKey = ifPrivKey,
            address = ifAddress,
            dns = ifDns,
            mtu = ifMtu,
            peers = peers
        )
    }

    private fun requireBase64Key(s: String): String {
        val bytes = try {
            java.util.Base64.getDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            throw WgConfigException("invalid base64 key")
        }
        if (bytes.size != 32) throw WgConfigException("key must decode to 32 bytes (got ${bytes.size})")
        return s
    }

    private fun parseKeepalive(s: String): Int {
        val value = s.toIntOrNull() ?: throw WgConfigException("invalid PersistentKeepalive: $s")
        if (value !in 0..65535)
            throw WgConfigException("PersistentKeepalive out of range: $s")
        return value
    }

    private val IPV4_OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
    private val IPV4_REGEX = Regex("^$IPV4_OCTET(\\.$IPV4_OCTET){3}$")

    private val IPV6_GROUP = Regex("^[0-9A-Fa-f]{1,4}$")

    /**
     * Numeric IPv6 literal check that never touches a resolver: up to eight
     * hex groups, at most one "::" compression, and optionally an IPv4
     * dotted-quad in place of the last two groups (as in ::ffff:192.0.2.0),
     * which the Rust IpNetwork parser also accepts. Zone ids are not part
     * of an AllowedIPs entry and are rejected.
     */
    internal fun isIpv6Literal(literal: String): Boolean {
        if (literal.count { it == ':' } < 2) return false
        var text = literal
        var groupsNeeded = 8
        val lastColon = text.lastIndexOf(':')
        if (text.indexOf('.', lastColon) >= 0) {
            if (!IPV4_REGEX.matches(text.substring(lastColon + 1))) return false
            text = text.substring(0, lastColon + 1)
            groupsNeeded = 6
            // A trailing "::" before the IPv4 tail leaves text ending in "::";
            // a plain group leaves it ending in a single ":" that must be
            // dropped before splitting.
            if (!text.endsWith("::")) text = text.dropLast(1)
        }
        val compressed = text.indexOf("::")
        if (compressed >= 0 && text.indexOf("::", compressed + 1) >= 0) return false
        val groups: List<String>
        if (compressed >= 0) {
            val head = text.substring(0, compressed)
            val tail = text.substring(compressed + 2)
            if (head.startsWith(":") || head.endsWith(":")) return false
            if (tail.startsWith(":") || tail.endsWith(":")) return false
            groups = (if (head.isEmpty()) emptyList() else head.split(':')) +
                (if (tail.isEmpty()) emptyList() else tail.split(':'))
            if (groups.size >= groupsNeeded) return false
        } else {
            groups = text.split(':')
            if (groups.size != groupsNeeded) return false
        }
        return groups.all { IPV6_GROUP.matches(it) }
    }

    /**
     * Reject an AllowedIPs entry unless it is a numeric IPv4 or IPv6 address
     * with an optional "/prefix". The Rust bridge (wgbridge-rs's
     * parse_uapi_config) already requires this – allowed_ip is parsed as an
     * IpNetwork, never resolved – so a hostname here would only surface as a
     * native startup failure; rejecting it here instead gives a clear error
     * at config-parse time, before any of it reaches the tunnel.
     */
    private fun requireAllowedIp(entry: String): String {
        val slash = entry.indexOf('/')
        val address = if (slash >= 0) entry.substring(0, slash) else entry
        val prefix = if (slash >= 0) entry.substring(slash + 1) else null

        val isV4 = IPV4_REGEX.matches(address)
        if (!isV4 && !isIpv6Literal(address))
            throw WgConfigException("invalid AllowedIPs entry: $entry")

        if (prefix != null) {
            val prefixLen = prefix.toIntOrNull()
                ?: throw WgConfigException("invalid AllowedIPs prefix: $entry")
            val maxPrefix = if (isV4) 32 else 128
            if (prefixLen !in 0..maxPrefix)
                throw WgConfigException("AllowedIPs prefix out of range: $entry")
        }

        return entry
    }

    internal fun base64ToHex(s: String): String {
        val bytes = java.util.Base64.getDecoder().decode(s)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(Character.forDigit((b.toInt() ushr 4) and 0xF, 16))
            sb.append(Character.forDigit(b.toInt() and 0xF, 16))
        }
        return sb.toString()
    }
}
