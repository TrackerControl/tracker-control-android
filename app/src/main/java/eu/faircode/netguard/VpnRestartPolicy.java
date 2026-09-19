/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation.
 */

package eu.faircode.netguard;

/**
 * Whether a VPN that is not running should be restarted from outside the
 * service.
 *
 * <p>Every recovery path in {@link ServiceSinkhole} is registered by the
 * service itself — the Doze-exit reload, the connectivity receiver, the network
 * callbacks — so all of them die with it. When the system kills the service
 * while the device is idle, nothing in the app is left to notice, and
 * protection stays off until the user opens the app or reboots. See issue #954.
 *
 * <p>What must not be restarted is a VPN that is down on purpose. The service
 * records a deliberate teardown as it happens; a kill records nothing, which is
 * exactly what distinguishes the two afterwards.
 */
final class VpnRestartPolicy {
    private VpnRestartPolicy() {
    }

    /**
     * @param enabled        the user's protection setting
     * @param stoppedCleanly whether the last teardown was one the service
     *                       carried out itself — a switch-off, a stop for a
     *                       phone call, or a revoke
     * @param vpnEstablished whether a tunnel is up right now
     */
    static boolean shouldRestart(boolean enabled, boolean stoppedCleanly, boolean vpnEstablished) {
        return enabled && !stoppedCleanly && !vpnEstablished;
    }
}
