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
 * A no-op {@link ApkPatcher} used when no real engine is available (e.g. the
 * build was stripped of the patcher dependency). Always reports unavailable
 * and fails with an explanatory message, so callers can show "not supported
 * in this build" rather than crashing.
 */
public final class NoOpPatcher implements ApkPatcher {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @NonNull
    @Override
    public PatchResult patch(@NonNull Context ctx,
                             @NonNull String packageName,
                             @NonNull File inputApk,
                             @NonNull File outputApk,
                             @NonNull ProgressListener listener) {
        return PatchResult.failure("No patcher engine is available in this build.");
    }
}
