/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package net.kollnig.missioncontrol.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;

import androidx.preference.PreferenceManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import eu.faircode.netguard.ServiceSinkhole;

@RunWith(RobolectricTestRunner.class)
public class TrackerListReloadTest {
    private static final String BLOCKING_MODE = BlockingMode.PREF_BLOCKING_MODE;
    private Context context;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .clear().commit();
        ServiceSinkhole.mapHostsBlocked.clear();
        resetTrackerList();
    }

    @After
    public void tearDown() throws Exception {
        ServiceSinkhole.mapHostsBlocked.clear();
        resetTrackerList();
    }

    @Test
    public void successfulReloadPublishesADistinctSnapshotWithoutMutatingTheOldOne() throws Exception {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(BLOCKING_MODE, BlockingMode.MODE_STANDARD)
                .putBoolean("domain_based_blocking", false).commit();
        TrackerList.getInstance(context);
        ServiceSinkhole.mapHostsBlocked.put("dynamic.example", true);
        Tracker cachedDynamicTracker = TrackerList.findTracker("dynamic.example");
        assertNotNull(cachedDynamicTracker);

        Object oldSnapshot = getPrivateStaticField("trackerSnapshot");
        Map<String, Tracker> oldMap = getSnapshotMap(oldSnapshot);
        int oldMapSize = oldMap.size();

        assertTrue(TrackerList.reloadTrackerData(context));

        Object newSnapshot = getPrivateStaticField("trackerSnapshot");
        Map<String, Tracker> newMap = getSnapshotMap(newSnapshot);

        assertNotSame(oldSnapshot, newSnapshot);
        assertNotSame(oldMap, newMap);
        assertEquals(oldMapSize, oldMap.size());
        assertSame(cachedDynamicTracker, oldMap.get("dynamic.example"));
        assertNull(newMap.get("dynamic.example"));
        assertNotNull(TrackerList.findTracker("crashlytics.com"));
    }

    @Test
    public void concurrentInitialisationReturnsOneSingleton() throws Exception {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(BLOCKING_MODE, BlockingMode.MODE_MINIMAL).commit();
        int callers = 8;
        TrackerList[] results = new TrackerList[callers];
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(callers);
        Thread[] threads = new Thread[callers];
        for (int i = 0; i < callers; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    results[index] = TrackerList.getInstance(context);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
            threads[i].start();
        }
        start.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS));
        for (Thread thread : threads)
            thread.join(1000);

        for (int i = 1; i < callers; i++)
            assertSame(results[0], results[i]);
    }

    @Test
    public void failedReloadRetainsPreviousSnapshot() throws Exception {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(BLOCKING_MODE, BlockingMode.MODE_STANDARD).commit();
        TrackerList.getInstance(context);
        Tracker oldTracker = TrackerList.findTracker("crashlytics.com");
        assertNotNull(oldTracker);
        assertEquals(BlockingMode.MODE_STANDARD, TrackerList.getBlockingMode(context));

        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(BLOCKING_MODE, BlockingMode.MODE_MINIMAL).commit();

        Constructor<AssetManager> assetConstructor = AssetManager.class.getDeclaredConstructor();
        assetConstructor.setAccessible(true);
        AssetManager brokenAssets = assetConstructor.newInstance();
        Context brokenContext = new ContextWrapper(context) {
            @Override
            public AssetManager getAssets() {
                return brokenAssets;
            }
        };

        assertFalse(TrackerList.reloadTrackerData(brokenContext));

        assertSame(oldTracker, TrackerList.findTracker("crashlytics.com"));
        assertEquals(BlockingMode.MODE_STANDARD, TrackerList.getBlockingMode(context));
    }

    /**
     * With no snapshot ever published there is no loaded mode to stay
     * consistent with, so the preference must win. Reporting the bootstrap
     * placeholder would silently drop a Strict-mode user to the default.
     */
    @Test
    public void blockingModeFallsBackToThePrefWhenNoLoadEverSucceeded() throws Exception {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(BLOCKING_MODE, BlockingMode.MODE_STRICT).commit();

        Constructor<AssetManager> assetConstructor = AssetManager.class.getDeclaredConstructor();
        assetConstructor.setAccessible(true);
        AssetManager brokenAssets = assetConstructor.newInstance();
        Context brokenContext = new ContextWrapper(context) {
            @Override
            public AssetManager getAssets() {
                return brokenAssets;
            }
        };

        assertFalse(TrackerList.reloadTrackerData(brokenContext));

        assertEquals(BlockingMode.MODE_STRICT, TrackerList.getBlockingMode(context));
    }

    @Test
    public void modeSelectionPublishesOnlyTheSelectedLists() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(BLOCKING_MODE, BlockingMode.MODE_MINIMAL).commit();
        TrackerList.getInstance(context);
        assertNotNull(TrackerList.findTracker("creativecdn.com"));
        assertNull(TrackerList.findTracker("crashlytics.com"));

        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(BLOCKING_MODE, BlockingMode.MODE_STANDARD).commit();
        assertTrue(TrackerList.reloadTrackerData(context));
        assertNotNull(TrackerList.findTracker("crashlytics.com"));
    }

    @Test
    public void dynamicHostEntriesAreCachedInTheActiveSnapshot() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(BLOCKING_MODE, BlockingMode.MODE_STANDARD)
                .putBoolean("domain_based_blocking", false).commit();
        TrackerList.getInstance(context);
        ServiceSinkhole.mapHostsBlocked.put("dynamic.example", true);

        Tracker first = TrackerList.findTracker("DYNAMIC.EXAMPLE");
        Tracker second = TrackerList.findTracker("dynamic.example");

        assertNotNull(first);
        assertSame(first, second);
        assertEquals("dynamic.example", first.getName());
    }

    @Test
    public void domainBasedDynamicHostsUseTheSharedHostlistTracker() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(BLOCKING_MODE, BlockingMode.MODE_STANDARD)
                .putBoolean("domain_based_blocking", true).commit();
        TrackerList.getInstance(context);
        ServiceSinkhole.mapHostsBlocked.put("dynamic.example", true);

        Tracker first = TrackerList.findTracker("dynamic.example");
        Tracker second = TrackerList.findTracker("dynamic.example");

        assertNotNull(first);
        assertSame(first, second);
        assertEquals(TrackerList.TRACKER_HOSTLIST, first.getName());
    }

    private static void resetTrackerList() throws Exception {
        Field instance = TrackerList.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);

        Class<?> snapshotClass = Class.forName(
                "net.kollnig.missioncontrol.data.TrackerList$TrackerSnapshot");
        Constructor<?> constructor = snapshotClass.getDeclaredConstructor(
                Map.class, boolean.class, boolean.class, String.class, boolean.class);
        constructor.setAccessible(true);
        Object emptySnapshot = constructor.newInstance(
                new ConcurrentHashMap<String, Tracker>(), false, false,
                BlockingMode.getDefaultMode(), false);
        Field snapshot = TrackerList.class.getDeclaredField("trackerSnapshot");
        snapshot.setAccessible(true);
        snapshot.set(null, emptySnapshot);
    }

    private static Object getPrivateStaticField(String name) throws Exception {
        Field field = TrackerList.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Tracker> getSnapshotMap(Object snapshot) throws Exception {
        Field map = snapshot.getClass().getDeclaredField("hostnameToTracker");
        map.setAccessible(true);
        return (Map<String, Tracker>) map.get(snapshot);
    }
}
