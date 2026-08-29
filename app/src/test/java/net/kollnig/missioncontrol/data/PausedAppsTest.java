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

@RunWith(RobolectricTestRunner.class)
public class PausedAppsTest {
    private Context context;
    private SharedPreferences paused;
    private SharedPreferences apply;
    private SharedPreferences trackerProtect;
    private SharedPreferences essentialOnly;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        paused = context.getSharedPreferences(PausedApps.PREFS_NAME, Context.MODE_PRIVATE);
        apply = context.getSharedPreferences("apply", Context.MODE_PRIVATE);
        trackerProtect = context.getSharedPreferences("tracker_protect", Context.MODE_PRIVATE);
        essentialOnly = context.getSharedPreferences("tracker_essential", Context.MODE_PRIVATE);
        paused.edit().clear().commit();
        apply.edit().clear().commit();
        trackerProtect.edit().clear().commit();
        essentialOnly.edit().clear().commit();
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
    public void essentialOnlyAppCanBePausedAndSweepRestoresItsState() {
        String packageName = "com.example.app";
        apply.edit().putBoolean(packageName, true).commit();
        trackerProtect.edit().putBoolean(packageName, true).commit();
        essentialOnly.edit().putBoolean(packageName, true).commit();

        PausedApps.pause(context, packageName, 0, 600_000L);
        assertTrue(PausedApps.isPaused(context, packageName));
        assertFalse(apply.getBoolean(packageName, true));

        paused.edit().putString(packageName, "1|1").commit();
        PausedApps.sweep(context);

        assertTrue(apply.getBoolean(packageName, false));
        assertEquals(AppProtectionState.ESSENTIAL_ONLY,
                AppProtectionState.resolve(apply.getBoolean(packageName, true),
                        trackerProtect.getBoolean(packageName, false), false,
                        essentialOnly.getBoolean(packageName, false)));
    }

    @Test
    public void selectingProtectedAfterBypassClearsEssentialOnlyFlag() {
        String packageName = "com.example.app";
        apply.edit().putBoolean(packageName, false).commit();
        trackerProtect.edit().putBoolean(packageName, true).commit();
        essentialOnly.edit().putBoolean(packageName, true).commit();

        assertEquals(AppProtectionState.BYPASSED,
                AppProtectionState.resolve(false, true, false, true));
        AppProtectionWriter.applyManual(context, packageName, 0,
                AppProtectionState.of(AppProtectionState.PROTECTED));

        assertTrue(apply.getBoolean(packageName, false));
        assertFalse(essentialOnly.getBoolean(packageName, true));
        assertEquals(AppProtectionState.PROTECTED,
                AppProtectionState.resolve(true, true, false,
                        essentialOnly.getBoolean(packageName, false)));
    }
}
