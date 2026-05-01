package net.kollnig.missioncontrol.wg;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public class WgEgressRecoveryTest {
    @Test
    public void wakeRecoveryIsNoopWhenWireGuardIsDisabled() {
        AtomicBoolean reloadRequested = new AtomicBoolean(false);

        WgEgress.INSTANCE.onInteractiveStateChanged(
                false,
                validConfig(),
                true,
                () -> reloadRequested.set(true),
                () -> reloadRequested.set(true));

        assertFalse(reloadRequested.get());
    }

    @Test
    public void wakeRecoveryIsNoopWhenConfigIsMissing() {
        AtomicBoolean reloadRequested = new AtomicBoolean(false);

        WgEgress.INSTANCE.onInteractiveStateChanged(
                true,
                "",
                true,
                () -> reloadRequested.set(true),
                () -> reloadRequested.set(true));

        assertFalse(reloadRequested.get());
    }

    private static String validConfig() {
        String key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        return "[Interface]\n" +
                "PrivateKey = " + key + "\n" +
                "Address = 10.0.0.2/32\n" +
                "\n" +
                "[Peer]\n" +
                "PublicKey = " + key + "\n" +
                "AllowedIPs = 0.0.0.0/0\n" +
                "Endpoint = 198.51.100.1:51820\n";
    }
}
