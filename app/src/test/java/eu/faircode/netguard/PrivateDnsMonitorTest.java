package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Looper;

import androidx.preference.PreferenceManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = PrivateDnsMonitorTest.RecordingConnectivityManager.class)
public class PrivateDnsMonitorTest {
    private Context context;
    private ShadowConnectivityManager connectivity;
    private PrivateDnsMonitor monitor;
    private final Network vpn = ShadowNetwork.newInstance(123);
    private int updates;
    private int warning;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        connectivity = shadowOf(manager);
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
                .putBoolean("block_dot", false).putBoolean("log", false).commit();
        android.provider.Settings.Global.putString(context.getContentResolver(),
                "private_dns_mode", "opportunistic");
        connectivity.addNetwork(vpn, null);
        NetworkCapabilities capabilities = new NetworkCapabilities();
        shadowOf(capabilities).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        connectivity.setNetworkCapabilities(vpn, capabilities);
        connectivity.setLinkProperties(vpn, new LinkProperties());
        monitor = new PrivateDnsMonitor(manager, () -> {
            updates++;
            warning = ServiceSinkhole.getPrivateDnsWarningState(context);
        });
    }

    @Test
    public void validationAfterThirtySecondsUpdatesAndClearsWarningWithoutTrafficLog() {
        monitor.setEnabled(true);
        NetworkRequest request = ((RecordingConnectivityManager) connectivity).request;
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN));

        ConnectivityManager.NetworkCallback callback = connectivity.getNetworkCallbacks().iterator().next();
        callback.onLinkPropertiesChanged(vpn, new LinkProperties());
        assertEquals(ServiceSinkhole.PRIVATE_DNS_WARNING_NONE, warning);

        // Validation may finish much later than the old single 30-second check.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2));
        LinkProperties active = new LinkProperties();
        ReflectionHelpers.callInstanceMethod(active, "setUsePrivateDns",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        connectivity.setLinkProperties(vpn, active);
        callback.onLinkPropertiesChanged(vpn, active);
        assertEquals(ServiceSinkhole.PRIVATE_DNS_WARNING_ACTIVE, warning);

        connectivity.setLinkProperties(vpn, new LinkProperties());
        callback.onLinkPropertiesChanged(vpn, new LinkProperties());
        assertEquals(ServiceSinkhole.PRIVATE_DNS_WARNING_NONE, warning);

        connectivity.removeNetwork(vpn);
        callback.onLost(vpn);
        assertEquals(4, updates);
        assertEquals(ServiceSinkhole.PRIVATE_DNS_WARNING_NONE, warning);
    }

    @Test
    public void disablingUnregistersAndIgnoresQueuedEventsEvenAfterRestart() {
        monitor.setEnabled(true);
        monitor.setEnabled(true);
        assertEquals(1, connectivity.getNetworkCallbacks().size());
        ConnectivityManager.NetworkCallback old = connectivity.getNetworkCallbacks().iterator().next();
        monitor.setEnabled(false);
        monitor.setEnabled(false);
        assertTrue(connectivity.getNetworkCallbacks().isEmpty());
        old.onLinkPropertiesChanged(vpn, new LinkProperties());
        old.onLost(vpn);
        assertEquals(0, updates);

        monitor.setEnabled(true);
        old.onLinkPropertiesChanged(vpn, new LinkProperties());
        assertEquals(0, updates);
        connectivity.getNetworkCallbacks().iterator().next()
                .onLinkPropertiesChanged(vpn, new LinkProperties());
        assertEquals(1, updates);
    }

    @Test
    @Config(sdk = 27)
    public void androidBeforePrivateDnsDoesNotRegister() {
        monitor.setEnabled(true);
        assertTrue(connectivity.getNetworkCallbacks().isEmpty());
    }

    @Implements(ConnectivityManager.class)
    public static class RecordingConnectivityManager extends ShadowConnectivityManager {
        NetworkRequest request;

        @Implementation
        protected void registerNetworkCallback(NetworkRequest request,
                                               ConnectivityManager.NetworkCallback callback) {
            this.request = request;
            super.registerNetworkCallback(request, callback);
        }
    }
}
