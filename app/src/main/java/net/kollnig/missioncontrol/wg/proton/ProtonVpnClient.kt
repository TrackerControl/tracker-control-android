package net.kollnig.missioncontrol.wg.proton

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import net.kollnig.missioncontrol.BuildConfig

/** VPN-specific certificate/server API and standard WireGuard profile builder. */
class ProtonVpnClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = ProtonAuthClient.DEFAULT_BASE_URL,
    private val deviceName: String = Build.MODEL ?: "Android"
) {
    suspend fun fetchCertificate(
        session: ProtonSession,
        publicKeyPem: String
    ): ProtonCertificate {
        val response = request(
            Request.Builder()
                .url(resolve("vpn/v1/certificate"))
                .post(JSONObject()
                    .put("ClientPublicKey", publicKeyPem)
                    .put("ClientPublicKeyMode", "EC")
                    .put("DeviceName", deviceName)
                    .put("Mode", "session")
                    .put("Features", JSONArray())
                    .toString().toRequestBody(JSON))
                .authenticated(session)
                .build()
        )
        return ProtonCertificate(
            certificate = requiredString(response, "Certificate"),
            expirationTimeMs = response.optLong("ExpirationTime") * 1000L,
            refreshTimeMs = response.optLong("RefreshTime") * 1000L
        )
    }

    suspend fun fetchLogicalServers(session: ProtonSession): List<ProtonLogicalServer> {
        val url = resolve("vpn/v2/logicals").newBuilder()
            .addQueryParameter("WithEntriesForProtocols", "WireGuardUDP")
            .addQueryParameter("WithState", "true")
            .build()
        val response = request(
            Request.Builder().url(url).authenticated(session).get().build()
        )
        val servers = response.optJSONArray("LogicalServers") ?: return emptyList()
        return (0 until servers.length()).mapNotNull { parseLogicalServer(servers.optJSONObject(it)) }
    }

    suspend fun refreshProfile(
        session: ProtonSession,
        keyMaterial: ProtonKeyMaterial,
        preferredServerId: String? = null
    ): ProtonGeneratedProfile {
        val certificate = fetchCertificate(session, keyMaterial.publicKeyPem)
        val servers = fetchLogicalServers(session)
        val endpoint = chooseEndpoint(servers, preferredServerId)
        return ProtonGeneratedProfile(
            config = buildWireGuardConfig(keyMaterial.privateKey, endpoint),
            certificate = certificate,
            endpoint = endpoint,
            keyMaterial = keyMaterial
        )
    }

    fun buildWireGuardConfig(
        privateKey: String,
        endpoint: ProtonWireGuardEndpoint,
        address: String = "10.2.0.2/32",
        dns: String = "10.2.0.1"
    ): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        appendLine("Address = $address")
        appendLine("DNS = $dns")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${endpoint.publicKey}")
        appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        appendLine("Endpoint = ${formatEndpointHost(endpoint.endpointHost)}:${endpoint.endpointPort}")
        appendLine("PersistentKeepalive = 60")
    }

    private fun parseLogicalServer(json: JSONObject?): ProtonLogicalServer? {
        if (json == null) return null
        val domainsJson = json.optJSONArray("Servers") ?: return null
        val domains = (0 until domainsJson.length()).mapNotNull { parseDomain(domainsJson.optJSONObject(it)) }
        return ProtonLogicalServer(
            id = json.optString("ID"),
            name = json.optString("Name"),
            entryCountry = json.optString("EntryCountry"),
            exitCountry = json.optString("ExitCountry"),
            domains = domains
        ).takeIf { it.id.isNotBlank() && it.domains.isNotEmpty() }
    }

    private fun parseDomain(json: JSONObject?): ProtonConnectingDomain? {
        if (json == null) return null
        val protocolEntries = json.optJSONObject("EntryPerProtocol")
        val protocol = protocolEntries?.keys()?.asSequence()
            ?.firstOrNull { it.equals("WireGuardUDP", ignoreCase = true) ||
                it.equals("wireguard", ignoreCase = true) }
            ?.let { protocolEntries.optJSONObject(it) }
        val ports = protocol?.optJSONArray("Ports")?.let { array ->
            (0 until array.length()).mapNotNull { array.optInt(it).takeIf { port -> port > 0 } }
        } ?: emptyList()
        return ProtonConnectingDomain(
            id = json.optString("ID"),
            domain = json.optString("Domain"),
            entryIp = protocol?.optString("IPv4")?.takeIf { it.isNotBlank() }
                ?: json.optString("EntryIP").takeIf { it.isNotBlank() }
                ?: json.optString("Domain").takeIf { it.isNotBlank() },
            publicKeyX25519 = json.optString("X25519PublicKey").takeIf { it.isNotBlank() },
            online = json.optInt("Status", 1) != 0,
            wireGuardPorts = ports
        )
    }

    private fun chooseEndpoint(
        servers: List<ProtonLogicalServer>,
        preferredServerId: String?
    ): ProtonWireGuardEndpoint {
        val candidates = servers.asSequence()
            .flatMap { server -> server.domains.asSequence().map { server to it } }
            .filter { (_, domain) -> domain.online && !domain.entryIp.isNullOrBlank() &&
                !domain.publicKeyX25519.isNullOrBlank() }
            .toList()
        val allCandidates = candidates.toList()
        val (server, domain) = allCandidates.firstOrNull { (server, _) ->
            preferredServerId != null && server.id == preferredServerId
        } ?: allCandidates.firstOrNull()
            ?: throw ProtonApiException(200, null, "Proton returned no online WireGuard server")
        return ProtonWireGuardEndpoint(
            serverId = server.id,
            serverName = server.name,
            publicKey = domain.publicKeyX25519!!,
            endpointHost = domain.entryIp!!,
            endpointPort = domain.wireGuardPorts.firstOrNull() ?: 51820
        )
    }

    private suspend fun request(request: Request): JSONObject = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = try { JSONObject(text) } catch (_: Throwable) { JSONObject() }
            if (!response.isSuccessful)
                throw ProtonApiException(response.code, json.optInt("Code"),
                    json.optString("Message", "Proton VPN request failed (${response.code})"))
            if (json.has("Code") && json.optInt("Code") != 1000)
                throw ProtonApiException(response.code, json.optInt("Code"),
                    json.optString("Message", "Proton VPN API rejected the request"))
            json
        }
    }

    private fun Request.Builder.authenticated(session: ProtonSession): Request.Builder =
        header("Authorization", session.authorizationHeader())
            .header("x-pm-uid", session.uid)
            .header("x-pm-appversion", "android-vpn@${BuildConfig.VERSION_NAME}")
            .header("x-pm-client", "android-vpn")

    private fun resolve(path: String): HttpUrl =
        requireNotNull(baseUrl.resolve(path)) { "Invalid Proton API path: $path" }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

private fun formatEndpointHost(host: String): String =
    if (host.contains(':') && !host.startsWith('[')) "[$host]" else host

private fun requiredString(json: JSONObject, name: String): String =
    json.optString(name, "").takeIf { it.isNotBlank() }
        ?: throw ProtonApiException(200, null, "Proton response omitted $name")
