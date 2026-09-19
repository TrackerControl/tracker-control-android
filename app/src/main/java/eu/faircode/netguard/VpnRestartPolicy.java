/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation.
 */

package eu.faircode.netguard;

/**
 * When a service that went down while protection was enabled should try again.
 *
 * <p>Every other recovery path in {@link ServiceSinkhole} is registered by the
 * service itself — the Doze-exit reload, the connectivity receiver, the network
 * callbacks — so all of them die with it. Once the system revokes the tunnel or
 * kills the process, nothing is left in the app to notice, and protection stays
 * off until the user opens the app or reboots. See issue #954.
 *
 * <p>The retry therefore has to outlive the process, which means an alarm. The
 * ladder starts short, because most of these teardowns are transient, and caps
 * at half an hour: the alarm only exists while protection is enabled and not
 * running, but a device that refuses background service starts would otherwise
 * be woken on the short delay indefinitely.
 */
final class VpnRestartPolicy {
    /** Delay ladder in milliseconds; the last entry repeats. */
    private static final long[] DELAYS_MS = {
            60_000L,
            5 * 60_000L,
            15 * 60_000L,
            30 * 60_000L,
    };

    private VpnRestartPolicy() {
    }

    /**
     * @param enabled            the user's protection setting, which a
     *                           deliberate switch-off or a lost VPN consent has
     *                           already cleared
     * @param vpnRunning         whether a tunnel is currently established
     * @param temporarilyStopped whether the tunnel is down on purpose, as it is
     *                           for the duration of a phone call; restarting it
     *                           there would undo that stop, and the call-state
     *                           listener brings it back on its own
     */
    static boolean shouldArm(boolean enabled, boolean vpnRunning, boolean temporarilyStopped) {
        return enabled && !vpnRunning && !temporarilyStopped;
    }

    /** @param attempt zero for the first retry after the service went down */
    static long delayMs(int attempt) {
        if (attempt < 0)
            attempt = 0;
        return DELAYS_MS[Math.min(attempt, DELAYS_MS.length - 1)];
    }

    /** Saturates at the capped rung, so the counter cannot overflow. */
    static int nextAttempt(int attempt) {
        if (attempt < 0)
            return 0;
        return Math.min(attempt + 1, DELAYS_MS.length - 1);
    }
}
