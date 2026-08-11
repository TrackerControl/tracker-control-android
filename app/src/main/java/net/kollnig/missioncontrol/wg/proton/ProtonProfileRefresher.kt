package net.kollnig.missioncontrol.wg.proton

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Refreshes a Proton WireGuard profile as one operation.
 *
 * The mutex prevents simultaneous connectivity-failure callbacks from
 * rotating the client certificate or replacing the profile twice. A 401
 * refreshes the Proton session once and retries the VPN request; other API
 * errors are surfaced to the caller so WgEgress can retain its fail-closed
 * behavior.
 */
class ProtonProfileRefresher(
    private val authClient: ProtonAuthClient,
    private val vpnClient: ProtonVpnClient,
    initialSession: ProtonSession,
    private val keyMaterial: ProtonKeyMaterial
) {
    private val mutex = Mutex()
    @Volatile private var session: ProtonSession = initialSession

    fun currentSession(): ProtonSession = session

    suspend fun refresh(preferredServerId: String? = null): ProtonGeneratedProfile = mutex.withLock {
        try {
            vpnClient.refreshProfile(session, keyMaterial, preferredServerId)
        } catch (error: ProtonApiException) {
            if (error.statusCode != 401) throw error
            session = authClient.refreshSession(session)
            vpnClient.refreshProfile(session, keyMaterial, preferredServerId)
        }
    }
}
