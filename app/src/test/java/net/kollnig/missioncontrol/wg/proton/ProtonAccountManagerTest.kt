package net.kollnig.missioncontrol.wg.proton

import android.content.Context
import androidx.preference.PreferenceManager
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProtonAccountManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = RuntimeEnvironment.getApplication()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun loginPersistsSessionWithoutPassword() {
        enqueueLogin()
        val manager = manager()

        manager.login("alice", "secret".toCharArray())

        assertTrue(manager.hasSession())
        assertEquals("alice", manager.username())
        val stored = PreferenceManager.getDefaultSharedPreferences(context).all.toString()
        assertTrue(!stored.contains("secret"))
    }

    @Test
    fun countriesAndGeneratedProfileUsePersistedAccountAndKeys() {
        enqueueLogin()
        val manager = manager()
        manager.login("alice", "secret".toCharArray())
        enqueueServers()

        val countries = manager.fetchCountries()
        assertEquals(listOf("NL"), countries.map { it.code })

        enqueueCertificateAndServers()
        val profile = manager.generateProfile(countries.single().serverId)
        assertTrue(profile.config.contains("PrivateKey = $KEY"))
        assertTrue(profile.config.contains("Endpoint = 198.51.100.2:51820"))
        server.takeRequest()
        server.takeRequest()
        server.takeRequest()
        val certificateRequest = server.takeRequest()
        assertEquals("/vpn/v1/certificate", certificateRequest.url.encodedPath)
    }

    private fun manager(): ProtonAccountManager {
        val auth = ProtonAuthClient(
            baseUrl = server.url("/"),
            srpProofGenerator = ProtonSrpProofGenerator { _, _, _, _, _, _ ->
                ProtonSrpProofs("ephemeral", "proof", "expected")
            },
            payloadFactory = { JSONObject().put("v", "test") }
        )
        return ProtonAccountManager(
            context,
            auth,
            ProtonVpnClient(baseUrl = server.url("/")),
            ProtonKeyFactory { ProtonKeyMaterial(KEY, PUBLIC_KEY_PEM) }
        )
    }

    private fun enqueueLogin() {
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"Version":4,"Salt":"salt","Modulus":"modulus","ServerEphemeral":"ephemeral","SRPSession":"srp"}"""
        ).build())
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"AccessToken":"access","RefreshToken":"refresh","TokenType":"Bearer","UID":"uid","UserID":"user","Scopes":["vpn"],"ServerProof":"expected"}"""
        ).build())
    }

    private fun enqueueCertificateAndServers() {
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"Certificate":"cert","ExpirationTime":200,"RefreshTime":100}"""
        ).build())
        enqueueServers()
    }

    private fun enqueueServers() {
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"LogicalServers":[{"ID":"server-1","Name":"NL-FREE#1","EntryCountry":"NL","ExitCountry":"NL","Servers":[{"ID":"entry-1","EntryIP":"198.51.100.2","X25519PublicKey":"$KEY","Status":1}]}]}"""
        ).build())
    }

    companion object {
        private const val KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        private const val PUBLIC_KEY_PEM =
            "-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n-----END PUBLIC KEY-----"
    }
}
