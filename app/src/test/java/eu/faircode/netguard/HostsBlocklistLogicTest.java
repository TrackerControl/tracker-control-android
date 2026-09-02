package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

public class HostsBlocklistLogicTest {
    @Test
    public void failedParseDoesNotPinPartialMapAtNewMtime() throws Exception {
        Map<String, Boolean> hosts = new HashMap<>();
        HostsBlocklistLogic.State state = new HostsBlocklistLogic.State(hosts, 10L);

        try {
            state.load(new FailingReader(), 20L);
            fail("Expected the parse to fail");
        } catch (IOException expected) {
            // The partial entry remains, matching the service's failure behavior.
        }

        assertEquals(10L, state.getLastModified());
        assertTrue(state.shouldReload(20L));
        assertTrue(hosts.containsKey("first.example"));

        assertTrue(state.load(new StringReader(
                "1.1.1.1 first.example\n2.2.2.2 second.example\n"), 20L));
        assertEquals(20L, state.getLastModified());
        assertEquals(3, hosts.size());
        assertTrue(hosts.containsKey("first.example"));
        assertTrue(hosts.containsKey("second.example"));
        assertTrue(hosts.containsKey("test.netguard.me"));

        assertFalse(state.load(new FailingReader(), 20L));
        assertEquals(20L, state.getLastModified());
        assertEquals(3, hosts.size());
    }

    @Test
    public void parseAcceptsMultipleHostnamesPerLine() throws Exception {
        Map<String, Boolean> hosts = new HashMap<>();
        HostsBlocklistLogic.State state = new HostsBlocklistLogic.State(hosts, 0L);

        assertTrue(state.load(new StringReader(
                "0.0.0.0 single.example\n"
                        + "0.0.0.0 first.example second.example third.example\n"), 1L));

        // The single-hostname and multi-hostname lines, plus the always-added
        // test entry.
        assertEquals(5, hosts.size());
        assertTrue(hosts.containsKey("single.example"));
        assertTrue(hosts.containsKey("first.example"));
        assertTrue(hosts.containsKey("second.example"));
        assertTrue(hosts.containsKey("third.example"));
        assertTrue(hosts.containsKey("test.netguard.me"));
    }

    @Test
    public void parseRejectsLineWithNoHostname() throws Exception {
        Map<String, Boolean> hosts = new HashMap<>();
        HostsBlocklistLogic.State state = new HostsBlocklistLogic.State(hosts, 0L);

        assertTrue(state.load(new StringReader("0.0.0.0\n"), 1L));

        // Only the always-added test entry, since the address-only line has no
        // hostname to key on.
        assertEquals(1, hosts.size());
        assertTrue(hosts.containsKey("test.netguard.me"));
    }

    @Test
    public void stripTrailingDotNormalisesFullyQualifiedHostnames() {
        assertEquals("ads.example", HostsBlocklistLogic.stripTrailingDot("ads.example."));
        assertEquals("ads.example", HostsBlocklistLogic.stripTrailingDot("ads.example"));
        assertEquals(".", HostsBlocklistLogic.stripTrailingDot("."));
    }

    private static final class FailingReader extends Reader {
        private final String firstLine = "1.1.1.1 first.example\n";
        private int position;
        private boolean failed;

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            if (failed)
                throw new IOException("mid-parse");
            if (position == firstLine.length()) {
                failed = true;
                throw new IOException("mid-parse");
            }

            int count = Math.min(len, firstLine.length() - position);
            firstLine.getChars(position, position + count, cbuf, off);
            position += count;
            return count;
        }

        @Override
        public void close() {
        }
    }
}
