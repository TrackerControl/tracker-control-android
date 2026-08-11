package net.kollnig.missioncontrol.wg.proton

/** The minimum session state needed by the Proton VPN API. */
data class ProtonSession(
    val uid: String,
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val scopes: List<String>
) {
    fun authorizationHeader(): String = "$tokenType $accessToken"
}

data class ProtonSrpProofs(
    val clientEphemeral: String,
    val clientProof: String,
    val expectedServerProof: String
)

fun interface ProtonSrpProofGenerator {
    suspend fun generate(
        username: String,
        password: ByteArray,
        version: Long,
        salt: String,
        modulus: String,
        serverEphemeral: String
    ): ProtonSrpProofs
}

sealed class ProtonLoginResult {
    data class Authenticated(val session: ProtonSession) : ProtonLoginResult()

    /** Login succeeded at the SRP layer but Proton requires a second factor. */
    data class TwoFactorRequired(
        val pendingSession: ProtonSession,
        val methods: List<String>
    ) : ProtonLoginResult()
}

data class ProtonCertificate(
    val certificate: String,
    val expirationTimeMs: Long,
    val refreshTimeMs: Long
)

data class ProtonKeyMaterial(
    val privateKey: String,
    val publicKeyPem: String
)

data class ProtonConnectingDomain(
    val id: String,
    val domain: String,
    val entryIp: String?,
    val publicKeyX25519: String?,
    val online: Boolean,
    val wireGuardPorts: List<Int>
)

data class ProtonLogicalServer(
    val id: String,
    val name: String,
    val entryCountry: String,
    val exitCountry: String,
    val domains: List<ProtonConnectingDomain>
)

data class ProtonWireGuardEndpoint(
    val serverId: String,
    val serverName: String,
    val publicKey: String,
    val endpointHost: String,
    val endpointPort: Int
)

data class ProtonGeneratedProfile(
    val config: String,
    val certificate: ProtonCertificate,
    val endpoint: ProtonWireGuardEndpoint,
    val keyMaterial: ProtonKeyMaterial
)

class ProtonApiException(
    val statusCode: Int,
    val apiCode: Int?,
    override val message: String
) : java.io.IOException(message)
