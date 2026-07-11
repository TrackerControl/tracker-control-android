package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VpnHealthRecoveryPolicyTest {
    @Test
    public void healthyVpnNeedsNoRecovery() {
        VpnHealthRecoveryPolicy policy = new VpnHealthRecoveryPolicy();

        assertEquals(VpnHealthRecoveryPolicy.Action.NONE, policy.record(true, false));
        assertEquals(0, policy.getRecoveries());
    }

    @Test
    public void offlineDeviceDoesNotCountAsVpnFailure() {
        VpnHealthRecoveryPolicy policy = new VpnHealthRecoveryPolicy();

        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.NONE, policy.record(false, false));
        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
    }

    @Test
    public void consecutiveVpnOnlyFailuresTriggerRecovery() {
        VpnHealthRecoveryPolicy policy = new VpnHealthRecoveryPolicy();

        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.RECOVER, policy.record(false, true));
        assertEquals(1, policy.getRecoveries());
    }

    @Test
    public void healthyResultBreaksFailureStreak() {
        VpnHealthRecoveryPolicy policy = new VpnHealthRecoveryPolicy();

        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.NONE, policy.record(true, false));
        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.RECOVER, policy.record(false, true));
    }

    @Test
    public void recoveryBudgetIsFiniteWithinEpoch() {
        VpnHealthRecoveryPolicy policy = new VpnHealthRecoveryPolicy();

        assertRecovery(policy);
        assertRecovery(policy);

        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.EXHAUSTED, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.MAX_RECOVERIES_PER_EPOCH, policy.getRecoveries());
    }

    @Test
    public void newNetworkEpochRestoresRecoveryBudget() {
        VpnHealthRecoveryPolicy policy = new VpnHealthRecoveryPolicy();

        assertRecovery(policy);
        assertRecovery(policy);
        policy.beginEpoch();

        assertRecovery(policy);
        assertEquals(1, policy.getRecoveries());
    }

    private static void assertRecovery(VpnHealthRecoveryPolicy policy) {
        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.RECOVER, policy.record(false, true));
    }
}
