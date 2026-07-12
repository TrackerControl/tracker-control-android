package net.kollnig.missioncontrol.dns;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
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
    public void resolveSendsCacheFriendlyGetAndReturnsResponse() throws Exception {
        server.enqueue(dnsResponse(200, RESPONSE));

        byte[] result = client().resolve(QUERY);

        // A fresh response (no Age/Cache-Control) has its transaction ID restored
        // by a direct byte patch; QUERY already carries 0x1234, so it is unchanged.
        assertArrayEquals(RESPONSE, result);
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("GET", request.getMethod());
        assertEquals("application/dns-message", request.getHeaders().get("Accept"));
        assertEquals(
                DnsOverHttpsClient.buildRequestUrl(server.url("/dns-query").toString(), QUERY)
                        .queryParameter("dns"),
                request.getUrl().queryParameter("dns"));
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

    @Test
    public void normalizeTransactionIdUsesZeroWithoutMutatingQuery() {
        byte[] query = new byte[]{0x12, 0x34, 0x01, 0x00};

        byte[] normalized = DnsOverHttpsClient.normalizeTransactionId(query);

        assertNotSame(query, normalized);
        assertArrayEquals(new byte[]{0x00, 0x00, 0x01, 0x00}, normalized);
        assertArrayEquals(new byte[]{0x12, 0x34, 0x01, 0x00}, query);
    }

    @Test
    public void transactionIdDoesNotChangeHttpCacheKey() {
        byte[] first = new byte[]{0x12, 0x34, 0x01, 0x00};
        byte[] second = new byte[]{0x56, 0x78, 0x01, 0x00};

        assertEquals(
                DnsOverHttpsClient.buildRequestUrl("https://dns.example/dns-query", first),
                DnsOverHttpsClient.buildRequestUrl("https://dns.example/dns-query", second));
    }

    @Test
    public void getQueryOmitsBase64Padding() {
        byte[] query = new byte[]{0x12, 0x34, 0x01, 0x00};

        String encoded = DnsOverHttpsClient
                .buildRequestUrl("https://dns.example/dns-query", query)
                .queryParameter("dns");

        assertEquals("AAABAA", encoded);
    }

    @Test
    public void dnsMessageContentsRemainPartOfHttpCacheKey() {
        byte[] recursive = new byte[]{0x12, 0x34, 0x01, 0x00};
        byte[] nonRecursive = new byte[]{0x12, 0x34, 0x00, 0x00};

        assertNotEquals(
                DnsOverHttpsClient.buildRequestUrl("https://dns.example/dns-query", recursive),
                DnsOverHttpsClient.buildRequestUrl("https://dns.example/dns-query", nonRecursive));
    }

    @Test
    public void cachedResponseRestoresIdAndSubtractsHttpAge() throws Exception {
        Message response = new Message(0);
        response.addRecord(new ARecord(Name.fromString("example.com."),
                DClass.IN, 300, InetAddress.getByName("192.0.2.1")), Section.ANSWER);
        byte[] query = new byte[]{0x12, 0x34};

        Message aged = new Message(DnsOverHttpsClient
                .ageDnsResponse(response.toWire(), 100, query));

        assertEquals(0x1234, aged.getHeader().getID());
        Record answer = aged.getSection(Section.ANSWER).get(0);
        assertEquals(200, answer.getTTL());
    }

    @Test
    public void cachedResponseNeverUnderflowsTtl() throws Exception {
        Message response = new Message(0);
        response.addRecord(new ARecord(Name.fromString("example.com."),
                DClass.IN, 5, InetAddress.getByName("192.0.2.1")), Section.ANSWER);

        Message aged = new Message(DnsOverHttpsClient
                .ageDnsResponse(response.toWire(), 100, new byte[]{0x00, 0x01}));

        assertEquals(0, aged.getSection(Section.ANSWER).get(0).getTTL());
    }

    @Test
    public void restoreTransactionIdPatchesIdWithoutTouchingRest() {
        byte[] response = new byte[]{0x00, 0x00, 0x01, 0x02, 0x03};
        byte[] query = new byte[]{0x12, 0x34, (byte) 0xFF};

        DnsOverHttpsClient.restoreTransactionId(response, query);

        assertArrayEquals(new byte[]{0x12, 0x34, 0x01, 0x02, 0x03}, response);
    }

    @Test
    public void freshResponseRestoresIdAndKeepsTtlUntouched() throws Exception {
        Message response = new Message(0);
        response.addRecord(new ARecord(Name.fromString("example.com."),
                DClass.IN, 300, InetAddress.getByName("192.0.2.1")), Section.ANSWER);
        byte[] wire = response.toWire();
        byte[] query = new byte[]{0x12, 0x34};

        // A fresh response (age 0) is finished by a direct byte patch, so the
        // wire message must be identical apart from the two transaction-ID bytes.
        DnsOverHttpsClient.restoreTransactionId(wire, query);

        Message restored = new Message(wire);
        assertEquals(0x1234, restored.getHeader().getID());
        assertEquals(300, restored.getSection(Section.ANSWER).get(0).getTTL());
    }

    @Test
    public void smallQueryUsesCacheFriendlyGet() {
        byte[] query = new byte[12];

        assertEquals("GET", DnsOverHttpsClient
                .buildRequest("https://dns.example/dns-query", query).method());
    }

    @Test
    public void oversizedGetFallsBackToPost() {
        byte[] query = new byte[4096];
        Arrays.fill(query, (byte) 1);

        assertEquals("POST", DnsOverHttpsClient
                .buildRequest("https://dns.example/dns-query", query).method());
    }

    private DnsOverHttpsClient client() {
        return DnsOverHttpsClient.getInstance(
                RuntimeEnvironment.getApplication(), server.url("/dns-query").toString());
    }

    private static MockResponse dnsResponse(int status, byte[] body) {
        return new MockResponse.Builder()
                .code(status)
                .addHeader("Content-Type", "application/dns-message")
                .body(new Buffer().write(body))
                .build();
    }
}
