package net.kollnig.missioncontrol.wg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtonConfigsTest {
    // Verbatim shape of a dashboard download (account.protonvpn.com).
    private static final String DASHBOARD_CONFIG = "[Interface]\n" +
            "# Key for tc-test\n" +
            "# Bouncing = 0\n" +
            "# NetShield = 1\n" +
            "# Moderate NAT = off\n" +
            "# NAT-PMP (Port Forwarding) = off\n" +
            "# VPN Accelerator = on\n" +
            "PrivateKey = 2Kh7TlGz+7PCFa0jEHat8IWkYZgPmDLAiagGq+dyLks=\n" +
            "Address = 10.2.0.2/32\n" +
            "DNS = 10.2.0.1\n" +
            "\n" +
            "[Peer]\n" +
            "# NO#21\n" +
            "PublicKey = KOITt3KQ72LHPbpVp7kp4cQo/qw2qvKPrN732UTWWFw=\n" +
            "AllowedIPs = 0.0.0.0/0\n" +
            "Endpoint = 146.70.170.18:51820\n";

    private static final String GENERIC_CONFIG = "[Interface]\n" +
            "PrivateKey = 2Kh7TlGz+7PCFa0jEHat8IWkYZgPmDLAiagGq+dyLks=\n" +
            "Address = 10.64.23.5/32\n" +
            "DNS = 10.64.0.1\n" +
            "\n" +
            "[Peer]\n" +
            "PublicKey = KOITt3KQ72LHPbpVp7kp4cQo/qw2qvKPrN732UTWWFw=\n" +
            "AllowedIPs = 0.0.0.0/0\n" +
            "Endpoint = 185.213.154.68:51820\n";

    @Test
    public void detectsDashboardConfig() {
        assertTrue(ProtonConfigs.isProtonConfig(DASHBOARD_CONFIG));
        assertEquals("NO#21", ProtonConfigs.getServerName(DASHBOARD_CONFIG));
    }

    @Test
    public void doesNotDetectAddressAndDnsPairWhenCommentsStripped() {
        String stripped = DASHBOARD_CONFIG.replaceAll("(?m)^#.*\\n", "");
        assertFalse(ProtonConfigs.isProtonConfig(stripped));
        assertEquals("", ProtonConfigs.getServerName(stripped));
    }

    @Test
    public void rejectsGenericFeatureComment() {
        String generic = GENERIC_CONFIG.replace("[Interface]\\n", "[Interface]\\n# NetShield compatibility\\n");
        assertFalse(ProtonConfigs.isProtonConfig(generic));
    }

    @Test
    public void rejectsSingleWellFormedFeatureComment() {
        String generic = GENERIC_CONFIG.replace("[Interface]\\n", "[Interface]\\n# NetShield = 1\\n");
        assertFalse(ProtonConfigs.isProtonConfig(generic));
    }

    @Test
    public void detectsDashboardConfigWithAlternateTunnelRange() {
        String alternate = DASHBOARD_CONFIG
                .replace("10.2.0.2/32", "10.3.0.2/32")
                .replace("10.2.0.1", "10.3.0.1");
        assertTrue(ProtonConfigs.isProtonConfig(alternate));
    }

    @Test
    public void detectsFeatureSignatureWithoutCanonicalAddressOrServerComment() {
        String alternate = DASHBOARD_CONFIG
                .replace("# NO#21\n", "")
                .replace("10.2.0.2/32", "10.5.0.2/32")
                .replace("10.2.0.1", "10.5.0.1");
        assertTrue(ProtonConfigs.isProtonConfig(alternate));
    }

    @Test
    public void extractsFreeAndSecureCoreServerNames() {
        assertEquals("NL-FREE#128", ProtonConfigs.getServerName(
                DASHBOARD_CONFIG.replace("# NO#21", "# NL-FREE#128")));
        assertEquals("CH-US#1", ProtonConfigs.getServerName(
                DASHBOARD_CONFIG.replace("# NO#21", "# CH-US#1")));
    }

    @Test
    public void ignoresGenericConfig() {
        assertFalse(ProtonConfigs.isProtonConfig(GENERIC_CONFIG));
        assertEquals("", ProtonConfigs.getServerName(GENERIC_CONFIG));
        assertFalse(ProtonConfigs.isProtonConfig(""));
        assertFalse(ProtonConfigs.isProtonConfig(null));
    }
}
