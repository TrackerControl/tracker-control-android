package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.kollnig.missioncontrol.data.Blocklist;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Covers {@link HostsDownloadWorker#download}, the seam that decides whether a single
 * blocklist needs re-downloading. Plain JUnit: the method touches only {@code java.net}
 * and {@code java.io}, no Android dependency.
 */
public class HostsDownloadWorkerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static Blocklist blocklist() {
        Blocklist item = new Blocklist("https://example.test/hosts", true);
        item.etag = null;
        item.lastModifiedHeader = null;
        return item;
    }

    @Test
    public void targetExistsWithStoredEtagSendsIfNoneMatchAndHandles304() throws IOException {
        File target = folder.newFile("target.txt");
        Files.write(target.toPath(), "old content".getBytes(StandardCharsets.UTF_8));
        File tmp = new File(folder.getRoot(), "target.tmp");

        Blocklist item = blocklist();
        item.etag = "\"abc123\"";
        item.lastDownloadSuccess = false;
        item.lastErrorMessage = "previous failure";

        FakeHttpURLConnection connection = new FakeHttpURLConnection(
                HttpURLConnection.HTTP_NOT_MODIFIED, Collections.emptyMap(), new byte[0]);

        HostsDownloadWorker.DownloadOutcome outcome =
                HostsDownloadWorker.download(item, connection, tmp, target, () -> false);

        assertEquals(HostsDownloadWorker.DownloadOutcome.UNCHANGED, outcome);
        assertEquals("\"abc123\"", connection.capturedHeader("If-None-Match"));
        assertEquals("old content", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertTrue(item.lastDownloadSuccess);
        assertNull(item.lastErrorMessage);
    }

    @Test
    public void targetMissingSendsNoConditionalHeadersEvenWithStoredValidators() throws IOException {
        File target = new File(folder.getRoot(), "target.txt");
        File tmp = new File(folder.getRoot(), "target.tmp");
        assertFalse(target.exists());

        Blocklist item = blocklist();
        item.etag = "\"stale-etag\"";
        item.lastModifiedHeader = "Wed, 01 Jan 2025 00:00:00 GMT";

        FakeHttpURLConnection connection = new FakeHttpURLConnection(
                HttpURLConnection.HTTP_OK, Collections.emptyMap(), "fresh content".getBytes(StandardCharsets.UTF_8));

        HostsDownloadWorker.DownloadOutcome outcome =
                HostsDownloadWorker.download(item, connection, tmp, target, () -> false);

        assertEquals(HostsDownloadWorker.DownloadOutcome.UPDATED, outcome);
        assertNull(connection.capturedHeader("If-None-Match"));
        assertNull(connection.capturedHeader("If-Modified-Since"));
        assertEquals("fresh content", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void okWithValidatorsStoresThemOnItemAndReplacesFile() throws IOException {
        File target = folder.newFile("target.txt");
        Files.write(target.toPath(), "old content".getBytes(StandardCharsets.UTF_8));
        File tmp = new File(folder.getRoot(), "target.tmp");

        Blocklist item = blocklist();

        Map<String, String> headers = Map.of(
                "ETag", "\"new-etag\"",
                "Last-Modified", "Thu, 02 Jan 2026 00:00:00 GMT");
        FakeHttpURLConnection connection = new FakeHttpURLConnection(
                HttpURLConnection.HTTP_OK, headers, "new content".getBytes(StandardCharsets.UTF_8));

        HostsDownloadWorker.DownloadOutcome outcome =
                HostsDownloadWorker.download(item, connection, tmp, target, () -> false);

        assertEquals(HostsDownloadWorker.DownloadOutcome.UPDATED, outcome);
        assertEquals("new content", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertEquals("\"new-etag\"", item.etag);
        assertEquals("Thu, 02 Jan 2026 00:00:00 GMT", item.lastModifiedHeader);
        assertTrue(item.lastDownloadSuccess);
        assertNull(item.lastErrorMessage);
    }

    @Test
    public void okWithoutValidatorHeadersClearsStaleOnesFromItem() throws IOException {
        File target = folder.newFile("target.txt");
        Files.write(target.toPath(), "old content".getBytes(StandardCharsets.UTF_8));
        File tmp = new File(folder.getRoot(), "target.tmp");

        Blocklist item = blocklist();
        item.etag = "\"stale-etag\"";
        item.lastModifiedHeader = "Wed, 01 Jan 2025 00:00:00 GMT";

        FakeHttpURLConnection connection = new FakeHttpURLConnection(
                HttpURLConnection.HTTP_OK, Collections.emptyMap(), "new content".getBytes(StandardCharsets.UTF_8));

        HostsDownloadWorker.DownloadOutcome outcome =
                HostsDownloadWorker.download(item, connection, tmp, target, () -> false);

        assertEquals(HostsDownloadWorker.DownloadOutcome.UPDATED, outcome);
        assertNull(item.etag);
        assertNull(item.lastModifiedHeader);
    }

    /**
     * Minimal fake so the seam can be exercised without a real network stack. Request
     * headers are captured into their own map rather than delegating to
     * {@code URLConnection}'s, since that throws once {@link #connect()} has run (as
     * {@link HostsDownloadWorker#download} always does before a test can inspect them).
     */
    private static final class FakeHttpURLConnection extends HttpURLConnection {
        private final int responseCode;
        private final Map<String, String> responseHeaders;
        private final byte[] body;
        private final Map<String, String> requestHeaders = new HashMap<>();

        FakeHttpURLConnection(int responseCode, Map<String, String> responseHeaders, byte[] body)
                throws MalformedURLException {
            super(new URL("https://example.test/hosts"));
            this.responseCode = responseCode;
            this.responseHeaders = responseHeaders;
            this.body = body;
        }

        @Override
        public void setRequestProperty(String key, String value) {
            requestHeaders.put(key, value);
        }

        String capturedHeader(String key) {
            return requestHeaders.get(key);
        }

        @Override
        public void connect() {
            connected = true;
        }

        @Override
        public void disconnect() {
            connected = false;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public String getResponseMessage() {
            return "fake";
        }

        @Override
        public String getHeaderField(String name) {
            return responseHeaders.get(name);
        }

        @Override
        public String getContentEncoding() {
            return null;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(body);
        }
    }
}
