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

/**
 * Provides the {@link ApkPatcher} appropriate for this build flavor. The
 * {@code play} flavor ships the no-op patcher (the real engine depends on
 * apksig, which is only bundled for the {@code fdroid} / {@code github}
 * flavors).
 */
public final class PatcherFactory {

    @NonNull
    public static ApkPatcher get() {
        try {
            Class<?> cls = Class.forName(
                    "net.kollnig.missioncontrol.patch.Dexlib2Patcher");
            return (ApkPatcher) cls.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            // Engine not compiled into this flavor (e.g. play).
            return new NoOpPatcher();
        }
    }

    private PatcherFactory() {
    }
}
