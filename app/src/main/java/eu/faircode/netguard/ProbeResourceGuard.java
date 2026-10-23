package eu.faircode.netguard;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;

/** Limits optional connectivity probes when the process cannot allocate descriptors. */
final class ProbeResourceGuard {
    // Process-wide: losing a Network or recreating the service must not bypass the cooldown.
    static final ProbeResourceGuard INSTANCE = new ProbeResourceGuard();
    static final long COOLDOWN_MS = 10 * 60 * 1000L;
    static final int MAX_SAMPLED_DESCRIPTORS = 256;
    private long retryAfterMs;

    synchronized boolean isBlocked(long nowMs, boolean interactive) {
        return retryAfterMs != 0 && (nowMs < retryAfterMs || !interactive);
    }

    /** Returns true only for the first failure in a cooldown, to bound diagnostics too. */
    synchronized boolean onExhaustion(long nowMs) {
        if (nowMs < retryAfterMs)
            return false;
        retryAfterMs = nowMs + COOLDOWN_MS;
        return true;
    }

    synchronized void onSuccess(long nowMs) {
        // A successful probe already in flight must not cancel a newly started cooldown.
        if (nowMs >= retryAfterMs)
            retryAfterMs = 0;
    }

    static boolean isDescriptorExhaustion(Throwable failure) {
        // Android wraps errno in IOException and sometimes UncheckedIOException.
        // Bound traversal defensively; never classify by a possibly misleading message.
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof ErrnoException) {
                int errno = ((ErrnoException) failure).errno;
                if (errno == OsConstants.EMFILE || errno == OsConstants.ENFILE)
                    return true;
            }
        }
        return false;
    }

    static String descriptorSummary() {
        try {
            return descriptorSummary(new File("/proc/self/fd").list(), Os::readlink);
        } catch (SecurityException ex) {
            return "fd census unavailable";
        }
    }

    interface LinkReader {
        String read(String path) throws ErrnoException;
    }

    static String descriptorSummary(String[] descriptors, LinkReader reader) {
        // Opening /proc/self/fd can itself fail under EMFILE. Do not reserve an FD or retry.
        if (descriptors == null)
            return "fd census unavailable";
        int sampled = Math.min(descriptors.length, MAX_SAMPLED_DESCRIPTORS);
        int sockets = 0, pipes = 0, anonymous = 0, other = 0, unreadable = 0;
        for (int i = 0; i < sampled; i++) {
            try {
                String target = reader.read("/proc/self/fd/" + descriptors[i]);
                if (target.startsWith("socket:"))
                    sockets++;
                else if (target.startsWith("pipe:"))
                    pipes++;
                else if (target.startsWith("anon_inode:"))
                    anonymous++;
                else
                    other++;
            } catch (ErrnoException | SecurityException ex) {
                // Descriptors may close during the census. Never log targets or exceptions.
                unreadable++;
            }
        }
        return "fd total=" + descriptors.length + " sampled=" + sampled
                + " sockets=" + sockets + " pipes=" + pipes + " anonymous=" + anonymous
                + " other=" + other + " unreadable=" + unreadable;
    }
}
