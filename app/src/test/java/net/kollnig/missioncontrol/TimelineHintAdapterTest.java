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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TimelineHintAdapterTest {

    @Test
    public void shouldShowOnlyWhenEntriesExistAndHintIsEnabled() {
        assertTrue(TimelineHintAdapter.shouldShow(true, true));
        assertFalse(TimelineHintAdapter.shouldShow(false, true));
        assertFalse(TimelineHintAdapter.shouldShow(true, false));
        assertFalse(TimelineHintAdapter.shouldShow(false, false));
    }
}
