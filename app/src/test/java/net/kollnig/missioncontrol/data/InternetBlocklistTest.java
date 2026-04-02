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

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

public class InternetBlocklistTest {

    @Before
    public void setUp() throws Exception {
        Field instance = InternetBlocklist.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    public void freshBlocklistAllowsAllUids() {
        InternetBlocklist blocklist = InternetBlocklist.getInstance(null);
        assertFalse(blocklist.blockedInternet(1001));
        assertFalse(blocklist.blockedInternet(9999));
    }

    @Test
    public void blockAndUnblockCycle() {
        InternetBlocklist blocklist = InternetBlocklist.getInstance(null);

        blocklist.block(1001);
        assertTrue(blocklist.blockedInternet(1001));

        blocklist.unblock(1001);
        assertFalse(blocklist.blockedInternet(1001));
    }

    @Test
    public void blockIsPerUid() {
        InternetBlocklist blocklist = InternetBlocklist.getInstance(null);

        blocklist.block(1001);
        assertTrue(blocklist.blockedInternet(1001));
        assertFalse(blocklist.blockedInternet(1002));
    }

    @Test
    public void clearRemovesAllEntries() {
        InternetBlocklist blocklist = InternetBlocklist.getInstance(null);

        blocklist.block(1001);
        blocklist.block(1002);
        blocklist.clear();

        assertFalse(blocklist.blockedInternet(1001));
        assertFalse(blocklist.blockedInternet(1002));
    }

    @Test
    public void doubleBlockIsIdempotent() {
        InternetBlocklist blocklist = InternetBlocklist.getInstance(null);

        blocklist.block(1001);
        blocklist.block(1001);
        assertTrue(blocklist.blockedInternet(1001));

        blocklist.unblock(1001);
        assertFalse(blocklist.blockedInternet(1001));
    }

    @Test
    public void unblockOnNonBlockedUidIsNoOp() {
        InternetBlocklist blocklist = InternetBlocklist.getInstance(null);
        blocklist.unblock(9999);
        assertFalse(blocklist.blockedInternet(9999));
    }
}
