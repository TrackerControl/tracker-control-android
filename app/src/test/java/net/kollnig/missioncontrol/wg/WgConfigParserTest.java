package net.kollnig.missioncontrol.wg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WgConfigParserTest {
    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    public void persistentKeepaliveIsParsedAndEmittedWhenEnabled() throws Exception {
        WgConfig config = WgConfigParser.INSTANCE.parse(config("PersistentKeepalive = 25"));

        assertEquals(Integer.valueOf(25), config.getPeers().get(0).getPersistentKeepalive());
        assertTrue(config.toUapi(true).contains("persistent_keepalive_interval=25\n"));
    }

    @Test
    public void persistentKeepaliveIsDisabledWhenNotEnabled() throws Exception {
        WgConfig config = WgConfigParser.INSTANCE.parse(config("PersistentKeepalive = 25"));

        assertTrue(config.toUapi(false).contains("persistent_keepalive_interval=0\n"));
    }

    @Test
    public void missingPersistentKeepaliveRemainsDisabled() throws Exception {
        WgConfig config = WgConfigParser.INSTANCE.parse(config(""));

        assertEquals(null, config.getPeers().get(0).getPersistentKeepalive());
        assertFalse(config.toUapi(true).contains("persistent_keepalive_interval="));
    }

    @Test
    public void invalidPersistentKeepaliveIsRejected() {
        assertThrows(WgConfigException.class,
                () -> WgConfigParser.INSTANCE.parse(config("PersistentKeepalive = -1")));
        assertThrows(WgConfigException.class,
                () -> WgConfigParser.INSTANCE.parse(config("PersistentKeepalive = invalid")));
    }

    @Test
    public void hostnameAllowedIpsIsRejected() {
        // A hostname here would otherwise reach the Rust bridge and get
        // resolved, leaking a DNS lookup for an address the user never chose.
        assertThrows(WgConfigException.class,
                () -> WgConfigParser.INSTANCE.parse(configWithAllowedIps("attacker.example/32")));
    }

    @Test
    public void numericIpv6AllowedIpsIsAccepted() throws Exception {
        WgConfig config = WgConfigParser.INSTANCE.parse(configWithAllowedIps("0.0.0.0/0, ::/0"));

        assertEquals(2, config.getPeers().get(0).getAllowedIPs().size());
    }

    @Test
    public void ipv4EmbeddedIpv6AllowedIpsIsAccepted() throws Exception {
        WgConfig config = WgConfigParser.INSTANCE.parse(
                configWithAllowedIps("::ffff:192.0.2.0/120, 2001:db8::1/128, ::/0"));

        assertEquals(3, config.getPeers().get(0).getAllowedIPs().size());
    }

    @Test
    public void malformedIpv6AllowedIpsIsRejected() {
        assertThrows(WgConfigException.class,
                () -> WgConfigParser.INSTANCE.parse(configWithAllowedIps("1:::2/64")));
        assertThrows(WgConfigException.class,
                () -> WgConfigParser.INSTANCE.parse(configWithAllowedIps("2001:db8::1%wlan0/64")));
        assertThrows(WgConfigException.class,
                () -> WgConfigParser.INSTANCE.parse(configWithAllowedIps("::ffff:999.0.2.0/120")));
    }

    private static String config(String keepaliveLine) {
        return configWithAllowedIps("0.0.0.0/0", keepaliveLine);
    }

    private static String configWithAllowedIps(String allowedIps) {
        return configWithAllowedIps(allowedIps, "");
    }

    private static String configWithAllowedIps(String allowedIps, String keepaliveLine) {
        return "[Interface]\n" +
                "PrivateKey = " + KEY + "\n" +
                "Address = 10.0.0.2/32\n" +
                "\n" +
                "[Peer]\n" +
                "PublicKey = " + KEY + "\n" +
                "AllowedIPs = " + allowedIps + "\n" +
                "Endpoint = 198.51.100.1:51820\n" +
                (keepaliveLine.isEmpty() ? "" : keepaliveLine + "\n");
    }
}
