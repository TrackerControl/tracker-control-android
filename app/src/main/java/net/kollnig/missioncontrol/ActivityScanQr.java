package net.kollnig.missioncontrol;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import net.kollnig.missioncontrol.wg.QrDecoder;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.faircode.netguard.Util;

/**
 * Scans a QR code with the camera and hands its text back to the caller.
 *
 * WireGuard providers commonly show a configuration as a QR code, which spares
 * users retyping or emailing a config to the phone. Everything stays on the
 * device: frames are decoded in-process by {@link QrDecoder} and never stored.
 */
public class ActivityScanQr extends AppCompatActivity {
    private static final String TAG = "TrackerControl.ScanQr";

    /** Result extra holding the decoded QR text. */
    public static final String EXTRA_RESULT = "qr_text";

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final QrDecoder decoder = new QrDecoder();
    // The analyzer keeps running for a frame or two after the first hit, and
    // finishing twice would deliver the result to a dead activity.
    private final AtomicBoolean delivered = new AtomicBoolean(false);

    private ActivityResultLauncher<String> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Util.setTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_qr);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.setting_wg_profile_scan);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        startCamera();
                    } else {
                        Toast.makeText(this, R.string.msg_wg_profile_scan_no_camera_permission,
                                Toast.LENGTH_LONG).show();
                        finish();
                    }
                });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED)
            startCamera();
        else
            permissionLauncher.launch(Manifest.permission.CAMERA);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        analysisExecutor.shutdown();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void startCamera() {
        PreviewView previewView = findViewById(R.id.scan_preview);
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        // A dense config QR needs more than preview resolution,
                        // but a full-size frame only slows the decoder down.
                        .setResolutionSelector(new ResolutionSelector.Builder()
                                .setResolutionStrategy(new ResolutionStrategy(
                                        new Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                                .build())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyze);

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, analysis);
            } catch (Throwable ex) {
                Log.e(TAG, "Cannot start camera", ex);
                Toast.makeText(this, getString(R.string.msg_wg_profile_scan_failed,
                        ex.getMessage()), Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(ImageProxy image) {
        try {
            if (delivered.get())
                return;
            String text = decoder.decode(luminance(image), image.getWidth(), image.getHeight());
            if (text != null && !text.isEmpty() && delivered.compareAndSet(false, true))
                runOnUiThread(() -> deliver(text));
        } catch (Throwable ex) {
            Log.w(TAG, "QR decoding failed", ex);
        } finally {
            image.close();
        }
    }

    /**
     * Copies the Y plane of a YUV_420_888 frame into a tightly packed
     * greyscale array, dropping the row and pixel padding some devices add.
     */
    private static byte[] luminance(ImageProxy image) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        byte[] out = new byte[width * height];
        if (pixelStride == 1 && rowStride == width && buffer.remaining() >= out.length) {
            buffer.get(out, 0, out.length);
            return out;
        }

        byte[] row = new byte[rowStride];
        for (int y = 0; y < height; y++) {
            int offset = y * rowStride;
            // The last row is often shorter than a full stride.
            if (offset >= buffer.limit())
                break;
            buffer.position(offset);
            int available = Math.min(rowStride, buffer.remaining());
            buffer.get(row, 0, available);
            for (int x = 0; x < width; x++) {
                int index = x * pixelStride;
                if (index >= available)
                    break;
                out[y * width + x] = row[index];
            }
        }
        return out;
    }

    private void deliver(String text) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT, text));
        finish();
    }
}
