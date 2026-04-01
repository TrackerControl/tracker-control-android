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
    public void enteringMinimalAutoExcludesUnsetApps() {
        BlockingModeLogic.ExclusionSyncResult result = BlockingModeLogic.syncVpnExclusions(
                BlockingModeLogic.MODE_MINIMAL,
                Set.of("browser"),
                Collections.emptyMap(),
                Collections.emptySet());

        assertEquals(Set.of("browser"), result.applyFalsePackages);
        assertTrue(result.applyRemovals.isEmpty());
        assertEquals(Set.of("browser"), result.autoExcludedApps);
    }

    @Test
    public void leavingMinimalRestoresOnlyAutoExcludedApps() {
        Map<String, Boolean> applyPrefs = new HashMap<>();
        applyPrefs.put("browser", false);
        applyPrefs.put("manual", false);

        BlockingModeLogic.ExclusionSyncResult result = BlockingModeLogic.syncVpnExclusions(
                BlockingModeLogic.MODE_STANDARD,
                Set.of("browser"),
                applyPrefs,
                Set.of("browser"));

        assertTrue(result.applyFalsePackages.isEmpty());
        assertEquals(Set.of("browser"), result.applyRemovals);
        assertTrue(result.autoExcludedApps.isEmpty());
        assertFalse(result.applyRemovals.contains("manual"));
    }

    @Test
    public void explicitVpnInclusionStopsFutureAutoRestore() {
        Map<String, Boolean> applyPrefs = new HashMap<>();
        applyPrefs.put("browser", true);

        BlockingModeLogic.ExclusionSyncResult result = BlockingModeLogic.syncVpnExclusions(
                BlockingModeLogic.MODE_MINIMAL,
                Set.of("browser"),
                applyPrefs,
                Set.of("browser"));

        assertTrue(result.applyFalsePackages.isEmpty());
        assertTrue(result.applyRemovals.isEmpty());
        assertTrue(result.autoExcludedApps.isEmpty());
    }
}
