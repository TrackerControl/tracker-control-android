/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package eu.faircode.netguard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.TrackerBlocklist;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ReceiverPackageRemovedTest {
    private static final int UID = 12345;
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(TrackerBlocklist.PREF_BLOCKLIST, Context.MODE_PRIVATE)
                .edit().clear().commit();
        TrackerBlocklist.getInstance(null).clear();
        InternetBlocklist.getInstance(null).clear();
    }

    @Test
    public void cleanupRunsWhenUidHasNoPackages() {
        seedUidState();

        ReceiverPackageRemoved.clearUidState(context, UID, uid -> new String[0]);

        assertFalse(InternetBlocklist.getInstance(null).blockedInternet(UID));
        assertFalse(TrackerBlocklist.getInstance(null).hasSubset(UID));
    }

    @Test
    public void cleanupPreservesSharedUidState() {
        seedUidState();

        ReceiverPackageRemoved.clearUidState(context, UID, uid -> new String[] {"com.example.sibling"});

        assertTrue(InternetBlocklist.getInstance(null).blockedInternet(UID));
        assertTrue(TrackerBlocklist.getInstance(null).hasSubset(UID));
    }

    @Test
    public void cleanupPreservesStateWhenPackagesAreUnknown() {
        seedUidState();

        ReceiverPackageRemoved.clearUidState(context, UID, uid -> {
            throw new SecurityException("unknown user");
        });

        assertTrue(InternetBlocklist.getInstance(null).blockedInternet(UID));
        assertTrue(TrackerBlocklist.getInstance(null).hasSubset(UID));
    }

    private void seedUidState() {
        InternetBlocklist.getInstance(null).block(UID);
        TrackerBlocklist.getInstance(null).ensureDefaults(UID, false);
    }
}
