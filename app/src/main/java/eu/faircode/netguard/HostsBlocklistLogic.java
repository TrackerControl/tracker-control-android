package eu.faircode.netguard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Locale;
import java.util.Map;

final class HostsBlocklistLogic {
    interface Logger {
        void log(String message);
    }

    private static final Logger NOOP_LOGGER = message -> {
    };

    static final class State {
        private final Map<String, Boolean> mapHostsBlocked;
        private final Logger logger;
        private long lastModified;

        State(Map<String, Boolean> mapHostsBlocked, long lastModified) {
            this(mapHostsBlocked, lastModified, NOOP_LOGGER);
        }

        State(Map<String, Boolean> mapHostsBlocked, long lastModified, Logger logger) {
            this.mapHostsBlocked = mapHostsBlocked;
            this.lastModified = lastModified;
            this.logger = logger;
        }

        boolean shouldReload(long modified) {
            return modified != lastModified || mapHostsBlocked.size() == 0;
        }

        boolean load(Reader reader, long modified) throws IOException {
            if (!shouldReload(modified))
                return false;

            parse(reader);
            lastModified = modified;
            return true;
        }

        void parse(Reader reader) throws IOException {
            mapHostsBlocked.clear();
            BufferedReader br = reader instanceof BufferedReader
                    ? (BufferedReader) reader : new BufferedReader(reader);
            int count = 0;
            String line;
            while ((line = br.readLine()) != null) {
                int hash = line.indexOf('#');
                if (hash >= 0)
                    line = line.substring(0, hash);
                line = line.trim();
                if (line.length() > 0) {
                    String[] words = line.split("\\s+");
                    if (words.length == 2) {
                        count++;
                        // Keyed lowercase to match TrackerList.findTracker(),
                        // which normalises qnames before the hosts lookup.
                        mapHostsBlocked.put(words[1].toLowerCase(Locale.ROOT), true);
                    } else
                        logger.log("Invalid hosts file line: " + line);
                }
            }
            mapHostsBlocked.put("test.netguard.me", true);
            logger.log(count + " hosts read");
        }

        long getLastModified() {
            return lastModified;
        }
    }

    private HostsBlocklistLogic() {
    }
}
