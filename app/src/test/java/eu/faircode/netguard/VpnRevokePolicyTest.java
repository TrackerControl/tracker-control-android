package eu.faircode.netguard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class VpnRevokePolicyTest {
    @Test
    public void alwaysOnRevokeKeepsEnabledStateForRecovery() {
        assertTrue(ServiceSinkhole.resolveEnabledAfterRevoke(true, true));
    }

    @Test
    public void ordinaryRevokeDisablesWatchdogRecovery() {
        assertFalse(ServiceSinkhole.resolveEnabledAfterRevoke(true, false));
    }

    @Test
    public void revokeNeverEnablesAnAlreadyDisabledService() {
        assertFalse(ServiceSinkhole.resolveEnabledAfterRevoke(false, true));
    }
}
