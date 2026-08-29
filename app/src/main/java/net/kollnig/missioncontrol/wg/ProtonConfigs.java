package net.kollnig.missioncontrol.wg;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises WireGuard configs downloaded from the Proton VPN dashboard
 * (account.protonvpn.com → Downloads → WireGuard configuration).
 *
 * Proton has no supported API for third-party clients, so TrackerControl
 * never talks to Proton servers: users download a config themselves and this
 * helper only labels it. Detection keys off the dashboard's characteristic
 * output: the feature comments in [Interface] ("# NetShield = 1",
 * "# VPN Accelerator = on", …) and the fixed 10.2.0.x tunnel addressing.
 * The "# NO#21"-style comment in [Peer] carries the server name.
 */
public final class ProtonConfigs {
    public static final String PROVIDER = "proton";
    public static final String DASHBOARD_URL =
            "https://account.protonvpn.com/downloads#wireguard-configuration";

    // e.g. "# NO#21", "# NL-FREE#128", "# CH-US#1" (Secure Core)
    private static final Pattern SERVER_COMMENT =
            Pattern.compile("^#\\s*([A-Z]{2}(?:-[A-Z0-9]+)*#\\d+)\\s*$");

    private ProtonConfigs() {
    }

    public static boolean isProtonConfig(String config) {
        if (config == null || config.isEmpty())
            return false;

        boolean featureComment = false;
        boolean protonDns = false;
        boolean protonAddress = false;
        for (String rawLine : config.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.startsWith("#")) {
                String comment = line.substring(1).trim();
                if (comment.startsWith("NetShield") ||
                        comment.startsWith("VPN Accelerator") ||
                        comment.startsWith("Moderate NAT") ||
                        comment.startsWith("Bouncing"))
                    featureComment = true;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0)
                continue;
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1).trim();
            if ("dns".equals(key) && value.startsWith("10.2.0."))
                protonDns = true;
            else if ("address".equals(key) && value.startsWith("10.2.0."))
                protonAddress = true;
        }
        return featureComment || (protonDns && protonAddress);
    }

    public static String getServerName(String config) {
        if (config == null || config.isEmpty())
            return "";
        for (String rawLine : config.split("\\r?\\n")) {
            Matcher matcher = SERVER_COMMENT.matcher(rawLine.trim());
            if (matcher.matches())
                return matcher.group(1);
        }
        return "";
    }
}
