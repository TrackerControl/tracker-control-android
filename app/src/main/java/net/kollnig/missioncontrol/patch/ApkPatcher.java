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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * Repackages an installed application's APK so that its network traffic can be
 * intercepted by a local TLS-terminating proxy. Concretely, an implementation
 * injects a {@code network_security_config.xml} that trusts user-installed
 * certificate authorities and overrides declared pin sets, and makes the
 * application debuggable so the debug trust-anchors apply.
 *
 * <p>This is the same circumvention offered by apk-mitm and ReVanced Manager's
 * "Override certificate pinning" patch. It defeats Network Security Config
 * pinning; applications that pin certificates in code (e.g. OkHttp
 * {@code CertificatePinner}) need an additional smali patch and will fall back
 * to pass-through until that is applied.</p>
 */
public interface ApkPatcher {

    /** Coarse progress callback. {@code message} is human-readable, may be null. */
    interface ProgressListener {
        void onProgress(@Nullable String message);
    }

    /**
     * Patch the given app's base APK and write the signed, installable result(s)
     * into {@code outputDir}. Only the base APK is patched (dex + resources +
     * manifest); any config splits (ABI, density, language) are re-signed
     * unchanged with the same key so the whole set installs together as a
     * split-install session. This is a long-running, blocking operation and
     * must be invoked off the main thread.
     *
     * @param inputApk  the app's base APK (its config splits are discovered via
     *                  {@link android.content.pm.PackageManager})
     * @param outputDir a writable directory to receive the signed APKs
     * @return a {@link PatchResult}; on success {@link PatchResult#outputFiles}
     *         lists the base APK first, then any re-signed splits.
     */
    @NonNull
    PatchResult patch(@NonNull Context ctx,
                     @NonNull String packageName,
                     @NonNull File inputApk,
                     @NonNull File outputDir,
                     @NonNull ProgressListener listener);

    /** Whether a real patching engine is available in this build. */
    boolean isAvailable();
}
