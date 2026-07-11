/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package eu.faircode.netguard;

final class NativeFailureRecoveryPolicy {
    static final long NO_RETRY = -1L;
    static final int FILE_DESCRIPTOR_LIMIT_ERROR = 24;

    private final int maxRetries;
    private final long initialDelayMs;
    private final long stableWindowMs;

    private int failures;
    private long lastFailureMs = Long.MIN_VALUE;

    NativeFailureRecoveryPolicy(int maxRetries, long initialDelayMs, long stableWindowMs) {
        if (maxRetries < 1 || initialDelayMs < 0 || stableWindowMs < 0)
            throw new IllegalArgumentException();

        this.maxRetries = maxRetries;
        this.initialDelayMs = initialDelayMs;
        this.stableWindowMs = stableWindowMs;
    }

    synchronized long onFailure(long nowMs) {
        if (lastFailureMs != Long.MIN_VALUE &&
                (nowMs < lastFailureMs || nowMs - lastFailureMs >= stableWindowMs))
            failures = 0;

        lastFailureMs = nowMs;
        if (failures >= maxRetries)
            return NO_RETRY;

        long delay = initialDelayMs << failures;
        failures++;
        return delay;
    }

    synchronized void reset() {
        failures = 0;
        lastFailureMs = Long.MIN_VALUE;
    }

    static boolean isFileDescriptorExhaustion(int error) {
        return error == FILE_DESCRIPTOR_LIMIT_ERROR;
    }
}
