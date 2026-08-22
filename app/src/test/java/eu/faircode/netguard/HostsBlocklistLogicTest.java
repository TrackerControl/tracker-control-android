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
