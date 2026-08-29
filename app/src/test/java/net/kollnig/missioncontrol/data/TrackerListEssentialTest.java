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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
public class TrackerListEssentialTest {
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
    public void xrayClaimedDomainStillUsesItsDdgCategoryForEssentialLookup() {
        Tracker mainTracker = TrackerList.findTracker("2mdn.net");
        Tracker essentialTracker = TrackerList.findEssentialTracker("2mdn.net");

        assertNotNull(mainTracker);
        assertNotNull(essentialTracker);
        assertEquals("Google", essentialTracker.getName());
        assertEquals(TrackerCategory.UNCATEGORISED, essentialTracker.category);
    }

    @Test
    public void hostsOnlyAndXrayOnlyHostsAreNotEssentialTrackers() {
        ServiceSinkhole.mapHostsBlocked.put("hosts-only.example", true);

        assertNotNull(TrackerList.findTracker("hosts-only.example"));
        assertNull(TrackerList.findEssentialTracker("hosts-only.example"));
        assertNotNull(TrackerList.findTracker("crashlytics.com"));
        assertNull(TrackerList.findEssentialTracker("crashlytics.com"));
    }

    @Test
    public void minimalModeUsesTheMainDdgMapForEssentialLookup() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(BlockingMode.PREF_BLOCKING_MODE, BlockingMode.MODE_MINIMAL)
                .commit();
        assertTrue(TrackerList.reloadTrackerData(context));

        assertSame(TrackerList.findTracker("2mdn.net"),
                TrackerList.findEssentialTracker("2mdn.net"));
        ServiceSinkhole.mapHostsBlocked.put("hosts-only.example", true);
        assertNull(TrackerList.findTracker("hosts-only.example"));
        assertNull(TrackerList.findEssentialTracker("hosts-only.example"));
    }
}
