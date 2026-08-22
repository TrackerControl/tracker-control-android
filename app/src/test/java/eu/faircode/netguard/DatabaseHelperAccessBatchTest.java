package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;
import android.os.Handler;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Covers the access/usage batching added to collapse per-flow SQLite writes
 * into a single transaction (see updateAccess/updateUsage in DatabaseHelper).
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseHelperAccessBatchTest {

    @Test
    public void repeatedUpdateAccessForSameKeyCoalescesIntoOneRow() {
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        dh.clearAccess();

        int uid = 12345;
        Packet p1 = packet(uid, "tracker.example.com", 443, 1_000L, true);
        Packet p2 = packet(uid, "tracker.example.com", 443, 2_000L, false);

        dh.updateAccess(p1, null, -1, DatabaseHelper.ACCESS_UNCERTAIN_NONE);
        dh.updateAccess(p2, null, -1, DatabaseHelper.ACCESS_UNCERTAIN_SHARED_IP);
        dh.flushAccessBatch();

        try (Cursor c = dh.getAccess(uid)) {
            assertEquals("two updates to the same key must coalesce into one row", 1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals(2000L, c.getLong(c.getColumnIndexOrThrow("time")));
            assertEquals("last write should win", 0, c.getInt(c.getColumnIndexOrThrow("allowed")));
            assertEquals(DatabaseHelper.ACCESS_UNCERTAIN_SHARED_IP,
                    c.getInt(c.getColumnIndexOrThrow("uncertain")));
        }
    }

    @Test
    public void repeatedUpdateUsageForSameKeyAccumulatesInsteadOfOverwriting() {
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        dh.clearAccess();

        int uid = 54321;
        Packet seed = packet(uid, "tracker.example.com", 443, 1_000L, true);
        dh.updateAccess(seed, null, -1, DatabaseHelper.ACCESS_UNCERTAIN_NONE);
        dh.flushAccessBatch();

        Usage u1 = usage(uid, "tracker.example.com", 443, 100, 200);
        Usage u2 = usage(uid, "tracker.example.com", 443, 300, 400);
        dh.updateUsage(u1, null);
        dh.updateUsage(u2, null);
        dh.flushUsageBatch();

        try (Cursor c = dh.getAccess(uid)) {
            assertTrue(c.moveToFirst());
            assertEquals(400L, c.getLong(c.getColumnIndexOrThrow("sent")));
            assertEquals(600L, c.getLong(c.getColumnIndexOrThrow("received")));
            assertEquals(2, c.getInt(c.getColumnIndexOrThrow("connections")));
        }
    }

    @Test
    public void usageFlushBeforeAccessFlushStillAccumulates() {
        // Regression: flushUsageBatch used to run independently of
        // accessBatch, so a usage delta whose access row was still pending
        // (not yet flushed) matched zero rows and was silently dropped.
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        dh.clearAccess();

        int uid = 24680;
        Packet seed = packet(uid, "tracker.example.com", 443, 1_000L, true);
        dh.updateAccess(seed, null, -1, DatabaseHelper.ACCESS_UNCERTAIN_NONE);
        // Note: no flushAccessBatch() here — the access row is still pending.

        Usage u = usage(uid, "tracker.example.com", 443, 100, 200);
        dh.updateUsage(u, null);
        dh.flushUsageBatch();

        try (Cursor c = dh.getAccess(uid)) {
            assertTrue(c.moveToFirst());
            assertEquals(100L, c.getLong(c.getColumnIndexOrThrow("sent")));
            assertEquals(200L, c.getLong(c.getColumnIndexOrThrow("received")));
            assertEquals(1, c.getInt(c.getColumnIndexOrThrow("connections")));
        }
    }

    @Test
    public void specifiedBlockSurvivesLaterUnspecifiedUpdateInSameBatch() {
        // A block-setting update (block >= 0) followed by an ordinary
        // (block < 0) update for the same key within one batch window must not
        // drop the block: an unbatched sequence would leave the row's block
        // untouched by the second write, so last-write-wins has to carry it.
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        dh.clearAccess();

        int uid = 13579;
        dh.updateAccess(packet(uid, "tracker.example.com", 443, 1_000L, true), null,
                1, DatabaseHelper.ACCESS_UNCERTAIN_NONE);
        dh.updateAccess(packet(uid, "tracker.example.com", 443, 2_000L, false), null,
                -1, DatabaseHelper.ACCESS_UNCERTAIN_NONE);
        dh.flushAccessBatch();

        try (Cursor c = dh.getAccess(uid)) {
            assertTrue(c.moveToFirst());
            assertEquals("specified block must not be lost when a later update omits it",
                    1, c.getInt(c.getColumnIndexOrThrow("block")));
        }
    }

    @Test
    public void differentKeysInSameBatchProduceSeparateRows() {
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        dh.clearAccess();

        int uid = 99999;
        dh.updateAccess(packet(uid, "a.example.com", 443, 1_000L, true), null,
                -1, DatabaseHelper.ACCESS_UNCERTAIN_NONE);
        dh.updateAccess(packet(uid, "b.example.com", 443, 1_000L, true), null,
                -1, DatabaseHelper.ACCESS_UNCERTAIN_NONE);
        dh.flushAccessBatch();

        try (Cursor c = dh.getAccess(uid)) {
            assertEquals(2, c.getCount());
        }
    }

    @Test
    public void repeatedAccessNotificationsCoalesceWithinWindow() throws Exception {
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        Handler handler = databaseHandler();
        waitForHandlerIdle(handler);
        handler.removeCallbacksAndMessages(null);

        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch firstCallback = new CountDownLatch(1);
        DatabaseHelper.AccessChangedListener listener = () -> {
            callbacks.incrementAndGet();
            firstCallback.countDown();
        };
        dh.addAccessChangedListener(listener);
        try {
            notifyAccessChanged(dh);
            notifyAccessChanged(dh);
            notifyAccessChanged(dh);

            ShadowLooper shadowLooper = Shadows.shadowOf(handler.getLooper());
            shadowLooper.idleFor(1100, TimeUnit.MILLISECONDS);
            assertTrue("batched notification was not delivered",
                    firstCallback.await(3, TimeUnit.SECONDS));
            waitForHandlerIdle(handler);
            assertEquals("notifications in one window must coalesce", 1, callbacks.get());
        } finally {
            dh.removeAccessChangedListener(listener);
        }
    }

    @Test
    public void notificationBurstDoesNotBlockDatabaseHandler() throws Exception {
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        Handler handler = databaseHandler();
        waitForHandlerIdle(handler);
        handler.removeCallbacksAndMessages(null);

        notifyAccessChanged(dh);
        notifyAccessChanged(dh);
        notifyAccessChanged(dh);

        try {
            CountDownLatch runnableRan = new CountDownLatch(1);
            AtomicLong elapsedMs = new AtomicLong();
            long startNanos = System.nanoTime();
            handler.post(() -> {
                elapsedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
                runnableRan.countDown();
            });

            assertTrue("database handler runnable was delayed",
                    runnableRan.await(3, TimeUnit.SECONDS));
            assertTrue("database handler runnable took " + elapsedMs.get() + " ms",
                    elapsedMs.get() < 500);
        } finally {
            handler.removeCallbacksAndMessages(null);
        }
    }

    private static Handler databaseHandler() throws Exception {
        Field field = DatabaseHelper.class.getDeclaredField("handler");
        field.setAccessible(true);
        return (Handler) field.get(null);
    }

    private static void notifyAccessChanged(DatabaseHelper dh) throws Exception {
        Method method = DatabaseHelper.class.getDeclaredMethod("notifyAccessChanged");
        method.setAccessible(true);
        method.invoke(dh);
    }

    private static void waitForHandlerIdle(Handler handler) throws InterruptedException {
        CountDownLatch idle = new CountDownLatch(1);
        handler.post(idle::countDown);
        assertTrue("database handler did not become idle",
                idle.await(3, TimeUnit.SECONDS));
    }

    private static Packet packet(int uid, String daddr, int dport, long time, boolean allowed) {
        Packet p = new Packet();
        p.time = time;
        p.version = 4;
        p.protocol = 6;
        p.uid = uid;
        p.daddr = daddr;
        p.dport = dport;
        p.allowed = allowed;
        p.flags = "";
        p.saddr = "10.0.0.2";
        p.sport = 12345;
        return p;
    }

    private static Usage usage(int uid, String daddr, int dport, long sent, long received) {
        Usage u = new Usage();
        u.Uid = uid;
        u.Version = 4;
        u.Protocol = 6;
        u.DAddr = daddr;
        u.DPort = dport;
        u.Sent = sent;
        u.Received = received;
        u.Time = System.currentTimeMillis();
        return u;
    }
}
