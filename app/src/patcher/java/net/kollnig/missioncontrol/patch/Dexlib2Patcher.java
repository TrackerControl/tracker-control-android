/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 */

package net.kollnig.missioncontrol.patch;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.apksig.ApkSigner;
import com.reandroid.apk.ApkModule;
import com.reandroid.apk.ApkSplitInfoCleaner;
import com.reandroid.apk.DexFileInputSource;
import com.reandroid.archive.InputSource;
import com.reandroid.archive.ByteInputSource;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.DexFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * The real {@link ApkPatcher} implementation. Repackages an installed
 * application's base APK so its HTTPS traffic can be inspected by a local
 * TLS-terminating proxy:
 *
 * <ol>
 *   <li>Code-level pinning defeat — {@link DexPatcher} replaces the bodies of
 *       {@code X509TrustManager}, {@code HostnameVerifier}, OkHttp
 *       {@code CertificatePinner} and other common pinning methods with
 *       no-op / always-accept stubs (see {@link SmaliPatches}).</li>
 *   <li>Resource-level trust defeat — {@link ResourcePatcher} injects a
 *       {@code network_security_config.xml} that trusts user-installed CAs
 *       with {@code overridePins="true"} and marks the app debuggable, so the
 *       platform's default {@code TrustManagerImpl} accepts the MITM CA.</li>
 *   <li>Strip the original signature and re-sign with an AndroidKeyStore RSA
 *       key via apksig (v1 + v2 + v3).</li>
 * </ol>
 *
 * <p>ARSCLib (Apache-2.0) handles binary resource read/write; dexlib2 (BSD-3)
 * handles dex; apksig (Apache-2.0) handles signing. No GPL dependency.</p>
 *
 * <p>Limitations:
 * <ul>
 *   <li>Apps that pin in code via classes not covered by {@link SmaliPatches}
 *       will still reject the MITM CA and fall back to pass-through.</li>
 * </ul></p>
 */
public final class Dexlib2Patcher implements ApkPatcher {

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("com.android.apksig.ApkSigner");
            Class.forName("com.reandroid.apk.ApkModule");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @NonNull
    @Override
    public PatchResult patch(@NonNull Context ctx,
                             @NonNull String packageName,
                             @NonNull File inputApk,
                             @NonNull File outputApk,
                             @NonNull ProgressListener listener) {
        File workDir = new File(ctx.getCacheDir(), "apk-patcher");
        workDir.mkdirs();
        File unsigned = new File(workDir, "unsigned.apk");
        if (unsigned.exists()) unsigned.delete();

        try (ApkModule apk = ApkModule.loadApkFile(inputApk)) {
            // Merge any split APKs (App Bundle config splits) into the base
            // module so the result is a single standalone installable APK.
            mergeSplits(ctx, packageName, apk, listener);

            // Strip the split manifest metadata (<split> / <isSplitRequired>)
            // so Android treats the merged APK as a regular base APK.
            ApkSplitInfoCleaner.cleanSplitInfo(apk);

            int dexPatched = patchDexes(apk, listener);
            ResourcePatcher.patchResources(apk, listener::onProgress);

            listener.onProgress("Writing APK…");
            apk.writeApk(unsigned);

            listener.onProgress("Signing APK…");
            SigningKeyManager.Signer signer = new SigningKeyManager().signer();
            sign(unsigned, outputApk, signer);

            if (!unsigned.delete()) unsigned.deleteOnExit();
            listener.onProgress("Done. Patched " + dexPatched + " method(s).");
            return PatchResult.success(outputApk, dexPatched);
        } catch (Throwable t) {
            return PatchResult.failure(firstLine(t));
        }
    }

    /**
     * Discover the app's split APKs (e.g. {@code split_config.arm64_v8a.apk},
     * {@code split_config.xxhdpi.apk}) via {@link PackageManager} and merge
     * each into {@code base}. After this, {@code base} contains the union of
     * all dex files, native libraries, resources and assets from every split.
     */
    private void mergeSplits(@NonNull Context ctx, @NonNull String packageName,
                             @NonNull ApkModule base,
                             @NonNull ProgressListener listener) throws IOException {
        String[] splitPaths = resolveSplitPaths(ctx, packageName);
        if (splitPaths == null || splitPaths.length == 0) return;
        for (String path : splitPaths) {
            File splitFile = new File(path);
            if (!splitFile.exists()) continue;
            String name = splitFile.getName();
            listener.onProgress("Merging " + name + "…");
            try (ApkModule split = ApkModule.loadApkFile(splitFile)) {
                base.merge(split);
            }
        }
    }

    @Nullable
    private static String[] resolveSplitPaths(@NonNull Context ctx,
                                              @NonNull String packageName) {
        try {
            ApplicationInfo ai = ctx.getPackageManager()
                    .getApplicationInfo(packageName, 0);
            return ai.splitSourceDirs;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Patch every classes*.dex in {@code apk} in place. Returns total methods replaced. */
    private int patchDexes(@NonNull ApkModule apk, @NonNull ProgressListener listener)
            throws Exception {
        int total = 0;
        List<DexFileInputSource> dexFiles = apk.listDexFiles();
        for (DexFileInputSource src : dexFiles) {
            String name = src.getAlias();
            byte[] original = readAll(src);
            DexPatcher.Result result = patchDex(original, name, listener);
            if (result != null) {
                apk.add(new ByteInputSource(result.bytes, name));
                total += result.patchedMethods;
            }
        }
        return total;
    }

    private DexPatcher.Result patchDex(byte[] dexBytes, String name,
                                       ProgressListener listener) throws Exception {
        File in = File.createTempFile("in-" + sanitize(name), ".dex");
        File out = File.createTempFile("out-" + sanitize(name), ".dex");
        try {
            try (FileOutputStream fos = new FileOutputStream(in)) {
                fos.write(dexBytes);
            }
            DexFile dex = DexFileFactory.loadDexFile(in, null);
            DexPatcher.Result result = DexPatcher.patchDex(dex);
            if (result.patchedMethods == 0) return null;
            DexFileFactory.writeDexFile(out.getAbsolutePath(), result.dex);
            listener.onProgress(name + ": " + result.patchedMethods + " method(s)");
            return new DexPatcher.Result(result.dex, readFile(out),
                    result.patchedMethods);
        } finally {
            in.delete();
            out.delete();
        }
    }

    private void sign(@NonNull File unsigned, @NonNull File output,
                      @NonNull SigningKeyManager.Signer signer) throws Exception {
        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder(
                "tc-patcher",
                signer.privateKey,
                Collections.singletonList(signer.certificate))
                .build();
        ApkSigner apkSigner = new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(unsigned)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setDebuggableApkPermitted(true)
                .build();
        apkSigner.sign();
    }

    private static byte[] readAll(@NonNull InputSource src) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.io.InputStream is = src.openStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private static byte[] readFile(@NonNull File f) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private static String sanitize(@NonNull String name) {
        return name.replace("/", "_").replace(".", "_");
    }

    private static String firstLine(@NonNull Throwable t) {
        String msg = t.getMessage();
        if (msg == null) msg = t.getClass().getSimpleName();
        return msg.split("\n")[0];
    }

    /** Resolve the base APK path for a package, or null on failure. */
    @SuppressWarnings("unused")
    public static String sourceApkPath(@NonNull Context ctx, @NonNull String pkg) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return ai.sourceDir;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
