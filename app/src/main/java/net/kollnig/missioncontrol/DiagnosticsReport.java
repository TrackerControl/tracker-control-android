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
 *
 * Copyright © 2026 Konrad Kollnig (University of Oxford)
 */

package net.kollnig.missioncontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.data.BlockingMode;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import eu.faircode.netguard.ServiceSinkhole;
import eu.faircode.netguard.Util;

/**
 * On-demand, privacy-preserving self-check.
 * <p>
 * This is a <b>diagnostic</b>, not configuration. It takes a one-shot snapshot of
 * the device/app state that explains the two most common (and un-triageable) bug
 * reports — "apps don't connect" and "TrackerControl drains my battery" — and
 * renders a plain-language verdict plus a paste-able report the user can choose to
 * share. Nothing runs in the background; nothing leaves the device unless the user
 * explicitly copies/shares the report.
 * <p>
 * Low-level state is read from the NetGuard-inherited {@link Util} helpers (network
 * info, connectivity, Private DNS, battery/data-saver) — the same helpers that once
 * backed NetGuard's removed "Technical information" settings screen — combined with
 * the TrackerControl-specific signals (blocking mode, Secure DNS, remote egress,
 * system-app routing, IPv4-only DNS on IPv6-only carriers).
 */
public class DiagnosticsReport {

    public enum Severity {OK, INFO, WARN}

    /** A single plain-language verdict line shown in a panel and included in the report. */
    public static class Finding {
        public final Severity severity;
        public final String message;

        Finding(Severity severity, String message) {
            this.severity = severity;
            this.message = message;
        }
    }

    public final List<Finding> connectivity = new ArrayList<>();
    public final List<Finding> battery = new ArrayList<>();
    public String report = "";

    private DiagnosticsReport() {
    }

    /**
     * Build the diagnostics snapshot. Does only fast, local reads (system settings,
     * shared prefs, network interfaces, DNS resolver enumeration) — no network I/O,
     * no background work. Call off the main thread as a precaution.
     */
    public static DiagnosticsReport generate(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);

        StringBuilder sb = new StringBuilder(4096);
        DiagnosticsReport r = new DiagnosticsReport();

        // ---- Header ---------------------------------------------------------
        sb.append("TrackerControl self-check\r\n");
        sb.append(DateFormat.getDateTimeInstance().format(new Date())).append("\r\n\r\n");

        // ---- App / device ---------------------------------------------------
        String mode = safeMode(app);
        sb.append("== App ==\r\n");
        sb.append("Version: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")")
                .append(" ").append(BuildConfig.FLAVOR).append("/").append(BuildConfig.BUILD_TYPE)
                .append("\r\n");
        sb.append("Application id: ").append(BuildConfig.APPLICATION_ID).append("\r\n");
        sb.append("Blocking mode: ").append(mode).append("\r\n\r\n");

        sb.append("== Device ==\r\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\r\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\r\n\r\n");

        // ---- TrackerControl state ------------------------------------------
        boolean enabled = prefs.getBoolean("enabled", false);
        boolean manageSystem = prefs.getBoolean("manage_system", false);
        boolean dohEnabled = prefs.getBoolean("doh_enabled", false);
        boolean wgEnabled = prefs.getBoolean("wg_enabled", false);
        boolean blockDot = prefs.getBoolean("block_dot", true);
        boolean ip6 = prefs.getBoolean("ip6", true);
        String watchdog = prefs.getString("watchdog", "0");
        boolean privateDns = safeBool(() -> Util.isPrivateDns(app));
        String privateDnsMode = safePrivateDnsMode(app);
        AlwaysOn alwaysOn = detectAlwaysOn(app);

