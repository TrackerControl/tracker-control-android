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

package net.kollnig.missioncontrol.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.kollnig.missioncontrol.data.AppProtectionState.Change;

import org.junit.Test;

public class AppProtectionStateTest {

    @Test
    public void defaultCombinationIsProtected() {
        assertEquals(AppProtectionState.PROTECTED,
                AppProtectionState.resolve(true, true, false));
    }

    @Test
    public void protectionOffInsideTunnelIsTrackersAllowed() {
        assertEquals(AppProtectionState.TRACKERS_ALLOWED,
                AppProtectionState.resolve(true, false, false));
    }

    @Test
    public void internetBlockWinsOverProtectionFlag() {
        assertEquals(AppProtectionState.NO_INTERNET,
                AppProtectionState.resolve(true, true, true, true));
        assertEquals(AppProtectionState.NO_INTERNET,
                AppProtectionState.resolve(true, false, true, true));
    }

    @Test
    public void fourArgResolveTruthTable() {
        for (boolean apply : new boolean[] { true, false })
            for (boolean trackerProtect : new boolean[] { true, false })
                for (boolean internetBlocked : new boolean[] { true, false })
                    for (boolean minimalOnly : new boolean[] { true, false }) {
                        AppProtectionState expected;
                        if (!apply)
                            expected = AppProtectionState.BYPASSED;
                        else if (internetBlocked)
                            expected = AppProtectionState.NO_INTERNET;
                        else if (!trackerProtect)
                            expected = AppProtectionState.TRACKERS_ALLOWED;
                        else
                            expected = minimalOnly
                                    ? AppProtectionState.MINIMAL_ONLY
                                    : AppProtectionState.PROTECTED;

                        assertEquals(expected, AppProtectionState.resolve(
                                apply, trackerProtect, internetBlocked, minimalOnly));
                    }
    }

    /**
     * An excluded app is removed from the tun, so the per-UID internet block
     * cannot be enforced for it; Bypass has to dominate.
     */
    @Test
    public void bypassWinsOverEverything() {
        for (boolean trackerProtect : new boolean[] { true, false })
            for (boolean internetBlocked : new boolean[] { true, false })
                assertEquals(AppProtectionState.BYPASSED,
                        AppProtectionState.resolve(false, trackerProtect, internetBlocked));
    }

    /**
     * XML import restores the four stores independently and never normalises
     * them, so resolve() must be total over all sixteen inputs.
     */
    @Test
    public void resolveIsTotalOverAllCombinations() {
        int combinations = 0;
        for (boolean apply : new boolean[] { true, false })
            for (boolean trackerProtect : new boolean[] { true, false })
                for (boolean internetBlocked : new boolean[] { true, false })
                    for (boolean minimalOnly : new boolean[] { true, false }) {
                        assertNotNull(AppProtectionState.resolve(
                                apply, trackerProtect, internetBlocked, minimalOnly));
                        combinations++;
                    }
        assertEquals(16, combinations);
    }

    @Test
    public void everyStateRoundTripsThroughItsChange() {
        for (AppProtectionState state : AppProtectionState.values()) {
            Change change = AppProtectionState.of(state);

            // Worst case: apply the change on top of every possible prior state.
            for (boolean priorProtect : new boolean[] { true, false })
                for (boolean priorInternet : new boolean[] { true, false })
                    for (boolean priorMinimal : new boolean[] { true, false }) {
                        boolean protect = change.trackerProtect == null
                                ? priorProtect
                                : change.trackerProtect;
                        boolean internet = change.internetBlocked == null
                                ? priorInternet
                                : change.internetBlocked;
                        boolean minimal = change.minimalOnly == null
                                ? priorMinimal
                                : change.minimalOnly;
                        assertEquals(state,
                                AppProtectionState.resolve(change.apply, protect, internet, minimal));
                    }
        }
    }

    @Test
    public void noInternetKeepsTrackerProtectionChoice() {
        Change change = AppProtectionState.of(AppProtectionState.NO_INTERNET);
        assertNull(change.trackerProtect);
        assertNull(change.minimalOnly);
        assertTrue(change.apply);
        assertEquals(Boolean.TRUE, change.internetBlocked);
    }

    @Test
    public void bypassTouchesOnlyApply() {
        Change change = AppProtectionState.of(AppProtectionState.BYPASSED);
        assertNull(change.trackerProtect);
        assertNull(change.internetBlocked);
        assertNull(change.minimalOnly);
    }

    @Test
    public void protectedAndMinimalOnlyWriteExplicitFlagValues() {
        assertEquals(Boolean.FALSE,
                AppProtectionState.of(AppProtectionState.PROTECTED).minimalOnly);
        assertEquals(Boolean.TRUE,
                AppProtectionState.of(AppProtectionState.MINIMAL_ONLY).minimalOnly);
        assertNull(AppProtectionState.of(AppProtectionState.TRACKERS_ALLOWED).minimalOnly);
        assertNull(AppProtectionState.of(AppProtectionState.NO_INTERNET).minimalOnly);
        assertNull(AppProtectionState.of(AppProtectionState.BYPASSED).minimalOnly);
    }

    /**
     * Leaving No-internet has to clear the block, otherwise the app would fall
     * straight back into No-internet by precedence.
     */
    @Test
    public void leavingNoInternetClearsTheBlock() {
        assertEquals(Boolean.FALSE,
                AppProtectionState.of(AppProtectionState.PROTECTED).internetBlocked);
        assertEquals(Boolean.FALSE,
                AppProtectionState.of(AppProtectionState.TRACKERS_ALLOWED).internetBlocked);
    }

    /**
     * Browsers default to tracker protection off, so an untouched browser
     * resolves to Trackers allowed while an ordinary app resolves to Protected.
     * Selecting Protected writes the flag explicitly and overrides that default.
     */
    @Test
    public void browserDefaultResolvesToTrackersAllowed() {
        boolean browserDefault = BlockingMode.resolveTrackerProtection(true, null);
        assertEquals(AppProtectionState.TRACKERS_ALLOWED,
                AppProtectionState.resolve(true, browserDefault, false));

        boolean ordinaryDefault = BlockingMode.resolveTrackerProtection(false, null);
        assertEquals(AppProtectionState.PROTECTED,
                AppProtectionState.resolve(true, ordinaryDefault, false));

        Change change = AppProtectionState.of(AppProtectionState.PROTECTED);
        boolean configuredBrowser = BlockingMode.resolveTrackerProtection(true, change.trackerProtect);
        assertEquals(AppProtectionState.PROTECTED,
                AppProtectionState.resolve(change.apply, configuredBrowser, false));
    }
}
