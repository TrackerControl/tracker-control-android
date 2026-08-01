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
 * Copyright © 2026
 */

package net.kollnig.missioncontrol;

import static org.junit.Assert.assertEquals;

import net.kollnig.missioncontrol.TimelineEmptyAdapter.EmptyState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TimelineEmptyStateTest {

    @Test
    public void trackerControlOffTakesPrecedence() {
        assertEquals(EmptyState.TRACKER_CONTROL_OFF, TimelineEmptyAdapter.stateFor(false, true));
        assertEquals(EmptyState.TRACKER_CONTROL_OFF, TimelineEmptyAdapter.stateFor(false, false));
    }

    @Test
    public void recordingOffIsReportedSeparatelyFromWatching() {
        // "Search new trackers" (log_app) off: trackers are still blocked but
        // never recorded, so the Timeline can never fill up on its own.
        assertEquals(EmptyState.RECORDING_OFF, TimelineEmptyAdapter.stateFor(true, false));
    }

    @Test
    public void unavailableRecordingIsReportedWithoutASettingsCallToAction() {
        assertEquals(EmptyState.RECORDING_UNAVAILABLE,
                TimelineEmptyAdapter.stateFor(true, false, false));
    }

    @Test
    public void watchingWhenEnabledAndRecording() {
        assertEquals(EmptyState.WATCHING, TimelineEmptyAdapter.stateFor(true, true));
    }
}
