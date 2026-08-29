package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WireGuardStartupRecoveryPolicyTest {
    @Test
    public void firstFailureAndBackoffClaimOneRetry() {
        WireGuardStartupRecoveryPolicy policy = new WireGuardStartupRecoveryPolicy(5, 1_000L, 120_000L);

        assertEquals(1_000L, policy.onFailure(0L, true));
        assertTrue(policy.isPending());
        assertEquals(WireGuardStartupRecoveryPolicy.NO_RETRY, policy.onFailure(0L, true));
        assertTrue(policy.claim());
        assertTrue(policy.begin());
        assertFalse(policy.claim());
        assertEquals(2_000L, policy.onFailure(1_000L, true));
    }

    @Test
    public void successfulStartupResetsBackoff() {
        WireGuardStartupRecoveryPolicy policy = new WireGuardStartupRecoveryPolicy(2, 500L, 10_000L);

        assertEquals(500L, policy.onFailure(0L, true));
        assertTrue(policy.claim());
        // What startNative's success path does, via
        // cancelWireGuardStartupRecovery(true).
        policy.cancel(true);
        assertEquals(500L, policy.onFailure(1L, true));
    }

    @Test
    public void uptimeClockDoesNotResetBeforeStableWindow() {
        WireGuardStartupRecoveryPolicy policy = new WireGuardStartupRecoveryPolicy(2, 500L, 10_000L);

        // ServiceSinkhole uses SystemClock.uptimeMillis() because the retry is
        // dispatched by Handler.postDelayed, whose delay also excludes sleep.
        assertEquals(500L, policy.onFailure(0L, true));
        assertTrue(policy.claim());
        assertTrue(policy.begin());
        assertEquals(1_000L, policy.onFailure(9_999L, true));
    }

    @Test
    public void exhaustionStopsFurtherRetries() {
        WireGuardStartupRecoveryPolicy policy = new WireGuardStartupRecoveryPolicy(2, 500L, 10_000L);

        assertEquals(500L, policy.onFailure(0L, true));
        assertTrue(policy.claim());
        assertTrue(policy.begin());
        assertEquals(1_000L, policy.onFailure(500L, true));
        assertTrue(policy.claim());
        assertTrue(policy.begin());
        assertEquals(WireGuardStartupRecoveryPolicy.NO_RETRY, policy.onFailure(1_500L, true));
        assertFalse(policy.isPending());
    }

    @Test
    public void cancellationRemovesPendingRetryAndResetsBudget() {
        WireGuardStartupRecoveryPolicy policy = new WireGuardStartupRecoveryPolicy(2, 500L, 10_000L);

        assertEquals(500L, policy.onFailure(0L, true));
        policy.cancel(true);
        assertFalse(policy.isPending());
        assertEquals(500L, policy.onFailure(1L, true));
    }

    @Test
    public void cancellationRemovesDispatchedRetry() {
        WireGuardStartupRecoveryPolicy policy = new WireGuardStartupRecoveryPolicy(2, 500L, 10_000L);

        assertEquals(500L, policy.onFailure(0L, true));
        assertTrue(policy.claim());
        policy.cancel(true);
        assertFalse(policy.begin());
        assertEquals(500L, policy.onFailure(1L, true));
    }

    @Test
    public void disabledServiceNeverSchedulesRetry() {
        WireGuardStartupRecoveryPolicy policy = new WireGuardStartupRecoveryPolicy(2, 500L, 10_000L);

        assertEquals(WireGuardStartupRecoveryPolicy.NO_RETRY, policy.onFailure(0L, false));
        assertFalse(policy.isPending());
        assertEquals(500L, policy.onFailure(1L, true));
    }
}
