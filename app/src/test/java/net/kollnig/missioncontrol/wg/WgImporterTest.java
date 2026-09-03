package net.kollnig.missioncontrol.wg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class WgImporterTest {
    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String CONFIG = "[Interface]\n" +
            "PrivateKey = " + KEY + "\n" +
            "Address = 10.0.0.2/32\n" +
            "\n" +
            "[Peer]\n" +
            "PublicKey = " + KEY + "\n" +
            "AllowedIPs = 0.0.0.0/0, ::/0\n" +
            "Endpoint = 192.0.2.1:51820\n";

    @Test
    public void singleConfigIsNamedAfterItsFile() throws Exception {
        WgImporter.Result result = read(CONFIG.getBytes(StandardCharsets.UTF_8), "berlin.conf");

        assertEquals(1, result.entries.size());
        assertEquals("berlin", result.entries.get(0).name);
        assertEquals(CONFIG, result.entries.get(0).config);
        assertTrue(result.skipped.isEmpty());
    }

    @Test
    public void fileThatIsNotAConfigIsReportedAsSkipped() throws Exception {
        WgImporter.Result result = read("shopping list".getBytes(StandardCharsets.UTF_8), "notes.txt");

        assertTrue(result.isEmpty());
        assertEquals(1, result.skipped.size());
        assertEquals("notes.txt", result.skipped.get(0));
    }

    @Test
    public void archiveImportsEveryConfigAndSkipsTheRest() throws Exception {
        // The case from issue #904: a provider's zip of per-server configs,
        // with the stray files such an archive usually also carries.
        byte[] archive = zip(
                "us-nyc.conf", CONFIG,
                "de-ber.conf", CONFIG,
                "README.txt", "not a config",
                "truncated.conf", "[Interface]\n");

        WgImporter.Result result = read(archive, "servers.zip");

        assertEquals(2, result.entries.size());
        assertEquals("us-nyc", result.entries.get(0).name);
        assertEquals("de-ber", result.entries.get(1).name);
        // README.txt is not a candidate at all; the malformed .conf is.
        assertEquals(1, result.skipped.size());
        assertEquals("truncated.conf", result.skipped.get(0));
    }

    @Test
    public void sameFileNameInTwoFoldersKeepsBothProfiles() throws Exception {
        byte[] archive = zip("us/newyork.conf", CONFIG, "de/newyork.conf", CONFIG);

        WgImporter.Result result = read(archive, "servers.zip");

        assertEquals(2, result.entries.size());
        assertEquals("newyork", result.entries.get(0).name);
        assertEquals("newyork (2)", result.entries.get(1).name);
    }

    @Test
    public void directoryEntriesAreIgnored() throws Exception {
        byte[] archive = zip("confs/", "", "confs/a.conf", CONFIG);

        WgImporter.Result result = read(archive, "servers.zip");

        assertEquals(1, result.entries.size());
        assertEquals("a", result.entries.get(0).name);
    }

    @Test
    public void traversingPathIsReducedToItsBaseName() throws Exception {
        // Nothing is written to disk, but the name must not carry the path
        // either, or a hostile archive picks how profiles are labelled.
        WgImporter.Result result = read(zip("../../etc/evil.conf", CONFIG), "servers.zip");

        assertEquals(1, result.entries.size());
        assertEquals("evil", result.entries.get(0).name);
    }

    @Test
    public void archiveEntryCountIsCapped() throws Exception {
        String[] entries = new String[(WgImporter.MAX_ENTRIES + 50) * 2];
        for (int i = 0; i < WgImporter.MAX_ENTRIES + 50; i++) {
            entries[i * 2] = "server" + i + ".conf";
            entries[i * 2 + 1] = CONFIG;
        }

        WgImporter.Result result = read(zip(entries), "servers.zip");

        assertEquals(WgImporter.MAX_ENTRIES, result.entries.size());
    }

    @Test
    public void oversizedEntryIsBounded() throws Exception {
        StringBuilder padded = new StringBuilder(CONFIG);
        while (padded.length() <= WgImporter.MAX_ENTRY_BYTES)
            padded.append("# padding\n");

        WgImporter.Result result = read(zip("big.conf", padded.toString()), "servers.zip");

        // Either outcome is fine; what matters is that the read stopped at
        // the cap instead of pulling an unbounded entry into memory.
        assertEquals(1, result.entries.size() + result.skipped.size());
    }

    @Test
    public void extensionsAreMatchedCaseInsensitively() throws Exception {
        WgImporter.Result result = read(zip("SERVER.CONF", CONFIG), "SERVERS.ZIP");

        assertEquals(1, result.entries.size());
        assertEquals("SERVER", result.entries.get(0).name);
    }

    @Test
    public void namesTakenByAnEarlierFileInTheSamePickAreNotReused() throws Exception {
        // Picking several providers' files at once often means several
        // "wg0.conf"; both must survive as separate profiles.
        java.util.Set<String> used = new java.util.HashSet<>();

        WgImporter.Result first = WgImporter.read(
                new ByteArrayInputStream(CONFIG.getBytes(StandardCharsets.UTF_8)), "wg0.conf", used);
        WgImporter.Result second = WgImporter.read(
                new ByteArrayInputStream(CONFIG.getBytes(StandardCharsets.UTF_8)), "wg0.conf", used);

        assertEquals("wg0", first.entries.get(0).name);
        assertEquals("wg0 (2)", second.entries.get(0).name);
    }

    @Test
    public void archiveIsDetectedWhenThePickerGivesNoUsableName() throws Exception {
        // Some document providers report a name such as "content" or nothing
        // at all; the zip magic keeps such a pick importable.
        WgImporter.Result result = read(zip("a.conf", CONFIG, "b.conf", CONFIG), "download");

        assertEquals(2, result.entries.size());
    }

    private static WgImporter.Result read(byte[] bytes, String name) throws IOException {
        return WgImporter.read(new ByteArrayInputStream(bytes), name);
    }

    private static byte[] zip(String... namesAndBodies) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndBodies.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndBodies[i]));
                zip.write(namesAndBodies[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
