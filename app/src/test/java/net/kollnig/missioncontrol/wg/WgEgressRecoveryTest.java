package net.kollnig.missioncontrol.wg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import net.kollnig.missioncontrol.wgbridge.Tunnel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;

import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Screen-state keepalive toggling for the WireGuard egress.
 *
 * <p>The wake-time "reload if the tunnel looks dead" recovery that this class
 * used to exercise has been replaced by the continuous {@link
 * WgConnectivityMonitor} watchdog (see {@link WgConnectivityCheckerTest}).
 * {@link WgEgress#onInteractiveStateChanged} now only re-applies the keepalive
 * interval and must be a safe no-op when there is no running tunnel.
 */
@RunWith(RobolectricTestRunner.class)
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
    public void networkChangedBypassesSameConfigShortcutBeforeNativeStart() throws Exception {
        // No production test seam exists for private lifecycle state. These
        // fields seed the exact same config/PFD identity and are restored
        // below so this singleton cannot affect later tests.
        Field tunnel = field("tunnel");
        Field currentConfig = field("currentConfig");
        Field currentTunFd = field("currentTunFd");
        Field currentTunPfd = field("currentTunPfd");
        Field currentKeepaliveAlwaysOn = field("currentKeepaliveAlwaysOn");
        Field forceRestartPending = field("forceRestartPending");
        Field lastCheapRecoveryMs = field("lastCheapRecoveryMs");
        Field verificationGeneration = field("verificationGeneration");
        Field tunnelGeneration = field("tunnelGeneration");
        Field recoveryNotificationGeneration = field("recoveryNotificationGeneration");
        Field lastError = field("lastError");
        Field providerFailureReason = field("providerFailureReason");
        Field pendingProviderFailure = field("pendingProviderFailure");
        Field pendingRestartTunnel = field("pendingRestartTunnel");
        Field pendingRestartTunnelGeneration = field("pendingRestartTunnelGeneration");
        Field endpointCache = field("endpointCache");

        Object oldTunnel = tunnel.get(WgEgress.INSTANCE);
        String oldConfig = (String) currentConfig.get(WgEgress.INSTANCE);
        int oldTunFd = currentTunFd.getInt(WgEgress.INSTANCE);
        Object oldTunPfd = currentTunPfd.get(WgEgress.INSTANCE);
        boolean oldKeepaliveAlwaysOn = currentKeepaliveAlwaysOn.getBoolean(WgEgress.INSTANCE);
        boolean oldForceRestartPending = forceRestartPending.getBoolean(WgEgress.INSTANCE);
        long oldLastCheapRecoveryMs = lastCheapRecoveryMs.getLong(WgEgress.INSTANCE);
        long oldVerificationGeneration = verificationGeneration.getLong(WgEgress.INSTANCE);
        AtomicLong generations = (AtomicLong) tunnelGeneration.get(WgEgress.INSTANCE);
        long oldTunnelGeneration = generations.get();
        long oldRecoveryNotificationGeneration =
                recoveryNotificationGeneration.getLong(WgEgress.INSTANCE);
        Object oldLastError = lastError.get(WgEgress.INSTANCE);
        Object oldProviderFailureReason = providerFailureReason.get(WgEgress.INSTANCE);
        Object oldPendingProviderFailure = pendingProviderFailure.get(WgEgress.INSTANCE);
        Object oldPendingRestartTunnel = pendingRestartTunnel.get(WgEgress.INSTANCE);
        long oldPendingRestartTunnelGeneration =
                pendingRestartTunnelGeneration.getLong(WgEgress.INSTANCE);
        Map<?, ?> oldEndpointCache = new HashMap<>((Map<?, ?>) endpointCache.get(WgEgress.INSTANCE));

        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor vpnFd = pipe[0];
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        String invalidConfig = "malformed";
        try {
            tunnel.set(WgEgress.INSTANCE, newTunnel(0L));
            currentConfig.set(WgEgress.INSTANCE, invalidConfig);
            currentTunFd.setInt(WgEgress.INSTANCE, vpnFd.getFd());
            currentTunPfd.set(WgEgress.INSTANCE, vpnFd);
            forceRestartPending.setBoolean(WgEgress.INSTANCE, false);

            boolean result = WgEgress.INSTANCE.startOrUpdate(
                    true,
                    invalidConfig,
                    new VpnService(),
                    vpnFd,
                    false,
                    false,
                    new Function0<Integer>() {
                        @Override
                        public Integer invoke() {
                            starts.incrementAndGet();
                            return -1;
                        }
                    },
                    new Function0<Unit>() {
                        @Override
                        public Unit invoke() {
                            stops.incrementAndGet();
                            return Unit.INSTANCE;
                        }
                    },
                    true);

            assertFalse("invalid config must fail after the existing tunnel is stopped", result);
            assertEquals("same-config handover must stop the old tunnel", 1, stops.get());
            assertEquals("config parsing must fail before JNI socket setup", 0, starts.get());
            assertNull(tunnel.get(WgEgress.INSTANCE));
        } finally {
            vpnFd.close();
            pipe[1].close();
            tunnel.set(WgEgress.INSTANCE, oldTunnel);
            currentConfig.set(WgEgress.INSTANCE, oldConfig);
            currentTunFd.setInt(WgEgress.INSTANCE, oldTunFd);
            currentTunPfd.set(WgEgress.INSTANCE, oldTunPfd);
            currentKeepaliveAlwaysOn.setBoolean(WgEgress.INSTANCE, oldKeepaliveAlwaysOn);
            forceRestartPending.setBoolean(WgEgress.INSTANCE, oldForceRestartPending);
            lastCheapRecoveryMs.setLong(WgEgress.INSTANCE, oldLastCheapRecoveryMs);
            verificationGeneration.setLong(WgEgress.INSTANCE, oldVerificationGeneration);
            generations.set(oldTunnelGeneration);
            recoveryNotificationGeneration.setLong(WgEgress.INSTANCE, oldRecoveryNotificationGeneration);
            lastError.set(WgEgress.INSTANCE, oldLastError);
            providerFailureReason.set(WgEgress.INSTANCE, oldProviderFailureReason);
            pendingProviderFailure.set(WgEgress.INSTANCE, oldPendingProviderFailure);
            pendingRestartTunnel.set(WgEgress.INSTANCE, oldPendingRestartTunnel);
            pendingRestartTunnelGeneration.setLong(WgEgress.INSTANCE, oldPendingRestartTunnelGeneration);
            Map<Object, Object> cache = (Map<Object, Object>) endpointCache.get(WgEgress.INSTANCE);
            cache.clear();
            cache.putAll((Map<Object, Object>) oldEndpointCache);
        }
    }

    private static Field field(String name) throws Exception {
        Field field = WgEgress.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Tunnel newTunnel(long handle) throws Exception {
        // Tunnel.stop() checks the handle before entering nativeStop(), so a
        // zero-handle fake exercises lifecycle cleanup without JNI.
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
