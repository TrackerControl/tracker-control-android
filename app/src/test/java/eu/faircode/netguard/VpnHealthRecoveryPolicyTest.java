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

    @Test
    public void wallClockCapSurvivesEpochReset() {
        final long[] now = {0L};
        VpnHealthRecoveryPolicy policy = new VpnHealthRecoveryPolicy(() -> now[0]);

        // Two epochs, each recovering up to the per-epoch budget, all within
        // the wall-clock window. Total recoveries so far: 4, hitting
        // MAX_RECOVERIES_PER_WINDOW.
        assertRecovery(policy);
        now[0] += 1_000L;
        assertRecovery(policy);

        policy.beginEpoch();
        now[0] += 1_000L;
        assertRecovery(policy);
        now[0] += 1_000L;
        assertRecovery(policy);

        assertEquals(VpnHealthRecoveryPolicy.MAX_RECOVERIES_PER_WINDOW, 4);

        // A third epoch reset would normally re-arm the per-epoch budget, but
        // the absolute wall-clock cap must still block further recoveries.
        policy.beginEpoch();
        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.EXHAUSTED, policy.record(false, true));
    }

    @Test
    public void wallClockCapReleasesRecoveriesOutsideWindow() {
        final long[] now = {0L};
        VpnHealthRecoveryPolicy policy = new VpnHealthRecoveryPolicy(() -> now[0]);

        assertRecovery(policy);
        now[0] += 1_000L;
        assertRecovery(policy);
        policy.beginEpoch();
        now[0] += 1_000L;
        assertRecovery(policy);
        now[0] += 1_000L;
        assertRecovery(policy);

        // Exhausted immediately after the epoch reset while still inside the window.
        policy.beginEpoch();
        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.EXHAUSTED, policy.record(false, true));

        // Advance past the rolling window: the oldest recoveries age out and
        // the cap allows a new recovery again.
        now[0] += VpnHealthRecoveryPolicy.RECOVERY_WINDOW_MS + 1;
        policy.beginEpoch();
        assertRecovery(policy);
    }

    private static void assertRecovery(VpnHealthRecoveryPolicy policy) {
        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.RETRY, policy.record(false, true));
        assertEquals(VpnHealthRecoveryPolicy.Action.RECOVER, policy.record(false, true));
    }
}
