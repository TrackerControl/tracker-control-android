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

public class TrackerBlocklistTest {
    private static final int UID = 1001;

    @Before
    public void setUp() {
        TrackerBlocklist.resetForTests();
    }

    @Test
    public void ensureDefaultsAddsContentForNonStrictMode() {
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);

        assertTrue(blocklist.ensureDefaults(UID, false));
        assertTrue(blocklist.getSubset(UID).contains(TrackerBlocklist.NECESSARY_CATEGORY));
        assertFalse(blocklist.ensureDefaults(UID, true));
    }

    @Test
    public void ensureDefaultsOmitsContentForStrictMode() {
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);

        assertTrue(blocklist.ensureDefaults(UID, true));
        assertFalse(blocklist.getSubset(UID).contains(TrackerBlocklist.NECESSARY_CATEGORY));
    }

    @Test
    public void applyStrictModeToAllTogglesContentWhitelist() {
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        blocklist.ensureDefaults(UID, false);
        blocklist.ensureDefaults(UID + 1, false);

        assertTrue(blocklist.applyStrictModeToAll(true));
        assertFalse(blocklist.getSubset(UID).contains(TrackerBlocklist.NECESSARY_CATEGORY));
        assertFalse(blocklist.getSubset(UID + 1).contains(TrackerBlocklist.NECESSARY_CATEGORY));

        assertTrue(blocklist.applyStrictModeToAll(false));
        assertTrue(blocklist.getSubset(UID).contains(TrackerBlocklist.NECESSARY_CATEGORY));
        assertTrue(blocklist.getSubset(UID + 1).contains(TrackerBlocklist.NECESSARY_CATEGORY));
    }

    @Test
    public void granularOverridesAllowEitherCategoryOrTracker() {
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        Tracker tracker = new Tracker("Branch", "Advertising");
        blocklist.ensureDefaults(UID, false);

        assertTrue(blocklist.blockedTracker(UID, tracker));

        blocklist.unblock(UID, tracker.category);
        assertFalse(blocklist.blockedTracker(UID, tracker));

        blocklist.block(UID, tracker.category);
        blocklist.unblock(UID, tracker);
        assertFalse(blocklist.blockedTracker(UID, tracker));

        blocklist.block(UID, tracker);
        assertTrue(blocklist.blockedTracker(UID, tracker));
    }

    @Test
    public void blockingKeyNormalizesLegacyTrackerNames() {
        assertEquals("Uncategorised | Google",
                TrackerBlocklist.getBlockingKey(new Tracker("Alphabet", "Uncategorised")));
    }

    @Test
    public void minimalModeOnlyAllowsContentCategory() {
        assertFalse(TrackerBlocklist.blockedTrackerMinimal(
                new Tracker("Google", TrackerBlocklist.NECESSARY_CATEGORY)));
        assertTrue(TrackerBlocklist.blockedTrackerMinimal(
                new Tracker("Branch", "Advertising")));
    }

    @Test
    public void resolveStoredUidParsesNumericIdsWithoutResolver() {
        assertEquals(UID, TrackerBlocklist.resolveStoredUid(Integer.toString(UID), null));
    }

    @Test
    public void resolveStoredUidMigratesLegacyPackageNames() {
        assertEquals(UID, TrackerBlocklist.resolveStoredUid("com.example.app",
                new TrackerBlocklist.PackageUidResolver() {
                    @Override
                    public Integer resolve(String packageName) {
                        assertEquals("com.example.app", packageName);
                        return UID;
                    }
                }));
    }

    @Test
    public void resolveStoredUidDropsUnknownLegacyPackageNames() {
        assertEquals(-1, TrackerBlocklist.resolveStoredUid("com.example.missing",
                new TrackerBlocklist.PackageUidResolver() {
                    @Override
                    public Integer resolve(String packageName) {
                        return null;
                    }
                }));
    }
}
