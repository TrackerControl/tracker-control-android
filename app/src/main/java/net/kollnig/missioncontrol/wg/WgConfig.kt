package net.kollnig.missioncontrol.wg

import net.kollnig.missioncontrol.wg.WgConfigParser.base64ToHex

/**
 * Minimal parser for the WireGuard `.conf` format (a subset of the
 * `wg-quick` syntax). We deliberately do not pull in
 * `com.wireguard.android:tunnel` to avoid duplicating its bundled
 * libwg-go.so with the one in `wgbridge.aar`.
 *
 * Returned [WgConfig] also exposes a [toUapi] string in the format
 * wireguard-go's IpcSet API expects — that's what `WgEgress` hands
 * to the Go bridge at startup.
 */
data class WgConfig(
    val privateKey: String,
    val address: List<String>,   // CIDR strings, e.g. "10.0.0.2/32"
    val dns: List<String>,       // resolver IPs (informational; not yet honored)
    val mtu: Int?,               // optional override
    val peers: List<WgPeer>
) {
    fun toUapi(): String {
        val sb = StringBuilder()
        sb.append("private_key=").append(base64ToHex(privateKey)).append('\n')
        for (peer in peers) {
            sb.append("public_key=").append(base64ToHex(peer.publicKey)).append('\n')
            peer.presharedKey?.let {
                sb.append("preshared_key=").append(base64ToHex(it)).append('\n')
            }
            peer.endpoint?.let { sb.append("endpoint=").append(it).append('\n') }
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
    val endpoint: String?         // host:port (host may need DNS resolution)
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
        val peers = mutableListOf<WgPeer>()

        fun flushPeer() {
            if (peerPub == null) return
            peers.add(
                WgPeer(
                    publicKey = peerPub!!,
                    presharedKey = peerPsk,
                    allowedIPs = peerAllowed.toList(),
                    endpoint = peerEndpoint
                )
            )
            peerPub = null
            peerPsk = null
            peerAllowed.clear()
            peerEndpoint = null
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
                    "allowedips" -> peerAllowed += value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    "endpoint" -> peerEndpoint = value
                    "persistentkeepalive" -> {
                        // Intentionally ignored: this app's WG egress is outbound-only,
                        // so NAT mappings are refreshed by real traffic. Keepalive would
                        // wake the cellular radio on every interval for no benefit.
                    }
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
            android.util.Base64.decode(s, android.util.Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw WgConfigException("invalid base64 key")
        }
        if (bytes.size != 32) throw WgConfigException("key must decode to 32 bytes (got ${bytes.size})")
        return s
    }

    internal fun base64ToHex(s: String): String {
        val bytes = android.util.Base64.decode(s, android.util.Base64.DEFAULT)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(Character.forDigit((b.toInt() ushr 4) and 0xF, 16))
            sb.append(Character.forDigit(b.toInt() and 0xF, 16))
        }
        return sb.toString()
    }
}
