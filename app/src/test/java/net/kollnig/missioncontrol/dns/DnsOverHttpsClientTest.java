package net.kollnig.missioncontrol.dns;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okio.Buffer;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36)
public class DnsOverHttpsClientTest {
    private static final byte[] QUERY = new byte[] {
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00
    };
    private static final byte[] RESPONSE = new byte[] {
            0x12, 0x34, (byte) 0x81, (byte) 0x80, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
    };

    private MockWebServer server;

    @Before
    public void setUp() throws IOException {
        DnsOverHttpsClient.resetInstance();
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws IOException {
        DnsOverHttpsClient.resetInstance();
        server.close();
    }

    @Test
    public void resolvePostsDnsWireMessageAndReturnsResponse() throws Exception {
        server.enqueue(dnsResponse(200, RESPONSE));

        byte[] result = client().resolve(QUERY);

        assertArrayEquals(RESPONSE, result);
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("POST", request.getMethod());
        assertEquals("application/dns-message", request.getHeaders().get("Accept"));
        assertEquals("application/dns-message", request.getHeaders().get("Content-Type"));
        assertArrayEquals(QUERY, request.getBody().toByteArray());
    }

    @Test
    public void resolveRejectsEmptyQueryWithoutNetworkRequest() throws Exception {
        assertNull(client().resolve(new byte[0]));

        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void resolveDoesNotRetryClientError() {
        server.enqueue(dnsResponse(400, new byte[0]));

        assertNull(client().resolve(QUERY));

        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void resolveRetriesServerErrorsThenGivesUp() {
        server.enqueue(dnsResponse(503, new byte[0]));
        server.enqueue(dnsResponse(503, new byte[0]));
        server.enqueue(dnsResponse(503, new byte[0]));

        assertNull(client().resolve(QUERY));

        assertEquals(3, server.getRequestCount());
    }

    @Test
    public void resolveRetriesInvalidShortDnsResponse() {
        server.enqueue(dnsResponse(200, new byte[] { 1, 2, 3 }));
        server.enqueue(dnsResponse(200, RESPONSE));

        assertArrayEquals(RESPONSE, client().resolve(QUERY));

        assertEquals(2, server.getRequestCount());
    }

    private DnsOverHttpsClient client() {
        return DnsOverHttpsClient.getInstance(server.url("/dns-query").toString());
    }

    private static MockResponse dnsResponse(int status, byte[] body) {
        return new MockResponse.Builder()
                .code(status)
                .addHeader("Content-Type", "application/dns-message")
                .body(new Buffer().write(body))
                .build();
    }
}
