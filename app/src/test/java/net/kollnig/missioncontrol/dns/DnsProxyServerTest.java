package net.kollnig.missioncontrol.dns;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okio.Buffer;

/**
 * Covers the query gates and SERVFAIL builder, plus the endpoint-scoped
 * circuit breaker through the local UDP and TCP listeners.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36)
public class DnsProxyServerTest {
    private static final byte[] QUERY = new byte[] {
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00
    };
    private static final byte[] RESPONSE = new byte[] {
            0x12, 0x34, (byte) 0x81, (byte) 0x80, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
    };

    private SharedPreferences prefs;
    private DnsProxyServer proxy;

    @Before
    public void setUp() throws Exception {
        resetProxySingleton();
        prefs = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication());
        proxy = DnsProxyServer.getInstance(RuntimeEnvironment.getApplication());
        DnsOverHttpsClient.resetInstance();
        prefs.edit()
                .putBoolean("doh_enabled", false)
                .putBoolean("doh_dns_fallback", false)
                .remove("doh_endpoint")
                .commit();
    }

    @After
    public void tearDown() {
        proxy.stop();
        DnsOverHttpsClient.resetInstance();
        prefs.edit()
                .putBoolean("doh_enabled", false)
                .putBoolean("doh_dns_fallback", false)
                .remove("doh_endpoint")
                .commit();
    }

    @Test
    public void rejectsNullAndHeaderlessQueries() {
        assertFalse(DnsProxyServer.isPlausibleDnsQuery(null));
        assertFalse(DnsProxyServer.isPlausibleDnsQuery(new byte[0]));
        assertFalse(DnsProxyServer.isPlausibleDnsQuery(new byte[11]));
    }

    @Test
    public void acceptsBareHeaderAndFullQuery() {
        assertTrue(DnsProxyServer.isPlausibleDnsQuery(new byte[12]));

        byte[] query = new byte[] {
                0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00
        };
        assertTrue(DnsProxyServer.isPlausibleDnsQuery(query));
    }

    @Test
    public void buildServFailResponseReturnsNullForUnbuildableQueries() {
        // Undersized/null queries can't safely have a transaction ID echoed
        // back, so callers must treat this as "nothing to send" rather than
        // synthesising a response -- this is the behaviour that replaces the
        // old always-no-op sendServFailResponse call for such queries.
        assertNull(DnsProxyServer.buildServFailResponse(null));
        assertNull(DnsProxyServer.buildServFailResponse(new byte[0]));
        assertNull(DnsProxyServer.buildServFailResponse(new byte[11]));
    }

    @Test
    public void buildServFailResponseSetsQrBitAndServFailRcode() {
        byte[] query = new byte[] {
                0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        };

        byte[] response = DnsProxyServer.buildServFailResponse(query);

        assertTrue(response != null);
        // Same length, transaction ID untouched.
        assertArrayEquals(new byte[] { 0x12, 0x34 },
                new byte[] { response[0], response[1] });
        // QR bit (0x80) set, other flag bits preserved.
        assertTrue((response[2] & 0x80) != 0);
        // RCODE (low nibble of byte 3) is SERVFAIL (2).
        assertTrue((response[3] & 0x0F) == 0x02);
        assertTrue(response.length == query.length);
    }

    @Test
    public void requestExecutorRejectsWorkBeyondWorkerAndQueueBound() throws Exception {
        ThreadPoolExecutor executor = DnsProxyServer.createRequestExecutor(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            assertTrue(DnsProxyServer.tryExecute(executor, () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));

            assertTrue(DnsProxyServer.tryExecute(executor, () -> { }));
            assertEquals(1, executor.getQueue().size());
            assertFalse(DnsProxyServer.tryExecute(executor, () -> { }));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void stoppedRequestExecutorRejectsNewWork() {
        ThreadPoolExecutor executor = DnsProxyServer.createRequestExecutor(1, 1);
        executor.shutdownNow();
        assertFalse(DnsProxyServer.tryExecute(executor, () -> { }));
    }

    @Test
    public void udpCircuitBreakerSkipsFurtherQueriesForSameEndpoint() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            enqueueFailures(server, 12);
            startProxy(server.url("/dns-query").toString());

            for (int i = 0; i < 4; i++)
                assertServFail(queryUdp());

            assertEquals(12, server.getRequestCount());
            assertServFail(queryUdp());
            assertEquals(12, server.getRequestCount());
        } finally {
            proxy.stop();
            server.close();
        }
    }

    @Test
    public void tcpCircuitBreakerSkipsFurtherQueriesForSameEndpoint() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            enqueueFailures(server, 12);
            startProxy(server.url("/dns-query").toString());

            for (int i = 0; i < 4; i++)
                assertServFail(queryTcp());

            assertEquals(12, server.getRequestCount());
            assertServFail(queryTcp());
            assertEquals(12, server.getRequestCount());
        } finally {
            proxy.stop();
            server.close();
        }
    }

    @Test
    public void changingEndpointGetsImmediateUdpAttempt() throws Exception {
        MockWebServer oldServer = new MockWebServer();
        MockWebServer newServer = new MockWebServer();
        oldServer.start();
        newServer.start();
        try {
            startProxy(oldServer.url("/dns-query").toString());
            for (int i = 0; i < 10; i++) {
                oldServer.enqueue(dnsResponse(400, new byte[0]));
                assertServFail(queryUdp());
            }

            assertServFail(queryUdp());
            assertEquals(10, oldServer.getRequestCount());

            String newEndpoint = newServer.url("/dns-query").toString();
            prefs.edit().putString("doh_endpoint", newEndpoint).commit();
            newServer.enqueue(dnsResponse(200, RESPONSE));

            assertArrayEquals(RESPONSE, queryUdp());
            assertEquals(1, newServer.getRequestCount());
        } finally {
            proxy.stop();
            oldServer.close();
            newServer.close();
        }
    }

    @Test
    public void changingEndpointGetsImmediateTcpAttempt() throws Exception {
        MockWebServer oldServer = new MockWebServer();
        MockWebServer newServer = new MockWebServer();
        oldServer.start();
        newServer.start();
        try {
            startProxy(oldServer.url("/dns-query").toString());
            for (int i = 0; i < 10; i++) {
                oldServer.enqueue(dnsResponse(400, new byte[0]));
                assertServFail(queryTcp());
            }

            assertServFail(queryTcp());
            assertEquals(10, oldServer.getRequestCount());

            String newEndpoint = newServer.url("/dns-query").toString();
            prefs.edit().putString("doh_endpoint", newEndpoint).commit();
            newServer.enqueue(dnsResponse(200, RESPONSE));

            assertArrayEquals(RESPONSE, queryTcp());
            assertEquals(1, newServer.getRequestCount());
        } finally {
            proxy.stop();
            oldServer.close();
            newServer.close();
        }
    }

    @Test
    public void lateOldStateResultsCannotChangeNewEndpointState() {
        startProxy("https://old.example/dns-query");
        DnsProxyServer.CircuitState oldState = proxy.getCurrentCircuitState();
        assertSame(oldState, proxy.getCurrentCircuitState());

        prefs.edit().putString("doh_endpoint", "https://new.example/dns-query").commit();
        DnsProxyServer.CircuitState newState = proxy.getCurrentCircuitState();
        assertNotSame(oldState, newState);
        assertEquals("https://new.example/dns-query", newState.endpoint);

        newState.failures.set(7);
        newState.openUntil = 123_456;
        oldState.recordSuccess();
        oldState.recordFailure(100);
        oldState.trip(1_000);

        assertEquals(7, newState.failures.get());
        assertEquals(123_456, newState.openUntil);
        assertTrue(newState.isOpen(123_455));
    }

    @Test
    public void freshStartResetsCircuitState() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            String endpoint = server.url("/dns-query").toString();
            startProxy(endpoint);
            proxy.getCurrentCircuitState().trip(System.currentTimeMillis());
            proxy.start(); // Starting an already running proxy must preserve its cooldown.

            assertServFail(queryUdp());
            assertEquals(0, server.getRequestCount());

            proxy.stop();
            startProxy(endpoint);
            server.enqueue(dnsResponse(200, RESPONSE));

            assertArrayEquals(RESPONSE, queryUdp());
            assertEquals(1, server.getRequestCount());
        } finally {
            proxy.stop();
            server.close();
        }
    }

    private void startProxy(String endpoint) {
        prefs.edit()
                .putBoolean("doh_enabled", true)
                .putBoolean("doh_dns_fallback", false)
                .putString("doh_endpoint", endpoint)
                .commit();
        proxy.start();
        assertTrue(proxy.isRunning());
    }

    private byte[] queryUdp() throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(5000);
            DatagramPacket request = new DatagramPacket(
                    QUERY, QUERY.length, InetAddress.getByName(DnsProxyServer.DNS_PROXY_ADDRESS),
                    DnsProxyServer.DNS_PROXY_PORT);
            socket.send(request);

            byte[] buffer = new byte[65535];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            return Arrays.copyOf(response.getData(), response.getLength());
        }
    }

    private byte[] queryTcp() throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(
                    InetAddress.getByName(DnsProxyServer.DNS_PROXY_ADDRESS),
                    DnsProxyServer.DNS_PROXY_PORT), 5000);
            socket.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeShort(QUERY.length);
            out.write(QUERY);
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] response = new byte[in.readUnsignedShort()];
            in.readFully(response);
            return response;
        }
    }

    private static void assertServFail(byte[] response) {
        assertTrue(response.length >= 4);
        assertEquals(QUERY[0], response[0]);
        assertEquals(QUERY[1], response[1]);
        assertTrue((response[2] & 0x80) != 0);
        assertEquals(0x02, response[3] & 0x0F);
    }

    private static void enqueueFailures(MockWebServer server, int count) {
        for (int i = 0; i < count; i++)
            server.enqueue(dnsResponse(503, new byte[0]));
    }

    private static MockResponse dnsResponse(int status, byte[] body) {
        return new MockResponse.Builder()
                .code(status)
                .addHeader("Content-Type", "application/dns-message")
                .body(new Buffer().write(body))
                .build();
    }

    private static void resetProxySingleton() throws Exception {
        Field field = DnsProxyServer.class.getDeclaredField("instance");
        field.setAccessible(true);
        DnsProxyServer previous = (DnsProxyServer) field.get(null);
        if (previous != null)
            previous.stop();
        field.set(null, null);
    }
}
