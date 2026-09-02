package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IdleStatePolicyTest {
    @Test
    public void healthyVpnUpdatesWireGuardWithoutReload() {
        boolean[] wireGuardInteractive = {false};
        boolean[] reload = {false};

        IdleStatePolicy.onDeviceIdleModeChanged(
                false,
                false,
                true,
                new IdleStatePolicy.Callbacks() {
                    @Override
                    public void onWireGuardInteractiveStateChanged(boolean interactive) {
                        wireGuardInteractive[0] = interactive;
                    }

                    @Override
                    public void onReload() {
                        reload[0] = true;
                    }
                });

        assertTrue(wireGuardInteractive[0]);
        assertFalse(reload[0]);
    }

    @Test
    public void enabledWithoutVpnReloadsAfterIdle() {
        boolean[] wireGuardUpdated = {false};
        boolean[] reload = {false};

        IdleStatePolicy.onDeviceIdleModeChanged(
                false,
                true,
                true,
                new IdleStatePolicy.Callbacks() {
                    @Override
                    public void onWireGuardInteractiveStateChanged(boolean interactive) {
                        wireGuardUpdated[0] = true;
                    }

                    @Override
                    public void onReload() {
                        reload[0] = true;
                    }
                });

        assertTrue(wireGuardUpdated[0]);
        assertTrue(reload[0]);
    }

    @Test
    public void enteringIdleDoesNothing() {
        int[] callbacks = {0};

        IdleStatePolicy.onDeviceIdleModeChanged(
                true,
                true,
                true,
                new IdleStatePolicy.Callbacks() {
                    @Override
                    public void onWireGuardInteractiveStateChanged(boolean interactive) {
                        callbacks[0]++;
                    }

                    @Override
                    public void onReload() {
                        callbacks[0]++;
                    }
                });

        assertEquals(0, callbacks[0]);
    }
}
