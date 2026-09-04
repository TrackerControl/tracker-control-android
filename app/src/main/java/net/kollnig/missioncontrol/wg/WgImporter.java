package net.kollnig.missioncontrol.wg;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads WireGuard profiles out of a file the user picked: either a single
 * {@code .conf} or a {@code .zip} of them, which is what providers hand out
 * when an account covers many servers (issue #904).
 *
 * The logic here is deliberately free of Android dependencies — the caller
 * opens the stream through the content resolver and passes the picker's
 * display name for naming. Every candidate is parsed by {@link WgConfigParser}
 * before it is offered for saving, so an archive that mixes configs with
 * unrelated files imports the configs and reports the rest as skipped rather
 * than failing as a whole.
 */
public final class WgImporter {
    /** Ceiling on configs taken from one archive, to bound a hostile zip. */
    static final int MAX_ENTRIES = 200;
    /**
     * Ceiling on entries walked in one archive. Directories and other files
     * are skipped without being imported, so they must be counted separately
     * or an archive of a million empty entries would still be walked whole.
     */
    static final int MAX_SCANNED_ENTRIES = 5000;
    /** Ceiling on a single config, far above any real one (they are ~1 KiB). */
    static final int MAX_ENTRY_BYTES = 128 * 1024;
    /**
     * Ceiling on everything read out of one archive. The per-entry limit alone
     * would still let a padded archive hold megabytes of text in memory at
     * once, all of which is then serialised into the profile store.
     */
    static final int MAX_TOTAL_BYTES = 2 * 1024 * 1024;

    private WgImporter() {
    }

    public static final class Entry {
        public final String name;
        public final String config;

        public Entry(String name, String config) {
            this.name = name;
            this.config = config;
        }
    }

    public static final class Result {
        /** Configs that parsed, in the order they appeared. */
        public final List<Entry> entries;
        /** File names that looked importable but did not parse. */
        public final List<String> skipped;

        Result(List<Entry> entries, List<String> skipped) {
            this.entries = entries;
            this.skipped = skipped;
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    /** Reads one file with a fresh name space. */
    public static Result read(InputStream in, String displayName) throws IOException {
        return read(in, displayName, new HashSet<>());
    }

    /**
     * @param in          stream over the picked file; the caller closes it
     * @param displayName file name from the picker, used for profile naming
     *                    and to tell an archive from a single config
     * @param usedNames   names already taken, carried across the files of one
     *                    multi-file pick so two servers that share a file name
     *                    both survive instead of one overwriting the other
     */
    public static Result read(InputStream in, String displayName, Set<String> usedNames)
            throws IOException {
        String name = displayName == null ? "" : displayName.trim();
        // Not every document provider reports a usable file name, so the
        // archive check falls back to the zip magic rather than treating an
        // archive as one unparsable config.
        BufferedInputStream buffered = new BufferedInputStream(in);
        if (isArchiveName(name) || (!isConfigName(name) && looksLikeArchive(buffered)))
            return readArchive(buffered, usedNames);

        List<Entry> entries = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        String text = new String(readBounded(buffered, MAX_ENTRY_BYTES), StandardCharsets.UTF_8);
        String label = name.isEmpty() ? "" : stripExtension(baseName(name));
        if (parses(text))
            entries.add(new Entry(uniqueName(label, usedNames), text));
        else
            skipped.add(name.isEmpty() ? label : name);
        return new Result(entries, skipped);
    }

    private static Result readArchive(InputStream in, Set<String> used) throws IOException {
        List<Entry> entries = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry;
        int scanned = 0;
        int taken = 0;
        int budget = MAX_TOTAL_BYTES;
        while ((entry = zip.getNextEntry()) != null) {
            if (++scanned > MAX_SCANNED_ENTRIES || taken >= MAX_ENTRIES || budget <= 0)
                break;

            // Only the base name is ever used, so a traversing path in a
            // hostile archive cannot escape anywhere: nothing is written out.
            String file = baseName(entry.getName());
            if (entry.isDirectory() || !isConfigName(file))
                continue;

            byte[] bytes = readBounded(zip, Math.min(MAX_ENTRY_BYTES, budget));
            budget -= bytes.length;
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (!parses(text)) {
                skipped.add(file);
                continue;
            }
            taken++;
            entries.add(new Entry(uniqueName(stripExtension(file), used), text));
        }
        return new Result(entries, skipped);
    }

    /**
     * Two servers can share a file name across an archive's folders. Keeping
     * both matters more than the tidier name, so later collisions get a
     * numeric suffix instead of overwriting the first.
     */
    private static String uniqueName(String base, Set<String> used) {
        // An empty label means the picker gave no usable name; the profile
        // store substitutes a default, so there is nothing to reserve here.
        if (base.isEmpty())
            return base;
        if (used.add(base))
            return base;
        for (int i = 2; ; i++) {
            String candidate = base + " (" + i + ")";
            if (used.add(candidate))
                return candidate;
        }
    }

    private static boolean parses(String text) {
        try {
            WgConfigParser.INSTANCE.parse(text);
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    private static byte[] readBounded(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while (out.size() <= limit && (read = in.read(buffer)) != -1)
            out.write(buffer, 0, read);
        byte[] bytes = out.toByteArray();
        // Truncated content simply fails to parse and is reported as skipped.
        return bytes.length > limit ? java.util.Arrays.copyOf(bytes, limit) : bytes;
    }

    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    private static boolean looksLikeArchive(BufferedInputStream in) throws IOException {
        in.mark(ZIP_MAGIC.length);
        byte[] head = new byte[ZIP_MAGIC.length];
        int read = 0;
        while (read < head.length) {
            int n = in.read(head, read, head.length - read);
            if (n < 0)
                break;
            read += n;
        }
        in.reset();
        return read == head.length && java.util.Arrays.equals(head, ZIP_MAGIC);
    }

    static boolean isArchiveName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    static boolean isConfigName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".conf");
    }

    static String baseName(String path) {
        if (path == null)
            return "";
        String name = path.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    static String stripExtension(String name) {
        if (name == null)
            return "";
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }
}
