package eu.faircode.netguard;

final class InteractiveStatePolicy {
    interface Callbacks {
        void onWireGuardInteractiveStateChanged(boolean interactive);

        void onStatsInteractiveStateChanged(boolean interactive);
    }

    private InteractiveStatePolicy() {
    }

    static void onScreenStateChanged(boolean interactive, boolean statsInteractive, Callbacks callbacks) {
        callbacks.onWireGuardInteractiveStateChanged(interactive);
        callbacks.onStatsInteractiveStateChanged(statsInteractive);
    }
}
