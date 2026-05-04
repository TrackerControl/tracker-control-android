package net.kollnig.missioncontrol.wg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class VpnKeyRotationManagerTest {
    private static final String ACCOUNT = "test-account";
    private static final String DEVICE_ID = "test-device";
    private static final String OLD_PRIVATE = key(0);
    private static final String NEW_PRIVATE = key(1);
    private static final String PENDING_PRIVATE = key(2);
    private static final String RETRY_PRIVATE = key(3);
    private static final String PEER_KEY = key(9);
    private static final String OLD_PUBLIC = "old-public";
    private static final String NEW_PUBLIC = "new-public";
    private static final String PENDING_PUBLIC = "pending-public";
    private static final String RETRY_PUBLIC = "retry-public";

    private Context context;
    private SharedPreferences prefs;
    private WgProfileManager manager;
    private FakeMullvadApi mullvad;
    private FakeIvpnApi ivpn;
    private FakeKeyApi keys;
    private FakeRuntime runtime;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        manager = new WgProfileManager(context);
        mullvad = new FakeMullvadApi();
        ivpn = new FakeIvpnApi();
        keys = new FakeKeyApi();
        runtime = new FakeRuntime();
    }

    @Test
    public void mullvadRotationUpdatesProviderProfilesAndClearsTemporaryState()
            throws Exception {
        saveMullvadProfile(OLD_PRIVATE);
        manager.saveMullvadDeviceId(DEVICE_ID);
        mullvad.devicePublicKey = OLD_PUBLIC;
        keys.queueGenerated(NEW_PRIVATE);

        String result = rotate("mullvad", true);

        assertEquals("Mullvad rotated", result);
        assertEquals(NEW_PUBLIC, mullvad.devicePublicKey);
        assertEquals(1, mullvad.rotateCount);
        assertTrue(manager.getActiveProfile().config.contains("PrivateKey = " + NEW_PRIVATE));
        assertTrue(manager.getActiveProfile().config.contains("# relay comment is preserved"));
        assertFalse(manager.getActiveProfile().config.contains("PrivateKey = " + OLD_PRIVATE));
        assertFalse(prefs.contains("mullvad_pending_privkey"));
        assertFalse(prefs.contains("mullvad_previous_privkey"));
        assertEquals(runtime.now, prefs.getLong("mullvad_key_rotated_at", 0L));
    }

    @Test
    public void mullvadApiRejectionDoesNotStorePendingOrRewriteLocalProfiles()
            throws Exception {
        saveMullvadProfile(OLD_PRIVATE);
        manager.saveMullvadDeviceId(DEVICE_ID);
        mullvad.rejectRotate = true;
        keys.queueGenerated(NEW_PRIVATE);

        String result = rotate("mullvad", true);

        assertTrue(result.startsWith("Mullvad failed:"));
        assertTrue(manager.getActiveProfile().config.contains("PrivateKey = " + OLD_PRIVATE));
        assertFalse(prefs.contains("mullvad_pending_privkey"));
        assertEquals(0L, prefs.getLong("mullvad_key_rotated_at", 0L));
    }

    @Test
    public void mullvadPublicKeyInUseRetriesWithAnotherGeneratedKey()
            throws Exception {
        saveMullvadProfile(OLD_PRIVATE);
        manager.saveMullvadDeviceId(DEVICE_ID);
        mullvad.rejectPublicKeyInUse = NEW_PUBLIC;
        keys.queueGenerated(NEW_PRIVATE, RETRY_PRIVATE);

        String result = rotate("mullvad", true);

        assertEquals("Mullvad rotated", result);
        assertEquals(RETRY_PUBLIC, mullvad.devicePublicKey);
        assertEquals(2, mullvad.rotateCount);
        assertTrue(manager.getActiveProfile().config.contains("PrivateKey = " + RETRY_PRIVATE));
        assertFalse(prefs.contains("mullvad_pending_privkey"));
    }

    @Test
    public void mullvadStaleCachedDeviceIdIsResolvedFromCurrentPublicKey()
            throws Exception {
        saveMullvadProfile(OLD_PRIVATE);
        manager.saveMullvadDeviceId("stale-device");
        mullvad.devicePublicKey = OLD_PUBLIC;
        keys.queueGenerated(NEW_PRIVATE);

        String result = rotate("mullvad", true);

        assertEquals("Mullvad rotated", result);
        assertEquals(DEVICE_ID, mullvad.lastRotatedDeviceId);
        assertEquals(DEVICE_ID, manager.getMullvadDeviceId());
        assertEquals(NEW_PUBLIC, mullvad.devicePublicKey);
        assertTrue(manager.getActiveProfile().config.contains("PrivateKey = " + NEW_PRIVATE));
    }

    @Test
    public void mullvadAmbiguousFailureStoresPendingWithoutLocalRewrite()
            throws Exception {
        saveMullvadProfile(OLD_PRIVATE);
        manager.saveMullvadDeviceId(DEVICE_ID);
        mullvad.failRotateWithIo = true;
        keys.queueGenerated(NEW_PRIVATE);

        String result = rotate("mullvad", true);

        assertTrue(result.startsWith("Mullvad failed:"));
        assertTrue(manager.getActiveProfile().config.contains("PrivateKey = " + OLD_PRIVATE));
        assertEquals(NEW_PRIVATE, prefs.getString("mullvad_pending_privkey", ""));
        assertEquals(NEW_PUBLIC, prefs.getString("mullvad_pending_pubkey", ""));
        assertEquals(0L, prefs.getLong("mullvad_key_rotated_at", 0L));
    }

    @Test
    public void mullvadPendingKeyIsCommittedWhenServerAlreadyHasIt()
            throws Exception {
        saveMullvadProfile(OLD_PRIVATE);
        manager.saveMullvadDeviceId(DEVICE_ID);
        mullvad.devicePublicKey = PENDING_PUBLIC;
        prefs.edit()
                .putString("mullvad_pending_privkey", PENDING_PRIVATE)
                .putString("mullvad_pending_pubkey", PENDING_PUBLIC)
                .commit();

        String result = rotate("mullvad", true);

        assertEquals("Mullvad pending committed", result);
        assertTrue(manager.getActiveProfile().config.contains("PrivateKey = " + PENDING_PRIVATE));
        assertFalse(prefs.contains("mullvad_pending_privkey"));
        assertEquals(runtime.now, prefs.getLong("mullvad_key_rotated_at", 0L));
    }

    @Test
    public void ivpnRotationUpdatesSessionAddressAndProviderProfiles()
            throws Exception {
        saveIvpnProfile(OLD_PRIVATE, "172.16.10.2/32");
        keys.queueGenerated(NEW_PRIVATE);
        ivpn.nextAddress = "172.16.10.99";

        String result = rotate("ivpn", true);

        assertEquals("IVPN rotated", result);
        assertEquals(1, ivpn.rotateCount);
        assertEquals(NEW_PRIVATE, manager.getIvpnSession(ACCOUNT).privateKey);
        assertEquals(NEW_PUBLIC, manager.getIvpnSession(ACCOUNT).publicKey);
        assertEquals("172.16.10.99", manager.getIvpnSession(ACCOUNT).address);
        assertTrue(manager.getActiveProfile().config.contains("PrivateKey = " + NEW_PRIVATE));
        assertTrue(manager.getActiveProfile().config.contains("Address = 172.16.10.99/32"));
        assertFalse(prefs.contains("ivpn_pending_privkey"));
    }

    @Test
    public void activeTunnelMissingHandshakeRollsBackLocalAndProviderKey()
            throws Exception {
        saveMullvadProfile(OLD_PRIVATE);
        manager.saveMullvadDeviceId(DEVICE_ID);
        prefs.edit().putBoolean("wg_enabled", true).commit();
        mullvad.devicePublicKey = OLD_PUBLIC;
        keys.queueGenerated(NEW_PRIVATE);
        runtime.latestHandshake = 0L;

        String result = rotate("mullvad", true);

        assertEquals("Mullvad rolled back: missing handshake", result);
        assertEquals(OLD_PUBLIC, mullvad.devicePublicKey);
        assertEquals(Arrays.asList("vpn provider key rotated",
                "vpn provider key rotation rollback"), runtime.reloadReasons);
        assertTrue(manager.getActiveProfile().config.contains("PrivateKey = " + OLD_PRIVATE));
        assertEquals(0L, prefs.getLong("mullvad_key_rotated_at", 0L));
    }

    @Test
    public void freshProviderIsSkippedUnlessForced() throws Exception {
        saveMullvadProfile(OLD_PRIVATE);
        manager.saveMullvadDeviceId(DEVICE_ID);
        prefs.edit().putLong("mullvad_key_rotated_at", runtime.now).commit();
        keys.queueGenerated(NEW_PRIVATE);

        String result = rotate("mullvad", false);

        assertEquals("Mullvad skipped: fresh", result);
        assertEquals(0, mullvad.rotateCount);
    }

    @Test
    public void newProviderProfileIsMarkedFreshBeforeScheduledRotation() throws Exception {
        saveMullvadProfile(OLD_PRIVATE);
        manager.saveMullvadDeviceId(DEVICE_ID);
        keys.queueGenerated(NEW_PRIVATE);

        String result = rotate("mullvad", false);

        assertEquals("Mullvad skipped: fresh", result);
        assertEquals(0, mullvad.rotateCount);
        assertEquals(runtime.now, prefs.getLong("mullvad_key_rotated_at", 0L));
        assertTrue(manager.getActiveProfile().config.contains("PrivateKey = " + OLD_PRIVATE));
    }

    private String rotate(String provider, boolean force) {
        return VpnKeyRotationManager.rotateProviderForTest(context, provider, force,
                new VpnKeyRotationManager.Dependencies(mullvad, ivpn, keys, runtime));
    }

    private void saveMullvadProfile(String privateKey) throws Exception {
        manager.saveMullvadAccount(ACCOUNT);
        manager.saveProfile("", "Mullvad", config(privateKey, "10.64.0.2/32"),
                "mullvad", ACCOUNT, "de", "Germany");
    }

    private void saveIvpnProfile(String privateKey, String address) throws Exception {
        manager.saveIvpnAccount(ACCOUNT);
        manager.saveIvpnSession(new WgProfileManager.IvpnSession("session",
                OLD_PRIVATE, OLD_PUBLIC, "172.16.10.2"));
        manager.saveProfile("", "IVPN", config(privateKey, address),
                "ivpn", ACCOUNT, "de", "Germany");
    }

    private static String config(String privateKey, String address) {
        return "[Interface]\n" +
                "PrivateKey = " + privateKey + "\n" +
                "Address = " + address + "\n" +
                "DNS = 10.0.0.1\n" +
                "\n" +
                "[Peer]\n" +
                "# relay comment is preserved\n" +
                "PublicKey = " + PEER_KEY + "\n" +
                "AllowedIPs = 0.0.0.0/0, ::/0\n" +
                "Endpoint = 198.51.100.1:51820\n";
    }

    private static String key(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private static class FakeMullvadApi implements VpnKeyRotationManager.MullvadApi {
        String devicePublicKey = OLD_PUBLIC;
        boolean rejectRotate;
        boolean failRotateWithIo;
        String rejectPublicKeyInUse;
        String lastRotatedDeviceId;
        int rotateCount;

        @Override
        public String findDeviceIdForPubkey(String accountNumber, String publicKey) {
            return publicKey.equals(devicePublicKey) ? DEVICE_ID : "";
        }

        @Override
        public boolean deviceHasPubkey(String accountNumber, String deviceId, String publicKey) {
            return DEVICE_ID.equals(deviceId) && publicKey.equals(devicePublicKey);
        }

        @Override
        public void rotateDevicePubkey(String accountNumber, String deviceId, String publicKey)
                throws Exception {
            rotateCount++;
            lastRotatedDeviceId = deviceId;
            if (publicKey.equals(rejectPublicKeyInUse))
                throw new MullvadProfileGenerator.ApiRejectedException(
                        "Mullvad request failed: 400 {\"detail\":\"This WireGuard public key is already in use.\",\"code\":\"PUBKEY_IN_USE\"}");
            if (rejectRotate)
                throw new MullvadProfileGenerator.ApiRejectedException("rejected");
            if (failRotateWithIo)
                throw new IOException("network lost");
            devicePublicKey = publicKey;
        }
    }

    private static class FakeIvpnApi implements VpnKeyRotationManager.IvpnApi {
        String nextAddress = "172.16.10.3";
        int rotateCount;

        @Override
        public WgProfileManager.IvpnSession rotateSessionKey(WgProfileManager.IvpnSession session,
                                                             String newPrivateKey,
                                                             String newPublicKey,
                                                             String connectedPublicKey) {
            rotateCount++;
            return new WgProfileManager.IvpnSession(session.token, newPrivateKey,
                    newPublicKey, nextAddress);
        }
    }

    private static class FakeKeyApi implements VpnKeyRotationManager.KeyApi {
        private final Map<String, String> publicKeys = new HashMap<>();
        private String nextPrivate;

        FakeKeyApi() {
            publicKeys.put(OLD_PRIVATE, OLD_PUBLIC);
            publicKeys.put(NEW_PRIVATE, NEW_PUBLIC);
            publicKeys.put(PENDING_PRIVATE, PENDING_PUBLIC);
            publicKeys.put(RETRY_PRIVATE, RETRY_PUBLIC);
        }

        void queueGenerated(String... privateKeys) {
            nextPrivate = String.join(",", privateKeys);
        }

        @Override
        public String generatePrivateKey() {
            if (nextPrivate == null)
                throw new AssertionError("No generated private key queued");
            int comma = nextPrivate.indexOf(',');
            if (comma < 0)
                return nextPrivate;
            String privateKey = nextPrivate.substring(0, comma);
            nextPrivate = nextPrivate.substring(comma + 1);
            return privateKey;
        }

        @Override
        public String publicKey(String privateKey) {
            return publicKeys.get(privateKey);
        }
    }

    private static class FakeRuntime implements VpnKeyRotationManager.RuntimeHooks {
        final java.util.List<String> reloadReasons = new java.util.ArrayList<>();
        long now = 1_700_000_000_000L;
        Long latestHandshake;

        @Override
        public long now() {
            return now;
        }

        @Override
        public void reload(String reason, Context context) {
            reloadReasons.add(reason);
        }

        @Override
        public void sleep(long millis) {
        }

        @Override
        public Long latestHandshakeMillisOrNull() {
            return latestHandshake;
        }
    }
}
