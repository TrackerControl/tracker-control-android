package eu.faircode.netguard;

import static org.junit.Assert.assertTrue;

import android.database.MatrixCursor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class AdapterLogTest {

    @Test
    public void closeReleasesActiveCursor() {
        MatrixCursor cursor = new MatrixCursor(new String[]{
                "_id", "time", "version", "protocol", "flags", "saddr", "sport", "daddr", "dport",
                "dname", "uid", "data", "allowed", "connection", "interactive"
        });

        AdapterLog adapter = new AdapterLog(
                RuntimeEnvironment.getApplication(), cursor, false, false, false);
        adapter.close();

        assertTrue(cursor.isClosed());
    }
}
