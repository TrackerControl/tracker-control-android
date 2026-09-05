package net.kollnig.missioncontrol.dns;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Live loopback sockets; isolated preferences keep the device configuration intact. */
@RunWith(AndroidJUnit4.class)
public class StackDeviceTest {
    private static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }

    private static void awaitPool(ThreadPoolExecutor pool, int active, int queued) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (pool.getActiveCount() == active && pool.getQueue().size() == queued) return;
            Thread.sleep(10);
        }
        assertEquals("active workers", active, pool.getActiveCount());
        assertEquals("queued requests", queued, pool.getQueue().size());
    }

    private static Socket tcp() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", 5353), 1000);
        s.setSoTimeout(1000);
        return s;
    }

    private static void servfail() throws Exception {
        byte[] query = {0x12,0x34,1,0,0,0,0,0,0,0,0,0};
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2000);
            socket.send(new DatagramPacket(query, query.length, InetAddress.getByName("127.0.0.1"), 5353));
            DatagramPacket reply = new DatagramPacket(new byte[512],512);
            socket.receive(reply);
            assertEquals(12, reply.getLength());
            assertEquals(0x12, reply.getData()[0]);
            assertEquals(0x34, reply.getData()[1]);
            assertEquals(2, reply.getData()[3] & 15);
            assertTrue((reply.getData()[2] & 128) != 0);
        }
    }

    @Test public void liveOverloadRejectsAndRestartRecovers() throws Exception {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context isolated = new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("stack_device_test_" + name, mode);
            }
        };
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(isolated);
        prefs.edit().clear().putBoolean("doh_enabled",true)
                .putBoolean("doh_dns_fallback",false).commit();
        Constructor<DnsProxyServer> constructor = DnsProxyServer.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        DnsProxyServer proxy = constructor.newInstance(isolated);
        List<Socket> clients = new ArrayList<>();
        try {
            proxy.start();
            ThreadPoolExecutor pool = (ThreadPoolExecutor)field(proxy,"executor");
            assertNotNull(pool);
            for (int i=0;i<16;i++) clients.add(tcp());
            awaitPool(pool,16,0);
            for (int i=0;i<64;i++) clients.add(tcp());
            awaitPool(pool,16,64);
            try (Socket rejected = tcp()) {
                assertEquals("81st TCP request is closed",-1,rejected.getInputStream().read());
            }
            servfail();
            assertEquals(16,pool.getPoolSize());
            assertEquals(64,pool.getQueue().size());
            proxy.stop();
            for (int i=16;i<80;i++)
                assertEquals("queued socket closed at stop",-1,clients.get(i).getInputStream().read());
            for (Socket client : clients) client.close();
            assertTrue("old workers finish after client close",pool.awaitTermination(3,TimeUnit.SECONDS));
            for(int i=0;i<5;i++) {
                proxy.start();
                Field circuit = DnsProxyServer.class.getDeclaredField("circuitOpenUntil");
                circuit.setAccessible(true);
                circuit.setLong(proxy,System.currentTimeMillis()+60000);
                servfail();
                proxy.stop();
            }
        } finally {
            for(Socket client:clients) client.close();
            proxy.stop();
            prefs.edit().clear().commit();
        }
    }
}
