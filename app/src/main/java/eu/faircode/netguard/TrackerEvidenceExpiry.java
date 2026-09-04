/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package eu.faircode.netguard;

/** Expiry arithmetic for DNS evidence-backed tracker verdicts. */
final class TrackerEvidenceExpiry {
    private TrackerEvidenceExpiry() {
    }

    /** Add a DNS TTL without allowing malformed or extreme input to wrap. */
    static long at(long timeMs, long ttlMs) {
        if (ttlMs > 0 && timeMs > Long.MAX_VALUE - ttlMs)
            return Long.MAX_VALUE;
        if (ttlMs < 0 && timeMs < Long.MIN_VALUE - ttlMs)
            return Long.MIN_VALUE;
        return timeMs + ttlMs;
    }

    /** A mixed verdict is valid only while both kinds of evidence remain fresh. */
    static long mixed(long selectedTrackerExpiry, long lastOtherExpiry) {
        return Math.min(selectedTrackerExpiry, lastOtherExpiry);
    }
}
