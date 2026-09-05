package net.kollnig.missioncontrol.dns;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Only covers the query plausibility gate and the SERVFAIL header builder;
 * the rest of DnsProxyServer needs live sockets and a wired VPN context.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36)
public class DnsProxyServerTest {

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
}
