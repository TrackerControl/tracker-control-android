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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.preference.PreferenceManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import eu.faircode.netguard.ServiceSinkhole;

@RunWith(RobolectricTestRunner.class)
public class TrackerListMinimalTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ServiceSinkhole.mapHostsBlocked.clear();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(BlockingMode.PREF_BLOCKING_MODE, BlockingMode.MODE_STANDARD)
                .putBoolean("domain_based_blocking", false)
                .commit();
        assertTrue(TrackerList.reloadTrackerData(context));
    }

    @After
    public void tearDown() {
        ServiceSinkhole.mapHostsBlocked.clear();
    }

    @Test
    public void xrayClaimedDomainStillUsesItsDdgCategoryForMinimalLookup() {
        Tracker mainTracker = TrackerList.findTracker("2mdn.net");
        Tracker minimalTracker = TrackerList.findMinimalTracker("2mdn.net");

        assertNotNull(mainTracker);
        assertNotNull(minimalTracker);
        assertEquals("Google", minimalTracker.getName());
        assertEquals(TrackerCategory.UNCATEGORISED, minimalTracker.category);
    }

    @Test
    public void hostsOnlyAndXrayOnlyHostsAreNotMinimalTrackers() {
        ServiceSinkhole.mapHostsBlocked.put("hosts-only.example", true);

        assertNotNull(TrackerList.findTracker("hosts-only.example"));
        assertNull(TrackerList.findMinimalTracker("hosts-only.example"));
        assertNotNull(TrackerList.findTracker("crashlytics.com"));
        assertNull(TrackerList.findMinimalTracker("crashlytics.com"));
    }

    @Test
    public void minimalModeDetectsWithEveryListButKeepsTheDdgMapForBlocking() {
        switchToMinimalMode();

        // Detected through X-Ray/Disconnect, so it shows up in the trackers
        // list, counts and timeline — but it is not part of the DDG set that
        // minimal mode blocks.
        assertNotNull(TrackerList.findTracker("crashlytics.com"));
        assertNull(TrackerList.findMinimalTracker("crashlytics.com"));

        // A DDG tracker stays blockable, with its DDG category.
        Tracker minimalTracker = TrackerList.findMinimalTracker("2mdn.net");
        assertNotNull(minimalTracker);
        assertEquals("Google", minimalTracker.getName());
        assertTrue(BlockingModeLogic.shouldBlockMinimalOnly(minimalTracker.category));
    }

    @Test
    public void minimalModeStillSkipsHostsFileDetection() {
        switchToMinimalMode();
        ServiceSinkhole.mapHostsBlocked.put("hosts-only.example", true);

        assertNull(TrackerList.findTracker("hosts-only.example"));
        assertNull(TrackerList.findMinimalTracker("hosts-only.example"));
    }

    @Test
    public void detectionMapKeepsDuckDuckGoCategoryConflictResolutionInMinimalMode() {
        switchToMinimalMode();

        // X-Ray claimed 2mdn.net with a real category; DDG must not overwrite it
        // in the detection map, exactly as in standard mode.
        Tracker mainTracker = TrackerList.findTracker("2mdn.net");
        assertNotNull(mainTracker);
        assertEquals("Fingerprinting", mainTracker.category);
        assertEquals(TrackerCategory.UNCATEGORISED,
                TrackerList.findMinimalTracker("2mdn.net").category);
    }

    @Test
    public void minimalMapIsIdenticalAcrossModes() {
        Tracker standard = TrackerList.findMinimalTracker("2mdn.net");
        assertNull(TrackerList.findMinimalTracker("crashlytics.com"));

        switchToMinimalMode();
        Tracker minimal = TrackerList.findMinimalTracker("2mdn.net");
        assertNull(TrackerList.findMinimalTracker("crashlytics.com"));

        assertNotNull(minimal);
        assertEquals(standard.getName(), minimal.getName());
        assertEquals(standard.category, minimal.category);
    }

    @Test
    public void detectionOnlyTrackerIsMonitoredRatherThanBlockedOrAllowed() {
        switchToMinimalMode();

        Tracker detectionOnly = TrackerList.findTracker("crashlytics.com");
        detectionOnly.addHost("crashlytics.com");
        assertFalse(TrackerList.isMinimallyBlocked(detectionOnly));
        assertFalse(TrackerList.isMinimallyKnown(detectionOnly));

        Tracker ddgTracker = TrackerList.findTracker("2mdn.net");
        ddgTracker.addHost("2mdn.net");
        assertTrue(TrackerList.isMinimallyBlocked(ddgTracker));
        assertTrue(TrackerList.isMinimallyKnown(ddgTracker));

        Tracker ddgContent = TrackerList.findTracker("ajax.googleapis.com");
        ddgContent.addHost("ajax.googleapis.com");
        assertFalse(TrackerList.isMinimallyBlocked(ddgContent));
        assertTrue(TrackerList.isMinimallyKnown(ddgContent));
    }

    private void switchToMinimalMode() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(BlockingMode.PREF_BLOCKING_MODE, BlockingMode.MODE_MINIMAL)
                .commit();
        assertTrue(TrackerList.reloadTrackerData(context));
    }
}