        sb.append("== TrackerControl state ==\r\n");
        sb.append("VPN enabled (setting): ").append(enabled).append("\r\n");
        sb.append("Always-on VPN: ").append(alwaysOn.describe(app)).append("\r\n");
        sb.append("Always-on lockdown: ").append(alwaysOn.lockdown).append("\r\n");
        sb.append("System apps routed: ").append(manageSystem).append("\r\n");
        sb.append("Secure DNS (DoH): ").append(dohEnabled ? "on" : "off");
        if (dohEnabled)
            sb.append(" (").append(prefs.getString("doh_endpoint", BuildConfig.DEFAULT_DOH_ENDPOINT)).append(")");
        sb.append("\r\n");
        sb.append("Remote egress (WireGuard): ").append(wgEnabled ? "on" : "off").append("\r\n");
        sb.append("Block DNS-over-TLS (port 853): ").append(blockDot).append("\r\n");
        sb.append("IPv6 enabled: ").append(ip6).append("\r\n");
        sb.append("Watchdog interval: ").append(watchdog).append(" min\r\n\r\n");

        // ---- DNS ------------------------------------------------------------
        List<String> systemDns = safeSystemDns(app);
        List<InetAddress> usedDns = safeUsedDns(app);
        boolean systemHasIpv6Dns = false;
        boolean systemHasIpv4Dns = false;
        for (String d : systemDns) {
            if (isIpv4(d)) systemHasIpv4Dns = true;
            else systemHasIpv6Dns = true;
        }
        boolean usedAllIpv4 = true;
        for (InetAddress d : usedDns)
            if (!(d instanceof Inet4Address)) usedAllIpv4 = false;
        // IPv6-only network heuristic: system resolvers are IPv6 only.
        boolean ipv6OnlyDns = systemHasIpv6Dns && !systemHasIpv4Dns;

        sb.append("== DNS ==\r\n");
        sb.append("Private DNS (DoT): ").append(privateDns ? "ON" : "off");
        if (privateDnsMode != null) sb.append(" (mode=").append(privateDnsMode).append(")");
        sb.append("\r\n");
        sb.append("System resolvers: ").append(systemDns.isEmpty() ? "(none reported)" : join(systemDns)).append("\r\n");
        sb.append("Resolvers TrackerControl uses: ")
                .append(usedDns.isEmpty() ? "(none)" : joinAddrs(usedDns)).append("\r\n\r\n");

        // ---- Connectivity now ----------------------------------------------
        boolean connected = safeBool(() -> Util.isConnected(app));
        boolean internetWorking = safeBool(() -> Util.isInternetWorking(app));
        boolean wifi = safeBool(() -> Util.isWifiActive(app));
        boolean metered = safeBool(() -> Util.isMeteredNetwork(app));
        boolean roaming = safeBool(() -> Util.isRoaming(app));

        sb.append("== Connectivity (now) ==\r\n");
        sb.append("Connected: ").append(connected).append("\r\n");
        sb.append("Internet validated: ").append(internetWorking).append("\r\n");
        sb.append("WiFi: ").append(wifi)
                .append(" · Metered: ").append(metered)
                .append(" · Roaming: ").append(roaming).append("\r\n\r\n");

        // ================================================================
        // Verdicts (also mirrored into the panels below)
        // ================================================================

        // --- Connectivity verdicts ---
        if (!enabled)
            r.connectivity.add(new Finding(Severity.WARN,
                    "TrackerControl is turned off in its settings — no tracker blocking or "
                            + "connection handling is active. Enable it from the main screen."));

        if (privateDns)
            r.connectivity.add(new Finding(Severity.WARN,
                    "Private DNS (DoT) is ON. Encrypted DNS bypasses TrackerControl's "
                            + "plaintext-DNS tracker detection, and some resolution can be blocked "
                            + "(TrackerControl blocks port 853). If apps can't connect or no trackers "
                            + "are detected, set Android's Private DNS to 'Off' or 'Automatic'."));
        else
            r.connectivity.add(new Finding(Severity.OK, "Private DNS (DoT) is off — plaintext-DNS detection can work."));

