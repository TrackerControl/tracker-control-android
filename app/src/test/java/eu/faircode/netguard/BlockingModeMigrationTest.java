/*
 * This file is from NetGuard.
 *
 * NetGuard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NetGuard is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Copyright © 2026
 */

package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;

import net.kollnig.missioncontrol.data.BlockingMode;

import org.junit.Test;

public class BlockingModeMigrationTest {

    @Test
    public void legacyStrictBlockingTrueMigratesToStrictMode() {
        assertEquals(BlockingMode.MODE_STRICT,
                ApplicationEx.resolveBlockingModeMigration(null, true));
    }

    @Test
    public void legacyStrictBlockingFalseMigratesToStandardMode() {
        assertEquals(BlockingMode.MODE_STANDARD,
                ApplicationEx.resolveBlockingModeMigration(null, false));
    }

    @Test
    public void freshInstallDefaultsToMinimalMode() {
        assertEquals(BlockingMode.MODE_MINIMAL,
                ApplicationEx.resolveBlockingModeMigration(null, null));
    }

    @Test
    public void existingBlockingModeWinsOverLegacyPreference() {
        assertEquals(BlockingMode.MODE_STANDARD,
                ApplicationEx.resolveBlockingModeMigration(BlockingMode.MODE_STANDARD, true));
    }
}
