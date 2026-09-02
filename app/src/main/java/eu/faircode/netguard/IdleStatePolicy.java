package eu.faircode.netguard;

/**
 * What happens when the device enters or leaves Doze.
 *
 * <p>Leaving Doze used to reload unconditionally. That was inherited from NetGuard and had
 * no purpose here: nothing in rule building depends on idle state, while the reload's
 * "Native restart" path calls {@code jni_clear}, which closes the socket of every live
 * session. Every maintenance window therefore dropped all connections for all apps, and
 * charged a wakelock, a rule rebuild and a full {@code access} table rescan for it.
 *
 * <p>What genuinely has to happen on the way out of Doze is the screen-state keepalive
 * policy, which the WireGuard egress owns. The reload survives only as a recovery net for
 * the case where the VPN really did die while the device was idle — protection enabled but
 * no established tunnel — since nothing else would notice until the next network event.
 */
final class IdleStatePolicy {
    interface Callbacks {
        void onWireGuardInteractiveStateChanged(boolean interactive);

        void onReload();
    }

    private IdleStatePolicy() {
    }

    /**
     * @param recoveryCondition protection is enabled but no VPN is established, i.e. the
     *                          only case in which leaving Doze should still reload
     */
    static void onDeviceIdleModeChanged(boolean deviceIdleMode, boolean recoveryCondition,
                                        boolean interactive, Callbacks callbacks) {
        if (deviceIdleMode)
            return;

        callbacks.onWireGuardInteractiveStateChanged(interactive);
        if (recoveryCondition)
            callbacks.onReload();
    }
}
