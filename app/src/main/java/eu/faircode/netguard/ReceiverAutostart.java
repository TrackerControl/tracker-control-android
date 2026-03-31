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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.util.Map;

public class ReceiverAutostart extends BroadcastReceiver {
    private static final String TAG = "TrackerControl.Receiver";

    @Override
    public void onReceive(final Context context, Intent intent) {
        Log.i(TAG, "Received " + intent);
        Util.logExtras(intent);

        String action = (intent == null ? null : intent.getAction());
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))
            try {
                // Upgrade settings
                upgrade(true, context);

                // Start service
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                if (prefs.getBoolean("enabled", false))
                    ServiceSinkhole.start("receiver", context);
                else if (prefs.getBoolean("show_stats", false))
                    ServiceSinkhole.run("receiver", context);

                if (Util.isInteractive(context))
                    ServiceSinkhole.reloadStats("receiver", context);
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }
    }

    public static void upgrade(boolean initialized, Context context) {
        synchronized (context.getApplicationContext()) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            int oldVersion = prefs.getInt("version", -1);
            int newVersion = Util.getSelfVersionCode(context);
            if (oldVersion == newVersion)
                return;
            Log.i(TAG, "Upgrading from version " + oldVersion + " to " + newVersion);

            SharedPreferences.Editor editor = prefs.edit();

            if (initialized) {
                if (oldVersion < 38) {
                    Log.i(TAG, "Converting screen wifi/mobile");
                    editor.putBoolean("screen_wifi", prefs.getBoolean("unused", false));
                    editor.putBoolean("screen_other", prefs.getBoolean("unused", false));
                    editor.remove("unused");

                    SharedPreferences unused = context.getSharedPreferences("unused", Context.MODE_PRIVATE);
                    SharedPreferences screen_wifi = context.getSharedPreferences("screen_wifi", Context.MODE_PRIVATE);
                    SharedPreferences screen_other = context.getSharedPreferences("screen_other", Context.MODE_PRIVATE);

                    Map<String, ?> punused = unused.getAll();
                    SharedPreferences.Editor edit_screen_wifi = screen_wifi.edit();
                    SharedPreferences.Editor edit_screen_other = screen_other.edit();
                    for (String key : punused.keySet()) {
                        edit_screen_wifi.putBoolean(key, (Boolean) punused.get(key));
                        edit_screen_other.putBoolean(key, (Boolean) punused.get(key));
                    }
                    edit_screen_wifi.apply();
                    edit_screen_other.apply();

                } else if (oldVersion <= 2017032112)
                    editor.remove("ip6");

                // Migrate beta builds that had vpn_exclude and repurposed apply
                // Step 1: current apply values were used for tracker protection → move to tracker_protect
                // Step 2: vpn_exclude=true → restore apply=false (VPN exclusion)
                SharedPreferences vpn_exclude = context.getSharedPreferences("vpn_exclude", Context.MODE_PRIVATE);
                Map<String, ?> allVpnExclude = vpn_exclude.getAll();
                if (!allVpnExclude.isEmpty()) {
                    Log.i(TAG, "Migrating beta vpn_exclude/apply to new scheme");
                    SharedPreferences apply = context.getSharedPreferences("apply", Context.MODE_PRIVATE);
                    SharedPreferences tracker_protect = context.getSharedPreferences("tracker_protect", Context.MODE_PRIVATE);

                    // Step 1: move current apply values to tracker_protect and reset apply
                    // (beta repurposed apply for tracker protection, so copy those values out)
                    SharedPreferences.Editor tp_editor = tracker_protect.edit();
                    SharedPreferences.Editor apply_editor = apply.edit();
                    for (Map.Entry<String, ?> entry : apply.getAll().entrySet()) {
                        if (entry.getValue() instanceof Boolean) {
                            tp_editor.putBoolean(entry.getKey(), (Boolean) entry.getValue());
                            apply_editor.putBoolean(entry.getKey(), true); // reset to default
                        }
                    }
                    tp_editor.apply();

                    // Step 2: vpn_exclude=true → apply=false (VPN exclusion)
                    for (Map.Entry<String, ?> entry : allVpnExclude.entrySet()) {
                        if (entry.getValue() instanceof Boolean && (Boolean) entry.getValue()) {
                            Log.i(TAG, "Setting apply=false for " + entry.getKey());
                            apply_editor.putBoolean(entry.getKey(), false);
                        }
                    }
                    apply_editor.apply();
                    vpn_exclude.edit().clear().apply();
                }

                if (oldVersion < 2026010401) {
                    // Migrate manage_system to include_system_vpn
                    // Users who had manage_system enabled should also have include_system_vpn
                    // enabled
                    if (prefs.getBoolean("manage_system", false)) {
                        Log.i(TAG, "Migrating manage_system=true to include_system_vpn=true");
                        editor.putBoolean("include_system_vpn", true);
                    }
                }

            } else {
                Log.i(TAG, "Initializing sdk=" + Build.VERSION.SDK_INT);
                editor.putBoolean("filter_udp", true);
                editor.putBoolean("whitelist_wifi", false);
                editor.putBoolean("whitelist_other", false);
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP)
                    editor.putBoolean("filter", true); // Optional
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
                editor.putBoolean("filter", true); // Mandatory

            if (!Util.canFilter(context)) {
                editor.putBoolean("log_app", false);
                editor.putBoolean("filter", false);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                editor.remove("show_top");
                if ("data".equals(prefs.getString("sort", "trackers_week")))
                    editor.remove("sort");
            }

            if (Util.isPlayStoreInstall(context)) {
                editor.remove("update_check");
                editor.remove("use_hosts");
                editor.remove("hosts_url_new");
            }

            if (!Util.isDebuggable(context))
                editor.remove("loglevel");

            editor.putInt("version", newVersion);
            editor.apply();
        }
    }
}
