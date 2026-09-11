package net.kollnig.missioncontrol.wg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import net.kollnig.missioncontrol.wgbridge.Tunnel;

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

    @org.junit.Test
    public void underlyingNetworkChangeIsNoopWhenWireGuardIsIdle() throws Exception {
        Field tunnel = field("tunnel");
        Field pending = field("forceRestartPending");
        Field attempts = field("restartAttempts");
        Field generation = field("verificationGeneration");
        Field reload = field("requestReloadCb");
        Object oldTunnel = tunnel.get(WgEgress.INSTANCE);
        boolean oldPending = pending.getBoolean(WgEgress.INSTANCE);
        int oldAttempts = attempts.getInt(WgEgress.INSTANCE);
        long oldGeneration = generation.getLong(WgEgress.INSTANCE);
        Object oldReload = reload.get(WgEgress.INSTANCE);
        AtomicInteger reloads = new AtomicInteger();
        try {
            tunnel.set(WgEgress.INSTANCE, null);
            pending.setBoolean(WgEgress.INSTANCE, false);
            attempts.setInt(WgEgress.INSTANCE, 7);
            reload.set(WgEgress.INSTANCE, (Runnable) reloads::incrementAndGet);

            WgEgress.INSTANCE.onUnderlyingNetworkChanged();

            assertFalse(pending.getBoolean(WgEgress.INSTANCE));
            assertEquals(7, attempts.getInt(WgEgress.INSTANCE));
            assertEquals(0, reloads.get());
        } finally {
            tunnel.set(WgEgress.INSTANCE, oldTunnel);
            pending.setBoolean(WgEgress.INSTANCE, oldPending);
            attempts.setInt(WgEgress.INSTANCE, oldAttempts);
            generation.setLong(WgEgress.INSTANCE, oldGeneration);
            reload.set(WgEgress.INSTANCE, oldReload);
        }
    }

    @org.junit.Test
    public void underlyingNetworkChangeMarksRunningTunnelForOneRestart() throws Exception {
        Field tunnel = field("tunnel");
        Field pending = field("forceRestartPending");
        Field attempts = field("restartAttempts");
        Field generation = field("verificationGeneration");
        Field reload = field("requestReloadCb");
        Object oldTunnel = tunnel.get(WgEgress.INSTANCE);
        boolean oldPending = pending.getBoolean(WgEgress.INSTANCE);
        int oldAttempts = attempts.getInt(WgEgress.INSTANCE);
        long oldGeneration = generation.getLong(WgEgress.INSTANCE);
        Object oldReload = reload.get(WgEgress.INSTANCE);
        AtomicInteger reloads = new AtomicInteger();
        try {
            tunnel.set(WgEgress.INSTANCE, newTunnel(0L));
            pending.setBoolean(WgEgress.INSTANCE, false);
            attempts.setInt(WgEgress.INSTANCE, 11);
            reload.set(WgEgress.INSTANCE, (Runnable) reloads::incrementAndGet);

            WgEgress.INSTANCE.onUnderlyingNetworkChanged();
            WgEgress.INSTANCE.onUnderlyingNetworkChanged();

            assertTrue(pending.getBoolean(WgEgress.INSTANCE));
            assertEquals(11, attempts.getInt(WgEgress.INSTANCE));
            assertEquals(0, reloads.get());
        } finally {
            tunnel.set(WgEgress.INSTANCE, oldTunnel);
            pending.setBoolean(WgEgress.INSTANCE, oldPending);
            attempts.setInt(WgEgress.INSTANCE, oldAttempts);
            generation.setLong(WgEgress.INSTANCE, oldGeneration);
            reload.set(WgEgress.INSTANCE, oldReload);
        }
    }

    private static Field field(String name) throws Exception {
        Field field = WgEgress.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Tunnel newTunnel(long handle) throws Exception {
        Constructor<Tunnel> constructor = Tunnel.class.getDeclaredConstructor(long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(handle);
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
