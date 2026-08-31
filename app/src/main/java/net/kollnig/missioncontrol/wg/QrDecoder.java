package net.kollnig.missioncontrol.wg;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * Decodes QR codes from raw camera luminance planes. Deliberately free of
 * Android APIs so the decoding path can be unit-tested on the JVM; the camera
 * plumbing lives in {@link net.kollnig.missioncontrol.ActivityScanQr}.
 */
public class QrDecoder {
    private final MultiFormatReader reader;

    public QrDecoder() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(BarcodeFormat.QR_CODE));
        // WireGuard configs are long, so the QR is dense: the extra scan passes
        // are worth the cost on a frame that would otherwise be dropped.
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader = new MultiFormatReader();
        reader.setHints(hints);
    }

    /**
     * @param luminance greyscale samples, row-major, {@code width * height} bytes
     * @return the decoded text, or {@code null} when the frame holds no QR code
     */
    public String decode(byte[] luminance, int width, int height) {
        if (luminance == null || width <= 0 || height <= 0
                || luminance.length < width * height)
            return null;

        PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                luminance, width, height, 0, 0, width, height, false);

        String text = decodeSource(source);
        if (text == null)
            // Some screens/printouts show light-on-dark codes, which ZXing only
            // reads once the source is inverted.
            text = decodeSource(source.invert());
        return text;
    }

    private String decodeSource(LuminanceSource source) {
        try {
            Result result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
            return result == null ? null : result.getText();
        } catch (NotFoundException ex) {
            return null;
        } finally {
            reader.reset();
        }
    }
}
