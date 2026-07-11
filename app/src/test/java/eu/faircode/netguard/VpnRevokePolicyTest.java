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

    @Test
    public void unknownAlwaysOnStateKeepsEnabledStateForRecovery() {
        // A null/unreadable always-on lookup (e.g. the hidden pre-Q
        // "always_on_vpn_app" setting returning null on some OEM builds even
        // though TC is the configured always-on VPN) must not force a
        // disable: the state is left as-is, biased toward staying enabled.
        assertTrue(ServiceSinkhole.resolveEnabledAfterRevoke(true, null));
    }

    @Test
    public void unknownAlwaysOnStateNeverEnablesAnAlreadyDisabledService() {
        assertFalse(ServiceSinkhole.resolveEnabledAfterRevoke(false, null));
    }
}
