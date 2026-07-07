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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Build;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Installs a patched app as a split-install session: the re-signed base APK
 * plus every re-signed config split are streamed into a single
 * {@link PackageInstaller} session and committed atomically. Because the base
 * and splits carry the same (patcher) signature, Android accepts them as one
 * coherent package.
 *
 * <p>The commit result — including the {@code STATUS_PENDING_USER_ACTION}
 * confirmation prompt — is delivered to {@link ApkInstallReceiver}.</p>
 */
public final class SplitApkInstaller {

    private SplitApkInstaller() {
    }

    /**
     * Create a session, write every APK in {@code apks} into it, and commit.
     * Must be called off the main thread (it performs file I/O).
     *
     * @param apks the signed APKs to install (base first, then splits)
     */
    public static void install(@NonNull Context ctx, @NonNull String packageName,
                               @NonNull List<File> apks) throws IOException {
        if (apks.isEmpty()) throw new IOException("No APKs to install");

        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            for (File apk : apks) {
                writeApk(session, apk);
            }
            session.commit(statusSender(ctx, sessionId));
        } catch (IOException | RuntimeException e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }

    private static void writeApk(@NonNull PackageInstaller.Session session,
                                 @NonNull File apk) throws IOException {
        try (InputStream in = new FileInputStream(apk);
             OutputStream out = session.openWrite(apk.getName(), 0, apk.length())) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            session.fsync(out);
        }
    }

    @NonNull
    private static IntentSender statusSender(@NonNull Context ctx, int sessionId) {
        Intent intent = new Intent(ctx, ApkInstallReceiver.class)
                .setAction(ApkInstallReceiver.ACTION_INSTALL_STATUS);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The installer fills in EXTRA_STATUS / EXTRA_INTENT, so the
            // PendingIntent must be mutable.
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pending = PendingIntent.getBroadcast(ctx, sessionId, intent, flags);
        return pending.getIntentSender();
    }
}
