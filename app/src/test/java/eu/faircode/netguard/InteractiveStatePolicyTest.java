package eu.faircode.netguard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public class InteractiveStatePolicyTest {
    @Test
    public void screenChangeUpdatesWireGuardAndStatsWithoutReloadAction() {
        AtomicBoolean wireGuardUpdated = new AtomicBoolean(false);
        AtomicBoolean statsStarted = new AtomicBoolean(false);

        InteractiveStatePolicy.onScreenStateChanged(
                true,
                true,
                new InteractiveStatePolicy.Callbacks() {
                    @Override
                    public void onWireGuardInteractiveStateChanged(boolean interactive) {
                        wireGuardUpdated.set(interactive);
                    }

                    @Override
                    public void onStatsInteractiveStateChanged(boolean interactive) {
                        statsStarted.set(interactive);
                    }
                });

        assertTrue(wireGuardUpdated.get());
        assertTrue(statsStarted.get());
    }

    @Test
    public void screenOffDisablesInteractiveWireGuardStateAndStopsStats() {
        AtomicBoolean wireGuardInteractive = new AtomicBoolean(true);
        AtomicBoolean statsInteractive = new AtomicBoolean(true);

        InteractiveStatePolicy.onScreenStateChanged(
                false,
                false,
                new InteractiveStatePolicy.Callbacks() {
                    @Override
                    public void onWireGuardInteractiveStateChanged(boolean interactive) {
                        wireGuardInteractive.set(interactive);
                    }

                    @Override
                    public void onStatsInteractiveStateChanged(boolean interactive) {
                        statsInteractive.set(interactive);
                    }
                });

        assertFalse(wireGuardInteractive.get());
        assertFalse(statsInteractive.get());
    }
}
