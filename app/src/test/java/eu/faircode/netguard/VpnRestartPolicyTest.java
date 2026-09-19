package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VpnRestartPolicyTest {
    @Test
    public void protectionEnabledWithoutATunnelArmsTheAlarm() {
        assertTrue(VpnRestartPolicy.shouldArm(true, false, false));
    }

    @Test
    public void runningTunnelNeedsNoAlarm() {
        assertFalse(VpnRestartPolicy.shouldArm(true, true, false));
    }

    @Test
    public void disabledProtectionNeedsNoAlarm() {
        assertFalse(VpnRestartPolicy.shouldArm(false, false, false));
    }

    @Test
    public void temporaryStopIsNotARestartCandidate() {
        assertFalse(VpnRestartPolicy.shouldArm(true, false, true));
    }

    @Test
    public void ladderBacksOffAndThenHolds() {
        assertEquals(60_000L, VpnRestartPolicy.delayMs(0));
        assertEquals(5 * 60_000L, VpnRestartPolicy.delayMs(1));
        assertEquals(15 * 60_000L, VpnRestartPolicy.delayMs(2));
        assertEquals(30 * 60_000L, VpnRestartPolicy.delayMs(3));
        assertEquals(30 * 60_000L, VpnRestartPolicy.delayMs(9));
    }

    @Test
    public void attemptCounterSaturatesInsteadOfGrowing() {
        assertEquals(1, VpnRestartPolicy.nextAttempt(0));
        assertEquals(3, VpnRestartPolicy.nextAttempt(2));
        assertEquals(3, VpnRestartPolicy.nextAttempt(3));
        assertEquals(3, VpnRestartPolicy.nextAttempt(Integer.MAX_VALUE));
    }

    @Test
    public void unknownAttemptStartsAtTheShortestDelay() {
        assertEquals(0, VpnRestartPolicy.nextAttempt(-1));
        assertEquals(60_000L, VpnRestartPolicy.delayMs(-1));
    }
}
