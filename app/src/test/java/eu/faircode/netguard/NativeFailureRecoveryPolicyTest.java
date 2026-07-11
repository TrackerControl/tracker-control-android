package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NativeFailureRecoveryPolicyTest {
    @Test
    public void retriesUseBoundedExponentialBackoff() {
        NativeFailureRecoveryPolicy policy = new NativeFailureRecoveryPolicy(5, 1_000L, 120_000L);

        assertEquals(1_000L, policy.onFailure(0L));
        assertEquals(2_000L, policy.onFailure(1_000L));
        assertEquals(4_000L, policy.onFailure(3_000L));
        assertEquals(8_000L, policy.onFailure(7_000L));
        assertEquals(16_000L, policy.onFailure(15_000L));
        assertEquals(NativeFailureRecoveryPolicy.NO_RETRY, policy.onFailure(31_000L));
    }

    @Test
    public void stableRunRestoresRetryBudget() {
        NativeFailureRecoveryPolicy policy = new NativeFailureRecoveryPolicy(2, 500L, 10_000L);

        assertEquals(500L, policy.onFailure(0L));
        assertEquals(1_000L, policy.onFailure(500L));
        assertEquals(500L, policy.onFailure(10_500L));
    }

    @Test
    public void clockResetRestoresRetryBudget() {
        NativeFailureRecoveryPolicy policy = new NativeFailureRecoveryPolicy(1, 250L, 10_000L);

        assertEquals(250L, policy.onFailure(20_000L));
        assertEquals(250L, policy.onFailure(1_000L));
    }

    @Test
    public void explicitResetRestoresRetryBudget() {
        NativeFailureRecoveryPolicy policy = new NativeFailureRecoveryPolicy(1, 250L, 10_000L);

        assertEquals(250L, policy.onFailure(0L));
        assertEquals(NativeFailureRecoveryPolicy.NO_RETRY, policy.onFailure(1L));
        policy.reset();
        assertEquals(250L, policy.onFailure(2L));
    }

    @Test
    public void largeRetryBudgetDoesNotOverflowBackoff() {
        // A generous config whose naive `initialDelayMs << failures` would
        // overflow a signed long; every delay must stay positive and non-decreasing.
        NativeFailureRecoveryPolicy policy = new NativeFailureRecoveryPolicy(80, 1_000L, Long.MAX_VALUE);

        long previous = 0L;
        for (int i = 0; i < 80; i++) {
            long delay = policy.onFailure(i);
            assertTrue("delay must stay positive (no overflow)", delay > 0L);
            assertTrue("backoff must be non-decreasing", delay >= previous);
            previous = delay;
        }
        assertEquals(NativeFailureRecoveryPolicy.NO_RETRY, policy.onFailure(81L));
    }

    @Test
    public void identifiesFileDescriptorExhaustion() {
        assertTrue(NativeFailureRecoveryPolicy.isFileDescriptorExhaustion(24));
        assertFalse(NativeFailureRecoveryPolicy.isFileDescriptorExhaustion(23));
    }
}
