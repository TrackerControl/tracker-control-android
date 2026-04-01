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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class BlockingBaselineSubsetTest {

    @Before
    public void setUp() throws Exception {
        Field instance = TrackerBlocklist.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    public void bundledXrayAndDisconnectListsContainRepresentativeDomains() throws IOException {
        String xray = new String(Files.readAllBytes(assetPath("xray-blacklist.json")), StandardCharsets.UTF_8);
        String disconnect = new StringBuilder(
                new String(Files.readAllBytes(assetPath("disconnect-blacklist.reversed.json")), StandardCharsets.UTF_8))
                .reverse()
                .toString();

        for (String domain : new String[] {
                "doubleclick.net",
                "google-analytics.com",
                "crashlytics.com",
                "branch.io"
        }) {
            assertTrue(xray.contains(domain));
            assertTrue(disconnect.contains(domain));
        }
    }

    @Test
    public void sharedTrackerBlocklistSemanticsRemainStable() {
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        Tracker tracker = new Tracker("Branch", "Advertising");

        assertTrue(blocklist.blockedTracker(1001, tracker));

        blocklist.unblock(1001, tracker.category);
        assertFalse(blocklist.blockedTracker(1001, tracker));

        blocklist.block(1001, tracker.category);
        blocklist.unblock(1001, tracker);
        assertFalse(blocklist.blockedTracker(1001, tracker));
    }

    @Test
    public void blockingKeyNormalizationMatchesLegacyTrackerMigrations() {
        assertEquals("Uncategorised | Google",
                TrackerBlocklist.getBlockingKey(new Tracker("Alphabet", "Uncategorised")));
    }

    private static Path assetPath(String assetName) {
        Path moduleRelative = Path.of("src", "main", "assets", assetName);
        if (Files.exists(moduleRelative))
            return moduleRelative;

        return Path.of("app", "src", "main", "assets", assetName);
    }
}
