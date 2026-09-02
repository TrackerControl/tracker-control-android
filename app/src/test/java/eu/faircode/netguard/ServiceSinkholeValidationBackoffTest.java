/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Connectivity-probe backoff arithmetic for {@code ServiceSinkhole.networkMonitorCallback}
 * (item 1: a network that keeps failing validation must back off, but a first probe or a
 * recovered network must not be held back).
 */
@RunWith(RobolectricTestRunner.class)
public class ServiceSinkholeValidationBackoffTest {
    @Test
    public void firstFailureBacksOffThirtySeconds() {
        assertEquals(30_000L, ServiceSinkhole.nextValidationBackoffMs(0L));
    }

    @Test
    public void repeatedFailuresDoubleTheBackoff() {
        long backoff = ServiceSinkhole.nextValidationBackoffMs(0L);
        assertEquals(30_000L, backoff);

        backoff = ServiceSinkhole.nextValidationBackoffMs(backoff);
        assertEquals(60_000L, backoff);

        backoff = ServiceSinkhole.nextValidationBackoffMs(backoff);
        assertEquals(120_000L, backoff);

        backoff = ServiceSinkhole.nextValidationBackoffMs(backoff);
        assertEquals(240_000L, backoff);
    }

    @Test
    public void backoffIsCappedAtTenMinutes() {
        long backoff = 0L;
        for (int i = 0; i < 20; i++)
            backoff = ServiceSinkhole.nextValidationBackoffMs(backoff);

        assertEquals(10 * 60 * 1000L, backoff);
        // One more failure must not overflow past the cap.
        assertEquals(10 * 60 * 1000L, ServiceSinkhole.nextValidationBackoffMs(backoff));
    }

    @Test
    public void backoffWindowIsOpenUntilItElapses() {
        long lastFailureUptimeMs = 100_000L;
        long backoffMs = 30_000L;

        assertTrue(ServiceSinkhole.isValidationBackoffOpen(lastFailureUptimeMs, backoffMs, lastFailureUptimeMs));
        assertTrue(ServiceSinkhole.isValidationBackoffOpen(lastFailureUptimeMs, backoffMs, lastFailureUptimeMs + 29_999L));
        assertFalse(ServiceSinkhole.isValidationBackoffOpen(lastFailureUptimeMs, backoffMs, lastFailureUptimeMs + 30_000L));
        assertFalse(ServiceSinkhole.isValidationBackoffOpen(lastFailureUptimeMs, backoffMs, lastFailureUptimeMs + 60_000L));
    }

    @Test
    public void successResetsToTheInitialBackoffOnTheNextFailure() {
        // Modelling mapValidateFailure.remove(network) on success: the next
        // failure after a success must start at the 30s floor again, not
        // continue doubling from wherever the network previously topped out.
        long backoffBeforeSuccess = ServiceSinkhole.nextValidationBackoffMs(
                ServiceSinkhole.nextValidationBackoffMs(0L));
        assertEquals(60_000L, backoffBeforeSuccess);

        long backoffAfterRecovery = ServiceSinkhole.nextValidationBackoffMs(0L);
        assertEquals(30_000L, backoffAfterRecovery);
    }
}
