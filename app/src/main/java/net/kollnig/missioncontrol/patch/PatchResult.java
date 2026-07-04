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

/** Outcome of a patching run. */
public final class PatchResult {

    public enum Status { SUCCESS, FAILED }

    public final Status status;
    @Nullable public final File outputFile;
    public final int patchedMethods;
    @Nullable public final String message;

    private PatchResult(Status status, File outputFile, int patchedMethods, String message) {
        this.status = status;
        this.outputFile = outputFile;
        this.patchedMethods = patchedMethods;
        this.message = message;
    }

    @NonNull
    static PatchResult success(@NonNull File output, int patchedMethods) {
        return new PatchResult(Status.SUCCESS, output, patchedMethods, null);
    }

    @NonNull
    static PatchResult failure(@NonNull String message) {
        return new PatchResult(Status.FAILED, null, 0, message);
    }
}
