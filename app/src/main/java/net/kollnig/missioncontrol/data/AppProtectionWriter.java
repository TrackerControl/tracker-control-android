/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package net.kollnig.missioncontrol.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.faircode.netguard.Rule;
import eu.faircode.netguard.ServiceSinkhole;

/**
 * Writes the four stores behind the per-app protection state.
 */
public final class AppProtectionWriter {
    /**
     * Where a UI-triggered protection write belongs.
     *
     * Every write here commits a snapshot to disk, asks the package manager for
     * the UID's packages and reschedules the pause alarm, and it contends with
     * the VPN service's rule rebuild — enough main-thread work to drop frames
     * and, in the worst case, to ANR on the tap that started it. One thread, so
     * two quick taps still land in the order they were made.
     */
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "TrackerControl protection"));

    private AppProtectionWriter() {
    }

    /** Run a protection write off the calling thread, after any already queued. */
    public static void post(Runnable write) {
        WRITER.execute(write);
    }

    /**
     * Apply a user-selected state. A manual selection always ends a temporary
     * pause first; the selected state is the only state this write changes.
     */
    public static void applyManual(Context context, String packageName, int uid,
            AppProtectionState.Change change) {
        if (change == null)
            return;
        Context appContext = context.getApplicationContext();
        PausedApps.cancel(appContext, packageName, uid);
        apply(appContext, packageName, uid, change);
    }

    /**
     * Apply a scheduled raw apply value. This path deliberately never cancels
     * a pause snapshot, because it is used while sweeping that snapshot.
     */
    public static void applyScheduled(Context context, String packageName, int uid,
            boolean applyValue) {
        Context appContext = context.getApplicationContext();
        apply(appContext, packageName, uid, applyValue, null, null, null);
    }

    /** Apply one scheduled apply value to a UID's packages in one preference edit. */
    static void applyScheduled(Context context, List<String> packageNames, boolean applyValue) {
        Context appContext = context.getApplicationContext();
        SharedPreferences apply = appContext.getSharedPreferences("apply", Context.MODE_PRIVATE);
        boolean changed = false;
        SharedPreferences.Editor editor = apply.edit();
        for (String packageName : packageNames) {
            changed |= apply.getBoolean(packageName, true) != applyValue;
            editor.putBoolean(packageName, applyValue);
        }
        editor.apply();
        if (changed)
            reload(appContext);
    }

    /**
     * Apply a scheduled complete state without cancelling pause snapshots.
     */
    public static void applyScheduled(Context context, String packageName, int uid,
            AppProtectionState.Change change) {
        if (change != null)
            apply(context.getApplicationContext(), packageName, uid, change);
    }

    private static void apply(Context context, String packageName, int uid,
            AppProtectionState.Change change) {
        apply(context, packageName, uid, change.apply, change.trackerProtect,
                change.internetBlocked, change.minimalOnly);
    }

    private static void apply(Context context, String packageName, int uid,
            boolean applyValue, Boolean trackerProtectValue, Boolean internetBlocked,
            Boolean minimalOnlyValue) {
        SharedPreferences apply = context.getSharedPreferences("apply", Context.MODE_PRIVATE);
        SharedPreferences trackerProtect = context.getSharedPreferences("tracker_protect", Context.MODE_PRIVATE);
        SharedPreferences minimalOnlyPrefs = context.getSharedPreferences("tracker_essential", Context.MODE_PRIVATE);

        boolean applyBefore = apply.getBoolean(packageName, true);
        boolean protectBefore = BlockingMode.isTrackerProtectionEnabled(context, trackerProtect, packageName);

        apply.edit().putBoolean(packageName, applyValue).apply();
        if (applyValue)
            BlockingMode.clearAutoExcludedApp(context, packageName);

        if (trackerProtectValue != null)
            trackerProtect.edit().putBoolean(packageName, trackerProtectValue).apply();
        if (minimalOnlyValue != null)
            minimalOnlyPrefs.edit().putBoolean(packageName, minimalOnlyValue).apply();

        InternetBlocklist.getInstance(context).apply(context, uid, internetBlocked);

        // The service reads this per-package flag through the live preferences
        // handle, so changing it alone needs no service reload.
        boolean needsReload = applyValue != applyBefore
                || (trackerProtectValue != null && trackerProtectValue != protectBefore);
        if (!needsReload)
            return;

        AsyncTask.execute(() -> {
            Rule.clearCache(context);
            ServiceSinkhole.reload("app protection changed", context, false);
        });
    }

    private static void reload(Context context) {
        AsyncTask.execute(() -> {
            Rule.clearCache(context);
            ServiceSinkhole.reload("app protection changed", context, false);
        });
    }
}
