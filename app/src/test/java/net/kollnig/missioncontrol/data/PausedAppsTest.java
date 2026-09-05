package net.kollnig.missioncontrol.data;

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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
public class PausedAppsTest {
    private Context context;
    private SharedPreferences paused;
    private SharedPreferences apply;
    private SharedPreferences trackerProtect;
    private SharedPreferences minimalOnlyPrefs;
    private InternetBlocklist internetBlocklist;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        paused = context.getSharedPreferences(PausedApps.PREFS_NAME, Context.MODE_PRIVATE);
        apply = context.getSharedPreferences("apply", Context.MODE_PRIVATE);
        trackerProtect = context.getSharedPreferences("tracker_protect", Context.MODE_PRIVATE);
        minimalOnlyPrefs = context.getSharedPreferences("tracker_essential", Context.MODE_PRIVATE);
        internetBlocklist = InternetBlocklist.getInstance(null);
        paused.edit().clear().commit();
        apply.edit().clear().commit();
        trackerProtect.edit().clear().commit();
        minimalOnlyPrefs.edit().clear().commit();
        internetBlocklist.clear();
    }

    @Test
    public void remainingTimeReadsExpirySnapshot() {
        long expiry = System.currentTimeMillis() + 180_000L;
        paused.edit().putString("com.example.app", expiry + "|1").commit();

        assertTrue(PausedApps.isPaused(context, "com.example.app"));
        assertTrue(PausedApps.getRemainingMinutes(context, "com.example.app") >= 2);
    }

    @Test
    public void expiredFalseApplyIsRestoredAndSnapshotRemoved() {
        apply.edit().putBoolean("com.example.app", false).commit();
        paused.edit().putString("com.example.app", "1|1").commit();

        PausedApps.sweep(context);

        assertTrue(apply.getBoolean("com.example.app", false));
        assertFalse(paused.contains("com.example.app"));
    }

    @Test
    public void expiredManualApplyDropsSnapshot() {
        apply.edit().putBoolean("com.example.app", true).commit();
        paused.edit().putString("com.example.app", "1|0").commit();

        PausedApps.sweep(context);

        assertTrue(apply.getBoolean("com.example.app", false));
        assertFalse(paused.contains("com.example.app"));
    }

    @Test
    public void manualCancelDropsSnapshotWithoutChangingApply() {
        apply.edit().putBoolean("com.example.app", false).commit();
        paused.edit().putString("com.example.app", "9999999999999|1").commit();

        PausedApps.cancel(context, "com.example.app");

        assertFalse(apply.getBoolean("com.example.app", true));
        assertFalse(paused.contains("com.example.app"));
    }

    @Test
    public void manualWriterCancelsPauseBeforeApplyingChoice() {
        apply.edit().putBoolean("com.example.app", true).commit();
        paused.edit().putString("com.example.app", "9999999999999|1").commit();

        AppProtectionWriter.applyManual(context, "com.example.app", 0,
                AppProtectionState.of(AppProtectionState.BYPASSED));

        assertFalse(apply.getBoolean("com.example.app", true));
        assertFalse(paused.contains("com.example.app"));
    }

    @Test
    public void scheduledWriterLeavesPauseSnapshotInPlace() {
        paused.edit().putString("com.example.app", "9999999999999|1").commit();

        AppProtectionWriter.applyScheduled(context, "com.example.app", 0, false);

        assertTrue(paused.contains("com.example.app"));
    }

    @Test
    public void protectedToNoInternetRequestsReload() {
        String packageName = "com.example.app";
        int uid = 10001;
        apply.edit().putBoolean(packageName, true).commit();
        trackerProtect.edit().putBoolean(packageName, true).commit();

        assertTrue(AppProtectionWriter.shouldReloadAfterChange(
                true, true, null, true, Boolean.TRUE, false));

        AppProtectionWriter.applyManual(context, packageName, uid,
                AppProtectionState.of(AppProtectionState.NO_INTERNET));

        assertTrue(internetBlocklist.blockedInternet(uid));
    }

    @Test
    public void noOpInternetWriteDoesNotReloadService() {
        String packageName = "com.example.app";
        int uid = 10002;
        apply.edit().putBoolean(packageName, true).commit();
        trackerProtect.edit().putBoolean(packageName, true).commit();
        internetBlocklist.block(context, uid);

        assertFalse(AppProtectionWriter.shouldReloadAfterChange(
                true, true, null, true, Boolean.TRUE, true));

        AppProtectionWriter.applyManual(context, packageName, uid,
                AppProtectionState.of(AppProtectionState.NO_INTERNET));

        assertTrue(internetBlocklist.blockedInternet(uid));
    }

    @Test
    public void nullInternetWriteLeavesBlockAndDoesNotReloadService() {
        String packageName = "com.example.app";
        int uid = 10003;
        apply.edit().putBoolean(packageName, true).commit();
        trackerProtect.edit().putBoolean(packageName, true).commit();
        internetBlocklist.block(context, uid);

        assertFalse(AppProtectionWriter.shouldReloadAfterChange(
                true, true, null, true, null, true));

        AppProtectionWriter.applyScheduled(context, packageName, uid, true);

        assertTrue(internetBlocklist.blockedInternet(uid));
    }

    @Test
    public void configuredPauseDurationUsesExistingPreference() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString("pause", "10").commit();

        assertEquals(10, PausedApps.getConfiguredDurationMinutes(context));
    }

    @Test
    public void exportViewRestoresPausedApplyValue() {
        apply.edit().putBoolean("com.example.app", false).commit();
        paused.edit().putString("com.example.app",
                (System.currentTimeMillis() + 600_000L) + "|1").commit();

        assertEquals(Boolean.TRUE,
                PausedApps.applyValuesWithoutPauses(context).get("com.example.app"));
    }

    @Test
    public void exportViewKeepsAValueChangedDuringThePause() {
        // Another writer re-included the app while it was paused. The snapshot
        // is stale, so the live value is the one worth exporting.
        apply.edit().putBoolean("com.example.app", true).commit();
        paused.edit().putString("com.example.app",
                (System.currentTimeMillis() + 600_000L) + "|0").commit();

        assertFalse(PausedApps.applyValuesWithoutPauses(context)
                .containsKey("com.example.app"));
    }

    @Test
    public void exportViewIsEmptyWithoutPauses() {
        apply.edit().putBoolean("com.example.app", false).commit();

        assertTrue(PausedApps.applyValuesWithoutPauses(context).isEmpty());
    }

    @Test
    public void minimalOnlyAppCanBePausedAndSweepRestoresItsState() {
        String packageName = "com.example.app";
        apply.edit().putBoolean(packageName, true).commit();
        trackerProtect.edit().putBoolean(packageName, true).commit();
        minimalOnlyPrefs.edit().putBoolean(packageName, true).commit();

        PausedApps.pause(context, packageName, 0, 600_000L);
        assertTrue(PausedApps.isPaused(context, packageName));
        assertFalse(apply.getBoolean(packageName, true));

        paused.edit().putString(packageName, "1|1").commit();
        PausedApps.sweep(context);

        assertTrue(apply.getBoolean(packageName, false));
        assertEquals(AppProtectionState.MINIMAL_ONLY,
                AppProtectionState.resolve(apply.getBoolean(packageName, true),
                        trackerProtect.getBoolean(packageName, false), false,
                        minimalOnlyPrefs.getBoolean(packageName, false)));
    }

    @Test
    public void selectingProtectedAfterBypassClearsMinimalOnlyFlag() {
        String packageName = "com.example.app";
        apply.edit().putBoolean(packageName, false).commit();
        trackerProtect.edit().putBoolean(packageName, true).commit();
        minimalOnlyPrefs.edit().putBoolean(packageName, true).commit();

        assertEquals(AppProtectionState.BYPASSED,
                AppProtectionState.resolve(false, true, false, true));
        AppProtectionWriter.applyManual(context, packageName, 0,
                AppProtectionState.of(AppProtectionState.PROTECTED));

        assertTrue(apply.getBoolean(packageName, false));
        assertFalse(minimalOnlyPrefs.getBoolean(packageName, true));
        assertEquals(AppProtectionState.PROTECTED,
                AppProtectionState.resolve(true, true, false,
                        minimalOnlyPrefs.getBoolean(packageName, false)));
    }

    /**
     * A pause write must not queue behind the application-context monitor, which
     * {@code Rule.getRules} holds for the whole of a rule rebuild on the VPN
     * service's command thread. It used to, and a tap that ended a pause waited
     * for that rebuild — long enough to ANR.
     */
    @Test
    public void pauseWritesDoNotWaitOnTheRuleCacheLock() throws Exception {
        paused.edit().putString("com.example.app", "9999999999999|1").commit();

        final Object contextLock = context.getApplicationContext();
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (contextLock) {
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
        });
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> write = executor.submit(
                    () -> PausedApps.cancel(context, "com.example.app", 0));
            // A TimeoutException here is the regression: the write is blocked.
            write.get(5, TimeUnit.SECONDS);
            assertFalse(paused.contains("com.example.app"));
        } finally {
            executor.shutdownNow();
            release.countDown();
            holder.join();
        }
    }
}