        if (BlockingMode.MODE_STRICT.equals(mode))
            r.connectivity.add(new Finding(Severity.WARN,
                    "Blocking mode is Strict — the most aggressive mode. Some app breakage is "
                            + "expected by design, including shared-IP over-blocking. Exclude the "
                            + "affected app or switch to Standard/Minimal to recover."));
        else if (BlockingMode.MODE_STANDARD.equals(mode))
            r.connectivity.add(new Finding(Severity.INFO,
                    "Blocking mode is Standard — some app breakage is expected; configure per-app "
                            + "and per-tracker exceptions if an app misbehaves."));
        else
            r.connectivity.add(new Finding(Severity.OK,
                    "Blocking mode is Minimal — the safest mode (least app breakage)."));

        if (ipv6OnlyDns && usedAllIpv4 && !usedDns.isEmpty())
            r.connectivity.add(new Finding(Severity.WARN,
                    "This network only advertises IPv6 DNS servers, but TrackerControl is using "
                            + "IPv4 resolvers. On IPv6-only mobile data this can make all name "
                            + "resolution fail. Try Wi-Fi, or enable Secure DNS (DoH)."));

        if (!connected)
            r.connectivity.add(new Finding(Severity.WARN, "The device reports no active network connection."));
        else if (!internetWorking)
            r.connectivity.add(new Finding(Severity.INFO,
                    "The active network is connected but not validated as having working internet."));

        if (alwaysOn.state == AlwaysOn.State.OTHER)
            r.connectivity.add(new Finding(Severity.INFO,
                    "Another app is set as the always-on VPN. Only one VPN can be active on "
                            + "Android at a time, so TrackerControl cannot run alongside it."));
        else if (alwaysOn.state == AlwaysOn.State.NONE)
            r.connectivity.add(new Finding(Severity.INFO,
                    "TrackerControl is not set as the always-on VPN. Setting it as always-on "
                            + "helps it re-establish after reboots or OS-initiated teardowns."));

        if (dohEnabled)
            r.connectivity.add(new Finding(Severity.INFO,
                    "Secure DNS (DoH) is on. Resolution goes to your configured DoH endpoint."));

        if (wgEnabled)
            r.connectivity.add(new Finding(Severity.INFO,
                    "Remote egress (WireGuard) is on — traffic is forwarded through your VPN provider."));

        // --- Battery verdicts ---
        r.battery.add(new Finding(Severity.INFO,
                "TrackerControl runs as an always-on foreground VPN. Its own wakelocks are "
                        + "per-command and time-boxed, and measured CPU use is ~0%. Android's "
                        + "battery screen frequently attributes other apps' background radio "
                        + "wakeups to the VPN, so TrackerControl can look like it uses far more "
                        + "battery than it actually does."));

        if ("0".equals(watchdog))
            r.battery.add(new Finding(Severity.OK, "Watchdog is off (0 min) — no periodic self-wakeups."));
        else
            r.battery.add(new Finding(Severity.WARN,
                    "Watchdog is set to " + watchdog + " min — this adds periodic wakeups. Set it "
                            + "to 0 unless you are recovering from the VPN dropping."));

        if (wgEnabled)
            r.battery.add(new Finding(Severity.INFO,
                    "Remote egress (WireGuard) is on. This is the one configuration with a "
                            + "continuous background loop (the connectivity monitor) and measurable "
                            + "extra drain. Default (no-egress) users have no such loop."));

        if (manageSystem)
            r.battery.add(new Finding(Severity.INFO,
                    "System apps are routed through the VPN. This can wake the packet loop on "
                            + "their background activity; excluding them, however, disables "
                            + "Android's 'Block connections without VPN'."));

