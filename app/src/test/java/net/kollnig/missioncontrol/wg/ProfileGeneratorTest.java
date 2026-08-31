package net.kollnig.missioncontrol.wg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "en")
public class ProfileGeneratorTest {
    private static final String ACCOUNT = "test-account";
    private static final String PRIVATE_KEY = key(0);
    private static final String OTHER_PRIVATE_KEY = key(2);
    private static final String PUBLIC_KEY = key(1);
    private static final String PEER_KEY = key(9);
    private static final String IPV6 = "fc00:bbbb:bbbb:bb01::2/128";
    private static final String IPV4 = "10.64.0.2/32";

    @Test
    public void mullvadRelayFailureDoesNotCreateDevice() throws Exception {
        IOException failure = new IOException("relay fetch failed");
        TestMullvadGenerator generator = new TestMullvadGenerator(failure);

        try {
            generator.generate(ACCOUNT, "de");
            fail("Expected relay fetch failure");
        } catch (IOException ex) {
            assertSame(failure, ex);
        }

        assertEquals(0, generator.fetchWebTokenCalls);
        assertEquals(0, generator.createDeviceCalls);
    }

    @Test
    public void ivpnRelayFailureDoesNotCreateSession() throws Exception {
        IOException failure = new IOException("relay fetch failed");
        TestIvpnGenerator generator = new TestIvpnGenerator(failure);

        try {
            generator.generate(ACCOUNT, "de", null);
            fail("Expected relay fetch failure");
        } catch (IOException ex) {
            assertSame(failure, ex);
        }

        assertEquals(0, generator.createSessionCalls);
    }

    @Test
    public void mullvadIpv6OnlyReusableConfigHasNoLeadingAddressComma() throws Exception {
        TestMullvadGenerator generator = new TestMullvadGenerator(null);
        generator.registerDevice(PUBLIC_KEY, "", IPV6);

        String config = generator.generate(ACCOUNT, "de", reusableConfig(IPV6), null, true).config;

        assertTrue(config.contains("Address = " + IPV6));
        assertFalse(config.contains("Address = ,"));
    }

    @Test
    public void mullvadReusableConfigKeepsBothAddresses() throws Exception {
        TestMullvadGenerator generator = new TestMullvadGenerator(null);
        generator.registerDevice(PUBLIC_KEY, IPV4, IPV6);

        String config = generator.generate(ACCOUNT, "de",
                reusableConfig(IPV4 + ", " + IPV6), null, true).config;

        assertTrue(config.contains("Address = " + IPV4 + ", " + IPV6));
    }

    @Test
    public void mullvadRegisteredReusableConfigKeepsIdentity() throws Exception {
        TestMullvadGenerator generator = new TestMullvadGenerator(null);
        generator.registerDevice(PUBLIC_KEY, IPV4, IPV6);

        MullvadProfileGenerator.GeneratedProfile generated =
                generator.generate(ACCOUNT, "de", reusableConfig(IPV4 + ", " + IPV6), null, true);

        assertFalse(generated.identityReplaced);
        assertEquals(0, generator.createDeviceCalls);
        assertEquals(PRIVATE_KEY, generated.privateKey);
        assertEquals("device", generated.deviceId);
        assertTrue(generated.config.contains("PrivateKey = " + PRIVATE_KEY));
    }

    @Test
    public void mullvadDeletedDeviceRegistersFreshIdentity() throws Exception {
        TestMullvadGenerator generator = new TestMullvadGenerator(null);
        generator.newPrivateKey = OTHER_PRIVATE_KEY;
        // The account lists a device, but not the one the saved profile uses:
        // this is the state left behind by deleting the device at Mullvad.
        generator.registerDevice(key(7), IPV4, IPV6);

        MullvadProfileGenerator.GeneratedProfile generated =
                generator.generate(ACCOUNT, "de", reusableConfig(IPV4 + ", " + IPV6), null, true);

        assertTrue(generated.identityReplaced);
        assertEquals(1, generator.createDeviceCalls);
        assertEquals(OTHER_PRIVATE_KEY, generated.privateKey);
        assertEquals("device", generated.deviceId);
        assertEquals(IPV4 + ", " + IPV6, generated.address);
        assertTrue(generated.config.contains("PrivateKey = " + OTHER_PRIVATE_KEY));
    }

