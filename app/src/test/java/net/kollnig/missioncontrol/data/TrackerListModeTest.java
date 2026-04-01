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
 * Copyright © 2026
 */

package net.kollnig.missioncontrol.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public class TrackerListModeTest {

    @Test
    public void minimalModeRepresentativeDomainsComeFromDuckDuckGoAsset() throws Exception {
        String duckDuckGo = readAsset("duckduckgo-android-tds.json");

        assertTrue(matchesDomainDefault(duckDuckGo, "accounts.google.com", "ignore"));
        assertTrue(matchesDomainDefault(duckDuckGo, "15.taboola.com", "block"));
    }

    @Test
    public void standardAndStrictRepresentativeDomainsComeFromXrayAndDisconnectAssets() throws IOException {
        String duckDuckGo = readAsset("duckduckgo-android-tds.json");
        String xray = readAsset("xray-blacklist.json");
        String disconnect = new StringBuilder(readAsset("disconnect-blacklist.reversed.json"))
                .reverse()
                .toString();

        for (String domain : new String[] {
                "doubleclick.net",
                "google-analytics.com",
                "crashlytics.com",
                "branch.io"
        }) {
            assertFalse(duckDuckGo.contains("\"" + domain + "\""));
            assertTrue(xray.contains(domain));
            assertTrue(disconnect.contains(domain));
        }
    }

    @Test
    public void minimalModeDoesNotSeeRepresentativeXrayOrDisconnectDomains() throws IOException {
        String duckDuckGo = readAsset("duckduckgo-android-tds.json");

        assertFalse(duckDuckGo.contains("\"doubleclick.net\""));
        assertFalse(duckDuckGo.contains("\"google-analytics.com\""));
        assertFalse(duckDuckGo.contains("\"crashlytics.com\""));
        assertFalse(duckDuckGo.contains("\"branch.io\""));
    }

    private static String readAsset(String assetName) throws IOException {
        return new String(Files.readAllBytes(assetPath(assetName)), StandardCharsets.UTF_8);
    }

    private static boolean matchesDomainDefault(String json, String domain, String expectedDefault) {
        return Pattern.compile(
                "\"" + Pattern.quote(domain) + "\"\\s*:\\s*\\{.*?\"default\"\\s*:\\s*\"" + expectedDefault + "\"",
                Pattern.DOTALL)
                .matcher(json)
                .find();
    }

    private static Path assetPath(String assetName) {
        Path moduleRelative = Path.of("src", "main", "assets", assetName);
        if (Files.exists(moduleRelative))
            return moduleRelative;

        return Path.of("app", "src", "main", "assets", assetName);
    }
}
