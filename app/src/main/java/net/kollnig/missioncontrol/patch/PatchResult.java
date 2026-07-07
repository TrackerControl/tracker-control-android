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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** Outcome of a patching run. */
public final class PatchResult {

    public enum Status { SUCCESS, FAILED }

    public final Status status;
    /**
     * The signed APKs to install, base first followed by any re-signed config
     * splits. On failure this is empty. Install the whole list together as a
     * single split-install session.
     */
    @NonNull public final List<File> outputFiles;
    public final int patchedMethods;
    @Nullable public final String message;

    private PatchResult(Status status, List<File> outputFiles, int patchedMethods, String message) {
        this.status = status;
        this.outputFiles = outputFiles;
        this.patchedMethods = patchedMethods;
        this.message = message;
    }

    /** The patched base APK, or null on failure. */
    @Nullable
    public File baseApk() {
        return outputFiles.isEmpty() ? null : outputFiles.get(0);
    }

    @NonNull
    static PatchResult success(@NonNull List<File> outputFiles, int patchedMethods) {
        return new PatchResult(Status.SUCCESS, outputFiles, patchedMethods, null);
    }

    @NonNull
    static PatchResult failure(@NonNull String message) {
        return new PatchResult(Status.FAILED, Collections.emptyList(), 0, message);
    }
}