    @Test
    public void mullvadReuseDoesNotAskProviderByDefault() throws Exception {
        // The handshake is the authoritative test of a saved identity, and it
        // only needs the relay endpoint. Reusing must not depend on reaching
        // the API host, or a profile that would have worked fails to generate.
        TestMullvadGenerator generator = new TestMullvadGenerator(null);
        generator.deviceListFailure = new IOException("API unreachable");

        MullvadProfileGenerator.GeneratedProfile generated =
                generator.generate(ACCOUNT, "de", reusableConfig(IPV4 + ", " + IPV6));

        assertFalse(generated.identityReplaced);
        assertEquals(0, generator.fetchWebTokenCalls);
        assertEquals(0, generator.createDeviceCalls);
        assertTrue(generated.config.contains("PrivateKey = " + PRIVATE_KEY));
    }

    @Test
    public void ivpnReuseDoesNotAskProviderByDefault() throws Exception {
        TestIvpnGenerator generator = new TestIvpnGenerator(null);
        generator.sessionStatusFailure = new IOException("API unreachable");
        WgProfileManager.IvpnSession session = new WgProfileManager.IvpnSession(
                "token", PRIVATE_KEY, PUBLIC_KEY, "10.64.0.9");

        IvpnProfileGenerator.GeneratedProfile generated =
                generator.generate(ACCOUNT, "de", session);

        assertFalse(generated.identityReplaced);
        assertEquals(0, generator.createSessionCalls);
        assertTrue(generated.config.contains("Address = 10.64.0.9/32"));
    }

    @Test
    public void mullvadDeviceListFailureDoesNotCreateDevice() throws Exception {
        IOException failure = new IOException("device list failed");
        TestMullvadGenerator generator = new TestMullvadGenerator(null);
        generator.deviceListFailure = failure;

        try {
            generator.generate(ACCOUNT, "de", reusableConfig(IPV4), null, true);
            fail("Expected device list failure");
        } catch (IOException ex) {
            assertSame(failure, ex);
        }

        assertEquals(0, generator.createDeviceCalls);
    }

    @Test
    public void ivpnDeletedSessionCreatesFreshSession() throws Exception {
        TestIvpnGenerator generator = new TestIvpnGenerator(null);
        generator.sessionRegistered = false;
        WgProfileManager.IvpnSession stale = new WgProfileManager.IvpnSession(
                "stale-token", PRIVATE_KEY, PUBLIC_KEY, "10.64.0.9");

        IvpnProfileGenerator.GeneratedProfile generated =
                generator.generate(ACCOUNT, "de", stale, "", "", null, true);

        assertTrue(generated.identityReplaced);
        assertEquals(1, generator.createSessionCalls);
        assertEquals("10.64.0.2/32", generated.address);
        assertTrue(generated.config.contains("Address = 10.64.0.2/32"));
    }

    @Test
    public void ivpnRegisteredSessionIsReused() throws Exception {
        TestIvpnGenerator generator = new TestIvpnGenerator(null);
        WgProfileManager.IvpnSession session = new WgProfileManager.IvpnSession(
                "token", PRIVATE_KEY, PUBLIC_KEY, "10.64.0.9");

        IvpnProfileGenerator.GeneratedProfile generated =
                generator.generate(ACCOUNT, "de", session, "", "", null, true);

        assertFalse(generated.identityReplaced);
        assertEquals(0, generator.createSessionCalls);
        assertTrue(generated.config.contains("Address = 10.64.0.9/32"));
    }

    @Test
    public void ivpnSessionStatusFailureDoesNotCreateSession() throws Exception {
        IOException failure = new IOException("session status failed");
        TestIvpnGenerator generator = new TestIvpnGenerator(null);
        generator.sessionStatusFailure = failure;
        WgProfileManager.IvpnSession session = new WgProfileManager.IvpnSession(
                "token", PRIVATE_KEY, PUBLIC_KEY, "10.64.0.9");

        try {
            generator.generate(ACCOUNT, "de", session, "", "", null, true);
            fail("Expected session status failure");
        } catch (IOException ex) {
            assertSame(failure, ex);
        }

        assertEquals(0, generator.createSessionCalls);
    }

    @Test
    public void ivpnIpv6AddressGetsIpv6Cidr() throws Exception {
        TestIvpnGenerator generator = new TestIvpnGenerator(null);
        WgProfileManager.IvpnSession session = new WgProfileManager.IvpnSession(
                "session", PRIVATE_KEY, PUBLIC_KEY, "fc00:bbbb:bbbb:bb01::2");

        String config = generator.generate(ACCOUNT, "de", session).config;

        assertTrue(config.contains("Address = fc00:bbbb:bbbb:bb01::2/128"));
    }

