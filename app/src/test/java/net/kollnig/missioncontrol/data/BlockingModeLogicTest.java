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

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class BlockingModeLogicTest {

    @Test
    public void minimalBlocksOnlyNonContentDuckDuckGoTrackers() {
        assertTrue(BlockingModeLogic.shouldBlockEssentialOnly("Advertising"));
        assertFalse(BlockingModeLogic.shouldBlockEssentialOnly(
                BlockingModeLogic.CONTENT_CATEGORY));
        // Detected by another list, but unknown to DuckDuckGo: monitored, not blocked.
        assertFalse(BlockingModeLogic.shouldBlockEssentialOnly(null));
    }

    @Test
    public void essentialOnlyBlocksOnlyNonContentCategories() {
        assertTrue(BlockingModeLogic.shouldBlockEssentialOnly("Advertising"));
        assertTrue(BlockingModeLogic.shouldBlockEssentialOnly("Uncategorised"));
        assertFalse(BlockingModeLogic.shouldBlockEssentialOnly(
                BlockingModeLogic.CONTENT_CATEGORY));
        assertFalse(BlockingModeLogic.shouldBlockEssentialOnly(null));
    }

    @Test
    public void onlyStrictBlocksAmbiguousTrackerIps() {
        assertFalse(BlockingModeLogic.blocksAmbiguousTrackerIp(BlockingModeLogic.MODE_MINIMAL));
        assertFalse(BlockingModeLogic.blocksAmbiguousTrackerIp(BlockingModeLogic.MODE_STANDARD));
        assertTrue(BlockingModeLogic.blocksAmbiguousTrackerIp(BlockingModeLogic.MODE_STRICT));
    }

    @Test
    public void unsetIncompatibleAppIsAutoExcluded() {
        BlockingModeLogic.ExclusionSyncResult result = BlockingModeLogic.syncVpnExclusions(
                Set.of("incompatible"),
                Collections.emptyMap(),
                Collections.emptySet());

        assertEquals(Set.of("incompatible"), result.applyFalsePackages);
        assertTrue(result.applyRemovals.isEmpty());
        assertEquals(Set.of("incompatible"), result.autoExcludedApps);
    }

    @Test
    public void leavingExcludedListRestoresOnlyAutoExcludedApps() {
        Map<String, Boolean> applyPrefs = new HashMap<>();
        applyPrefs.put("incompatible", false);
        applyPrefs.put("manual", false);

        BlockingModeLogic.ExclusionSyncResult result = BlockingModeLogic.syncVpnExclusions(
                Collections.emptySet(),
                applyPrefs,
                Set.of("incompatible"));

        assertTrue(result.applyFalsePackages.isEmpty());
        assertEquals(Set.of("incompatible"), result.applyRemovals);
        assertTrue(result.autoExcludedApps.isEmpty());
        assertFalse(result.applyRemovals.contains("manual"));
    }

    @Test
    public void explicitVpnInclusionIsNeverOverriddenAndClearsAutoManagement() {
        Map<String, Boolean> applyPrefs = new HashMap<>();
        applyPrefs.put("incompatible", true);

        BlockingModeLogic.ExclusionSyncResult result = BlockingModeLogic.syncVpnExclusions(
                Set.of("incompatible"),
                applyPrefs,
                Set.of("incompatible"));

        assertTrue(result.applyFalsePackages.isEmpty());
        assertTrue(result.applyRemovals.isEmpty());
        assertTrue(result.autoExcludedApps.isEmpty());
    }

    @Test
    public void staleAutoExcludedAppIsRemovedWhenNoLongerManaged() {
        Map<String, Boolean> applyPrefs = new HashMap<>();
        applyPrefs.put("browser", false);

        BlockingModeLogic.ExclusionSyncResult result = BlockingModeLogic.syncVpnExclusions(
                Collections.emptySet(),
                applyPrefs,
                Set.of("browser"));

        assertTrue(result.applyFalsePackages.isEmpty());
        assertEquals(Set.of("browser"), result.applyRemovals);
        assertTrue(result.autoExcludedApps.isEmpty());
    }

    @Test
    public void clearingAutoExcludedAppRemovesItFromManagedSet() {
        assertEquals(Set.of("browser"),
                BlockingModeLogic.clearAutoExcludedApp(Set.of("browser", "other"), "other"));
    }

    /**
     * The UI must keep an auto-excluded app in the managed set while it stays
     * excluded, otherwise it silently converts a compatibility auto-exclusion
     * into a permanent one. The app is restored only after leaving the DDG list.
     */
    @Test
    public void reExcludedAutoExcludedAppRemainsManagedUntilItLeavesList() {
        Map<String, Boolean> applyPrefs = new HashMap<>();
        applyPrefs.put("incompatible", false);

        BlockingModeLogic.ExclusionSyncResult stable = BlockingModeLogic.syncVpnExclusions(
                Set.of("incompatible"),
                applyPrefs,
                Set.of("incompatible"));

        assertTrue(stable.applyFalsePackages.isEmpty());
        assertTrue(stable.applyRemovals.isEmpty());
        assertEquals(Set.of("incompatible"), stable.autoExcludedApps);

        BlockingModeLogic.ExclusionSyncResult result = BlockingModeLogic.syncVpnExclusions(
                Collections.emptySet(),
                applyPrefs,
                stable.autoExcludedApps);

        assertEquals(Set.of("incompatible"), result.applyRemovals);
        assertTrue(result.autoExcludedApps.isEmpty());
    }

    /**
     * Re-including an auto-excluded app does clear its membership, and the next
     * sync must then leave the app alone rather than excluding it again.
     */
    @Test
    public void reIncludedAutoExcludedAppIsNotExcludedAgain() {
        Set<String> autoExcludedApps =
                BlockingModeLogic.clearAutoExcludedApp(Set.of("incompatible"), "incompatible");
        assertTrue(autoExcludedApps.isEmpty());

        Map<String, Boolean> applyPrefs = new HashMap<>();
        applyPrefs.put("incompatible", true);

        BlockingModeLogic.ExclusionSyncResult result = BlockingModeLogic.syncVpnExclusions(
                Set.of("incompatible"),
                applyPrefs,
                autoExcludedApps);

        assertTrue(result.applyFalsePackages.isEmpty());
        assertTrue(result.applyRemovals.isEmpty());
        assertTrue(result.autoExcludedApps.isEmpty());
    }

    @Test
    public void browserCategoryIsNotParsedAsVpnExclusion() {
        Set<String> excludedApps = BlockingModeLogic.parseExcludedAppsJson(
                "{\"browsers\":[\"browser\"],\"vpn_incompatible\":[\"vpn.bad\"],\"user_reported\":[\"reported\"]}");

        assertFalse(excludedApps.contains("browser"));
        assertTrue(excludedApps.contains("vpn.bad"));
        assertTrue(excludedApps.contains("reported"));
    }

    @Test
    public void browserCategoryIsParsedSeparatelyForTrackerDefaults() {
        Set<String> browserApps = BlockingModeLogic.parseBrowserAppsJson(
                "{\"browsers\":[\"browser\"],\"vpn_incompatible\":[\"vpn.bad\"],\"user_reported\":[\"reported\"]}");

        assertTrue(browserApps.contains("browser"));
        assertFalse(browserApps.contains("vpn.bad"));
        assertFalse(browserApps.contains("reported"));
    }

    @Test
    public void minimalBlocksAllNonContentDuckDuckGoCategories() {
        for (String category : new String[]{
                "Advertising", "Analytics", "Social", "Fingerprinting",
                "Email", "Uncategorised"}) {
            assertTrue("Should block " + category,
                    BlockingModeLogic.shouldBlockEssentialOnly(category));
        }
    }

    @Test
    public void standardWithGranularFalseDoesNotBlock() {
        // In standard mode, the granular rule is the whole verdict
        TrackerBlocklist.resetForTests();
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        int uid = 1001;

        blocklist.ensureDefaults(uid, false);
        Tracker tracker = new Tracker("Branch", "Advertising");
        blocklist.unblock(uid, tracker.category);

        assertFalse(blocklist.blockedTracker(uid, tracker));
    }

    @Test
    public void endToEndMinimalBlocksAdvertisingTrackerRegardlessOfBlocklist() {
        // Minimal mode never consults TrackerBlocklist: the DuckDuckGo category
        // of the flow is the whole verdict.
        TrackerBlocklist.resetForTests();
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        Tracker tracker = new Tracker("Branch", "Advertising");

        blocklist.unblock(1001, tracker.category);
        assertFalse(blocklist.blockedTracker(1001, tracker));
        assertTrue(BlockingModeLogic.shouldBlockEssentialOnly(tracker.category));
    }

    @Test
    public void endToEndStandardContentTrackerAllowedByDefault() {
        // Simulates: standard mode + default blocklist = Content trackers pass through
        TrackerBlocklist.resetForTests();
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        Tracker contentTracker = new Tracker("Akamai", "Content");
        int uid = 1001;

        blocklist.ensureDefaults(uid, false);
        boolean blockedByGranular = blocklist.blockedTracker(uid, contentTracker);
        assertFalse("Content should not be blocked by granular rule in standard defaults",
                blockedByGranular);
    }

    @Test
    public void endToEndStrictContentTrackerBlocked() {
        // Simulates: strict mode + strict blocklist = Content trackers blocked
        TrackerBlocklist.resetForTests();
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        Tracker contentTracker = new Tracker("Akamai", "Content");
        int uid = 1001;

        blocklist.ensureDefaults(uid, true);
        boolean blockedByGranular = blocklist.blockedTracker(uid, contentTracker);
        assertTrue("Content should be blocked by granular rule in strict defaults",
                blockedByGranular);
    }

    @Test
    public void endToEndStandardUserUnblocksSpecificTracker() {
        // User unblocks a specific tracker in standard mode -> it passes through
        TrackerBlocklist.resetForTests();
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        Tracker tracker = new Tracker("Branch", "Advertising");
        int uid = 1001;

        blocklist.ensureDefaults(uid, false);
        assertTrue(blocklist.blockedTracker(uid, tracker));

        // User unblocks Branch specifically
        blocklist.unblock(uid, tracker);
        assertFalse(blocklist.blockedTracker(uid, tracker));
    }
}
