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
 * Copyright © 2019–2026 Konrad Kollnig
 */
package net.kollnig.missioncontrol.data;

import android.content.Context;

import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared machinery for the two per-app stores that are keyed by Android UID:
 * {@link TrackerBlocklist} and {@link InternetBlocklist}.
 * <p>
 * Both keep the same two tiers. {@link #blockmap} is canonical and UID-keyed.
 * {@link #rawBlockmap} holds entries whose key could not be turned into a UID —
 * a package name from an import whose app is not installed yet, or a numeric
 * string too large to be a UID. Those are written back under their original key
 * so a later install can still claim them, rather than being dropped.
 *
 * @param <V> Per-UID payload; {@link Boolean} where membership is the whole
 *            state, a set of tracker keys where it is not
 */
public abstract class UidKeyedStore<V> {
    /**
     * Canonical, UID-keyed state.
     * <p>
     * Concurrent because it is read from native packet threads while the UI
     * thread may be writing.
     */
    protected final Map<Integer, V> blockmap = new ConcurrentHashMap<>();
    /**
     * Entries retained under a non-UID key because none could be resolved.
     * <p>
     * A plain map: it is only touched under the instance monitor, and unlike
     * {@link #blockmap} it must be able to hold a {@code null} payload.
     */
    protected final Map<String, V> rawBlockmap = new HashMap<>();
    /** Raw keys retained because a canonical numeric UID won a collision. */
    protected final Map<String, Integer> retainedRawUids = new HashMap<>();

    /** What {@link #absorb} did with one newly-resolved raw entry. */
    protected enum Resolution {
        /** Folded into {@link #blockmap}; the raw entry can be dropped. */
        ABSORBED,
        /** {@link #blockmap} already owns the UID; the raw entry stays, and this is news. */
        RETAINED,
        /** Already retained on an earlier pass; nothing changed. */
        UNCHANGED
    }

    /**
     * Turn a stored key into a UID.
     * <p>
     * A numeric key is canonical and never costs a {@link PackageUids} call.
     * Numeric but unparseable means the key is not a UID and never will be, so
     * it is not offered to the resolver either — only a genuine package name is.
     *
     * @param storedUid Key as written in shared preferences
     * @param resolver  Resolver for package-name keys; may be {@code null} to
     *                  resolve numeric keys only
     * @return The UID, or {@code null} when the key cannot be resolved
     */
    static Integer resolveStoredUid(String storedUid, PackageUids.Resolver resolver) {
        if (storedUid == null || storedUid.length() == 0)
            return null;

        if (StringUtils.isNumeric(storedUid)) {
            try {
                return Integer.parseInt(storedUid);
            } catch (NumberFormatException ignored) {
                // Numeric, but too large to be a UID. Keep the entry as written
                // rather than dropping settings we cannot parse.
                return null;
            }
        }

        return resolver == null ? null : resolver.resolve(storedUid);
    }

    /**
     * Fold one resolved raw entry into {@link #blockmap}.
     *
     * @param uid    The UID the raw key resolved to
     * @param rawKey The raw key, still present in {@link #rawBlockmap}
     * @param raw    Its payload
     * @return What was done with it
     */
    protected abstract Resolution absorb(int uid, String rawKey, V raw);

    /**
     * Resolve every pending entry.
     * <p>
     * This costs one {@link PackageUids} call per pending entry, so it belongs
     * on rare paths — process start, a settings import — and not on a broadcast
     * that arrives once per app install <em>and</em> once per app update. Use
     * {@link #resolvePendingPackage(Context, String)} there instead.
     *
     * @param c Context
     * @return Whether anything changed, i.e. whether a save is warranted
     */
    public synchronized boolean resolvePendingPackages(Context c) {
        return resolvePendingPackages(PackageUids.resolver(c));
    }

    synchronized boolean resolvePendingPackages(PackageUids.Resolver resolver) {
        boolean changed = false;
        Iterator<Map.Entry<String, V>> pending = rawBlockmap.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<String, V> entry = pending.next();
            Integer uid = resolveStoredUid(entry.getKey(), resolver);
            if (uid == null)
                continue;

            Resolution resolution = absorb(uid, entry.getKey(), entry.getValue());
            if (resolution == Resolution.ABSORBED)
                pending.remove();
            changed |= resolution != Resolution.UNCHANGED;
        }
        return changed;
    }

    /**
     * Resolve just the entry a package-added broadcast is about.
     * <p>
     * The common case is a hash lookup that misses, so no {@link PackageUids}
     * call is made at all; at most one is made when the name is actually
     * pending.
     *
     * @param c           Context
     * @param packageName Package that was just installed; {@code null} is a no-op
     * @return Whether anything changed, i.e. whether a save is warranted
     */
    public synchronized boolean resolvePendingPackage(Context c, String packageName) {
        return resolvePendingPackage(PackageUids.resolver(c), packageName);
    }

    synchronized boolean resolvePendingPackage(PackageUids.Resolver resolver, String packageName) {
        if (packageName == null || !rawBlockmap.containsKey(packageName))
            return false;

        Integer uid = resolveStoredUid(packageName, resolver);
        if (uid == null)
            return false;

        Resolution resolution = absorb(uid, packageName, rawBlockmap.get(packageName));
        if (resolution == Resolution.ABSORBED)
            rawBlockmap.remove(packageName);
        return resolution != Resolution.UNCHANGED;
    }

    /**
     * Completely clear the store.
     */
    public synchronized void clear() {
        blockmap.clear();
        rawBlockmap.clear();
        retainedRawUids.clear();
    }
}
