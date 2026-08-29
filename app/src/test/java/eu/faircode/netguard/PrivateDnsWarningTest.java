package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import androidx.preference.PreferenceManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** Focused tests for the silent Private DNS bypass warnings. */
@RunWith(RobolectricTestRunner.class)
public class PrivateDnsWarningTest {
    private Context context;
    private SharedPreferences prefs;
    private DatabaseHelper database;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        setPrivateDns("off", null);

        database = DatabaseHelper.getInstance(context);
        database.clearLog(-1);
    }

    private void setPrivateDns(String mode, String specifier) {
        Settings.Global.putString(context.getContentResolver(), "private_dns_mode", mode);
        Settings.Global.putString(context.getContentResolver(), "private_dns_specifier", specifier);
    }

    @Test
    public void hostnameAndBlockDotCombinations() {
        String[] modes = { "off", "opportunistic", "hostname" };
        for (String mode : modes)
            for (boolean blockDot : new boolean[] { false, true }) {
                setPrivateDns(mode, "hostname".equals(mode) ? "dns.google" : null);
                prefs.edit().putBoolean("block_dot", blockDot).commit();

                boolean hostnameWithoutBlocking = "hostname".equals(mode) && !blockDot;
                assertEquals(hostnameWithoutBlocking,
                        Util.isPrivateDnsDetectionDefeated(context));
                assertEquals(hostnameWithoutBlocking
                                ? ServiceSinkhole.PRIVATE_DNS_WARNING_HOSTNAME
                                : ServiceSinkhole.PRIVATE_DNS_WARNING_NONE,
                        ServiceSinkhole.getPrivateDnsWarningState(context));
            }
    }

    @Test
    public void hostnameWarningTakesPrecedenceOverAllowedDotFlow() {
        long now = System.currentTimeMillis();
        database.insertLog(packet(now, 6, 853, true, "A"), null, 0, false);

        setPrivateDns("hostname", "dns.google");
        prefs.edit().putBoolean("block_dot", false).putBoolean("log", true).commit();

        assertEquals(ServiceSinkhole.PRIVATE_DNS_WARNING_HOSTNAME,
                ServiceSinkhole.getPrivateDnsWarningState(context));
    }

    @Test
    public void allowedDotWarningRequiresTrafficLog() {
        long now = System.currentTimeMillis();
        database.insertLog(packet(now, 6, 853, true, "A"), null, 0, false);
        setPrivateDns("opportunistic", null);
        prefs.edit().putBoolean("block_dot", false).commit();

        assertEquals(ServiceSinkhole.PRIVATE_DNS_WARNING_NONE,
                ServiceSinkhole.getPrivateDnsWarningState(context));

        prefs.edit().putBoolean("log", true).commit();
        assertEquals(ServiceSinkhole.PRIVATE_DNS_WARNING_ALLOWED_DOT,
                ServiceSinkhole.getPrivateDnsWarningState(context));
    }

    @Test
    public void allowedDotQueryRequiresRecentAllowedFlow() {
        long now = System.currentTimeMillis();
        database.insertLog(packet(now - 2 * 60 * 60 * 1000L, 6, 853, true, "A"), null, 0, false);
        database.insertLog(packet(now, 6, 853, false, "A"), null, 0, false);
        database.insertLog(packet(now, 6, 443, true, "A"), null, 0, false);
        database.insertLog(packet(now, 6, 853, true, "S"), null, 0, false);
        database.insertLog(packet(now, 6, 853, true, "RA"), null, 0, false);
        database.insertLog(packet(now, 17, 853, true, "A"), null, 0, false);

        assertFalse(database.hasRecentAllowedDot(now - 60 * 60 * 1000L));

        database.insertLog(packet(now, 6, 853, true, "A"), null, 0, false);
        assertTrue(database.hasRecentAllowedDot(now - 60 * 60 * 1000L));
    }

    @Test
    public void onlyEstablishedAllowedTcpDotQualifies() {
        assertTrue(DatabaseHelper.isAllowedDotEvidence(packet(0, 6, 853, true, "A")));
        assertTrue(DatabaseHelper.isAllowedDotEvidence(packet(0, 6, 853, true, "PA")));
        assertFalse(DatabaseHelper.isAllowedDotEvidence(packet(0, 6, 853, true, "S")));
        assertFalse(DatabaseHelper.isAllowedDotEvidence(packet(0, 6, 853, true, "SA")));
        assertFalse(DatabaseHelper.isAllowedDotEvidence(packet(0, 6, 853, true, "RA")));
        assertFalse(DatabaseHelper.isAllowedDotEvidence(packet(0, 6, 853, true, "a")));
        assertFalse(DatabaseHelper.isAllowedDotEvidence(packet(0, 17, 853, true, "")));
        assertFalse(DatabaseHelper.isAllowedDotEvidence(packet(0, 6, 853, false, "A")));
    }

    @Test
    public void expiryDelayFollowsLatestEvidence() {
        long now = 10_000L;
        assertEquals(60 * 60 * 1000L - 500L,
                ServiceSinkhole.getPrivateDnsWarningExpiryDelay(now, 9_500L));
        assertEquals(60 * 60 * 1000L + 500L,
                ServiceSinkhole.getPrivateDnsWarningExpiryDelay(now, 10_500L));
        assertEquals(1L,
                ServiceSinkhole.getPrivateDnsWarningExpiryDelay(now + 60 * 60 * 1000L, 10_000L));
        assertEquals(-1L, ServiceSinkhole.getPrivateDnsWarningExpiryDelay(now, -1L));
    }

    @Test
    public void warningShowsAndClearsOnlyOnStateTransitions() {
        assertTrue(ServiceSinkhole.shouldShowPrivateDnsWarning(
                ServiceSinkhole.PRIVATE_DNS_WARNING_NONE,
                ServiceSinkhole.PRIVATE_DNS_WARNING_HOSTNAME));
        assertFalse(ServiceSinkhole.shouldShowPrivateDnsWarning(
                ServiceSinkhole.PRIVATE_DNS_WARNING_HOSTNAME,
                ServiceSinkhole.PRIVATE_DNS_WARNING_HOSTNAME));
        assertTrue(ServiceSinkhole.shouldClearPrivateDnsWarning(
                ServiceSinkhole.PRIVATE_DNS_WARNING_NONE));
        assertFalse(ServiceSinkhole.shouldClearPrivateDnsWarning(
                ServiceSinkhole.PRIVATE_DNS_WARNING_ALLOWED_DOT));
    }

    private static Packet packet(long time, int protocol, int dport, boolean allowed, String flags) {
        Packet packet = new Packet();
        packet.time = time;
        packet.version = 4;
        packet.protocol = protocol;
        packet.saddr = "10.0.0.2";
        packet.sport = 12345;
        packet.daddr = "1.1.1.1";
        packet.dport = dport;
        packet.allowed = allowed;
        packet.flags = flags;
        return packet;
    }
}
