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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VpnReplacementRecoveryPolicyTest {
    @Test
    public void retriesUseBoundedExponentialBackoff() {
        VpnReplacementRecoveryPolicy policy = new VpnReplacementRecoveryPolicy(3, 1_000L);

        assertEquals(1_000L, policy.onFailure());
        assertEquals(2_000L, policy.onFailure());
        assertEquals(4_000L, policy.onFailure());
        assertEquals(VpnReplacementRecoveryPolicy.NO_RETRY, policy.onFailure());
    }

    @Test
    public void exhaustionIsBoundedAndResetRestoresBudget() {
        VpnReplacementRecoveryPolicy policy = new VpnReplacementRecoveryPolicy(1, 250L);

        assertEquals(250L, policy.onFailure());
        assertEquals(VpnReplacementRecoveryPolicy.NO_RETRY, policy.onFailure());
        policy.reset();
        assertEquals(250L, policy.onFailure());
    }

    @Test
    public void largeRetryBudgetDoesNotOverflowBackoff() {
        VpnReplacementRecoveryPolicy policy = new VpnReplacementRecoveryPolicy(80, 1_000L);

        long previous = 0L;
        for (int i = 0; i < 80; i++) {
            long delay = policy.onFailure();
            assertTrue("delay must stay positive (no overflow)", delay > 0L);
            assertTrue("backoff must be non-decreasing", delay >= previous);
            previous = delay;
        }
        assertEquals(VpnReplacementRecoveryPolicy.NO_RETRY, policy.onFailure());
    }
}
