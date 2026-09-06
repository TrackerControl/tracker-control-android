package net.kollnig.missioncontrol.wgbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

/** Exercises the packaged Rust library through its real JNI entry points. */
@RunWith(AndroidJUnit4.class)
public class WgbridgeInstrumentedTest {
    @Test
    public void generatedKeysRoundTripAcrossJni() {
        String firstPrivateKey = Wgbridge.generatePrivateKey();
        String secondPrivateKey = Wgbridge.generatePrivateKey();

        assertNotNull(firstPrivateKey);
        assertNotNull(secondPrivateKey);
        assertEquals(44, firstPrivateKey.length());
        assertEquals(44, Wgbridge.publicKey(firstPrivateKey).length());
        assertNotEquals(firstPrivateKey, secondPrivateKey);
        assertNotEquals(Wgbridge.publicKey(firstPrivateKey), Wgbridge.publicKey(secondPrivateKey));
    }

    @Test(expected = RuntimeException.class)
    public void invalidPrivateKeyIsReportedAsJavaException() {
        Wgbridge.publicKey("not-a-wireguard-key");
    }

    /** Uses synthetic keys and descriptors; no external endpoint or user VPN configuration. */
    @Test(timeout = 30000)
    public void tunnelStatsRebindAndStopUseRealJni() throws Exception {
        byte[] key = Base64.decode(Wgbridge.generatePrivateKey(), Base64.DEFAULT);
        StringBuilder hex = new StringBuilder();
        for (byte value : key) hex.append(String.format("%02x", value & 255));
        byte[] publicKey = Base64.decode(Wgbridge.publicKey(Wgbridge.generatePrivateKey()), Base64.DEFAULT);
        StringBuilder peerHex = new StringBuilder();
        for (byte value : publicKey) peerHex.append(String.format("%02x", value & 255));
        String config = "private_key=" + hex + "\npublic_key=" + peerHex
                + "\nallowed_ip=192.0.2.1/32\n";
        for (int cycle = 0; cycle < 3; cycle++) {
            ParcelFileDescriptor[] outbound = ParcelFileDescriptor.createSocketPair();
            ParcelFileDescriptor[] inbound = ParcelFileDescriptor.createPipe();
            AtomicInteger protectedSockets = new AtomicInteger();
            Tunnel tunnel = null;
            try {
                tunnel = Wgbridge.startTunnel(config, outbound[0].getFd(),
                        inbound[1].getFd(), 1280, fd -> {
                            protectedSockets.incrementAndGet();
                            return true;
                        }, null, null);
                assertTrue("UDP sockets created", protectedSockets.get() > 0);
                java.lang.reflect.Field handle = Tunnel.class.getDeclaredField("handle");
                handle.setAccessible(true);
                java.lang.reflect.Method nativeStats = Tunnel.class.getDeclaredMethod("nativeStats", long.class);
                nativeStats.setAccessible(true);
                long[] rawStats = (long[]) nativeStats.invoke(null, handle.getLong(tunnel));
                assertEquals("packaged JNI includes both TUN failure counters", 5, rawStats.length);
                TunnelStats stats = tunnel.stats();
                assertEquals(0L, stats.rxBytes);
                assertEquals(0L, stats.txBytes);
                assertEquals(0L, stats.latestHandshakeMillis);
                assertEquals(0L, stats.tunWriteFailuresTotal);
                assertEquals(0L, stats.tunWriteFailuresStreak);
                int before = protectedSockets.get();
                tunnel.rebind();
                assertTrue("rebind recreates protected sockets", protectedSockets.get() > before);
                tunnel.setConfig(config);
                assertEquals(0L, tunnel.stats().tunWriteFailuresTotal);
                tunnel.stop();
                tunnel.stop();
                try {
                    tunnel.stats();
                    fail("stopped tunnel must reject stats");
                } catch (RuntimeException expected) {
                    // A stale handle must report an exception, never crash native code.
                }
                assertTrue("caller outbound descriptor remains owned by caller",
                        Os.fcntlInt(outbound[0].getFileDescriptor(), OsConstants.F_GETFD, 0) >= 0);
                assertTrue("caller inbound descriptor remains owned by caller",
                        Os.fcntlInt(inbound[1].getFileDescriptor(), OsConstants.F_GETFD, 0) >= 0);
            } finally {
                if (tunnel != null) tunnel.stop();
                for (ParcelFileDescriptor fd : outbound) fd.close();
                for (ParcelFileDescriptor fd : inbound) fd.close();
            }
        }
    }
}
