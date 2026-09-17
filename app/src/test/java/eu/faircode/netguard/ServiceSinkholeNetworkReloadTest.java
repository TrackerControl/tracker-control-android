package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.wg.WgEgress;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
public class ServiceSinkholeNetworkReloadTest {
    // Invoke the private debounce/command seams without onCreate(), which starts
    // the native VPN. Reflection keeps these test seams out of the service API.
    private Context context;
    private SharedPreferences prefs;
    private ShadowApplication shadowApplication;

    private static class TestService extends ServiceSinkhole {
        void attach(Context context) {
            attachBaseContext(context);
        }
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        shadowApplication = Shadows.shadowOf(RuntimeEnvironment.getApplication());
        shadowApplication.clearStartedServices();
    }

    @Test
    public void networkChangeSurvivesPrivateDnsReasonInDebouncedBurst() throws Exception {
        prefs.edit().putBoolean("enabled", true).commit();
        TestService service = newService();

        invokeReloadAfterNetworkChange(service, NetworkReloadPolicy.REASON_NETWORK_CHANGED);
        invokeReloadAfterNetworkChange(service, NetworkReloadPolicy.REASON_PRIVATE_DNS_CHANGED);
        idleDebounce();

        List<Intent> intents = shadowApplication.getAllStartedServices();
        assertEquals(1, intents.size());
        Intent dispatched = shadowApplication.getNextStartedService();
        assertTrue(dispatched.getBooleanExtra(networkChangedExtra(), false));
        assertNull(shadowApplication.getNextStartedService());
    }

    @Test
    public void privateDnsChangeDoesNotMarkWireGuardNetworkChanged() throws Exception {
        prefs.edit().putBoolean("enabled", true).commit();
        invokeReloadAfterNetworkChange(newService(), NetworkReloadPolicy.REASON_PRIVATE_DNS_CHANGED);
        idleDebounce();

        List<Intent> intents = shadowApplication.getAllStartedServices();
        assertEquals(1, intents.size());
        Intent dispatched = shadowApplication.getNextStartedService();
        assertFalse(dispatched.getBooleanExtra(networkChangedExtra(), false));
        assertNull(shadowApplication.getNextStartedService());
    }

    @Test
    public void disabledReloadDoesNotDispatchOrMarkWireGuard() throws Exception {
        boolean oldPending = forceRestartPending();
        try {
            setForceRestartPending(false);
            invokeReloadAfterNetworkChange(newService(), NetworkReloadPolicy.REASON_NETWORK_CHANGED);
            idleDebounce();

            assertTrue(shadowApplication.getAllStartedServices().isEmpty());
            assertFalse(forceRestartPending());
        } finally {
            setForceRestartPending(oldPending);
        }
    }

    @Test
    public void droppedReloadDoesNotMarkWireGuard() throws Exception {
        TestService service = newService();
        Field foreground = field(ServiceSinkhole.class, "user_foreground");
        boolean oldForeground = foreground.getBoolean(service);
        boolean oldPending = forceRestartPending();
        try {
            foreground.setBoolean(service, false);
            setForceRestartPending(false);

            Class<?> handlerClass = Class.forName(
                    "eu.faircode.netguard.ServiceSinkhole$CommandHandler");
            Constructor<?> constructor = handlerClass.getDeclaredConstructor(
                    ServiceSinkhole.class, Looper.class);
            constructor.setAccessible(true);
            Object handler = constructor.newInstance(service, Looper.getMainLooper());
            Method handleIntent = handlerClass.getDeclaredMethod("handleIntent", Intent.class);
            handleIntent.setAccessible(true);
            Intent intent = new Intent(context, ServiceSinkhole.class);
            intent.putExtra(ServiceSinkhole.EXTRA_COMMAND, ServiceSinkhole.Command.reload);
            intent.putExtra(networkChangedExtra(), true);
            handleIntent.invoke(handler, intent);

            assertFalse(forceRestartPending());
            assertTrue(shadowApplication.getAllStartedServices().isEmpty());
        } finally {
            foreground.setBoolean(service, oldForeground);
            setForceRestartPending(oldPending);
        }
    }

    private TestService newService() {
        TestService service = new TestService();
        service.attach(context);
        return service;
    }

    private static void invokeReloadAfterNetworkChange(ServiceSinkhole service, String reason)
            throws Exception {
        Method reload = ServiceSinkhole.class.getDeclaredMethod(
                "reloadAfterNetworkChange", String.class);
        reload.setAccessible(true);
        reload.invoke(service, reason);
    }

    private static void idleDebounce() {
        ShadowLooper shadowLooper = Shadows.shadowOf(Looper.getMainLooper());
        shadowLooper.idleFor(1600, TimeUnit.MILLISECONDS);
    }

    private static String networkChangedExtra() throws Exception {
        Field field = field(ServiceSinkhole.class, "EXTRA_WG_NETWORK_CHANGED");
        return (String) field.get(null);
    }

    private static boolean forceRestartPending() throws Exception {
        return field(WgEgress.class, "forceRestartPending").getBoolean(WgEgress.INSTANCE);
    }

    private static void setForceRestartPending(boolean value) throws Exception {
        field(WgEgress.class, "forceRestartPending").setBoolean(WgEgress.INSTANCE, value);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