        boolean batteryOptimising = safeBool(() -> Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Util.batteryOptimizing(app));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (batteryOptimising)
                r.battery.add(new Finding(Severity.INFO,
                        "Battery optimisation is ON for TrackerControl. If the VPN stops working "
                                + "in the background, exempt it (Troubleshooting → battery)."));
            else
                r.battery.add(new Finding(Severity.OK, "Battery optimisation is disabled for TrackerControl."));
        }

        // ---- Append verdicts + NetGuard dumps to the paste-able report ------
        sb.append("== Connectivity verdict ==\r\n");
        appendFindings(sb, r.connectivity);
        sb.append("\r\n== Battery verdict ==\r\n");
        appendFindings(sb, r.battery);

        sb.append("\r\n== General (NetGuard) ==\r\n");
        sb.append(safeText(() -> Util.getGeneralInfo(app))).append("\r\n");
        sb.append("\r\n== Networks (NetGuard) ==\r\n");
        sb.append(safeText(() -> Util.getNetworkInfo(app))).append("\r\n");

        r.report = sb.toString();
        return r;
    }

    private static void appendFindings(StringBuilder sb, List<Finding> findings) {
        for (Finding f : findings)
            sb.append(glyph(f.severity)).append(' ').append(f.message).append("\r\n");
    }

    public static String glyph(Severity s) {
        switch (s) {
            case WARN:
                return "[!]";
            case OK:
                return "[OK]";
            default:
                return "[i]";
        }
    }

    // ---- Always-on VPN detection -------------------------------------------

    private static class AlwaysOn {
        enum State {SELF, OTHER, NONE, UNKNOWN}

        final State state;
        final String otherApp;
        final boolean lockdown;

        AlwaysOn(State state, String otherApp, boolean lockdown) {
            this.state = state;
            this.otherApp = otherApp;
            this.lockdown = lockdown;
        }

        String describe(Context c) {
            switch (state) {
                case SELF:
                    return "yes (TrackerControl)";
                case OTHER:
                    return "another app (" + otherApp + ")";
                case NONE:
                    return "no";
                default:
                    return "unknown";
            }
        }
    }

    private static AlwaysOn detectAlwaysOn(Context context) {
        boolean lockdown = false;
        try {
            lockdown = Settings.Secure.getInt(context.getContentResolver(), "always_on_vpn_lockdown", 0) != 0;
        } catch (Throwable ignored) {
        }
        try {
            String pkg = Settings.Secure.getString(context.getContentResolver(), "always_on_vpn_app");
            if (pkg == null || pkg.isEmpty())
                return new AlwaysOn(AlwaysOn.State.NONE, null, lockdown);
            if (pkg.equals(context.getPackageName()))
                return new AlwaysOn(AlwaysOn.State.SELF, pkg, lockdown);
            return new AlwaysOn(AlwaysOn.State.OTHER, pkg, lockdown);
        } catch (Throwable ex) {
            return new AlwaysOn(AlwaysOn.State.UNKNOWN, null, lockdown);
        }
    }

    // ---- Small safe helpers -------------------------------------------------

    private interface BoolSupplier {
        boolean get();
    }

    private interface TextSupplier {
        String get();
    }

    private static boolean safeBool(BoolSupplier s) {
        try {
            return s.get();
        } catch (Throwable ex) {
            return false;
        }
    }

    private static String safeText(TextSupplier s) {
        try {
            String t = s.get();
            return t == null ? "" : t;
        } catch (Throwable ex) {
            return "(unavailable: " + ex.getClass().getSimpleName() + ")";
        }
    }

    private static String safeMode(Context c) {
        try {
            return BlockingMode.getMode(c);
        } catch (Throwable ex) {
            return "unknown";
        }
    }

    private static String safePrivateDnsMode(Context c) {
        try {
            return Settings.Global.getString(c.getContentResolver(), "private_dns_mode");
        } catch (Throwable ex) {
            return null;
        }
    }

    private static List<String> safeSystemDns(Context c) {
        try {
            return Util.getDefaultDNS(c);
        } catch (Throwable ex) {
            return new ArrayList<>();
        }
    }

    private static List<InetAddress> safeUsedDns(Context c) {
        try {
            return ServiceSinkhole.getDns(c);
        } catch (Throwable ex) {
            return new ArrayList<>();
        }
    }

    private static boolean isIpv4(String address) {
        // System resolvers arrive as numeric literals (from getHostAddress); an IPv4
        // literal never contains ':', an IPv6 literal always does.
        return address != null && !address.contains(":");
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static String joinAddrs(List<InetAddress> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i).getHostAddress());
        }
        return sb.toString();
    }
}