    private static String reusableConfig(String address) {
        return "[Interface]\n" +
                "PrivateKey = " + PRIVATE_KEY + "\n" +
                "Address = " + address + "\n" +
                "DNS = 10.64.0.1\n\n" +
                "[Peer]\n" +
                "PublicKey = " + PEER_KEY + "\n" +
                "AllowedIPs = 0.0.0.0/0, ::/0\n" +
                "Endpoint = 198.51.100.1:51820\n";
    }

    private static MullvadProfileGenerator.Relay mullvadRelay() {
        MullvadProfileGenerator.Relay relay = new MullvadProfileGenerator.Relay();
        relay.hostname = "de-test-wireguard";
        relay.countryCode = "de";
        relay.countryName = "Germany";
        relay.ipv4 = "198.51.100.1";
        relay.publicKey = PEER_KEY;
        relay.speed = 1;
        return relay;
    }

    private static IvpnProfileGenerator.Relay ivpnRelay() {
        IvpnProfileGenerator.Relay relay = new IvpnProfileGenerator.Relay();
        relay.hostname = "de-test-wireguard";
        relay.countryCode = "de";
        relay.countryName = "Germany";
        relay.host = "198.51.100.1";
        relay.publicKey = PEER_KEY;
        return relay;
    }

    private static String key(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static class TestMullvadGenerator extends MullvadProfileGenerator {
        private final IOException relayFailure;
        private final List<JSONObject> devices = new java.util.ArrayList<>();
        IOException deviceListFailure;
        String newPrivateKey = PRIVATE_KEY;
        int fetchWebTokenCalls;
        int createDeviceCalls;

        TestMullvadGenerator(IOException relayFailure) {
            this.relayFailure = relayFailure;
        }

        void registerDevice(String publicKey, String ipv4, String ipv6) throws Exception {
            devices.add(new JSONObject()
                    .put("id", "device")
                    .put("name", "registered")
                    .put("pubkey", publicKey)
                    .put("ipv4_address", ipv4)
                    .put("ipv6_address", ipv6));
        }

        @Override
        List<JSONObject> listDevices(String token) throws Exception {
            if (deviceListFailure != null)
                throw deviceListFailure;
            return devices;
        }

        @Override
        List<Relay> fetchRelays() throws Exception {
            if (relayFailure != null)
                throw relayFailure;
            return Collections.singletonList(mullvadRelay());
        }

        @Override
        String fetchWebToken(String accountNumber) {
            fetchWebTokenCalls++;
            return "token";
        }

        @Override
        JSONObject createDevice(String token, String publicKey) throws Exception {
            createDeviceCalls++;
            return new JSONObject()
                    .put("id", "device")
                    .put("name", "test")
                    .put("ipv4_address", IPV4)
                    .put("ipv6_address", IPV6);
        }

        @Override
        String newPrivateKey() {
            return newPrivateKey;
        }

        @Override
        String derivePublicKey(String privateKey) {
            return PRIVATE_KEY.equals(privateKey) ? PUBLIC_KEY : key(3);
        }
    }

    private static class TestIvpnGenerator extends IvpnProfileGenerator {
        private final IOException relayFailure;
        IOException sessionStatusFailure;
        boolean sessionRegistered = true;
        int createSessionCalls;

        TestIvpnGenerator(IOException relayFailure) {
            this.relayFailure = relayFailure;
        }

        @Override
        boolean sessionIsRegistered(String sessionToken) throws Exception {
            if (sessionStatusFailure != null)
                throw sessionStatusFailure;
            return sessionRegistered;
        }

        @Override
        List<Relay> fetchRelays() throws Exception {
            if (relayFailure != null)
                throw relayFailure;
            return Collections.singletonList(ivpnRelay());
        }

        @Override
        WgProfileManager.IvpnSession createSession(String account, String privateKey,
                                                   String publicKey, String captchaId,
                                                   String captchaValue) {
            createSessionCalls++;
            return new WgProfileManager.IvpnSession("session", privateKey, publicKey,
                    "10.64.0.2");
        }

        @Override
        String newPrivateKey() {
            return PRIVATE_KEY;
        }

        @Override
        String derivePublicKey(String privateKey) {
            return PUBLIC_KEY;
        }
    }
}
