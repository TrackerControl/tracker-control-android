package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import net.kollnig.missioncontrol.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class CustomDnsTest {
    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().putString("dns", "9.9.9.9")
                .putString("dns2", "149.112.112.112").commit();
        setNetworkDns("192.168.1.1");
    }

    @Test
    public void olderSettingsKeepCustomDnsAndToggleRestoresSavedPair() throws Exception {
        assertEquals(Arrays.asList(InetAddress.getByName("9.9.9.9"),
                InetAddress.getByName("149.112.112.112")), ServiceSinkhole.getDns(context));

        prefs.edit().putBoolean("custom_dns_enabled", false).commit();
        assertEquals(Collections.singletonList(InetAddress.getByName("192.168.1.1")),
                ServiceSinkhole.getDns(context));
        assertEquals("9.9.9.9", prefs.getString("dns", null));
        assertEquals("149.112.112.112", prefs.getString("dns2", null));

        prefs.edit().putBoolean("custom_dns_enabled", true).commit();
        assertEquals(Arrays.asList(InetAddress.getByName("9.9.9.9"),
                InetAddress.getByName("149.112.112.112")), ServiceSinkhole.getDns(context));
    }

    @Test
    public void disabledCustomDnsUsesIpv6OnlyNetworkResolver() throws Exception {
        setNetworkDns("fd00::53");
        prefs.edit().putBoolean("custom_dns_enabled", false).commit();
        assertEquals(Collections.singletonList(InetAddress.getByName("fd00::53")),
                ServiceSinkhole.getDns(context));
    }

    @Test
    public void enabledWithoutSavedAddressesStillUsesNetworkDns() throws Exception {
        prefs.edit().remove("dns").remove("dns2").commit();
        assertEquals(Collections.singletonList(InetAddress.getByName("192.168.1.1")),
                ServiceSinkhole.getDns(context));
    }

    @Test
    public void preferenceDisablesFieldsWithoutErasingTheirValues() {
        PreferenceManager manager = new PreferenceManager(context);
        PreferenceScreen screen = manager.inflateFromResource(context, R.xml.preferences, null);
        manager.setPreferences(screen);
        screen.onAttached();
        try {
            SwitchPreference toggle = screen.findPreference("custom_dns_enabled");
            EditTextPreference primary = screen.findPreference("dns");
            EditTextPreference secondary = screen.findPreference("dns2");
            assertTrue(toggle.isChecked());
            toggle.setChecked(false);
            assertFalse(primary.isEnabled());
            assertFalse(secondary.isEnabled());
            assertEquals("9.9.9.9", primary.getText());
            assertEquals("149.112.112.112", secondary.getText());
            toggle.setChecked(true);
            assertTrue(primary.isEnabled());
            assertTrue(secondary.isEnabled());
        } finally {
            screen.onDetached();
        }
    }

    private void setNetworkDns(String address) throws Exception {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network network = manager.getActiveNetwork();
        assertNotNull(network);
        LinkProperties properties = new LinkProperties();
        ReflectionHelpers.callInstanceMethod(properties, "addDnsServer",
                ReflectionHelpers.ClassParameter.from(InetAddress.class, InetAddress.getByName(address)));
        shadowOf(manager).setLinkProperties(network, properties);
    }
}
