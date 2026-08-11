package net.kollnig.missioncontrol.wg.proton

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.crypto.android.srp.GOpenPGPSrpCrypto
import me.proton.core.util.kotlin.DefaultDispatcherProvider
import net.kollnig.missioncontrol.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone

/**
 * Small, dependency-injected Proton authentication client.
 *
 * Proton Core's account graph is deliberately not embedded here. This class
 * owns only the documented auth-v4 exchange and accepts an SRP implementation
 * so the HTTP boundary can be tested without real credentials or crypto.
 */
class ProtonAuthClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val srpProofGenerator: ProtonSrpProofGenerator = Companion.defaultSrpProofGenerator(),
    private val payloadFactory: () -> JSONObject = { ProtonChallengePayload.device() },
    private val appVersion: String = "android-vpn@${BuildConfig.VERSION_NAME}"
) {
    suspend fun login(username: String, password: CharArray): ProtonLoginResult {
        require(username.isNotBlank()) { "Proton username is required" }
        require(password.isNotEmpty()) { "Proton password is required" }

        val info = postJson("auth/v4/info", JSONObject()
            .put("Username", username)
            .put("Intent", "proton"))
        val version = requiredLong(info, "Version")
        val salt = requiredString(info, "Salt")
        val modulus = requiredString(info, "Modulus")
        val serverEphemeral = requiredString(info, "ServerEphemeral")
        val srpSession = requiredString(info, "SRPSession")

        val passwordBytes = password.concatToString().toByteArray(Charsets.UTF_8)
        val proofs = try {
            srpProofGenerator.generate(
                username = username,
                password = passwordBytes,
                version = version,
                salt = salt,
                modulus = modulus,
                serverEphemeral = serverEphemeral
            )
        } finally {
            passwordBytes.fill(0)
            password.fill('\u0000')
        }

        val response = postJson("auth/v4", JSONObject()
            .put("Username", username)
            .put("ClientEphemeral", proofs.clientEphemeral)
            .put("ClientProof", proofs.clientProof)
            .put("SRPSession", srpSession)
            .put("Payload", JSONObject().put(
                "vpn-android-v4-challenge-0", payloadFactory())))

        val session = parseSession(response)
        val serverProof = response.optString("ServerProof", "")
        if (serverProof.isNotEmpty() && !constantTimeEquals(serverProof, proofs.expectedServerProof))
            throw ProtonApiException(200, null, "Proton server proof validation failed")

        val secondFactor = response.optJSONObject("2FA")
        return if (secondFactor == null) {
            ProtonLoginResult.Authenticated(session)
        } else {
            ProtonLoginResult.TwoFactorRequired(
                pendingSession = session,
                methods = secondFactorMethods(secondFactor)
            )
        }
    }

    suspend fun completeTwoFactor(pendingSession: ProtonSession, code: String): ProtonSession {
        require(code.isNotBlank()) { "Proton two-factor code is required" }
        val response = postJson(
            path = "auth/v4/2fa",
            body = JSONObject().put("TwoFactorCode", code),
            session = pendingSession
        )
        val scopes = response.optJSONArray("Scopes").strings()
        return pendingSession.copy(scopes = if (scopes.isEmpty()) pendingSession.scopes else scopes)
    }

    suspend fun refreshSession(session: ProtonSession): ProtonSession {
        val response = postJson(
            path = "auth/v4/refresh",
            body = JSONObject()
                .put("UID", session.uid)
                .put("RefreshToken", session.refreshToken)
                .put("ResponseType", "token")
                .put("GrantType", "refresh_token")
                .put("RedirectURI", "http://protonmail.ch"),
            session = session
        )
        return session.copy(
            accessToken = requiredString(response, "AccessToken"),
            refreshToken = requiredString(response, "RefreshToken"),
            tokenType = requiredString(response, "TokenType"),
            scopes = response.optJSONArray("Scopes").strings().ifEmpty { session.scopes }
        )
    }

    private suspend fun postJson(path: String, body: JSONObject, session: ProtonSession? = null): JSONObject =
        requestJson(
            Request.Builder()
                .url(resolve(path))
                .post(body.toString().toRequestBody(JSON))
                .apply { addHeaders(session) }
                .build()
        )

    private suspend fun requestJson(request: Request): JSONObject = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = try { JSONObject(text) } catch (_: Throwable) { JSONObject() }
            if (!response.isSuccessful)
                throw ProtonApiException(
                    response.code,
                    if (json.has("Code")) json.optInt("Code") else null,
                    json.optString("Message", "Proton request failed (${response.code})")
                )
            if (json.has("Code") && json.optInt("Code") != 1000)
                throw ProtonApiException(
                    response.code,
                    json.optInt("Code"),
                    json.optString("Message", "Proton API rejected the request")
                )
            json
        }
    }

    private fun Request.Builder.addHeaders(session: ProtonSession?) {
        header("x-pm-appversion", appVersion)
        header("x-pm-client", "android-vpn")
        if (session != null) {
            header("Authorization", session.authorizationHeader())
            header("x-pm-uid", session.uid)
        }
    }

    private fun resolve(path: String): HttpUrl =
        requireNotNull(baseUrl.resolve(path)) { "Invalid Proton API path: $path" }

    private fun parseSession(json: JSONObject): ProtonSession = ProtonSession(
        uid = requiredString(json, "UID"),
        userId = requiredString(json, "UserID"),
        accessToken = requiredString(json, "AccessToken"),
        refreshToken = requiredString(json, "RefreshToken"),
        tokenType = requiredString(json, "TokenType"),
        scopes = json.optJSONArray("Scopes").strings()
    )

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        val DEFAULT_BASE_URL: HttpUrl = "https://vpn-api.proton.me/".toHttpUrlCompat()

        private fun defaultSrpProofGenerator() = ProtonSrpProofGenerator { username, password,
                                                                            version, salt,
                                                                            modulus,
                                                                            serverEphemeral ->
            val proofs = GOpenPGPSrpCrypto(DefaultDispatcherProvider()).generateSrpProofs(
                username, password, version, salt, modulus, serverEphemeral)
            ProtonSrpProofs(
                proofs.clientEphemeral,
                proofs.clientProof,
                proofs.expectedServerProof
            )
        }

        private fun secondFactorMethods(json: JSONObject): List<String> {
            val scopes = json.optJSONArray("Scopes").strings()
            if (scopes.isNotEmpty()) return scopes
            val enabled = json.optInt("Enabled", 0)
            return buildList {
                if (enabled and 0b01 != 0) add("totp")
                if (enabled and 0b10 != 0) add("security-key")
            }
        }

        private fun String.toHttpUrlCompat(): HttpUrl =
            this.toHttpUrl()
    }
}

