package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.kollnig.missioncontrol.data.Tracker;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class TrackerCacheTest {
    private static final String ADDRESS = "192.0.2.1";
    private static final long NEVER_EXPIRES = Long.MAX_VALUE;

    @Test
    public void publicationNeverExposesHalfEntry() throws Exception {
        TrackerCache cache = new TrackerCache();
        Tracker tracker = tracker("Example");
        TrackerCache.Entry entry = new TrackerCache.Entry("tracker.example", tracker, null, NEVER_EXPIRES);
        CountDownLatch readerReady = new CountDownLatch(1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            try {
                readerReady.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                while (published.getCount() != 0) {
                    TrackerCache.Entry observed = cache.get(ADDRESS, System.currentTimeMillis());
                    if (observed != null) {
                        assertEquals("tracker.example", observed.getHostname());
                        assertSame(tracker, observed.getTracker());
                    }
                }
                TrackerCache.Entry observed = cache.get(ADDRESS, System.currentTimeMillis());
                assertNotNull(observed);
                assertEquals("tracker.example", observed.getHostname());
                assertSame(tracker, observed.getTracker());
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        reader.start();
        assertTrue(readerReady.await(5, TimeUnit.SECONDS));

        Thread writer = new Thread(() -> {
            try {
                assertTrue(cache.putIfGeneration(ADDRESS, entry, cache.generation()));
            } catch (Throwable ex) {
                failure.set(ex);
            } finally {
                published.countDown();
            }
        });
        writer.start();
        start.countDown();
        writer.join(5_000);
        reader.join(5_000);

        assertFalse("writer did not finish", writer.isAlive());
        assertFalse("reader did not finish", reader.isAlive());
        if (failure.get() != null)
            throw new AssertionError(failure.get());
    }

    @Test
    public void expiredEntryIsRemovedAsOneSnapshot() {
        TrackerCache cache = new TrackerCache();
        TrackerCache.Entry entry = new TrackerCache.Entry(
                "tracker.example", tracker("Example"), null, System.currentTimeMillis() - 1);

        assertTrue(cache.putIfGeneration(ADDRESS, entry, cache.generation()));
        assertNull(cache.get(ADDRESS, System.currentTimeMillis()));
    }

    @Test
    public void expiryBoundaryIsStillValid() {
        TrackerCache cache = new TrackerCache();
        TrackerCache.Entry entry = new TrackerCache.Entry("tracker.example", tracker("Example"), null, 100L);

        assertTrue(cache.putIfGeneration(ADDRESS, entry, cache.generation()));
        assertNotNull(cache.get(ADDRESS, 100L));
        assertNull(cache.get(ADDRESS, 101L));
    }

    @Test
    public void invalidationRejectsStalePublication() {
        TrackerCache cache = new TrackerCache();
        TrackerCache.Entry entry = new TrackerCache.Entry("old.example", tracker("Old"), null, NEVER_EXPIRES);
        long generationBefore = cache.generation();
        assertTrue(cache.putIfGeneration(ADDRESS, entry, generationBefore));

        cache.invalidate(ADDRESS);

        assertNull(cache.get(ADDRESS, System.currentTimeMillis()));
        assertFalse(cache.putIfGeneration(
                ADDRESS,
                new TrackerCache.Entry("stale.example", tracker("Stale"), null, NEVER_EXPIRES),
                generationBefore));
        assertNull(cache.get(ADDRESS, System.currentTimeMillis()));
        assertEquals(generationBefore + 1, cache.generation());
    }

    @Test
    public void clearInvalidatesAllEntries() {
        TrackerCache cache = new TrackerCache();
        long generationBefore = cache.generation();
        assertTrue(cache.putIfGeneration(
                ADDRESS,
                new TrackerCache.Entry("one.example", tracker("One"), null, NEVER_EXPIRES),
                generationBefore));
        assertTrue(cache.putIfGeneration(
                "192.0.2.2",
                new TrackerCache.Entry("two.example", tracker("Two"), null, NEVER_EXPIRES),
                generationBefore));

        cache.clear();

        assertNull(cache.get(ADDRESS, System.currentTimeMillis()));
        assertNull(cache.get("192.0.2.2", System.currentTimeMillis()));
        assertEquals(generationBefore + 1, cache.generation());
    }

    @Test
    public void invalidateCannotLoseRaceToPublication() throws Exception {
        assertRaceLeavesAddressEmpty(false);
    }

    @Test
    public void clearCannotLoseRaceToPublication() throws Exception {
        assertRaceLeavesAddressEmpty(true);
    }

    @Test
    public void blockingSnapshotAlwaysContainsTracker() {
        TrackerCache cache = new TrackerCache();
        TrackerCache.Entry entry = new TrackerCache.Entry(
                ServiceSinkhole.NO_DNAME, ServiceSinkhole.NO_TRACKER, null, NEVER_EXPIRES);

        assertTrue(cache.putIfGeneration(ADDRESS, entry, cache.generation()));
        TrackerCache.Entry blockingSnapshot = cache.get(ADDRESS, System.currentTimeMillis());
        assertNotNull(blockingSnapshot);
        assertSame(ServiceSinkhole.NO_DNAME, blockingSnapshot.getHostname());
        assertSame(ServiceSinkhole.NO_TRACKER, blockingSnapshot.getTracker());
    }

    @Test
    public void nullTrackerCannotEnterCache() {
        try {
            new TrackerCache.Entry("tracker.example", null, null, NEVER_EXPIRES);
            fail("null tracker must not be cacheable");
        } catch (NullPointerException expected) {
            // The blocking path can only read entries made through this value.
        }
    }

    @Test
    public void nullHostnameCannotEnterCache() {
        try {
            new TrackerCache.Entry(null, tracker("Example"), null, NEVER_EXPIRES);
            fail("null hostname must not be cacheable");
        } catch (NullPointerException expected) {
            // The blocking path can only read complete snapshots.
        }
    }

    private static void assertRaceLeavesAddressEmpty(boolean clear) throws Exception {
        for (int attempt = 0; attempt < 128; attempt++) {
            TrackerCache cache = new TrackerCache();
            long generation = cache.generation();
            assertTrue(cache.putIfGeneration(
                    ADDRESS,
                    new TrackerCache.Entry("old.example", tracker("Old"), null, NEVER_EXPIRES),
                    generation));
            CyclicBarrier barrier = new CyclicBarrier(3);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread publisher = new Thread(() -> {
                try {
                    barrier.await();
                    cache.putIfGeneration(
                            ADDRESS,
                            new TrackerCache.Entry("stale.example", tracker("Stale"), null, NEVER_EXPIRES),
                            generation);
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                }
            });
            Thread invalidator = new Thread(() -> {
                try {
                    barrier.await();
                    if (clear)
                        cache.clear();
                    else
                        cache.invalidate(ADDRESS);
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                }
            });
            publisher.start();
            invalidator.start();
            barrier.await();
            publisher.join(5_000);
            invalidator.join(5_000);

            assertFalse("publisher did not finish", publisher.isAlive());
            assertFalse("invalidator did not finish", invalidator.isAlive());
            if (failure.get() != null)
                throw new AssertionError(failure.get());
            assertNull("stale publication survived invalidation", cache.get(ADDRESS, System.currentTimeMillis()));
        }
    }

    private static Tracker tracker(String name) {
        return new Tracker(name, "Advertising", 0);
    }
}
