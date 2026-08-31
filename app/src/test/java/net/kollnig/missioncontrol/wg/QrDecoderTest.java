package net.kollnig.missioncontrol.wg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.junit.Test;

import java.util.EnumMap;
import java.util.Map;

public class QrDecoderTest {
    private static final String CONFIG = ""
            + "[Interface]\n"
            + "PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n"
            + "Address = 10.64.0.2/32\n"
            + "DNS = 10.64.0.1\n"
            + "\n"
            + "[Peer]\n"
            + "PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n"
            + "AllowedIPs = 0.0.0.0/0, ::/0\n"
            + "Endpoint = 192.0.2.1:51820\n";

    @Test
    public void decodesAWireGuardConfigQr() throws Exception {
        String text = new QrDecoder().decode(render(CONFIG, 600), 600, 600);

        assertEquals(CONFIG, text);
        // The scanned text must be usable as-is by the profile import path.
        WgConfigParser.INSTANCE.parse(text);
    }

    @Test
    public void decodesALightOnDarkQr() throws Exception {
        byte[] luminance = render(CONFIG, 600);
        for (int i = 0; i < luminance.length; i++)
            luminance[i] = (byte) (255 - (luminance[i] & 0xFF));

        assertEquals(CONFIG, new QrDecoder().decode(luminance, 600, 600));
    }

    @Test
    public void returnsNullOnAFrameWithoutAQr() {
        assertEquals(null, new QrDecoder().decode(new byte[600 * 600], 600, 600));
    }

    @Test
    public void rejectsUndersizedOrEmptyFrames() {
        QrDecoder decoder = new QrDecoder();

        assertNull(decoder.decode(null, 600, 600));
        assertNull(decoder.decode(new byte[10], 600, 600));
        assertNull(decoder.decode(new byte[0], 0, 0));
    }

    /** Renders {@code text} as a QR code into a greyscale luminance plane. */
    private static byte[] render(String text, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix matrix = new QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, size, size, hints);

        byte[] luminance = new byte[size * size];
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                luminance[y * size + x] = (byte) (matrix.get(x, y) ? 0x00 : 0xFF);
        return luminance;
    }
}
