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
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2019–2021 Konrad Kollnig (University of Oxford)
 */

package net.kollnig.missioncontrol.analysis;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * A Trie (prefix tree) optimized for matching tracker signatures against class
 * names.
 * Provides O(k) lookup time where k is the length of the class name being
 * searched,
 * compared to O(n*k) for linear search through n signatures.
 */
class SignatureTrie {
    private final TrieNode root = new TrieNode();

    /**
     * Inserts a tracker signature into the trie.
     *
     * @param signature The class signature pattern to match (e.g.,
     *                  "com.google.ads")
     * @param name      Tracker name
     * @param web       Tracker website
     * @param id        Tracker ID in database
     */
    void insert(String signature, String name, String web, int id) {
        TrieNode current = root;
        for (char c : signature.toCharArray()) {
            current = current.children.computeIfAbsent(c, k -> new TrieNode());
        }
        current.trackerInfo = new TrackerInfo(name, web, id, signature);
    }

    /**
     * Searches for any tracker signature that is a substring of the given class
     * name.
     * Returns the first match found (checking all possible starting positions).
     *
     * @param className The fully qualified class name to check
     * @return TrackerInfo if a match is found, null otherwise
     */
    @Nullable
    TrackerInfo findMatch(String className) {
        // Try starting the match from each position in the class name
        for (int startPos = 0; startPos < className.length(); startPos++) {
            TrackerInfo result = searchFromPosition(className, startPos);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * Tries to match a signature starting from the given position.
     */
    @Nullable
    private TrackerInfo searchFromPosition(String className, int startPos) {
        TrieNode current = root;
        TrackerInfo lastMatch = null;

        for (int i = startPos; i < className.length(); i++) {
            char c = className.charAt(i);
            TrieNode next = current.children.get(c);

            if (next == null) {
                break; // No more matches possible from this starting position
            }

            current = next;
            if (current.trackerInfo != null) {
                lastMatch = current.trackerInfo; // Found a complete signature match
            }
        }

        return lastMatch;
    }

    /**
     * Data class to hold tracker information associated with a signature.
     */
    static class TrackerInfo {
        final String name;
        final String web;
        final int id;
        final String signature;

        TrackerInfo(String name, String web, int id, String signature) {
            this.name = name;
            this.web = web;
            this.id = id;
            this.signature = signature;
        }
    }

    private static class TrieNode {
        final Map<Character, TrieNode> children = new HashMap<>();
        TrackerInfo trackerInfo = null; // Non-null if this node marks end of a signature
    }
}
