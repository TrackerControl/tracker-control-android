package net.kollnig.missioncontrol.wg;

/**
 * Screen-state keepalive toggling for the WireGuard egress.
 *
 * <p>The wake-time "reload if the tunnel looks dead" recovery that this class
 * used to exercise has been replaced by the continuous {@link
 * WgConnectivityMonitor} watchdog (see {@link WgConnectivityCheckerTest}).
 * {@link WgEgress#onInteractiveStateChanged} now only re-applies the keepalive
 * interval and must be a safe no-op when there is no running tunnel.
 */
public class WgEgressRecoveryTest {
    @org.junit.Test
    public void interactiveStateChangeIsNoopWhenWireGuardIsDisabled() {
        // No tunnel is running in a unit test; this must not throw.
        WgEgress.INSTANCE.onInteractiveStateChanged(false, validConfig(), true, false);
    }

    @org.junit.Test
    public void interactiveStateChangeIsNoopWhenConfigIsMissing() {
        WgEgress.INSTANCE.onInteractiveStateChanged(true, "", true, false);
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
