package net.kollnig.missioncontrol.wg.proton

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProtonAuthClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun loginUsesProtonSrpExchangeAndValidatesProof() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"Version":4,"Salt":"salt","Modulus":"modulus","ServerEphemeral":"ephemeral","SRPSession":"srp"}"""
        ).build())
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"AccessToken":"access","RefreshToken":"refresh","TokenType":"Bearer","UID":"uid","UserID":"user","Scopes":["vpn"],"ServerProof":"expected"}"""
        ).build())

        var receivedPassword = byteArrayOf()
        val client = ProtonAuthClient(
            baseUrl = server.url("/"),
            srpProofGenerator = ProtonSrpProofGenerator { _, password, version, salt, modulus, ephemeral ->
                receivedPassword = password.copyOf()
                assertEquals(4L, version)
                assertEquals("salt", salt)
                assertEquals("modulus", modulus)
                assertEquals("ephemeral", ephemeral)
                ProtonSrpProofs("client-ephemeral", "client-proof", "expected")
            },
            payloadFactory = { JSONObject().put("v", "test") },
            appVersion = "android-vpn@test"
        )

        val result = client.login("alice", "secret".toCharArray())
        assertTrue(result is ProtonLoginResult.Authenticated)
        val session = (result as ProtonLoginResult.Authenticated).session
        assertEquals("uid", session.uid)
        assertEquals("Bearer access", session.authorizationHeader())
        assertEquals("secret", String(receivedPassword, Charsets.UTF_8))

        val info = server.takeRequest()
        assertEquals("/auth/v4/info", info.url.encodedPath)
        assertTrue(info.body!!.utf8().contains("\"Username\":\"alice\""))
        assertEquals("android-vpn@test", info.headers["x-pm-appversion"])

        val login = server.takeRequest()
        assertEquals("/auth/v4", login.url.encodedPath)
        val body = JSONObject(login.body!!.utf8())
        assertEquals("client-proof", body.getString("ClientProof"))
        assertEquals("test", body.getJSONObject("Payload")
            .getJSONObject("vpn-android-v4-challenge-0").getString("v"))
    }

    @Test
    fun loginRejectsUnexpectedServerProof() {
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"Version":4,"Salt":"salt","Modulus":"modulus","ServerEphemeral":"ephemeral","SRPSession":"srp"}"""
        ).build())
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"AccessToken":"access","RefreshToken":"refresh","TokenType":"Bearer","UID":"uid","UserID":"user","Scopes":[],"ServerProof":"wrong"}"""
        ).build())

        assertThrows(ProtonApiException::class.java) {
            runBlocking {
                ProtonAuthClient(
                    baseUrl = server.url("/"),
                    srpProofGenerator = ProtonSrpProofGenerator { _, _, _, _, _, _ ->
                        ProtonSrpProofs("e", "p", "expected")
                    }
                ).login("alice", "secret".toCharArray())
            }
        }
    }

    @Test
    fun twoFactorAndRefreshUseAuthenticatedSession() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"Version":4,"Salt":"salt","Modulus":"modulus","ServerEphemeral":"ephemeral","SRPSession":"srp"}"""
        ).build())
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"AccessToken":"access","RefreshToken":"refresh","TokenType":"Bearer","UID":"uid","UserID":"user","Scopes":[],"ServerProof":"expected","2FA":{"Scopes":["totp"]}}"""
        ).build())
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"Scope":"totp","Scopes":["vpn"]}"""
        ).build())

        val client = ProtonAuthClient(
            baseUrl = server.url("/") ,
            srpProofGenerator = ProtonSrpProofGenerator { _, _, _, _, _, _ ->
                ProtonSrpProofs("e", "p", "expected")
            }
        )
        val pending = client.login("alice", "secret".toCharArray()) as ProtonLoginResult.TwoFactorRequired
        val authenticated = client.completeTwoFactor(pending.pendingSession, "123456")
        assertEquals(listOf("vpn"), authenticated.scopes)

        val request = server.takeRequest()
        server.takeRequest() // login
        val secondFactor = server.takeRequest()
        assertEquals("/auth/v4/2fa", secondFactor.url.encodedPath)
        assertEquals("Bearer access", secondFactor.headers["Authorization"])
        assertEquals("123456", JSONObject(secondFactor.body!!.utf8()).getString("TwoFactorCode"))
        assertEquals("/auth/v4/info", request.url.encodedPath)
    }
}
