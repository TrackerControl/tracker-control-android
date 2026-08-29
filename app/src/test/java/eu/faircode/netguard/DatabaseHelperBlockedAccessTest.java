package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class DatabaseHelperBlockedAccessTest {
    @Test
    public void blockedAccessGroupsByAddressAndUsesLatestTimeAndUncertainty() {
        DatabaseHelper helper = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        helper.clearAccess();
        int uid = 24681;
        long now = System.currentTimeMillis();

        helper.updateAccess(packet(uid, "203.0.113.10", 443, now - 2_000L, false, 1),
                null, -1, DatabaseHelper.ACCESS_UNCERTAIN_SHARED_IP);
        helper.updateAccess(packet(uid, "203.0.113.10", 8443, now - 1_000L, false, 2),
                null, -1, DatabaseHelper.ACCESS_UNCERTAIN_NONE);
        helper.updateAccess(packet(uid, "203.0.113.11", 443, now - 1_000L, true, 3),
                null, -1, DatabaseHelper.ACCESS_UNCERTAIN_NONE);
        helper.updateAccess(packet(uid, "203.0.113.12", 443, now - 20_000L, false, 4),
                null, -1, DatabaseHelper.ACCESS_UNCERTAIN_NONE);

        try (Cursor cursor = helper.getBlockedAccess(uid, now - 10_000L)) {
            assertEquals(1, cursor.getCount());
            assertTrue(cursor.moveToFirst());
            assertEquals("203.0.113.10", cursor.getString(cursor.getColumnIndexOrThrow("daddr")));
            assertEquals(now - 1_000L, cursor.getLong(cursor.getColumnIndexOrThrow("time")));
            assertEquals(DatabaseHelper.ACCESS_UNCERTAIN_SHARED_IP,
                    cursor.getInt(cursor.getColumnIndexOrThrow("uncertain")));
        }
    }

    private static Packet packet(int uid, String daddr, int dport, long time,
            boolean allowed, int version) {
        Packet packet = new Packet();
        packet.uid = uid;
        packet.version = version;
        packet.protocol = 6;
        packet.daddr = daddr;
        packet.dport = dport;
        packet.time = time;
        packet.allowed = allowed;
        packet.saddr = "10.0.0.2";
        packet.sport = 12345;
        packet.flags = "";
        return packet;
    }
}
