package net.kollnig.missioncontrol.dns;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Only covers the query plausibility gate; the rest of DnsProxyServer needs
 * live sockets and a wired VPN context.
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
}
