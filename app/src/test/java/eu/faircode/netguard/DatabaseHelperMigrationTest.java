package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class DatabaseHelperMigrationTest {
    private DatabaseHelper helper;
    private SQLiteDatabase database;

    @Before
    public void setUp() {
        helper = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        database = SQLiteDatabase.create(null);
    }

    @After
    public void tearDown() {
        database.close();
    }

    @Test
    public void upgradeFrom21AddsUncertainWithoutLosingAccessRows() {
        createVersion16AccessTable(database);
        database.execSQL("ALTER TABLE access ADD COLUMN sent INTEGER");
        database.execSQL("ALTER TABLE access ADD COLUMN received INTEGER");
        database.execSQL("ALTER TABLE access ADD COLUMN connections INTEGER");
        database.execSQL("INSERT INTO access "
                + "(uid, version, protocol, daddr, dport, time, allowed, block, sent, received, connections) "
                + "VALUES (1001, 4, 6, 'tracker.example', 443, 123456, 0, 1, 10, 20, 3)");
        database.setVersion(21);

        helper.onUpgrade(database, 21, 22);

        assertEquals(22, database.getVersion());
        assertTrue(columnExists("access", "uncertain"));
        try (Cursor cursor = database.rawQuery("SELECT * FROM access WHERE uid = 1001", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("tracker.example", cursor.getString(cursor.getColumnIndexOrThrow("daddr")));
            assertEquals(10, cursor.getInt(cursor.getColumnIndexOrThrow("sent")));
            assertEquals(20, cursor.getInt(cursor.getColumnIndexOrThrow("received")));
            assertEquals(3, cursor.getInt(cursor.getColumnIndexOrThrow("connections")));
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("uncertain")));
        }
    }

    @Test
    public void upgradeFrom16PreservesDataAndBuildsCurrentSchema() {
        createVersion16AccessTable(database);
        database.execSQL("CREATE UNIQUE INDEX idx_access "
                + "ON access(uid, version, protocol, daddr, dport)");
        database.execSQL("CREATE TABLE dns ("
                + "ID INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, "
                + "qname TEXT NOT NULL, aname TEXT NOT NULL, resource TEXT NOT NULL, ttl INTEGER)");
        database.execSQL("CREATE UNIQUE INDEX idx_dns ON dns(qname, resource)");
        database.execSQL("INSERT INTO access "
                + "(uid, version, protocol, daddr, dport, time, allowed, block) "
                + "VALUES (2002, 4, 17, '1.2.3.4', 53, 654321, 1, 0)");
        database.execSQL("INSERT INTO dns (time, qname, aname, resource, ttl) "
                + "VALUES (654321, 'example.org', 'alias.example.org', '1.2.3.4', 60)");
        database.setVersion(16);

        helper.onUpgrade(database, 16, 22);

        assertEquals(22, database.getVersion());
        assertTrue(columnExists("access", "sent"));
        assertTrue(columnExists("access", "received"));
        assertTrue(columnExists("access", "connections"));
        assertTrue(columnExists("access", "uncertain"));
        assertTrue(tableExists("app"));
        assertTrue(indexExists("idx_access_block"));
        assertTrue(indexExists("idx_access_daddr"));
        assertTrue(indexExists("idx_dns_resource"));

        try (Cursor cursor = database.rawQuery("SELECT uid, daddr, block FROM access", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(2002, cursor.getInt(0));
            assertEquals("1.2.3.4", cursor.getString(1));
            assertEquals(0, cursor.getInt(2));
        }
        try (Cursor cursor = database.rawQuery("SELECT qname, aname, resource FROM dns", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("example.org", cursor.getString(0));
            assertEquals("alias.example.org", cursor.getString(1));
            assertEquals("1.2.3.4", cursor.getString(2));
        }
    }

    private static void createVersion16AccessTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE access ("
                + "ID INTEGER PRIMARY KEY AUTOINCREMENT, uid INTEGER NOT NULL, "
                + "version INTEGER NOT NULL, protocol INTEGER NOT NULL, daddr TEXT NOT NULL, "
                + "dport INTEGER NOT NULL, time INTEGER NOT NULL, allowed INTEGER, block INTEGER NOT NULL)");
    }

    private boolean columnExists(String table, String column) {
        try (Cursor cursor = database.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext())
                if (column.equals(cursor.getString(cursor.getColumnIndexOrThrow("name"))))
                    return true;
        }
        return false;
    }

    private boolean tableExists(String table) {
        return schemaObjectExists("table", table);
    }

    private boolean indexExists(String index) {
        return schemaObjectExists("index", index);
    }

    private boolean schemaObjectExists(String type, String name) {
        try (Cursor cursor = database.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?", new String[] { type, name })) {
            return cursor.moveToFirst();
        }
    }
}
