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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public class InternetBlocklistTest {
    private Context context;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(TrackerBlocklist.PREF_BLOCKLIST, Context.MODE_PRIVATE)
                .edit().clear().commit();
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

    @Test
    public void packageNameAndMalformedEntriesLoadWithoutThrowing() {
        String packageName = context.getPackageName();
        String malformed = "2147483648";
        String unresolved = "com.example.missing";
        Set<String> entries = new HashSet<>(Arrays.asList(packageName, malformed, unresolved));
        SharedPreferences prefs = context.getSharedPreferences(
                TrackerBlocklist.PREF_BLOCKLIST, Context.MODE_PRIVATE);
        prefs.edit().putStringSet(InternetBlocklist.SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY,
                entries).commit();

        InternetBlocklist blocklist = InternetBlocklist.getInstance(context);

        assertTrue(blocklist.blockedInternet(context.getApplicationInfo().uid));
        assertFalse(blocklist.blockedInternet(9999));

        blocklist.saveSettings(context);
        Set<String> saved = prefs.getStringSet(
                InternetBlocklist.SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY, null);
        Set<String> expected = new HashSet<>(Arrays.asList(
                Integer.toString(context.getApplicationInfo().uid), malformed, unresolved));
        assertEquals(expected, saved);
    }

    @Test
    public void unresolvedPackageNameResolvesLater() {
        String packageName = "com.example.later";
        SharedPreferences prefs = context.getSharedPreferences(
                TrackerBlocklist.PREF_BLOCKLIST, Context.MODE_PRIVATE);
        prefs.edit().putStringSet(
                InternetBlocklist.SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY,
                Collections.singleton(packageName)).commit();

        InternetBlocklist blocklist = InternetBlocklist.getInstance(context);
        assertFalse(blocklist.blockedInternet(1001));

        assertTrue(blocklist.resolvePendingPackages(new PackageUids.Resolver() {
            @Override
            public Integer resolve(String storedPackageName) {
                assertEquals(packageName, storedPackageName);
                return 1001;
            }
        }));
        assertTrue(blocklist.blockedInternet(1001));
    }

    @Test
    public void resolvingOnePackageSkipsThePackageManagerWhenNothingIsPending() {
        String packageName = "com.example.later";
        SharedPreferences prefs = context.getSharedPreferences(
                TrackerBlocklist.PREF_BLOCKLIST, Context.MODE_PRIVATE);
        prefs.edit().putStringSet(
                InternetBlocklist.SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY,
                Collections.singleton(packageName)).commit();

        InternetBlocklist blocklist = InternetBlocklist.getInstance(context);
        PackageUids.Resolver strict = new PackageUids.Resolver() {
            @Override
            public Integer resolve(String storedPackageName) {
                assertEquals(packageName, storedPackageName);
                return 1001;
            }
        };

        // A package-added broadcast for any other app must not cost a
        // PackageManager call.
        assertFalse(blocklist.resolvePendingPackage(strict, "com.example.unrelated"));
        assertFalse(blocklist.resolvePendingPackage(strict, null));
        assertFalse(blocklist.blockedInternet(1001));

        assertTrue(blocklist.resolvePendingPackage(strict, packageName));
        assertTrue(blocklist.blockedInternet(1001));
        assertFalse(blocklist.resolvePendingPackage(strict, packageName));
    }
}
