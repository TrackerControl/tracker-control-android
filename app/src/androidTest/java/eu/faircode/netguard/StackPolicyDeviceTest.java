package eu.faircode.netguard;

import static org.junit.Assert.*;
import android.content.*;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.preference.PreferenceManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import net.kollnig.missioncontrol.data.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.*;

/** Runs the production classifier on Android with a separate database and preferences. */
@RunWith(AndroidJUnit4.class)
public class StackPolicyDeviceTest {
    private static final String IP="203.0.113.211";
    private static final int UID=54321;
    private static class AttachedService extends ServiceSinkhole {
        void attach(Context c) { attachBaseContext(c); }
    }
    private static Field accessible(Class<?> type,String name) throws Exception {
        Field f=type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void answer(Method dns, ServiceSinkhole service,String q,String a,int ttl) throws Exception {
        ResourceRecord rr=new ResourceRecord();
        rr.Time=System.currentTimeMillis(); rr.QName=q; rr.AName=a; rr.Resource=IP; rr.TTL=ttl;
        dns.invoke(service,rr);
    }
    @Test public void blockingModesArrivalOrderExpiryAndRefresh() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context isolated=new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String n,int m) {
                return super.getSharedPreferences("stack_policy_test_"+n,m);
            }
            @Override public File getDatabasePath(String n) { return super.getDatabasePath("stack_policy_test_"+n); }
            @Override public SQLiteDatabase openOrCreateDatabase(String n,int m,SQLiteDatabase.CursorFactory f) {
                return super.openOrCreateDatabase("stack_policy_test_"+n,m,f);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String n,int m,SQLiteDatabase.CursorFactory f,DatabaseErrorHandler h) {
                return super.openOrCreateDatabase("stack_policy_test_"+n,m,f,h);
            }
        };
        Field dbField=accessible(DatabaseHelper.class,"dh");
        Field blockField=accessible(TrackerBlocklist.class,"instance");
        Object oldDb=dbField.get(null),oldBlock=blockField.get(null);
        dbField.set(null,null); blockField.set(null,null);
        SharedPreferences prefs=PreferenceManager.getDefaultSharedPreferences(isolated);
        DatabaseHelper db=null;
        try {
            prefs.edit().clear().putString("ttl","0").commit();
            db=DatabaseHelper.getInstance(isolated);
            assertTrue(db.getDatabaseName()!=null);
            assertTrue("test database must be isolated",db.getWritableDatabase().getPath().contains("stack_policy_test_"));
            assertTrue(TrackerList.getInstance(isolated).loadTrackers(isolated));
            assertNotNull(TrackerList.findMinimalTracker("doubleclick.net"));
            Method block=ServiceSinkhole.class.getDeclaredMethod("blockKnownTracker",String.class,int.class);
            block.setAccessible(true);
            Method dns=ServiceSinkhole.class.getDeclaredMethod("dnsResolved",ResourceRecord.class);
            dns.setAccessible(true);
            for(String mode:new String[]{"minimal","standard","strict"}) {
                prefs.edit().putString("blocking_mode",mode).commit();
                assertTrue(TrackerList.getInstance(isolated).loadTrackers(isolated));
                assertEquals(mode,TrackerList.getBlockingMode(isolated));
                TrackerBlocklist.getInstance(isolated).ensureDefaults(UID,mode.equals("strict"));
                for(boolean benignFirst:new boolean[]{false,true}) {
                    db.clearDns(); ServiceSinkhole.clearTrackerCaches();
                    AttachedService service=new AttachedService(); service.attach(isolated);
                    if(benignFirst) answer(dns,service,"terminal.test","terminal.test",1);
                    answer(dns,service,"front.test","doubleclick.net",300);
                    answer(dns,service,"doubleclick.net","edge.test",300);
                    answer(dns,service,"edge.test","terminal.test",300);
                    if(!benignFirst) {
                        assertTrue(mode+" connected chain blocks",(Boolean)block.invoke(service,IP,UID));
                        answer(dns,service,"terminal.test","terminal.test",1);
                    }
                    assertEquals(mode+" shared IP, benignFirst="+benignFirst,
                            mode.equals("strict"),(Boolean)block.invoke(service,IP,UID));
                    Thread.sleep(1200);
                    assertTrue(mode+" expired benign evidence blocks",(Boolean)block.invoke(service,IP,UID));
                    answer(dns,service,"terminal.test","terminal.test",300);
                    assertEquals(mode+" revived benign evidence updates cached verdict",
                            mode.equals("strict"),(Boolean)block.invoke(service,IP,UID));
                }
            }
        } finally {
            if(db!=null) { db.clearDns(); db.getWritableDatabase().close(); }
            ServiceSinkhole.clearTrackerCaches();
            dbField.set(null,oldDb); blockField.set(null,oldBlock);
            TrackerList.getInstance(base).loadTrackers(base);
            prefs.edit().clear().commit();
        }
    }
}
