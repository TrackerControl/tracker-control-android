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
    public void minimalBlocksOnlyNonContentTrackers() {
        assertTrue(BlockingModeLogic.shouldBlockKnownTracker(
                BlockingModeLogic.MODE_MINIMAL,
                "Advertising",
                false));
        assertFalse(BlockingModeLogic.shouldBlockKnownTracker(
                BlockingModeLogic.MODE_MINIMAL,
                BlockingModeLogic.CONTENT_CATEGORY,
                true));
    }

    @Test
    public void standardAndStrictFollowGranularRules() {
        assertFalse(BlockingModeLogic.shouldBlockKnownTracker(
                BlockingModeLogic.MODE_STANDARD,
                "Advertising",
                false));
        assertTrue(BlockingModeLogic.shouldBlockKnownTracker(
                BlockingModeLogic.MODE_STANDARD,
                BlockingModeLogic.CONTENT_CATEGORY,
                true));
        assertTrue(BlockingModeLogic.shouldBlockKnownTracker(
                BlockingModeLogic.MODE_STRICT,
                BlockingModeLogic.CONTENT_CATEGORY,
                true));
    }

    @Test
    public void onlyStrictBlocksAmbiguousTrackerIps() {
        assertFalse(BlockingModeLogic.blocksAmbiguousTrackerIp(BlockingModeLogic.MODE_MINIMAL));
        assertFalse(BlockingModeLogic.blocksAmbiguousTrackerIp(BlockingModeLogic.MODE_STANDARD));
        assertTrue(BlockingModeLogic.blocksAmbiguousTrackerIp(BlockingModeLogic.MODE_STRICT));
    }

    @Test
    public void enteringMinimalAutoExcludesUnsetIncompatibleApps() {
        BlockingModeLogic.ExclusionSyncResult result = BlockingModeLogic.syncVpnExclusions(
                BlockingModeLogic.MODE_MINIMAL,
                Set.of("incompatible"),
                Collections.emptyMap(),
                Collections.emptySet());

        assertEquals(Set.of("incompatible"), result.applyFalsePackages);
        assertTrue(result.applyRemovals.isEmpty());
        assertEquals(Set.of("incompatible"), result.autoExcludedApps);
    }

    @Test
    public void leavingMinimalRestoresOnlyAutoExcludedApps() {
        Map<String, Boolean> applyPrefs = new HashMap<>();
        applyPrefs.put("incompatible", false);
        applyPrefs.put("manual", false);

        BlockingModeLogic.ExclusionSyncResult result = BlockingModeLogic.syncVpnExclusions(
                BlockingModeLogic.MODE_STANDARD,
                Set.of("incompatible"),
                applyPrefs,
                Set.of("incompatible"));

        assertTrue(result.applyFalsePackages.isEmpty());
        assertEquals(Set.of("incompatible"), result.applyRemovals);
        assertTrue(result.autoExcludedApps.isEmpty());
        assertFalse(result.applyRemovals.contains("manual"));
    }

    @Test
    public void explicitVpnInclusionStopsFutureAutoRestore() {
        Map<String, Boolean> applyPrefs = new HashMap<>();
        applyPrefs.put("incompatible", true);

        BlockingModeLogic.ExclusionSyncResult result = BlockingModeLogic.syncVpnExclusions(
                BlockingModeLogic.MODE_MINIMAL,
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
                BlockingModeLogic.MODE_MINIMAL,
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
    public void minimalIgnoresGranularRuleParameter() {
        // Even when blockedByGranularRule is false, minimal still blocks non-Content
        assertTrue(BlockingModeLogic.shouldBlockKnownTracker(
                BlockingModeLogic.MODE_MINIMAL,
                "Advertising",
                false));
        // Even when blockedByGranularRule is true, minimal still allows Content
        assertFalse(BlockingModeLogic.shouldBlockKnownTracker(
                BlockingModeLogic.MODE_MINIMAL,
                BlockingModeLogic.CONTENT_CATEGORY,
                true));
    }

    @Test
    public void minimalBlocksAllNonContentCategories() {
        for (String category : new String[]{
                "Advertising", "Analytics", "Social", "Fingerprinting",
                "Email", "Uncategorised"}) {
            assertTrue("Should block " + category,
                    BlockingModeLogic.shouldBlockKnownTracker(
                            BlockingModeLogic.MODE_MINIMAL, category, false));
        }
    }

    @Test
    public void standardWithGranularFalseDoesNotBlock() {
        // In standard mode, if granular rule says not blocked, it's not blocked
        // regardless of category
        assertFalse(BlockingModeLogic.shouldBlockKnownTracker(
                BlockingModeLogic.MODE_STANDARD,
                "Advertising",
                false));
        assertFalse(BlockingModeLogic.shouldBlockKnownTracker(
                BlockingModeLogic.MODE_STANDARD,
                "Analytics",
                false));
    }

    @Test
    public void strictWithGranularTrueBlocksContent() {
        assertTrue(BlockingModeLogic.shouldBlockKnownTracker(
                BlockingModeLogic.MODE_STRICT,
                BlockingModeLogic.CONTENT_CATEGORY,
                true));
    }

    @Test
    public void endToEndMinimalBlocksAdvertisingTrackerRegardlessOfBlocklist() {
        // Simulates the full decision chain for minimal mode:
        // TrackerBlocklist is not consulted, only category matters
        TrackerBlocklist.resetForTests();
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        Tracker tracker = new Tracker("Branch", "Advertising");

        // Even if user hasn't set up any rules (no ensureDefaults), minimal blocks
        boolean blockedByGranular = blocklist.blockedTracker(1001, tracker);
        assertTrue(BlockingModeLogic.shouldBlockKnownTracker(
                BlockingModeLogic.MODE_MINIMAL,
                tracker.category,
                blockedByGranular));
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
        assertFalse(BlockingModeLogic.shouldBlockKnownTracker(
                BlockingModeLogic.MODE_STANDARD,
                contentTracker.category,
                blockedByGranular));
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
        assertTrue(BlockingModeLogic.shouldBlockKnownTracker(
                BlockingModeLogic.MODE_STRICT,
                contentTracker.category,
                blockedByGranular));
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
        boolean blockedByGranular = blocklist.blockedTracker(uid, tracker);
        assertFalse(BlockingModeLogic.shouldBlockKnownTracker(
                BlockingModeLogic.MODE_STANDARD,
                tracker.category,
                blockedByGranular));
    }
}