private object ProtonChallengePayload {
    private const val VERSION = "2.0.7"

    fun device(): JSONObject {
        val timezone = TimeZone.getDefault()
        val language = Locale.getDefault().toLanguageTag()
        val region = Locale.getDefault().country
        return JSONObject()
            .put("v", VERSION)
            .put("appLang", language)
            .put("timezone", timezone.id)
            .put("deviceName", Build.MODEL.hashCode().toLong())
            .put("regionCode", region)
            .put("timezoneOffset", timezone.getOffset(System.currentTimeMillis()) / 60_000)
            .put("isJailbreak", false)
            .put("preferredContentSize", "1")
            .put("storageCapacity", 0.0)
            .put("isDarkmodeOn", false)
            .put("keyboards", JSONArray())
    }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    val result = ArrayList<String>(length())
    for (i in 0 until length()) optString(i).takeIf { it.isNotEmpty() }?.let(result::add)
    return result
}

private fun requiredString(json: JSONObject, name: String): String =
    json.optString(name, "").takeIf { it.isNotBlank() }
        ?: throw ProtonApiException(200, null, "Proton response omitted $name")

private fun requiredLong(json: JSONObject, name: String): Long {
    val value = if (json.has(name)) {
        json.optLong(name, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
    } else {
        null
    }
    return value ?: throw ProtonApiException(200, null, "Proton response omitted $name")
}

private fun constantTimeEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
