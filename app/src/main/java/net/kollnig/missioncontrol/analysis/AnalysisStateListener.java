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
 *
 * Copyright © 2019–2021 Konrad Kollnig (University of Oxford)
 */

package net.kollnig.missioncontrol.analysis;

import androidx.annotation.MainThread;

/**
 * Callback interface for reporting analysis lifecycle events to the UI.
 */
public interface AnalysisStateListener {
    /**
     * Called when analysis is queued (waiting for other analyses to finish).
     */
    @MainThread
    void onAnalysisQueued();

    /**
     * Called when analysis starts or is already running when observed.
     */
    @MainThread
    void onAnalysisRunning();

    /**
     * Called when analysis progress is updated.
     *
     * @param percent Progress percentage (0-100)
     */
    @MainThread
    void onAnalysisProgress(int percent);

    /**
     * Called when analysis completes successfully.
     *
     * @param result The analysis result string
     */
    @MainThread
    void onAnalysisFinished(String result);

    /**
     * Called when analysis fails.
     *
     * @param message Error message
     */
    @MainThread
    void onAnalysisFailed(String message);
}
