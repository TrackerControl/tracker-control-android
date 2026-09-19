/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation.
 *
 * Copyright © 2019–2026 Konrad Kollnig
 */

package eu.faircode.netguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Brings the VPN back after the system killed the service without warning.
 *
 * <p>The work is periodic rather than an alarm on purpose. An alarm that has to
 * fire inside a Doze window must be an allow-while-idle one, which wakes the
 * device; periodic work is deferred to the maintenance window instead, so the
 * check costs no wakeups of its own. The price is latency, which is the right
 * trade for a state the user will otherwise sit in until they next open the
 * app.
 *
 * <p>{@link VpnRestartPolicy} decides whether to act, from the protection
 * setting, the teardown the service last recorded, and whether a tunnel is up.
 */
public class VpnRestartWorker extends Worker {
    private static final String TAG = "TrackerControl.Restart";

    /** Unique name of the periodic check. */
    static final String WORK_RESTART_MONITOR = "VpnRestartMonitor";

    /**
     * Fifteen minutes is WorkManager's shortest period, and the interval only
     * bounds how long a dead VPN stays dead: Doze defers the run to the next
     * maintenance window regardless.
     */
    private static final long PERIOD_MINUTES = 15;

    public VpnRestartWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * Enqueues the check, keeping any existing schedule. WorkManager persists
     * it across process death and restores it after a reboot, which is what
     * makes it a net for a service that cannot report its own death.
     */
    static void schedule(Context context) {
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(VpnRestartWorker.class, PERIOD_MINUTES, TimeUnit.MINUTES)
                        .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_RESTART_MONITOR, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (!VpnRestartPolicy.shouldRestart(
                prefs.getBoolean("enabled", false),
                ServiceSinkhole.wasStoppedCleanly(context),
                ServiceSinkhole.isVpnEstablished()))
            return Result.success();

        Log.w(TAG, "Protection is on without a tunnel, restarting the service");
        ServiceSinkhole.start("restart monitor", context);
        return Result.success();
    }
}
