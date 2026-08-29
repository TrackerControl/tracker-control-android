/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 */

package net.kollnig.missioncontrol.details;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;

import net.kollnig.missioncontrol.data.RemoteRoutingLogic;
import net.kollnig.missioncontrol.wg.WgConfig;
import net.kollnig.missioncontrol.wg.WgConfigParser;
import net.kollnig.missioncontrol.wg.WgPeer;

import java.util.ArrayList;
import java.util.List;

import eu.faircode.netguard.Rule;

/**
 * Reads the per-app remote-VPN routing state. Shared by the summary row on the
 * Trackers tab and the control on {@link ProtectionActivity}, so both answer
 * "does this app go through the tunnel?" the same way.
 */
final class RemoteRoutingHelper {
    private static final String TAG = RemoteRoutingHelper.class.getSimpleName();

    private RemoteRoutingHelper() {
    }

    /**
     * The per-app override, or null when the app follows the global mode.
     */
    @Nullable
    static Boolean getRouteOverride(Context context, String packageName) {
        SharedPreferences wgRoute = context.getSharedPreferences(Rule.PREF_WG_ROUTE,
                Context.MODE_PRIVATE);
        return wgRoute.contains(packageName) ? wgRoute.getBoolean(packageName, true) : null;
    }

    /**
     * Whether the configured tunnel is a full tunnel. Per-app routing needs one,
     * because routes are shared by every app.
     */
    static boolean hasDefaultRoutes(Context context, SharedPreferences prefs) {
        try {
            WgConfig config = WgConfigParser.INSTANCE.parse(prefs.getString("wg_config", ""));
            List<String> allowedIps = new ArrayList<>();
            for (WgPeer peer : config.getPeers())
                allowedIps.addAll(peer.getAllowedIPs());
            return RemoteRoutingLogic.hasDefaultRoutes(allowedIps, prefs.getBoolean("ip6", true));
        } catch (Throwable ex) {
            Log.w(TAG, "Cannot read AllowedIPs, hiding per-app routing: " + ex);
            return false;
        }
    }
}
