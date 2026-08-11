package net.kollnig.missioncontrol.wg.proton

import kotlinx.coroutines.runBlocking
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
import net.kollnig.missioncontrol.wg.WgConfigParser

@RunWith(RobolectricTestRunner::class)
class ProtonVpnClientTest {
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
    fun refreshProfileFetchesCertificateAndWireGuardServer() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"Certificate":"cert","ExpirationTime":200,"RefreshTime":100}"""
        ).build())
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"LogicalServers":[{"ID":"server-1","Name":"NL-FREE#1","EntryCountry":"NL","ExitCountry":"NL","Servers":[{"ID":"entry-1","Domain":"entry.example","EntryPerProtocol":{"wireguard":{"IPv4":"198.51.100.2","Ports":[51820]}},"X25519PublicKey":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","Status":1}]}]}"""
        ).build())

        val session = ProtonSession("uid", "user", "access", "refresh", "Bearer", listOf("vpn"))
        val keys = ProtonKeyMaterial(
            privateKey = KEY,
            publicKeyPem = "public-pem"
        )
        val profile = ProtonVpnClient(baseUrl = server.url("/")).refreshProfile(session, keys)

        assertEquals("cert", profile.certificate.certificate)
        assertEquals("198.51.100.2", profile.endpoint.endpointHost)
        assertEquals(51820, profile.endpoint.endpointPort)
        val parsed = WgConfigParser.parse(profile.config)
        assertEquals(KEY, parsed.privateKey)
        assertEquals("198.51.100.2:51820", parsed.peers.first().endpoint)
        assertTrue(parsed.peers.first().allowedIPs.contains("0.0.0.0/0"))

        val certificateRequest = server.takeRequest()
        assertEquals("/vpn/v1/certificate", certificateRequest.url.encodedPath)
        assertEquals("Bearer access", certificateRequest.headers["Authorization"])
        assertEquals("public-pem", JSONObject(certificateRequest.body!!.utf8())
            .getString("ClientPublicKey"))
        val logicalRequest = server.takeRequest()
        assertEquals("/vpn/v2/logicals", logicalRequest.url.encodedPath)
        assertEquals("WireGuardUDP", logicalRequest.url.queryParameter("WithEntriesForProtocols"))
    }

    @Test
    fun profileRefresherRenewsSessionAfterUnauthorizedResponse() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body(
            """{"Code":1003,"Message":"Expired session"}"""
        ).build())
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"AccessToken":"new-access","RefreshToken":"new-refresh","TokenType":"Bearer","UID":"uid","Scopes":["vpn"]}"""
        ).build())
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"Certificate":"cert","ExpirationTime":200,"RefreshTime":100}"""
        ).build())
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"Code":1000,"LogicalServers":[{"ID":"server-1","Name":"NL-FREE#1","EntryCountry":"NL","ExitCountry":"NL","Servers":[{"ID":"entry-1","Domain":"entry.example","EntryIP":"198.51.100.2","X25519PublicKey":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","Status":1}]}]}"""
        ).build())

        val initial = ProtonSession("uid", "user", "old-access", "old-refresh", "Bearer", listOf("vpn"))
        val refresher = ProtonProfileRefresher(
            ProtonAuthClient(baseUrl = server.url("/")),
            ProtonVpnClient(baseUrl = server.url("/")),
            initial,
            ProtonKeyMaterial(KEY, "public-pem")
        )
        val profile = refresher.refresh()

        assertEquals("new-access", refresher.currentSession().accessToken)
        assertEquals("198.51.100.2:51820", WgConfigParser.parse(profile.config)
            .peers.first().endpoint)
        assertEquals("/vpn/v1/certificate", server.takeRequest().url.encodedPath)
        assertEquals("/auth/v4/refresh", server.takeRequest().url.encodedPath)
        assertEquals("/vpn/v1/certificate", server.takeRequest().url.encodedPath)
        assertEquals("/vpn/v2/logicals", server.takeRequest().url.encodedPath)
    }

    companion object {
        private const val KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
