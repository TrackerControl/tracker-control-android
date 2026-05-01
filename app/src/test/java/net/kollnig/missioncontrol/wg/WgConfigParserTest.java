package net.kollnig.missioncontrol.wg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WgConfigParserTest {
    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    public void persistentKeepaliveIsParsedAndEmittedWhenInteractive() throws Exception {
        WgConfig config = WgConfigParser.INSTANCE.parse(config("PersistentKeepalive = 25"));

        assertEquals(Integer.valueOf(25), config.getPeers().get(0).getPersistentKeepalive());
        assertTrue(config.toUapi(true).contains("persistent_keepalive_interval=25\n"));
    }

    @Test
    public void persistentKeepaliveIsDisabledWhenNotInteractive() throws Exception {
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

    private static String config(String keepaliveLine) {
        return "[Interface]\n" +
                "PrivateKey = " + KEY + "\n" +
                "Address = 10.0.0.2/32\n" +
                "\n" +
                "[Peer]\n" +
                "PublicKey = " + KEY + "\n" +
                "AllowedIPs = 0.0.0.0/0\n" +
                "Endpoint = 198.51.100.1:51820\n" +
                (keepaliveLine.isEmpty() ? "" : keepaliveLine + "\n");
    }
}
