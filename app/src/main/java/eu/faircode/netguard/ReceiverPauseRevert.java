/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package eu.faircode.netguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.util.Log;

import net.kollnig.missioncontrol.data.PausedApps;

/** Receives the single alarm used to revert expired per-app pauses. */
public class ReceiverPauseRevert extends BroadcastReceiver {
    private static final String TAG = "TrackerControl.Receiver";

    @Override
    public void onReceive(final Context context, Intent intent) {
        Log.i(TAG, "Received " + intent);
        Util.logExtras(intent);

        final BroadcastReceiver.PendingResult pendingResult = goAsync();
        AsyncTask.execute(() -> {
            try {
                PausedApps.sweep(context);
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            } finally {
                pendingResult.finish();
            }
        });
    }
}
