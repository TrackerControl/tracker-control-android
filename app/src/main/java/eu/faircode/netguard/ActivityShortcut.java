/*
 * This file is from NetGuard.
 *
 * NetGuard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NetGuard is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2015–2020 by Marcel Bokhorst (M66B), Konrad
 * Kollnig (University of Oxford)
 */

package eu.faircode.netguard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import androidx.preference.PreferenceManager;

public class ActivityShortcut extends Activity {
    private static final String TAG = "TrackerControl.Shortcut";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent != null) {
            String action = intent.getStringExtra("action");
            Log.i(TAG, "Shortcut action: " + action);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean currentlyEnabled = prefs.getBoolean("enabled", false);

            if ("enable".equals(action)) {
                if (!currentlyEnabled) {
                    prefs.edit().putBoolean("enabled", true).apply();
                    ServiceSinkhole.start("shortcut", this);
                    Log.i(TAG, "TrackerControl enabled via shortcut");
                } else {
                    Log.i(TAG, "TrackerControl already enabled");
                }
            } else if ("disable".equals(action)) {
                if (currentlyEnabled) {
                    prefs.edit().putBoolean("enabled", false).apply();
                    ServiceSinkhole.stop("shortcut", this, false);
                    Log.i(TAG, "TrackerControl disabled via shortcut");
                } else {
                    Log.i(TAG, "TrackerControl already disabled");
                }
            }
        }

        finish();
    }
}
