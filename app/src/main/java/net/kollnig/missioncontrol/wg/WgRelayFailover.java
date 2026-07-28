package net.kollnig.missioncontrol.wg;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

/**
 * Moves the active WireGuard profile to a different relay server for the
 * same provider/account/country, reusing the existing key material, when
 * {@link WgEgress} has given up trying to recover the current relay.
 *
 * <p>Mullvad and IVPN each expose many relay servers, but a profile bakes in
 * one fixed {@code [Peer]} at creation time (see {@link
 * MullvadProfileGenerator} / {@link IvpnProfileGenerator}). If that specific
 * server goes down, re-resolving and restarting against the same endpoint
 * forever never recovers — this picks a fresh relay instead.
 */
public class WgRelayFailover {
    private static final String TAG = "TrackerControl.WgFailover";

    private WgRelayFailover() {
    }

    /**
     * Performs blocking network I/O (a relay-list fetch); call off the main
     * thread. Returns true only if the active profile's config was actually
     * rewritten to a different server.
     */
    public static boolean attemptFailover(Context context) {
        WgProfileManager manager = new WgProfileManager(context);
        WgProfileManager.Profile active = manager.getActiveProfile();
        if (active == null || TextUtils.isEmpty(active.provider) || TextUtils.isEmpty(active.config)) {
            // Self-hosted or manually imported configs have a single,
            // user-chosen server — there is nothing to fail over to.
            return false;
        }

        try {
            String newConfig;
            if ("mullvad".equals(active.provider)) {
                MullvadProfileGenerator.GeneratedProfile generated = new MullvadProfileGenerator()
                        .generate(active.account, active.countryCode, active.config);
                newConfig = generated.config;
            } else if ("ivpn".equals(active.provider)) {
                IvpnProfileGenerator.GeneratedProfile generated = new IvpnProfileGenerator()
                        .generate(active.account, active.countryCode,
                                manager.getIvpnSession(active.account));
                newConfig = generated.config;
            } else {
                return false;
            }

            if (TextUtils.isEmpty(newConfig) || newConfig.equals(active.config))
                return false;

            manager.updateActiveProfileConfig(newConfig);
            Log.w(TAG, "Switched " + active.provider + " profile to a different relay after repeated failures");
            return true;
        } catch (Throwable ex) {
            Log.w(TAG, "Relay failover for " + active.provider + " failed: " + ex.getMessage());
            return false;
        }
    }
}
