/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package net.kollnig.missioncontrol.data;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import eu.faircode.netguard.Rule;
import eu.faircode.netguard.Util;

/**
 * Durable snapshots for temporary per-package protection pauses.
 */
public final class PausedApps {
    public static final String PREFS_NAME = "paused_apps";
    public static final String ACTION_REVERT = "net.kollnig.missioncontrol.PAUSE_REVERT";
    public static final int DEFAULT_DURATION_MINUTES = 10;
    public static final long DEFAULT_DURATION_MS = DEFAULT_DURATION_MINUTES * 60_000L;

    private static final String TAG = "TrackerControl.PausedApps";
    private static final int ALARM_REQUEST_CODE = 20260829;

    private PausedApps() {
    }

    private static final class Snapshot {
        final long expiry;
        final boolean previousApply;

        Snapshot(long expiry, boolean previousApply) {
            this.expiry = expiry;
            this.previousApply = previousApply;
        }
    }

    /**
     * Pause the package and every installed package sharing its UID. The
     * snapshot preference is one value per package in the format
     * {@code expiryWallClockMs|prevApply}.
     */
    public static void pause(Context context, String packageName, int uid) {
        pause(context, packageName, uid, getConfiguredDurationMinutes(context) * 60_000L);
    }

    public static void pause(Context context, String packageName, int uid, long durationMs) {
        Context appContext = context.getApplicationContext();
        synchronized (appContext) {
            SharedPreferences paused = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences apply = appContext.getSharedPreferences("apply", Context.MODE_PRIVATE);
            long expiry = System.currentTimeMillis() + Math.max(1L, durationMs);
            List<String> packages = resolvePackages(appContext, packageName, uid);

            SharedPreferences.Editor snapshots = paused.edit();
            List<String> packagesToPause = new ArrayList<>();
            for (String pkg : packages) {
                Snapshot old = readSnapshot(paused.getString(pkg, null));
                boolean previousApply = old == null ? apply.getBoolean(pkg, true) : old.previousApply;
                snapshots.putString(pkg, encode(expiry, previousApply));
                packagesToPause.add(pkg);
            }

            // Persist the durable snapshot before flipping the live routing
            // values. A crash between these commits leaves a safe snapshot
            // which the next sweep will drop if routing was not changed.
            if (!snapshots.commit()) {
                Log.e(TAG, "Cannot persist pause snapshot");
                return;
            }
            AppProtectionWriter.applyScheduled(appContext, packagesToPause, false);
            scheduleAlarm(appContext);
        }
    }

    /**
     * Restore or drop a package's current pause snapshot and its UID siblings.
     * A snapshot is restored only while the package still has apply=false.
     */
    public static void resume(Context context, String packageName, int uid) {
        Context appContext = context.getApplicationContext();
        synchronized (appContext) {
            restorePackages(appContext, resolvePackages(appContext, packageName, uid));
            scheduleAlarm(appContext);
        }
    }

    /** Drop a pause without changing any protection state. */
    public static void cancel(Context context, String packageName, int uid) {
        Context appContext = context.getApplicationContext();
        synchronized (appContext) {
            SharedPreferences paused = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = paused.edit();
            for (String pkg : resolvePackages(appContext, packageName, uid))
                editor.remove(pkg);
            editor.commit();
            scheduleAlarm(appContext);
        }
    }

    /** Drop only the named package's pause snapshot. */
    public static void cancel(Context context, String packageName) {
        if (packageName == null)
            return;
        Context appContext = context.getApplicationContext();
        synchronized (appContext) {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().remove(packageName).commit();
            scheduleAlarm(appContext);
        }
    }

    /**
     * Revert all expired snapshots. This method is called by the explicit
     * alarm receiver and is safe to call during boot as well.
     */
    public static void sweep(Context context) {
        Context appContext = context.getApplicationContext();
        synchronized (appContext) {
            SharedPreferences paused = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            List<String> expired = new ArrayList<>();
            for (Map.Entry<String, ?> entry : paused.getAll().entrySet()) {
                if (!(entry.getValue() instanceof String)) {
                    expired.add(entry.getKey());
                    continue;
                }

                Snapshot snapshot = readSnapshot((String) entry.getValue());
                if (snapshot == null || snapshot.expiry <= now)
                    expired.add(entry.getKey());
            }
            restorePackages(appContext, expired);
            scheduleAlarm(appContext);
        }
    }

    /**
     * Remove a package's durable pause state when Android has fully removed it.
     */
    public static void onPackageRemoved(Context context, String packageName, int uid) {
        onPackageRemoved(context, packageName);
    }

    public static void onPackageRemoved(Context context, String packageName) {
        if (packageName == null)
            return;

        Context appContext = context.getApplicationContext();
        synchronized (appContext) {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().remove(packageName).commit();
            scheduleAlarm(appContext);
        }
    }

    public static boolean isPaused(Context context, String packageName) {
        Snapshot snapshot = getSnapshot(context, packageName);
        return snapshot != null && snapshot.expiry > System.currentTimeMillis();
    }

    public static long getExpiry(Context context, String packageName) {
        Snapshot snapshot = getSnapshot(context, packageName);
        return snapshot == null ? 0L : snapshot.expiry;
    }

