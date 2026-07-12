package net.kollnig.missioncontrol.wgbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

/** Exercises the packaged Rust library through its real JNI entry points. */
@RunWith(AndroidJUnit4.class)
public class WgbridgeInstrumentedTest {
    @Test
    public void generatedKeysRoundTripAcrossJni() {
        String firstPrivateKey = Wgbridge.generatePrivateKey();
        String secondPrivateKey = Wgbridge.generatePrivateKey();

        assertNotNull(firstPrivateKey);
        assertNotNull(secondPrivateKey);
        assertEquals(44, firstPrivateKey.length());
        assertEquals(44, Wgbridge.publicKey(firstPrivateKey).length());
        assertNotEquals(firstPrivateKey, secondPrivateKey);
        assertNotEquals(Wgbridge.publicKey(firstPrivateKey), Wgbridge.publicKey(secondPrivateKey));
    }

    @Test(expected = RuntimeException.class)
    public void invalidPrivateKeyIsReportedAsJavaException() {
        Wgbridge.publicKey("not-a-wireguard-key");
    }
}
