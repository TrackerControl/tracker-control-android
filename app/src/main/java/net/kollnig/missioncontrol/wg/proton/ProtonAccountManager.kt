package net.kollnig.missioncontrol.wg.proton

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.runBlocking
import net.kollnig.missioncontrol.wgbridge.Wgbridge
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Bridges the Proton API prototype to the Android profile UI. Passwords are never persisted. */
class ProtonAccountManager @JvmOverloads constructor(
    context: Context,
    private val authClient: ProtonAuthClient = ProtonAuthClient(),
    private val vpnClient: ProtonVpnClient = ProtonVpnClient(),
    private val keyFactory: ProtonKeyFactory = NativeProtonKeyFactory,
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
) {
    data class Country(val serverId: String, val code: String, val name: String)

    fun login(username: String, password: CharArray): ProtonLoginResult = runBlocking {
        authClient.login(username.trim(), password)
    }.also { result ->
        if (result is ProtonLoginResult.Authenticated)
            saveAuthenticated(username, result.session)
    }

    fun completeTwoFactor(username: String, pending: ProtonSession, code: String): ProtonSession =
        runBlocking { authClient.completeTwoFactor(pending, code) }
            .also { saveAuthenticated(username, it) }

    fun fetchCountries(): List<Country> {
        val session = requireSession()
        return runBlocking { vpnClient.fetchLogicalServers(session) }
            .asSequence()
            .filter { server -> server.exitCountry.isNotBlank() }
            .groupBy { server -> server.exitCountry.uppercase(Locale.ROOT) }
            .map { (code, servers) ->
                val server = servers.first()
                val countryName = Locale.Builder().setRegion(code).build().displayCountry
                Country(server.id, code, countryName.ifBlank { code })
            }
            .sortedBy { it.name }
    }

    fun generateProfile(preferredServerId: String?): ProtonGeneratedProfile {
        val session = requireSession()
        val keys = loadOrCreateKeys()
        val refresher = ProtonProfileRefresher(authClient, vpnClient, session, keys)
        return runBlocking { refresher.refresh(preferredServerId) }.also {
            saveSession(refresher.currentSession())
        }
    }

    fun hasSession(): Boolean = loadSession() != null

    fun username(): String = prefs.getString(PREF_USERNAME, "").orEmpty()

    fun clear() {
        prefs.edit()
            .remove(PREF_USERNAME)
            .remove(PREF_SESSION)
            .remove(PREF_PRIVATE_KEY)
            .remove(PREF_PUBLIC_KEY_PEM)
            .apply()
    }

    private fun saveAuthenticated(username: String, session: ProtonSession) {
        prefs.edit().putString(PREF_USERNAME, username.trim()).apply()
        saveSession(session)
    }

    private fun saveSession(session: ProtonSession) {
        prefs.edit().putString(PREF_SESSION, JSONObject()
            .put("uid", session.uid)
            .put("userId", session.userId)
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("tokenType", session.tokenType)
            .put("scopes", JSONArray(session.scopes))
            .toString()).apply()
    }

    private fun requireSession(): ProtonSession = loadSession()
        ?: throw IllegalStateException("Sign in to Proton VPN first")

    private fun loadSession(): ProtonSession? {
        return try {
            val raw = prefs.getString(PREF_SESSION, "").orEmpty()
            if (raw.isBlank()) return null
            val json = JSONObject(raw)
            ProtonSession(
                uid = json.getString("uid"),
                userId = json.getString("userId"),
                accessToken = json.getString("accessToken"),
                refreshToken = json.getString("refreshToken"),
                tokenType = json.getString("tokenType"),
                scopes = json.optJSONArray("scopes").strings()
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadOrCreateKeys(): ProtonKeyMaterial {
        val privateKey = prefs.getString(PREF_PRIVATE_KEY, "").orEmpty()
        val publicPem = prefs.getString(PREF_PUBLIC_KEY_PEM, "").orEmpty()
        if (privateKey.isNotBlank() && publicPem.isNotBlank())
            return ProtonKeyMaterial(privateKey, publicPem)

        val generated = keyFactory.generate()
        prefs.edit()
            .putString(PREF_PRIVATE_KEY, generated.privateKey)
            .putString(PREF_PUBLIC_KEY_PEM, generated.publicKeyPem)
            .apply()
        return generated
    }

    companion object {
        const val PREF_USERNAME = "proton_username"
        const val PREF_SESSION = "proton_session"
        const val PREF_PRIVATE_KEY = "proton_private_key"
        const val PREF_PUBLIC_KEY_PEM = "proton_public_key_pem"

    }
}

fun interface ProtonKeyFactory {
    fun generate(): ProtonKeyMaterial
}

private object NativeProtonKeyFactory : ProtonKeyFactory {
    override fun generate(): ProtonKeyMaterial = Wgbridge.generateProtonKeyPair().let {
        ProtonKeyMaterial(it.privateKey, it.publicKeyPem)
    }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
}
