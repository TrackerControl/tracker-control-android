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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public class TrackerBlocklistTest {
    private static final int UID = 1001;

    @Before
    public void setUp() {
        TrackerBlocklist.resetForTests();
        RuntimeEnvironment.getApplication()
                .getSharedPreferences(TrackerBlocklist.PREF_BLOCKLIST, Context.MODE_PRIVATE)
                .edit().clear().commit();
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
        assertEquals(Integer.valueOf(UID),
                UidKeyedStore.resolveStoredUid(Integer.toString(UID), null));
    }

    @Test
    public void resolveStoredUidMigratesLegacyPackageNames() {
        assertEquals(Integer.valueOf(UID), UidKeyedStore.resolveStoredUid("com.example.app",
                new PackageUids.Resolver() {
                    @Override
                    public Integer resolve(String packageName) {
                        assertEquals("com.example.app", packageName);
                        return UID;
                    }
                }));
    }

    @Test
    public void resolveStoredUidNeverOffersANumericKeyToTheResolver() {
        // "Numeric but unparseable" is not a package name, so it must not cost
        // a PackageManager call.
        assertNull(UidKeyedStore.resolveStoredUid("2147483648", new PackageUids.Resolver() {
            @Override
            public Integer resolve(String packageName) {
                throw new AssertionError("resolver called for " + packageName);
            }
        }));
    }

    @Test
    public void unresolvedLegacyPackageNamesRemainUnrepresentedAtRuntime() {
        SharedPreferences prefs = blocklistPreferences();
        String rawId = "com.example.missing";
        prefs.edit()
                .putStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY,
                        Collections.singleton(rawId))
                .putStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + rawId,
                        Collections.singleton("Advertising | Example"))
                .commit();

        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(RuntimeEnvironment.getApplication());

        assertTrue(blocklist.getBlocklist().isEmpty());
    }

    @Test
    public void unresolvedLegacyPackageNamesRoundTripThroughLoadAndSave() {
        SharedPreferences prefs = blocklistPreferences();
        String rawId = "com.example.missing";
        Set<String> subset = new HashSet<>(Collections.singleton("Uncategorised | Alphabet"));
        prefs.edit()
                .putStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY,
                        Collections.singleton(rawId))
                .putStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + rawId, subset)
                .commit();

        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(RuntimeEnvironment.getApplication());
        blocklist.saveSettings(RuntimeEnvironment.getApplication());

        assertEquals(Collections.singleton(rawId),
                prefs.getStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY, null));
        assertEquals(subset,
                prefs.getStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + rawId, null));
    }

    @Test
    public void unresolvedLegacyPackageNamesResolveLater() {
        SharedPreferences prefs = blocklistPreferences();
        String rawId = "com.example.later";
        Set<String> subset = new HashSet<>(Collections.singleton("Advertising | Example"));
        prefs.edit()
                .putStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY,
                        Collections.singleton(rawId))
                .putStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + rawId, subset)
                .commit();

        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(RuntimeEnvironment.getApplication());
        assertTrue(blocklist.getBlocklist().isEmpty());

        assertTrue(blocklist.resolvePendingPackages(new PackageUids.Resolver() {
            @Override
            public Integer resolve(String packageName) {
                assertEquals(rawId, packageName);
                return UID;
            }
        }));
        assertEquals(subset, blocklist.getSubset(UID));
    }

    @Test
    public void resolvingOnePackageSkipsThePackageManagerWhenNothingIsPending() {
        SharedPreferences prefs = blocklistPreferences();
        String rawId = "com.example.later";
        Set<String> subset = new HashSet<>(Collections.singleton("Advertising | Example"));
        prefs.edit()
                .putStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY,
                        Collections.singleton(rawId))
                .putStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + rawId, subset)
                .commit();

        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(RuntimeEnvironment.getApplication());
        PackageUids.Resolver strict = new PackageUids.Resolver() {
            @Override
            public Integer resolve(String packageName) {
                assertEquals(rawId, packageName);
                return UID;
            }
        };

        // The overwhelmingly common case: some other app was installed, so no
        // PackageManager call may be made at all.
        assertFalse(blocklist.resolvePendingPackage(strict, "com.example.unrelated"));
        assertFalse(blocklist.resolvePendingPackage(strict, null));
        assertTrue(blocklist.getBlocklist().isEmpty());

        // The package that is actually pending resolves, once.
        assertTrue(blocklist.resolvePendingPackage(strict, rawId));
        assertEquals(subset, blocklist.getSubset(UID));
        assertFalse(blocklist.resolvePendingPackage(strict, rawId));
    }

    @Test
    public void numericUidWinsLegacyCollisionButLegacyEntryIsRetained() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = blocklistPreferences();
        String rawId = context.getPackageName();
        String numericId = Integer.toString(context.getApplicationInfo().uid);
        Set<String> ids = new HashSet<>(java.util.Arrays.asList(numericId, rawId));
        Set<String> numericSubset = new HashSet<>(Collections.singleton("numeric"));
        Set<String> rawSubset = new HashSet<>(Collections.singleton("legacy"));
        prefs.edit()
                .putStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY, ids)
                .putStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + numericId,
                        numericSubset)
                .putStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + rawId,
                        rawSubset)
                .commit();

        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(context);
        assertEquals(numericSubset, blocklist.getSubset(context.getApplicationInfo().uid));

        blocklist.saveSettings(context);
        assertEquals(ids, prefs.getStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY, null));
        assertEquals(rawSubset,
                prefs.getStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + rawId, null));

        blocklist.clear(context.getApplicationInfo().uid);
        blocklist.saveSettings(context);
        assertFalse(prefs.getStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY, null)
                .contains(numericId));
        assertFalse(prefs.getStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY, null)
                .contains(rawId));
    }

    private SharedPreferences blocklistPreferences() {
        return RuntimeEnvironment.getApplication()
                .getSharedPreferences(TrackerBlocklist.PREF_BLOCKLIST, Context.MODE_PRIVATE);
    }

    @Test
    public void blockedReturnsTrueForUnknownUid() {
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        assertTrue(blocklist.blocked(9999, "Advertising"));
        assertTrue(blocklist.blocked(9999, "Advertising | Branch"));
    }

    @Test
    public void blockOnNonexistentUidIsNoOp() {
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        blocklist.block(9999, "Advertising");
        assertFalse(blocklist.hasSubset(9999));
        assertTrue(blocklist.blocked(9999, "Advertising"));
    }

    @Test
    public void unblockOnNonexistentUidCreatesEntry() {
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        blocklist.unblock(9999, "Advertising");
        assertTrue(blocklist.hasSubset(9999));
        assertFalse(blocklist.blocked(9999, "Advertising"));
    }

    @Test
    public void contentTrackerNotBlockedWithNonStrictDefaults() {
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        Tracker contentTracker = new Tracker("Akamai", "Content");
        blocklist.ensureDefaults(UID, false);

        assertFalse(blocklist.blockedTracker(UID, contentTracker));
    }

    @Test
    public void contentTrackerBlockedWithStrictDefaults() {
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        Tracker contentTracker = new Tracker("Akamai", "Content");
        blocklist.ensureDefaults(UID, true);

        assertTrue(blocklist.blockedTracker(UID, contentTracker));
    }

    @Test
    public void multipleUidsHaveIndependentBlockingRules() {
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        Tracker tracker = new Tracker("Branch", "Advertising");

        blocklist.ensureDefaults(UID, false);
        blocklist.ensureDefaults(UID + 1, false);

        blocklist.unblock(UID, tracker);
        assertFalse(blocklist.blockedTracker(UID, tracker));
        assertTrue(blocklist.blockedTracker(UID + 1, tracker));
    }

    @Test
    public void blockedTrackerRequiresBothCategoryAndKeyBlocked() {
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        Tracker tracker = new Tracker("Branch", "Advertising");
        blocklist.ensureDefaults(UID, false);

        assertTrue(blocklist.blockedTracker(UID, tracker));

        // Unblock category only -> tracker unblocked
        blocklist.unblock(UID, tracker.category);
        assertFalse(blocklist.blockedTracker(UID, tracker));

        // Re-block category, unblock specific tracker -> still unblocked
        blocklist.block(UID, tracker.category);
        blocklist.unblock(UID, tracker);
        assertFalse(blocklist.blockedTracker(UID, tracker));

        // Unblock both -> definitely unblocked
        blocklist.unblock(UID, tracker.category);
        assertFalse(blocklist.blockedTracker(UID, tracker));

        // Re-block both -> blocked again
        blocklist.block(UID, tracker.category);
        blocklist.block(UID, tracker);
        assertTrue(blocklist.blockedTracker(UID, tracker));
    }

    @Test
    public void concurrentReadersAndWritersDoNotThrow() throws Exception {
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(null);
        int uidCount = 8;
        for (int uid = 0; uid < uidCount; uid++)
            blocklist.ensureDefaults(uid, false);

        final int iterations = 1000;
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        Runnable writer = () -> {
            try {
                for (int i = 0; i < iterations; i++) {
                    blocklist.applyStrictModeToAll(i % 2 == 0);
                    blocklist.unblock(0, "Advertising | Writer");
                    blocklist.block(0, "Advertising | Writer");
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        };
        Runnable reader = () -> {
            try {
                for (int i = 0; i < iterations; i++)
                    for (int uid = 0; uid < uidCount; uid++)
                        blocklist.blocked(uid, "Content");
            } catch (Throwable t) {
                errors.add(t);
            }
        };

        Thread[] threads = {new Thread(writer), new Thread(writer),
                new Thread(reader), new Thread(reader)};
        for (Thread thread : threads)
            thread.start();
        for (Thread thread : threads)
            thread.join(30000);

        assertTrue(errors.toString(), errors.isEmpty());
    }
}
