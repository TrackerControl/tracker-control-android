/*
 * This file is from NetGuard.
 *
 * NetGuard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NetGuard is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2015–2020 Marcel Bokhorst (M66B)
 * Copyright © 2019–2026 Konrad Kollnig
 */

package eu.faircode.netguard;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DatabaseHelper extends SQLiteOpenHelper {
    public static final int ACCESS_UNCERTAIN_NONE = 0;
    public static final int ACCESS_UNCERTAIN_SHARED_IP = 1;
    public static final int ACCESS_UNCERTAIN_MIXED_TRACKER_AND_NON_TRACKER = 2;
    public static final int ACCESS_UNCERTAIN_MULTIPLE_TRACKERS = 3;

    public enum DnsInsertOutcome { FAILED, INSERTED, REFRESHED }

    public Cursor getHosts(int uid) {
        flushAccessBatch();
        flushUsageBatch();
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is a segmented index on uid
            // There is an index on block
            return db.query(true, "access", new String[] { "daddr", "time", "uncertain" }, "uid = ?",
                    new String[] { Integer.toString(uid) }, null, null, null, null);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Cursor getHosts() {
        flushAccessBatch();
        flushUsageBatch();
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is a segmented index on uid
            // There is an index on block
            return db.query(true, "access", new String[] { "uid", "daddr", "time", "uncertain" }, null, null, null,
                    null, null, null);
        } finally {
            lock.readLock().unlock();
        }
    }

    private static final String TAG = "TrackerControl.Database";

    private static final String DB_NAME = "Netguard";
    private static final int DB_VERSION = 23;

    private static boolean once = true;
    // CopyOnWriteArrayList: listeners are added/removed from activity/fragment
    // lifecycle methods on the main thread while handleChangedNotification()
    // iterates them on the DB handler thread. A plain ArrayList let a
    // same-tick removal throw ConcurrentModificationException out of that
    // iteration, which is not covered by the per-listener try/catch below and
    // kills the notification thread.
    private static List<LogChangedListener> logChangedListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static List<AccessChangedListener> accessChangedListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static List<ForwardChangedListener> forwardChangedListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static HandlerThread hthread = null;
    private static Handler handler = null;

    private static final Map<Integer, Long> mapUidHosts = new HashMap<>();

    private final static int MSG_LOG = 1;
    private final static int MSG_ACCESS = 2;
    private final static int MSG_FORWARD = 3;
    private static final long NOTIFY_BATCH_MS = 1000;

    private SharedPreferences prefs;
    private ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    // Log batching to reduce per-packet database I/O
    private static final int LOG_BATCH_SIZE = 50;
    private static final long LOG_BATCH_FLUSH_MS = 5000;
    private final List<ContentValues> logBatch = new ArrayList<>();
    private long lastLogFlush = System.currentTimeMillis();

    // Access/usage batching: coalesces repeated updates to the same
    // (uid, version, protocol, daddr, dport) row into a single write instead
    // of one transaction per tracker flow.
    private static final int ACCESS_BATCH_SIZE = 50;
    private static final long ACCESS_BATCH_FLUSH_MS = 2000;
    private final Map<AccessKey, PendingAccess> accessBatch = new LinkedHashMap<>();
    private long lastAccessFlush = System.currentTimeMillis();
    // Bounds how long the tail of a burst can sit unflushed: a size/next-write
    // flush may never come if traffic goes quiet, so the first entry of an
    // otherwise-idle batch schedules a time-based flush on the DB handler
    // thread. Without this, a lone late access update stays invisible to
    // listeners (no notifyAccessChanged) until the next reader or shutdown.
    private final Runnable accessFlushRunnable = this::flushAccessBatch;
    private final Runnable usageFlushRunnable = this::flushUsageBatch;

    private static final int USAGE_BATCH_SIZE = 50;
    private static final long USAGE_BATCH_FLUSH_MS = 2000;
    private final Map<AccessKey, long[]> usageBatch = new LinkedHashMap<>();
    private long lastUsageFlush = System.currentTimeMillis();

    private static final class AccessKey {
        final int uid, version, protocol, dport;
        final String daddr;

        AccessKey(int uid, int version, int protocol, String daddr, int dport) {
            this.uid = uid;
            this.version = version;
            this.protocol = protocol;
            this.daddr = daddr;
            this.dport = dport;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof AccessKey))
                return false;
            AccessKey k = (AccessKey) o;
            return uid == k.uid && version == k.version && protocol == k.protocol &&
                    dport == k.dport && daddr.equals(k.daddr);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(uid, version, protocol, daddr, dport);
        }
    }

    // Last-write-wins per key: only the latest state of a rapidly repeated
    // access update needs to reach the row.
    private static final class PendingAccess {
        long time;
        boolean allowed;
        int uncertain;
        boolean blockSpecified;
        int block;
    }

    static {
        hthread = new HandlerThread("DatabaseHelper");
        hthread.start();
        handler = new Handler(hthread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                handleChangedNotification(msg);
            }
        };
    }

    private static DatabaseHelper dh = null;

    public static DatabaseHelper getInstance(Context context) {
        if (dh == null)
            dh = new DatabaseHelper(context.getApplicationContext());
        return dh;
    }

    public static void clearCache() {
        synchronized (mapUidHosts) {
            mapUidHosts.clear();
        }
    }

    @Override
    public void close() {
        Log.w(TAG, "Database is being closed");
    }

    private DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (!once) {
            once = true;

            File dbfile = context.getDatabasePath(DB_NAME);
            if (dbfile.exists()) {
                Log.w(TAG, "Deleting " + dbfile);
                dbfile.delete();
            }

            File dbjournal = context.getDatabasePath(DB_NAME + "-journal");
            if (dbjournal.exists()) {
                Log.w(TAG, "Deleting " + dbjournal);
                dbjournal.delete();
            }
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.i(TAG, "Creating database " + DB_NAME + " version " + DB_VERSION);
        createTableLog(db);
        createTableAccess(db);
        createTableDns(db);
        createTableForward(db);
        createTableApp(db);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.enableWriteAheadLogging();
        super.onConfigure(db);
    }

    private void createTableLog(SQLiteDatabase db) {
        Log.i(TAG, "Creating log table");
        db.execSQL("CREATE TABLE log (" +
                " ID INTEGER PRIMARY KEY AUTOINCREMENT" +
                ", time INTEGER NOT NULL" +
                ", version INTEGER" +
                ", protocol INTEGER" +
                ", flags TEXT" +
                ", saddr TEXT" +
                ", sport INTEGER" +
                ", daddr TEXT" +
                ", dport INTEGER" +
                ", dname TEXT" +
                ", uid INTEGER" +
                ", data TEXT" +
                ", allowed INTEGER" +
                ", connection INTEGER" +
                ", interactive INTEGER" +
                ");");
        db.execSQL("CREATE INDEX idx_log_time ON log(time)");
        db.execSQL("CREATE INDEX idx_log_dest ON log(daddr)");
        db.execSQL("CREATE INDEX idx_log_dname ON log(dname)");
        db.execSQL("CREATE INDEX idx_log_dport ON log(dport)");
        db.execSQL("CREATE INDEX idx_log_uid ON log(uid)");
    }

    private void createTableAccess(SQLiteDatabase db) {
        Log.i(TAG, "Creating access table");
        db.execSQL("CREATE TABLE access (" +
                " ID INTEGER PRIMARY KEY AUTOINCREMENT" +
                ", uid INTEGER NOT NULL" +
                ", version INTEGER NOT NULL" +
                ", protocol INTEGER NOT NULL" +
                ", daddr TEXT NOT NULL" +
                ", dport INTEGER NOT NULL" +
                ", time INTEGER NOT NULL" +
                ", allowed INTEGER" +
                ", block INTEGER NOT NULL" +
                ", sent INTEGER" +
                ", received INTEGER" +
                ", connections INTEGER" +
                ", uncertain INTEGER" +
                ");");
        db.execSQL("CREATE UNIQUE INDEX idx_access ON access(uid, version, protocol, daddr, dport)");
        db.execSQL("CREATE INDEX idx_access_daddr ON access(daddr)");
        db.execSQL("CREATE INDEX idx_access_block ON access(block)");
    }

    private void createTableDns(SQLiteDatabase db) {
        Log.i(TAG, "Creating dns table");
        db.execSQL("CREATE TABLE dns (" +
                " ID INTEGER PRIMARY KEY AUTOINCREMENT" +
                ", time INTEGER NOT NULL" +
                ", qname TEXT NOT NULL" +
                ", aname TEXT NOT NULL" +
                ", resource TEXT NOT NULL" +
                ", ttl INTEGER" +
                ");");
        db.execSQL("CREATE UNIQUE INDEX idx_dns ON dns(qname, aname, resource)");
        db.execSQL("CREATE INDEX idx_dns_resource ON dns(resource)");
    }

    private void createTableForward(SQLiteDatabase db) {
        Log.i(TAG, "Creating forward table");
        db.execSQL("CREATE TABLE forward (" +
                " ID INTEGER PRIMARY KEY AUTOINCREMENT" +
                ", protocol INTEGER NOT NULL" +
                ", dport INTEGER NOT NULL" +
                ", raddr TEXT NOT NULL" +
                ", rport INTEGER NOT NULL" +
                ", ruid INTEGER NOT NULL" +
                ");");
        db.execSQL("CREATE UNIQUE INDEX idx_forward ON forward(protocol, dport)");
    }

    private void createTableApp(SQLiteDatabase db) {
        Log.i(TAG, "Creating app table");
        db.execSQL("CREATE TABLE app (" +
                " ID INTEGER PRIMARY KEY AUTOINCREMENT" +
                ", package TEXT" +
                ", label TEXT" +
                ", system INTEGER  NOT NULL" +
                ", internet INTEGER NOT NULL" +
                ", enabled INTEGER NOT NULL" +
                ");");
        db.execSQL("CREATE UNIQUE INDEX idx_package ON app(package)");
    }

    private boolean columnExists(SQLiteDatabase db, String table, String column) {
        try (Cursor cursor = db.rawQuery("SELECT * FROM " + table + " LIMIT 0", null)) {
            return (cursor.getColumnIndex(column) >= 0);
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            return false;
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.i(TAG, DB_NAME + " upgrading from version " + oldVersion + " to " + newVersion);

        db.beginTransaction();
        try {
            if (oldVersion < 2) {
                if (!columnExists(db, "log", "version"))
                    db.execSQL("ALTER TABLE log ADD COLUMN version INTEGER");
                if (!columnExists(db, "log", "protocol"))
                    db.execSQL("ALTER TABLE log ADD COLUMN protocol INTEGER");
                if (!columnExists(db, "log", "uid"))
                    db.execSQL("ALTER TABLE log ADD COLUMN uid INTEGER");
                oldVersion = 2;
            }
            if (oldVersion < 3) {
                if (!columnExists(db, "log", "port"))
                    db.execSQL("ALTER TABLE log ADD COLUMN port INTEGER");
                if (!columnExists(db, "log", "flags"))
                    db.execSQL("ALTER TABLE log ADD COLUMN flags TEXT");
                oldVersion = 3;
            }
            if (oldVersion < 4) {
                if (!columnExists(db, "log", "connection"))
                    db.execSQL("ALTER TABLE log ADD COLUMN connection INTEGER");
                oldVersion = 4;
            }
            if (oldVersion < 5) {
                if (!columnExists(db, "log", "interactive"))
                    db.execSQL("ALTER TABLE log ADD COLUMN interactive INTEGER");
                oldVersion = 5;
            }
            if (oldVersion < 6) {
                if (!columnExists(db, "log", "allowed"))
                    db.execSQL("ALTER TABLE log ADD COLUMN allowed INTEGER");
                oldVersion = 6;
            }
            if (oldVersion < 7) {
                db.execSQL("DROP TABLE log");
                createTableLog(db);
                oldVersion = 8;
            }
            if (oldVersion < 8) {
                if (!columnExists(db, "log", "data"))
                    db.execSQL("ALTER TABLE log ADD COLUMN data TEXT");
                db.execSQL("DROP INDEX idx_log_source");
                db.execSQL("DROP INDEX idx_log_dest");
                db.execSQL("CREATE INDEX idx_log_source ON log(saddr)");
                db.execSQL("CREATE INDEX idx_log_dest ON log(daddr)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_log_uid ON log(uid)");
                oldVersion = 8;
            }
            if (oldVersion < 9) {
                createTableAccess(db);
                oldVersion = 9;
            }
            if (oldVersion < 10) {
                db.execSQL("DROP TABLE log");
                db.execSQL("DROP TABLE access");
                createTableLog(db);
                createTableAccess(db);
                oldVersion = 10;
            }
            if (oldVersion < 12) {
                db.execSQL("DROP TABLE access");
                createTableAccess(db);
                oldVersion = 12;
            }
            if (oldVersion < 13) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_log_dport ON log(dport)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_log_dname ON log(dname)");
                oldVersion = 13;
            }
            if (oldVersion < 14) {
                createTableDns(db);
                oldVersion = 14;
            }
            if (oldVersion < 15) {
                db.execSQL("DROP TABLE access");
                createTableAccess(db);
                oldVersion = 15;
            }
            if (oldVersion < 16) {
                createTableForward(db);
                oldVersion = 16;
            }
            if (oldVersion < 17) {
                if (!columnExists(db, "access", "sent"))
                    db.execSQL("ALTER TABLE access ADD COLUMN sent INTEGER");
                if (!columnExists(db, "access", "received"))
                    db.execSQL("ALTER TABLE access ADD COLUMN received INTEGER");
                oldVersion = 17;
            }
            if (oldVersion < 18) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_access_block ON access(block)");
                db.execSQL("DROP INDEX idx_dns");
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_dns ON dns(qname, aname, resource)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_dns_resource ON dns(resource)");
                oldVersion = 18;
            }
            if (oldVersion < 19) {
                if (!columnExists(db, "access", "connections"))
                    db.execSQL("ALTER TABLE access ADD COLUMN connections INTEGER");
                oldVersion = 19;
            }
            if (oldVersion < 20) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_access_daddr ON access(daddr)");
                oldVersion = 20;
            }
            if (oldVersion < 21) {
                createTableApp(db);
                oldVersion = 21;
            }
            if (oldVersion < 22) {
                db.execSQL("ALTER TABLE access ADD COLUMN uncertain INTEGER");
                oldVersion = 22;
            }
            if (oldVersion < 23) {
                // Remove case-variant duplicates before lowercasing because idx_dns is UNIQUE.
                // Keep the freshest row for each DNS identity, breaking time ties by ID.
                db.execSQL("DELETE FROM dns WHERE ID NOT IN (" +
                        "SELECT MAX(d.ID) FROM dns d" +
                        " JOIN (" +
                        "SELECT lower(qname) AS qname, lower(aname) AS aname, resource, MAX(time) AS time" +
                        " FROM dns" +
                        " GROUP BY lower(qname), lower(aname), resource" +
                        ") latest ON lower(d.qname) = latest.qname" +
                        " AND lower(d.aname) = latest.aname" +
                        " AND d.resource = latest.resource" +
                        " AND d.time = latest.time" +
                        " GROUP BY latest.qname, latest.aname, latest.resource)");
                db.execSQL("UPDATE dns SET qname = lower(qname), aname = lower(aname)");
                oldVersion = 23;
            }

            if (oldVersion == DB_VERSION) {
                db.setVersion(oldVersion);
                db.setTransactionSuccessful();
                Log.i(TAG, DB_NAME + " upgraded to " + DB_VERSION);
            } else
                throw new IllegalArgumentException(
                        DB_NAME + " upgraded to " + oldVersion + " but required " + DB_VERSION);

        } catch (Throwable ex) {
            // Rethrow rather than swallow: SQLiteOpenHelper wraps onUpgrade and its own
            // version write in an outer transaction, so propagating the failure rolls
            // back the version as well and the migration runs again on the next open.
            // Returning normally would leave the old schema marked as the new version.
            Log.e(TAG, DB_NAME + " upgrade failed at version " + oldVersion + ": "
                    + ex + "\n" + Log.getStackTraceString(ex));
            throw ex;
        } finally {
            db.endTransaction();
        }
    }

    // Log
    public void insertLog(Packet packet, String dname, int connection, boolean interactive) {
        ContentValues cv = new ContentValues();
        cv.put("time", packet.time);
        cv.put("version", packet.version);

        if (packet.protocol < 0)
            cv.putNull("protocol");
        else
            cv.put("protocol", packet.protocol);

        cv.put("flags", packet.flags);

        cv.put("saddr", packet.saddr);
        if (packet.sport < 0)
            cv.putNull("sport");
        else
            cv.put("sport", packet.sport);

        cv.put("daddr", packet.daddr);
        if (packet.dport < 0)
            cv.putNull("dport");
        else
            cv.put("dport", packet.dport);

        if (dname == null)
            cv.putNull("dname");
        else
            cv.put("dname", dname);

        cv.put("data", packet.data);

        if (packet.uid < 0)
            cv.putNull("uid");
        else
            cv.put("uid", packet.uid);

        cv.put("allowed", packet.allowed ? 1 : 0);

        cv.put("connection", connection);
        cv.put("interactive", interactive ? 1 : 0);

        synchronized (logBatch) {
            logBatch.add(cv);
            long now = System.currentTimeMillis();
            if (logBatch.size() >= LOG_BATCH_SIZE || now - lastLogFlush >= LOG_BATCH_FLUSH_MS)
                flushLogBatch();
        }
    }

    public void flushLogBatch() {
        List<ContentValues> batch;
        synchronized (logBatch) {
            if (logBatch.isEmpty())
                return;
            batch = new ArrayList<>(logBatch);
            logBatch.clear();
            lastLogFlush = System.currentTimeMillis();
        }

        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                for (ContentValues cv : batch) {
                    if (db.insert("log", null, cv) == -1)
                        Log.e(TAG, "Insert log failed");
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }

        notifyLogChanged();
    }

    public void clearLog(int uid) {
        flushLogBatch();
        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                if (uid < 0)
                    db.delete("log", null, new String[] {});
                else
                    db.delete("log", "uid = ?", new String[] { Integer.toString(uid) });

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            db.execSQL("VACUUM");
        } finally {
            lock.writeLock().unlock();
        }

        notifyLogChanged();
    }

    public void cleanupLog(long time) {
        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                // There an index on time
                int rows = db.delete("log", "time < ?", new String[] { Long.toString(time) });
                Log.i(TAG, "Cleanup log" +
                        " before=" + SimpleDateFormat.getDateTimeInstance().format(new Date(time)) +
                        " rows=" + rows);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Cursor getLog(boolean udp, boolean tcp, boolean other, boolean allowed, boolean blocked) {
        flushLogBatch();
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is an index on time
            // There is no index on protocol/allowed for write performance
            String query = "SELECT ID AS _id, *";
            query += " FROM log";
            query += " WHERE (0 = 1";
            if (udp)
                query += " OR protocol = 17";
            if (tcp)
                query += " OR protocol = 6";
            if (other)
                query += " OR (protocol <> 6 AND protocol <> 17)";
            query += ") AND (0 = 1";
            if (allowed)
                query += " OR allowed = 1";
            if (blocked)
                query += " OR allowed = 0";
            query += ")";
            query += " ORDER BY time DESC";
            return db.rawQuery(query, new String[] {});
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Check whether the traffic log contains a recent allowed DoT flow.
     *
     * <p>The caller gates this on the full traffic-log preference: the log is
     * intentionally empty when that preference is off, and the application log
     * is only a tracker-contact subset that cannot answer this question.</p>
     */
    static boolean isAllowedDotEvidence(Packet packet) {
        return packet != null && packet.allowed && packet.protocol == 6 && packet.dport == 853
                && packet.flags != null && packet.flags.indexOf('A') >= 0
                && packet.flags.indexOf('S') < 0 && packet.flags.indexOf('R') < 0;
    }

    private static final String ALLOWED_DOT_WHERE =
            "protocol = 6 AND dport = 853 AND allowed = 1 " +
                    "AND flags GLOB '*A*' AND flags NOT GLOB '*S*' " +
                    "AND flags NOT GLOB '*R*' AND time >= ?";

    public boolean hasRecentAllowedDot(long sinceMs) {
        flushLogBatch();
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            try (Cursor cursor = db.rawQuery(
                    "SELECT 1 FROM log WHERE " + ALLOWED_DOT_WHERE + " LIMIT 1",
                    new String[] { Long.toString(sinceMs) })) {
                return cursor.moveToFirst();
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Return the newest qualifying DoT flow at or after {@code sinceMs}. */
    public long getLatestAllowedDot(long sinceMs) {
        flushLogBatch();
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            try (Cursor cursor = db.rawQuery(
                    "SELECT MAX(time) FROM log WHERE " + ALLOWED_DOT_WHERE,
                    new String[] { Long.toString(sinceMs) })) {
                if (!cursor.moveToFirst() || cursor.isNull(0))
                    return -1;
                return cursor.getLong(0);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    public Cursor searchLog(String find) {
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is an index on daddr, dname, dport and uid
            String query = "SELECT ID AS _id, *";
            query += " FROM log";
            query += " WHERE daddr LIKE ? OR dname LIKE ? OR dport = ? OR uid = ?";
            query += " ORDER BY time DESC";
            return db.rawQuery(query, new String[] { "%" + find + "%", "%" + find + "%", find, find });
        } finally {
            lock.readLock().unlock();
        }
    }

    // Access

    public void updateAccess(Packet packet, String dname, int block, int uncertain) {
        String daddr = (dname == null ? packet.daddr : dname);
        AccessKey key = new AccessKey(packet.uid, packet.version, packet.protocol, daddr, packet.dport);

        PendingAccess p = new PendingAccess();
        p.time = packet.time;
        p.allowed = packet.allowed;
        p.uncertain = uncertain;
        p.blockSpecified = (block >= 0);
        p.block = block;

        synchronized (accessBatch) {
            boolean wasEmpty = accessBatch.isEmpty();
            // Coalescing is last-write-wins, but block is only carried on the
            // updates that specify it (block >= 0). If the newer update leaves
            // block unspecified, preserve the pending specified value instead
            // of dropping it — an unbatched sequence would have left the row's
            // block untouched by the later (block < 0) write.
            PendingAccess prev = accessBatch.get(key);
            if (prev != null && prev.blockSpecified && !p.blockSpecified) {
                p.blockSpecified = true;
                p.block = prev.block;
            }
            accessBatch.put(key, p);
            long now = System.currentTimeMillis();
            if (accessBatch.size() >= ACCESS_BATCH_SIZE || now - lastAccessFlush >= ACCESS_BATCH_FLUSH_MS)
                flushAccessBatch();
            else if (wasEmpty)
                scheduleAccessFlush();
        }
    }

    private void scheduleAccessFlush() {
        handler.removeCallbacks(accessFlushRunnable);
        handler.postDelayed(accessFlushRunnable, ACCESS_BATCH_FLUSH_MS);
    }

    public void flushAccessBatch() {
        Map<AccessKey, PendingAccess> batch;
        synchronized (accessBatch) {
            if (accessBatch.isEmpty())
                return;
            handler.removeCallbacks(accessFlushRunnable);
            batch = new LinkedHashMap<>(accessBatch);
            accessBatch.clear();
            lastAccessFlush = System.currentTimeMillis();
        }

        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                for (Map.Entry<AccessKey, PendingAccess> entry : batch.entrySet()) {
                    AccessKey key = entry.getKey();
                    PendingAccess p = entry.getValue();

                    ContentValues cv = new ContentValues();
                    cv.put("time", p.time);
                    cv.put("allowed", p.allowed ? 1 : 0);
                    cv.put("uncertain", p.uncertain);
                    if (p.blockSpecified)
                        cv.put("block", p.block);

                    // There is a segmented index on uid, version, protocol, daddr and dport
                    int rows = db.update("access", cv,
                            "uid = ? AND version = ? AND protocol = ? AND daddr = ? AND dport = ?",
                            new String[] {
                                    Integer.toString(key.uid),
                                    Integer.toString(key.version),
                                    Integer.toString(key.protocol),
                                    key.daddr,
                                    Integer.toString(key.dport) });

                    if (rows == 0) {
                        cv.put("uid", key.uid);
                        cv.put("version", key.version);
                        cv.put("protocol", key.protocol);
                        cv.put("daddr", key.daddr);
                        cv.put("dport", key.dport);
                        if (!p.blockSpecified)
                            cv.put("block", -1);

                        if (db.insert("access", null, cv) == -1)
                            Log.e(TAG, "Insert access failed");
                    } else if (rows != 1)
                        Log.e(TAG, "Update access failed rows=" + rows);
                }

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }

        notifyAccessChanged();
    }

    public void updateUsage(Usage usage, String dname) {
        String daddr = (dname == null ? usage.DAddr : dname);
        AccessKey key = new AccessKey(usage.Uid, usage.Version, usage.Protocol, daddr, usage.DPort);

        synchronized (usageBatch) {
            boolean wasEmpty = usageBatch.isEmpty();
            long[] acc = usageBatch.get(key);
            if (acc == null) {
                acc = new long[3];
                usageBatch.put(key, acc);
            }
            acc[0] += usage.Sent;
            acc[1] += usage.Received;
            acc[2] += 1;

            long now = System.currentTimeMillis();
            if (usageBatch.size() >= USAGE_BATCH_SIZE || now - lastUsageFlush >= USAGE_BATCH_FLUSH_MS)
                flushUsageBatch();
            else if (wasEmpty)
                scheduleUsageFlush();
        }
    }

    private void scheduleUsageFlush() {
        handler.removeCallbacks(usageFlushRunnable);
        handler.postDelayed(usageFlushRunnable, USAGE_BATCH_FLUSH_MS);
    }

    public void flushUsageBatch() {
        // A usage delta's access row may still be sitting unflushed in
        // accessBatch (independent batch/flush timers); flush it first so the
        // UPDATE below always has a row to land on instead of silently
        // matching zero rows and losing the delta.
        flushAccessBatch();

        Map<AccessKey, long[]> batch;
        synchronized (usageBatch) {
            if (usageBatch.isEmpty())
                return;
            handler.removeCallbacks(usageFlushRunnable);
            batch = new LinkedHashMap<>(usageBatch);
            usageBatch.clear();
            lastUsageFlush = System.currentTimeMillis();
        }

        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                // Collapses the previous SELECT-then-UPDATE round trip into a
                // single UPDATE; COALESCE guards against NULL (never-accounted) columns.
                SQLiteStatement stmt = db.compileStatement(
                        "UPDATE access SET " +
                                "sent = COALESCE(sent, 0) + ?, " +
                                "received = COALESCE(received, 0) + ?, " +
                                "connections = COALESCE(connections, 0) + ? " +
                                "WHERE uid = ? AND version = ? AND protocol = ? AND daddr = ? AND dport = ?");
                try {
                    for (Map.Entry<AccessKey, long[]> entry : batch.entrySet()) {
                        AccessKey key = entry.getKey();
                        long[] delta = entry.getValue();

                        stmt.clearBindings();
                        stmt.bindLong(1, delta[0]);
                        stmt.bindLong(2, delta[1]);
                        stmt.bindLong(3, delta[2]);
                        stmt.bindLong(4, key.uid);
                        stmt.bindLong(5, key.version);
                        stmt.bindLong(6, key.protocol);
                        stmt.bindString(7, key.daddr);
                        stmt.bindLong(8, key.dport);

                        int rows = stmt.executeUpdateDelete();
                        if (rows != 1)
                            Log.e(TAG, "Update usage failed rows=" + rows);
                    }
                } finally {
                    stmt.close();
                }

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }

        notifyAccessChanged();
    }

    public void setAccess(long id, int block) {
        flushAccessBatch();
        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                ContentValues cv = new ContentValues();
                cv.put("block", block);
                cv.put("allowed", -1);

                if (db.update("access", cv, "ID = ?", new String[] { Long.toString(id) }) != 1)
                    Log.e(TAG, "Set access failed");

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }

        notifyAccessChanged();
    }

    public void clearAccess() {
        flushAccessBatch();
        flushUsageBatch();
        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                db.delete("access", null, null);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }

        notifyAccessChanged();
    }

    public void clearAccess(int uid, boolean keeprules) {
        flushAccessBatch();
        flushUsageBatch();
        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                // There is a segmented index on uid
                // There is an index on block
                if (keeprules)
                    db.delete("access", "uid = ? AND block < 0", new String[] { Integer.toString(uid) });
                else
                    db.delete("access", "uid = ?", new String[] { Integer.toString(uid) });

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }

        notifyAccessChanged();
    }

    public void resetUsage(int uid) {
        flushUsageBatch();
        lock.writeLock().lock();
        try {
            // There is a segmented index on uid
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                ContentValues cv = new ContentValues();
                cv.putNull("sent");
                cv.putNull("received");
                cv.putNull("connections");
                db.update("access", cv,
                        (uid < 0 ? null : "uid = ?"),
                        (uid < 0 ? null : new String[] { Integer.toString(uid) }));

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }

        notifyAccessChanged();
    }

    public Cursor getAccess(int uid) {
        flushAccessBatch();
        flushUsageBatch();
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is a segmented index on uid
            // There is no index on time for write performance
            String query = "SELECT a.ID AS _id, a.*";
            query += ", (SELECT COUNT(DISTINCT d.qname) FROM dns d WHERE d.resource IN (SELECT d1.resource FROM dns d1 WHERE d1.qname = lower(a.daddr))) count";
            query += " FROM access a";
            query += " WHERE a.uid = ?";
            query += " ORDER BY a.time DESC";
            query += " LIMIT 250";
            return db.rawQuery(query, new String[] { Integer.toString(uid) });
        } finally {
            lock.readLock().unlock();
        }
    }

    public Cursor getAccess() {
        flushAccessBatch();
        flushUsageBatch();
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is a segmented index on uid
            // There is an index on block
            return db.query("access", null, "block >= 0", null, null, null, "uid");
        } finally {
            lock.readLock().unlock();
        }
    }

    public Cursor getAccessUnset(int uid, int limit, long since) {
        flushAccessBatch();
        flushUsageBatch();
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is a segmented index on uid, block and daddr
            // There is no index on allowed and time for write performance
            String query = "SELECT MAX(time) AS time, daddr, allowed";
            query += " FROM access";
            query += " WHERE uid = ?";
            query += " AND block < 0";
            query += " AND time >= ?";
            query += " GROUP BY daddr, allowed";
            query += " ORDER BY time DESC";
            if (limit > 0)
                query += " LIMIT " + limit;
            return db.rawQuery(query, new String[] { Integer.toString(uid), Long.toString(since) });
        } finally {
            lock.readLock().unlock();
        }
    }

    public long getHostCount(int uid, boolean usecache) {
        if (usecache)
            synchronized (mapUidHosts) {
                if (mapUidHosts.containsKey(uid))
                    return mapUidHosts.get(uid);
            }

        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is a segmented index on uid
            // There is an index on block
            long hosts = db.compileStatement("SELECT COUNT(*) FROM access WHERE block >= 0 AND uid =" + uid)
                    .simpleQueryForLong();
            synchronized (mapUidHosts) {
                mapUidHosts.put(uid, hosts);
            }
            return hosts;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get recent tracker access rows for the Insights screen.
     * The provider deduplicates these rows into the latest app-host contact.
     *
     * @return Cursor with columns: uid, daddr, allowed, time, uncertain
     */
    public Cursor getInsightsData7Days() {
        flushAccessBatch();
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            long sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000);

            String query = "SELECT uid, daddr, allowed, time, uncertain " +
                    "FROM access " +
                    "WHERE time >= ? " +
                    "ORDER BY time DESC, ID DESC";

            return db.rawQuery(query, new String[] { Long.toString(sevenDaysAgo) });
        } finally {
            lock.readLock().unlock();
        }
    }

    public Cursor getBlockedAccess(int uid, long since) {
        flushAccessBatch();
        flushUsageBatch();
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            String query = "SELECT daddr, MAX(time) AS time, MAX(uncertain) AS uncertain " +
                    "FROM access WHERE uid = ? AND time >= ? AND allowed = 0 " +
                    "GROUP BY daddr ORDER BY MAX(time) DESC";
            return db.rawQuery(query, new String[] { Integer.toString(uid), Long.toString(since) });
        } finally {
            lock.readLock().unlock();
        }
    }

    public Cursor getRecentTrackerActivity() {
        flushAccessBatch();
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            long sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000);

            String query = "SELECT uid, daddr, allowed, MAX(time) as last_time, " +
                    "COUNT(*) as attempts, uncertain " +
                    "FROM access " +
                    "WHERE time >= ? " +
                    "GROUP BY uid, daddr, allowed " +
                    "ORDER BY last_time DESC " +
                    "LIMIT 500";

            return db.rawQuery(query, new String[] { Long.toString(sevenDaysAgo) });
        } finally {
            lock.readLock().unlock();
        }
    }

    // DNS

    private static String lower(String name) {
        return (name == null ? null : name.toLowerCase(Locale.ROOT));
    }

    public DnsInsertOutcome insertDns(ResourceRecord rr) {
        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                int ttl = rr.TTL;

                int min = 259200;
                try {
                    min = Integer.parseInt(prefs.getString("ttl", "259200"));
                } catch (NumberFormatException ex) {
                    // Keep the default minimum TTL.
                }
                if (ttl < min)
                    ttl = min;

                ContentValues cv = new ContentValues();
                cv.put("time", rr.Time);
                cv.put("ttl", ttl * 1000L);

                // DNS names are case-insensitive, but the tracker and hosts lookups
                // are keyed in lowercase (see TrackerList.findTracker). Storing the
                // wire case verbatim made a server-chosen CNAME case — or a resolver
                // using 0x20 randomisation — a distinct row under the BINARY-collated
                // idx_dns, splitting one domain across several rows and making
                // getQAName report it as several qnames sharing an IP.
                String qname = lower(rr.QName);
                String aname = lower(rr.AName);

                int rows = db.update("dns", cv, "qname = ? AND aname = ? AND resource = ?",
                        new String[] { qname, aname, rr.Resource });

                DnsInsertOutcome outcome;
                if (rows == 0) {
                    cv.put("qname", qname);
                    cv.put("aname", aname);
                    cv.put("resource", rr.Resource);

                    if (db.insert("dns", null, cv) == -1)
                        outcome = DnsInsertOutcome.FAILED;
                    else
                        outcome = DnsInsertOutcome.INSERTED;
                    if (outcome == DnsInsertOutcome.FAILED)
                        Log.e(TAG, "Insert dns failed");
                } else {
                    if (rows != 1)
                        Log.e(TAG, "Update dns failed rows=" + rows);
                    outcome = DnsInsertOutcome.REFRESHED;
                }

                db.setTransactionSuccessful();

                return outcome;
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void cleanupDns() {
        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                // There is no index on time for write performance
                long now = new Date().getTime();
                db.execSQL("DELETE FROM dns WHERE time + ttl < " + now);
                Log.i(TAG, "Cleanup DNS");

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clearDns() {
        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                db.delete("dns", null, new String[] {});

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private SQLiteDatabase readableDb;

    public String getQName(int uid, String ip) {
        lock.readLock().lock();
        try {
            if (readableDb == null)
                readableDb = this.getReadableDatabase();
            SQLiteDatabase db = readableDb;
            // There is a segmented index on resource
            String query = "SELECT d.qname";
            query += " FROM dns AS d";
            query += " WHERE d.resource = '" + ip.replace("'", "''") + "'";
            query += " ORDER BY d.qname";
            query += " LIMIT 1";
            // There is no way to known for sure which domain name an app used, so just pick
            // the first one
            return db.compileStatement(query).simpleQueryForString();
        } catch (SQLiteDoneException ignored) {
            // Not found
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * DNS evidence for an IP, freshest qname first, one row per qname.
     *
     * <p>Expired rows are always excluded. Both callers — the runtime
     * blocking decision in {@code blockKnownTracker()} and the UI
     * classification in {@code ServiceSinkhole.log()} — must answer "is this
     * IP shared, and whose is it?" from the same row set. When the UI alone
     * saw the full history (see issue #759), it drew the shared-IP marker and
     * the ALLOWED/BLOCKED text from evidence the blocker had already
     * discarded, so the log contradicted what actually happened.
     */
    public Cursor getQAName(int uid, String ip) {
        long now = new Date().getTime();
        lock.readLock().lock();
        try {
            if (readableDb == null)
                readableDb = this.getReadableDatabase();
            SQLiteDatabase db = readableDb;
            String escapedIp = ip.replace("'", "''");
            String aliveFilter = " AND (d.time IS NULL OR d.time + d.ttl >= " + now + ")";
            // There is a segmented index on resource. A shared IP can carry
            // DNS evidence for several qnames; keep only the most recently
            // observed row per qname (dedup) and order qnames by recency, so
            // the freshest resolution — most likely tied to the connection
            // that's actually being made now — is attributed first instead
            // of an alphabetically-first but possibly stale one.
            //
            // The dedup deliberately uses a single MAX(time) aggregate: with
            // exactly one min/max aggregate, SQLite takes the bare columns
            // from the row that supplied the maximum, so this is one index
            // range scan over the IP's rows. A correlated per-row subquery
            // here re-scans the IP's rows once per candidate row — O(n²) —
            // and this query runs for every new connection (log() and
            // blockKnownTracker()), where it grows with DNS history until
            // it shows up as battery drain and heat.
            String query = "SELECT d.qname, d.aname, d.time, d.ttl, MAX(d.time)" +
                    " FROM dns AS d" +
                    " WHERE d.resource = '" + escapedIp + "'" +
                    aliveFilter +
                    " GROUP BY d.qname" +
                    " ORDER BY d.time DESC, d.ID DESC";
            return db.rawQuery(query, new String[] {});
        } finally {
            lock.readLock().unlock();
        }
    }

    public Cursor getAlternateQNames(String qname) {
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            qname = lower(qname);
            String query = "SELECT DISTINCT d2.qname";
            query += " FROM dns d1";
            query += " JOIN dns d2";
            query += "   ON d2.resource = d1.resource AND d2.id <> d1.id";
            query += " WHERE d1.qname = ?";
            query += " ORDER BY d2.qname";
            return db.rawQuery(query, new String[] { qname });
        } finally {
            lock.readLock().unlock();
        }
    }

    public Cursor getAName(String qname, boolean alive) {
        long now = new Date().getTime();
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            qname = lower(qname);
            String query = "SELECT d.qname, d.aname, d.time, d.ttl";
            query += " FROM dns d";
            query += " WHERE d.qname = ?";
            if (alive)
                query += " AND (d.time IS NULL OR d.time + d.ttl >= " + now + ")";
            query += " LIMIT 1";
            return db.rawQuery(query, new String[] { qname });
        } finally {
            lock.readLock().unlock();
        }
    }

    public Cursor getDns() {
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // There is an index on resource
            // There is a segmented index on qname
            String query = "SELECT ID AS _id, *";
            query += " FROM dns";
            query += " ORDER BY resource, qname";
            return db.rawQuery(query, new String[] {});
        } finally {
            lock.readLock().unlock();
        }
    }

    public Cursor getAccessDns(String dname) {
        long now = new Date().getTime();
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            // dns.qname is stored lowercase; access.daddr is written from it, so
            // the parameter is matched against the indexed column as-is.
            dname = lower(dname);

            // There is a segmented index on dns.qname
            // There is an index on access.daddr and access.block
            String query = "SELECT a.uid, a.version, a.protocol, a.daddr, d.resource, a.dport, a.block, d.time, d.ttl";
            query += " FROM access AS a";
            query += " LEFT JOIN dns AS d";
            query += "   ON d.qname = lower(a.daddr)";
            query += " WHERE a.block >= 0";
            query += " AND (d.time IS NULL OR d.time + d.ttl >= " + now + ")";
            if (dname != null)
                query += " AND a.daddr = ?";

            return db.rawQuery(query, dname == null ? new String[] {} : new String[] { dname });
        } finally {
            lock.readLock().unlock();
        }
    }

    // Forward

    public void addForward(int protocol, int dport, String raddr, int rport, int ruid) {
        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                ContentValues cv = new ContentValues();
                cv.put("protocol", protocol);
                cv.put("dport", dport);
                cv.put("raddr", raddr);
                cv.put("rport", rport);
                cv.put("ruid", ruid);

                if (db.insert("forward", null, cv) < 0)
                    Log.e(TAG, "Insert forward failed");

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }

        notifyForwardChanged();
    }

    public void deleteForward() {
        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                db.delete("forward", null, null);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }

        notifyForwardChanged();
    }

    public void deleteForward(int protocol, int dport) {
        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                db.delete("forward", "protocol = ? AND dport = ?",
                        new String[] { Integer.toString(protocol), Integer.toString(dport) });

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }

        notifyForwardChanged();
    }

    public Cursor getForwarding() {
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            String query = "SELECT ID AS _id, *";
            query += " FROM forward";
            query += " ORDER BY dport";
            return db.rawQuery(query, new String[] {});
        } finally {
            lock.readLock().unlock();
        }
    }

    public void addApp(String packageName, String label, boolean system, boolean internet, boolean enabled) {
        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                ContentValues cv = new ContentValues();
                cv.put("package", packageName);
                if (label == null)
                    cv.putNull("label");
                else
                    cv.put("label", label);
                cv.put("system", system ? 1 : 0);
                cv.put("internet", internet ? 1 : 0);
                cv.put("enabled", enabled ? 1 : 0);

                if (db.insert("app", null, cv) < 0)
                    Log.e(TAG, "Insert app failed");

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Cursor getApp(String packageName) {
        lock.readLock().lock();
        try {
            SQLiteDatabase db = this.getReadableDatabase();

            // There is an index on package
            String query = "SELECT * FROM app WHERE package = ?";

            return db.rawQuery(query, new String[] { packageName });
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clearApps() {
        lock.writeLock().lock();
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                db.delete("app", null, null);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void addLogChangedListener(LogChangedListener listener) {
        logChangedListeners.add(listener);
    }

    public void removeLogChangedListener(LogChangedListener listener) {
        logChangedListeners.remove(listener);
    }

    public void addAccessChangedListener(AccessChangedListener listener) {
        accessChangedListeners.add(listener);
    }

    public void removeAccessChangedListener(AccessChangedListener listener) {
        accessChangedListeners.remove(listener);
    }

    public void addForwardChangedListener(ForwardChangedListener listener) {
        forwardChangedListeners.add(listener);
    }

    public void removeForwardChangedListener(ForwardChangedListener listener) {
        forwardChangedListeners.remove(listener);
    }

    private void notifyLogChanged() {
        if (!handler.hasMessages(MSG_LOG))
            handler.sendEmptyMessageDelayed(MSG_LOG, NOTIFY_BATCH_MS);
    }

    private void notifyAccessChanged() {
        if (!handler.hasMessages(MSG_ACCESS))
            handler.sendEmptyMessageDelayed(MSG_ACCESS, NOTIFY_BATCH_MS);
    }

    private void notifyForwardChanged() {
        if (!handler.hasMessages(MSG_FORWARD))
            handler.sendEmptyMessageDelayed(MSG_FORWARD, NOTIFY_BATCH_MS);
    }

    private static void handleChangedNotification(Message msg) {
        // Notify listeners
        if (msg.what == MSG_LOG) {
            for (LogChangedListener listener : logChangedListeners)
                try {
                    listener.onChanged();
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                }

        } else if (msg.what == MSG_ACCESS) {
            for (AccessChangedListener listener : accessChangedListeners)
                try {
                    listener.onChanged();
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                }

        } else if (msg.what == MSG_FORWARD) {
            for (ForwardChangedListener listener : forwardChangedListeners)
                try {
                    listener.onChanged();
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                }
        }
    }

    public interface LogChangedListener {
        void onChanged();
    }

    public interface AccessChangedListener {
        void onChanged();
    }

    public interface ForwardChangedListener {
        void onChanged();
    }
}