    public static int getRemainingMinutes(Context context, String packageName) {
        long expiry = getExpiry(context, packageName);
        long remaining = expiry - System.currentTimeMillis();
        if (remaining <= 0)
            return 0;
        return (int) Math.max(1L, (remaining + 59_999L) / 60_000L);
    }

    public static long remainingMillis(Context context, String packageName) {
        long expiry = getExpiry(context, packageName);
        return Math.max(0L, expiry - System.currentTimeMillis());
    }

    public static int getConfiguredDurationMinutes(Context context) {
        String value = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("pause", Integer.toString(DEFAULT_DURATION_MINUTES));
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            return DEFAULT_DURATION_MINUTES;
        }
    }

    /**
     * Return actual packages sharing the UID, excluding the selected package.
     * This is used for the explanatory UI, where predefined Rule relations
     * must not be described as shared-UID relations.
     */
    public static List<String> getSharedUidPackages(Context context, String packageName, int uid) {
        if (packageName == null)
            return Collections.emptyList();
        String[] packages = Util.getPackagesForUid(context.getPackageManager(), uid);
        if (packages == null)
            return Collections.emptyList();

        List<String> result = new ArrayList<>();
        for (String pkg : packages)
            if (!packageName.equals(pkg))
                result.add(pkg);
        Collections.sort(result);
        return result;
    }

    private static Snapshot getSnapshot(Context context, String packageName) {
        if (packageName == null)
            return null;
        String value = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(packageName, null);
        return readSnapshot(value);
    }

    private static void restorePackages(Context context, List<String> packages) {
        if (packages.isEmpty())
            return;

        SharedPreferences paused = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences apply = context.getSharedPreferences("apply", Context.MODE_PRIVATE);
        Map<String, Snapshot> restore = new HashMap<>();
        List<String> remove = new ArrayList<>();
        for (String pkg : packages) {
            Snapshot snapshot = readSnapshot(paused.getString(pkg, null));
            if (snapshot == null) {
                remove.add(pkg);
                continue;
            }

            // A true apply value means another actor changed this package
            // after the pause. Never overwrite that manual decision.
            if (!apply.getBoolean(pkg, true))
                restore.put(pkg, snapshot);
            else
                remove.add(pkg);
        }

        // Restore the live value before deleting the durable snapshot. If the
        // process is killed between these operations, the next sweep can retry
        // the still-present snapshot rather than losing the previous value.
        for (Map.Entry<String, Snapshot> entry : restore.entrySet())
            AppProtectionWriter.applyScheduled(context, entry.getKey(),
                    0, entry.getValue().previousApply);

        remove.addAll(restore.keySet());
        SharedPreferences.Editor snapshots = paused.edit();
        for (String pkg : remove)
            snapshots.remove(pkg);
        snapshots.commit();
    }

    private static List<String> resolvePackages(Context context, String packageName, int uid) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (packageName == null)
            return new ArrayList<>(result);
        result.add(packageName);

        String[] uidPackages = Util.getPackagesForUid(context.getPackageManager(), uid);
        if (uidPackages != null)
            result.addAll(Arrays.asList(uidPackages));

        try {
            List<Rule> rules = Rule.getRules(true, true, context);
            Map<String, Rule> byPackage = new HashMap<>();
            for (Rule rule : rules)
                byPackage.put(rule.packageName, rule);

            ArrayDeque<String> pending = new ArrayDeque<>(result);
            Set<String> visited = new LinkedHashSet<>();
            while (!pending.isEmpty()) {
                String current = pending.removeFirst();
                if (!visited.add(current))
                    continue;
                Rule rule = byPackage.get(current);
                if (rule == null || rule.related == null || rule.uid != uid)
                    continue;
                for (String related : rule.related) {
                    Rule relatedRule = byPackage.get(related);
                    if (relatedRule != null && relatedRule.uid == uid && result.add(related))
                        pending.addLast(related);
                }
            }
        } catch (Throwable ex) {
            Log.w(TAG, "Cannot resolve Rule.related packages: " + ex);
        }

        return new ArrayList<>(result);
    }

    private static String encode(long expiry, boolean previousApply) {
        return expiry + "|" + (previousApply ? "1" : "0");
    }

    private static Snapshot readSnapshot(String value) {
        if (value == null)
            return null;
        String[] parts = value.split("\\|", -1);
        if (parts.length != 2 || !("0".equals(parts[1]) || "1".equals(parts[1])
                || "true".equals(parts[1]) || "false".equals(parts[1])))
            return null;
        try {
            boolean previousApply = "1".equals(parts[1]) || "true".equals(parts[1]);
            return new Snapshot(Long.parseLong(parts[0]), previousApply);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static void scheduleAlarm(Context context) {
        SharedPreferences paused = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long earliest = Long.MAX_VALUE;
        for (Object value : paused.getAll().values()) {
            if (!(value instanceof String))
                continue;
            Snapshot snapshot = readSnapshot((String) value);
            if (snapshot != null && snapshot.expiry < earliest)
                earliest = snapshot.expiry;
        }

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null)
            return;

        Intent intent = new Intent(context, eu.faircode.netguard.ReceiverPauseRevert.class);
        intent.setAction(ACTION_REVERT);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags);
        alarmManager.cancel(pendingIntent);
        if (earliest != Long.MAX_VALUE)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliest, pendingIntent);
    }

}
