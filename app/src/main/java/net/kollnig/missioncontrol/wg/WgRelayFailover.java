package net.kollnig.missioncontrol.wg;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // Both generators stamp the chosen relay's hostname into the generated
    // config as a comment (see MullvadProfileGenerator#buildConfig /
    // IvpnProfileGenerator#buildConfig) — that comment is the only place the
    // hostname survives once the config is saved, so it's how a repeat
    // failover recognizes and excludes the relay that just failed.
    private static final Pattern MULLVAD_RELAY_PATTERN =
            Pattern.compile("(?m)^#\\s*Mullvad relay\\s*=\\s*(\\S+)");
    private static final Pattern IVPN_RELAY_PATTERN =
            Pattern.compile("(?m)^#\\s*IVPN relay\\s*=\\s*(\\S+)");

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
            String newCountryCode;
            if ("mullvad".equals(active.provider)) {
                String excludeHostname = currentRelayHostname(active.config, MULLVAD_RELAY_PATTERN);
                MullvadProfileGenerator.GeneratedProfile generated = new MullvadProfileGenerator()
                        .generate(active.account, active.countryCode, active.config, excludeHostname);
                newConfig = generated.config;
                newCountryCode = generated.countryCode;
            } else if ("ivpn".equals(active.provider)) {
                String excludeHostname = currentRelayHostname(active.config, IVPN_RELAY_PATTERN);
                WgProfileManager.IvpnSession session = manager.getIvpnSession(active.account);
                IvpnProfileGenerator.GeneratedProfile generated = new IvpnProfileGenerator()
                        .generate(active.account, active.countryCode, session, "", "", excludeHostname);
                // generate() may have had to mint a fresh session (no reusable
                // one, or the reusable one was stale); persist it regardless of
                // whether the switch below goes through, or the device/session
                // this config now authenticates as is lost and never saved.
                if (generated.session != null)
                    manager.saveIvpnSession(generated.session);
                newConfig = generated.config;
                newCountryCode = generated.countryCode;
            } else {
                return false;
            }

            if (TextUtils.isEmpty(newConfig) || newConfig.equals(active.config))
                return false;

            // Both generators try the profile's own country first and only
            // widen the pool if that country currently has no relays. A
            // mismatch here means a widened pick — surfacing that silently as
            // a "still connected" recovery would relocate the user's exit
            // country without telling them, which defeats the point of
            // choosing a country in the first place.
            if (!TextUtils.isEmpty(active.countryCode) && !active.countryCode.equals(newCountryCode)) {
                Log.w(TAG, "Relay failover for " + active.provider + " found no relay left in "
                        + active.countryCode + "; not silently switching country to " + newCountryCode);
                return false;
            }

            if (!manager.updateProfileConfigIfActive(active.id, newConfig)) {
                Log.w(TAG, "Relay failover for " + active.provider
                        + " found a new relay, but the active profile changed meanwhile; discarding");
                return false;
            }
            Log.w(TAG, "Switched " + active.provider + " profile to a different relay after repeated failures");
            return true;
        } catch (MullvadProfileGenerator.ApiRejectedException | IvpnProfileGenerator.ApiRejectedException ex) {
            Log.w(TAG, "Relay failover for " + active.provider + " was rejected by the provider API: "
                    + ex.getMessage());
            return false;
        } catch (IvpnProfileGenerator.CaptchaRequiredException ex) {
            Log.w(TAG, "Relay failover for " + active.provider
                    + " requires solving a captcha; cannot proceed automatically");
            return false;
        } catch (Throwable ex) {
            Log.w(TAG, "Relay failover for " + active.provider + " failed: " + ex.getMessage());
            return false;
        }
    }

    private static String currentRelayHostname(String config, Pattern pattern) {
        if (TextUtils.isEmpty(config))
            return null;
        Matcher matcher = pattern.matcher(config);
        return matcher.find() ? matcher.group(1) : null;
    }
}
