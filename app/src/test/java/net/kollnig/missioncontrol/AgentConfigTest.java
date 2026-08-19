/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Copyright © 2026
 */

package net.kollnig.missioncontrol;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Guards the topology of the agent configuration described in agents/README.md.
 * <p>
 * AGENTS.md is loaded into every agent session; agents/docs/ is loaded by nothing and is
 * only ever read because an always-loaded file points at it. That makes three failures
 * silent rather than loud: a doc nothing points at (never read), a pointer that no longer
 * resolves (guidance quietly removed), and a fact restated in AGENTS.md that has since
 * drifted from its source of truth. Each is cheap to catch mechanically and expensive to
 * notice by hand.
 */
public class AgentConfigTest {

    /**
     * AGENTS.md is paid for in every session by every tool, so it carries only what decides
     * most work. Situational material belongs in agents/docs/ behind the "read these when"
     * table. The exact number is arbitrary; the ratchet is not.
     */
    private static final int AGENTS_MD_LINE_BUDGET = 150;

    /** Extensions we are willing to assert about when a reference names a file. */
    private static final Pattern FILE_REFERENCE = Pattern.compile(
            ".*\\.(md|sh|json|java|kt|c|h|gradle|toml|rs|py|yml|yaml)$");

    /**
     * A tool name immediately followed by a version. Versions live in the build files and are
     * pointed at, not copied — a restated one goes stale without anything failing.
     */
    private static final Pattern VOLATILE_VERSION = Pattern.compile(
            "(?i)\\b(jdk|java|ndk|sdk|cmake|gradle|agp|kotlin|rust|cargo|robolectric|compileSdk|targetSdk|minSdk)"
                    + "\\b[^.\\n]{0,20}?\\b\\d+(\\.\\d+)*\\b");

    @Test
    public void everyAgentDocIsReachableFromAnAlwaysLoadedFile() throws IOException {
        String agentsMd = read(repoRoot().resolve("AGENTS.md"));

        List<String> unreachable = new ArrayList<>();
        for (Path doc : agentDocs()) {
            String reference = "agents/docs/" + doc.getFileName();
            if (!agentsMd.contains(reference))
                unreachable.add(reference);
        }

        assertTrue("Nothing loads agents/docs/, so a doc AGENTS.md does not point at will never be"
                + " read. Add a row to the \"read these when the situation applies\" table for: "
                + unreachable, unreachable.isEmpty());
    }

    @Test
    public void everyPathReferenceResolves() throws IOException {
        List<String> dangling = new ArrayList<>();

        for (Path file : configFiles()) {
            int lineNumber = 0;
            boolean inCodeBlock = false;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.trim().startsWith("```")) {
                    inCodeBlock = !inCodeBlock;
                    continue;
                }
                if (inCodeBlock)
                    continue;

                for (String reference : referencesIn(line)) {
                    if (!resolves(file, reference))
                        dangling.add(relative(file) + ":" + lineNumber + " -> " + reference);
                }
            }
        }

        if (!dangling.isEmpty())
            fail("A mistyped pointer silently removes guidance instead of failing loudly. Dangling"
                    + " references:\n  " + String.join("\n  ", dangling));
    }

    @Test
    public void agentsMdDoesNotRestateVolatileVersions() throws IOException {
        List<String> restated = new ArrayList<>();

        int lineNumber = 0;
        boolean inCodeBlock = false;
        for (String line : Files.readAllLines(repoRoot().resolve("AGENTS.md"), StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.trim().startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }
            if (inCodeBlock)
                continue;

            Matcher matcher = VOLATILE_VERSION.matcher(line);
            while (matcher.find())
                restated.add("AGENTS.md:" + lineNumber + " -> " + matcher.group().trim());
        }

        if (!restated.isEmpty())
            fail("Versions belong in the build files and in agents/docs/build-and-test.md, pointed"
                    + " at rather than copied into the always-loaded file:\n  "
                    + String.join("\n  ", restated));
    }

    @Test
    public void agentsMdStaysShortEnoughToLoadEverySession() throws IOException {
        List<String> lines = Files.readAllLines(repoRoot().resolve("AGENTS.md"), StandardCharsets.UTF_8);

        assertTrue("AGENTS.md is " + lines.size() + " lines, over the " + AGENTS_MD_LINE_BUDGET
                + "-line budget. Situational guidance belongs in agents/docs/ behind a row in the"
                + " \"read these when the situation applies\" table.", lines.size() <= AGENTS_MD_LINE_BUDGET);
    }

    @Test
    public void vendorWiringPointsAtSharedScripts() throws IOException {
        Path claudeMd = repoRoot().resolve("CLAUDE.md");
        assertTrue("CLAUDE.md must hand Claude Code the shared entry point, not carry its own copy"
                + " of the guidance", read(claudeMd).contains("AGENTS.md"));

        Path settings = repoRoot().resolve(".claude/settings.json");
        if (!Files.exists(settings))
            return;

        Matcher matcher = Pattern.compile("agents/hooks/[A-Za-z0-9_.-]+\\.sh").matcher(read(settings));
        while (matcher.find()) {
            Path script = repoRoot().resolve(matcher.group());
            assertTrue(".claude/settings.json points at " + matcher.group() + ", which does not exist."
                    + " Vendor directories hold wiring; the scripts live in agents/.", Files.exists(script));
        }
    }

    /** Backticked or markdown-linked paths that name something inside this repository. */
    private static List<String> referencesIn(String line) throws IOException {
        List<String> references = new ArrayList<>();
        Matcher matcher = Pattern.compile("`([^`]+)`|\\]\\(([^)]+)\\)").matcher(line);

        while (matcher.find()) {
            String candidate = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            candidate = candidate.trim();

            int anchor = candidate.indexOf('#');
            if (anchor >= 0)
                candidate = candidate.substring(0, anchor);

            if (candidate.isEmpty() || !candidate.contains("/"))
                continue;
            // Globs, placeholders, shell fragments and absolute paths are not repo references.
            if (candidate.matches(".*[*{}<>\\[\\]|$ ].*") || candidate.startsWith("/")
                    || candidate.startsWith("~") || candidate.contains("://"))
                continue;
            if (!FILE_REFERENCE.matcher(candidate).matches() && !candidate.endsWith("/"))
                continue;

            String first = candidate.startsWith("..") ? ".." : candidate.split("/")[0];
            if (!"..".equals(first) && !Files.exists(repoRoot().resolve(first)))
                continue; // e.g. a package path or a build-output path, not a repo reference

            references.add(candidate);
        }
        return references;
    }

    private static boolean resolves(Path referencedFrom, String reference) throws IOException {
        if (reference.startsWith(".."))
            return Files.exists(referencedFrom.getParent().resolve(reference).normalize());

        return Files.exists(repoRoot().resolve(reference));
    }

    private static List<Path> configFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        files.add(repoRoot().resolve("AGENTS.md"));
        files.add(repoRoot().resolve("agents/README.md"));
        files.addAll(agentDocs());
        return files;
    }

    private static List<Path> agentDocs() throws IOException {
        try (Stream<Path> docs = Files.list(repoRoot().resolve("agents/docs"))) {
            return docs.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String relative(Path path) {
        return repoRoot().relativize(path).toString();
    }

    /** Unit tests run from the module directory locally and from the repo root in some setups. */
    private static Path repoRoot() {
        Path moduleRelative = Path.of("..", "AGENTS.md");
        if (Files.exists(moduleRelative))
            return Path.of("..").toAbsolutePath().normalize();

        return Path.of(".").toAbsolutePath().normalize();
    }
}
