/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package eu.faircode.netguard;

final class VpnReplacementRecoveryPolicy {
    static final long NO_RETRY = -1L;

    private final int maxRetries;
    private final long initialDelayMs;
    private int failures;

    VpnReplacementRecoveryPolicy(int maxRetries, long initialDelayMs) {
        if (maxRetries < 1 || initialDelayMs < 0)
            throw new IllegalArgumentException();

        this.maxRetries = maxRetries;
        this.initialDelayMs = initialDelayMs;
    }

    synchronized long onFailure() {
        if (failures >= maxRetries)
            return NO_RETRY;

        // Exponential backoff, clamped to avoid signed-long overflow when
        // maxRetries (and thus the shift amount) is large.
        long delay = (failures < Long.SIZE - 1 && initialDelayMs <= (Long.MAX_VALUE >> failures))
                ? initialDelayMs << failures
                : Long.MAX_VALUE;
        failures++;
        return delay;
    }

    synchronized void reset() {
        failures = 0;
    }
}
