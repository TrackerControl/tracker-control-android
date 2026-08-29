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
 * output: its exact feature comments in [Interface] ("# NetShield = 1",
 * "# VPN Accelerator = on", …). The "# NO#21"-style comment in [Peer]
 * carries the server name.
 */
public final class ProtonConfigs {
    public static final String PROVIDER = "proton";
    public static final String DASHBOARD_URL =
            "https://account.protonvpn.com/downloads#wireguard-configuration";

    // e.g. "# NO#21", "# NL-FREE#128", "# CH-US#1" (Secure Core)
    private static final Pattern SERVER_COMMENT =
            Pattern.compile("^#\\s*([A-Z]{2}(?:-[A-Z0-9]+)*#\\d+)\\s*$");
    private static final Pattern BOUNCING_COMMENT =
            Pattern.compile("^Bouncing\\s*=\\s*\\d+$");
    private static final Pattern NETSHIELD_COMMENT =
            Pattern.compile("^NetShield\\s*=\\s*[012]$");
    private static final Pattern MODERATE_NAT_COMMENT =
            Pattern.compile("^Moderate NAT\\s*=\\s*(?:on|off)$");
    private static final Pattern NAT_PMP_COMMENT =
            Pattern.compile("^NAT-PMP\\s+\\(Port Forwarding\\)\\s*=\\s*(?:on|off)$");
    private static final Pattern VPN_ACCELERATOR_COMMENT =
            Pattern.compile("^VPN Accelerator\\s*=\\s*(?:on|off)$");

    private ProtonConfigs() {
    }

    public static boolean isProtonConfig(String config) {
        if (config == null || config.isEmpty())
            return false;

        boolean inInterface = false;
        boolean inPeer = false;
        boolean hasInterface = false;
        boolean hasPeer = false;
        int featureComments = 0;
        boolean serverComment = false;
        for (String rawLine : config.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                inInterface = "[interface]".equals(line.toLowerCase(Locale.ROOT));
                inPeer = "[peer]".equals(line.toLowerCase(Locale.ROOT));
                hasInterface |= inInterface;
                hasPeer |= inPeer;
                continue;
            }
            if (line.startsWith("#")) {
                String comment = line.substring(1).trim();
                if (inInterface) {
                    if (BOUNCING_COMMENT.matcher(comment).matches())
                        featureComments |= 1;
                    if (NETSHIELD_COMMENT.matcher(comment).matches())
                        featureComments |= 1 << 1;
                    if (MODERATE_NAT_COMMENT.matcher(comment).matches())
                        featureComments |= 1 << 2;
                    if (NAT_PMP_COMMENT.matcher(comment).matches())
                        featureComments |= 1 << 3;
                    if (VPN_ACCELERATOR_COMMENT.matcher(comment).matches())
                        featureComments |= 1 << 4;
                } else if (inPeer && SERVER_COMMENT.matcher(line).matches()) {
                    serverComment = true;
                }
                continue;
            }
        }
        if (!hasInterface || !hasPeer)
            return false;

        // A single comment or the private tunnel address is not identity
        // evidence: both are easy for an unrelated WireGuard profile to share.
        // Count distinct exact option comments, so repeated text cannot satisfy
        // the signature. A server comment is an additional Proton-specific
        // marker, so it can complete a two-part signature with one option.
        int featureCount = Integer.bitCount(featureComments);
        return featureCount >= 2 || (serverComment && featureCount >= 1);
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
