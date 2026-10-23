package eu.faircode.netguard;

import static org.junit.Assert.*;

import android.system.ErrnoException;
import android.system.OsConstants;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ProbeResourceGuardTest {
    @Test
    public void recognisesCheckedAndUncheckedDescriptorFailures() {
        for (int errno : new int[]{OsConstants.EMFILE, OsConstants.ENFILE}) {
            IOException checked = new IOException(new ErrnoException("fcntl", errno));
            assertTrue(ProbeResourceGuard.isDescriptorExhaustion(checked));
            assertTrue(ProbeResourceGuard.isDescriptorExhaustion(new UncheckedIOException(checked)));
        }
        assertFalse(ProbeResourceGuard.isDescriptorExhaustion(
                new IOException(new ErrnoException("connect", OsConstants.ETIMEDOUT))));
        assertFalse(ProbeResourceGuard.isDescriptorExhaustion(new IOException("EMFILE")));
    }

    @Test
    public void cooldownSuppressesAllProbesAndDiagnosticsUntilBoundary() {
        ProbeResourceGuard guard = new ProbeResourceGuard();
        assertFalse(guard.isBlocked(100, false));
        assertTrue(guard.onExhaustion(100));
        assertTrue(guard.isBlocked(101, true));
        assertFalse(guard.onExhaustion(101));
        assertTrue(guard.isBlocked(100 + ProbeResourceGuard.COOLDOWN_MS - 1, true));
        assertFalse(guard.isBlocked(100 + ProbeResourceGuard.COOLDOWN_MS, true));
        // Duplicate failures neither extend the cooldown nor allow duplicate diagnostics.
        assertTrue(guard.onExhaustion(100 + ProbeResourceGuard.COOLDOWN_MS));
    }

    @Test
    public void expiredCooldownStillDefersScreenOffRetriesUntilRecovery() {
        ProbeResourceGuard guard = new ProbeResourceGuard();
        guard.onExhaustion(100);
        long later = 100 + ProbeResourceGuard.COOLDOWN_MS;
        assertTrue(guard.isBlocked(later, false));
        assertFalse(guard.isBlocked(later, true));
        guard.onSuccess(later);
        assertFalse(guard.isBlocked(later, false));
    }

    @Test
    public void inFlightSuccessCannotClearNewCooldown() {
        ProbeResourceGuard guard = new ProbeResourceGuard();
        guard.onExhaustion(100);
        guard.onSuccess(101);
        assertTrue(guard.isBlocked(102, true));
    }

    @Test
    public void censusContainsOnlyAggregateCounts() {
        String[] targets = {"socket:[123]", "pipe:[456]", "anon_inode:[eventpoll]",
                "/private/sensitive-file", "/another/private-file"};
        AtomicInteger index = new AtomicInteger();
        String result = ProbeResourceGuard.descriptorSummary(new String[]{"0", "1", "2", "3", "4", "5"},
                path -> {
                    int i = index.getAndIncrement();
                    if (i == targets.length)
                        throw new ErrnoException("private diagnostic text", OsConstants.ENOENT);
                    return targets[i];
                });
        assertEquals("fd total=6 sampled=6 sockets=1 pipes=1 anonymous=1 other=2 unreadable=1", result);
    }

    @Test
    public void censusBoundsReadlinkWorkAndHandlesDirectoryFailure() {
        AtomicInteger reads = new AtomicInteger();
        String result = ProbeResourceGuard.descriptorSummary(new String[1000], path -> {
            reads.incrementAndGet();
            return "socket:[123]";
        });
        assertEquals(256, reads.get());
        assertEquals("fd total=1000 sampled=256 sockets=256 pipes=0 anonymous=0 other=0 unreadable=0", result);
        assertEquals("fd census unavailable", ProbeResourceGuard.descriptorSummary(null, path -> {
            fail("Must not read links when the directory is unavailable");
            return "";
        }));
    }
}
