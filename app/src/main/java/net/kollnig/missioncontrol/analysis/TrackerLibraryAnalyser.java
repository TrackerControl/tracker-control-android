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

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.TrackerLibrary;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import eu.faircode.netguard.Rule;

public class TrackerLibraryAnalyser {
    /**
     * Number of concurrent DEX files to process. Limited to control memory usage.
     * Each DEX file can use significant heap space when loaded.
     */
    private static final int MAX_CONCURRENT_DEX = 2;

    private final Context mContext;
    private AnalysisProgressCallback mProgressCallback;

    // Pre-built trie for fast signature matching (lazy initialized)
    private SignatureTrie mSignatureTrie;

    public TrackerLibraryAnalyser(Context mContext) {
        this.mContext = mContext;
    }

    /**
     * Sets the progress callback to receive progress updates during analysis.
     *
     * @param callback The callback to receive progress updates
     */
    public void setProgressCallback(AnalysisProgressCallback callback) {
        this.mProgressCallback = callback;
    }

    /**
     * Builds and caches the signature trie from resources.
     * Thread-safe lazy initialization.
     */
    private synchronized SignatureTrie getSignatureTrie() {
        if (mSignatureTrie == null) {
            String[] signatures = mContext.getResources().getStringArray(R.array.trackers);
            String[] names = mContext.getResources().getStringArray(R.array.tname);
            String[] webs = mContext.getResources().getStringArray(R.array.tweb);

            mSignatureTrie = new SignatureTrie();
            for (int i = 0; i < signatures.length; i++) {
                mSignatureTrie.insert(signatures[i], names[i], webs[i], i);
            }
        }
        return mSignatureTrie;
    }

    /**
     * Processes a single DEX file and returns found trackers.
     *
     * @param apkPath  Path to the APK
     * @param dexEntry The DEX entry name within the APK
     * @param trie     Pre-built signature trie
     * @return Set of found trackers in this DEX
     */
    private Set<TrackerLibrary> processSingleDex(String apkPath, String dexEntry, SignatureTrie trie)
            throws IOException {
        Set<TrackerLibrary> trackers = new HashSet<>();

        DexFile dx = DexFileFactory.loadDexEntry(new File(apkPath), dexEntry, true, Opcodes.getDefault())
                .getDexFile();

        for (ClassDef classDef : dx.getClasses()) {
            String className = classDef.getType();
            className = className.replace('/', '.');
            className = className.substring(1, className.length() - 1);

            // Skip short class names or those without packages
            if (className.length() <= 8 || !className.contains(".")) {
                continue;
            }

            // O(k) trie lookup instead of O(n*k) linear scan
            SignatureTrie.TrackerInfo match = trie.findMatch(className);
            if (match != null) {
                trackers.add(new TrackerLibrary(match.name, match.web, match.id, match.signature));
            }
        }

        return trackers;
    }

    /**
     * Does the tracker library analysis with parallel DEX processing.
     * Uses bounded concurrency to limit memory usage while improving speed.
     * <p>
     * Matches class names of the app to be analysed against the Exodus tracker
     * database using a Trie for O(k) lookups.
     *
     * @param apk      Path to apk to analyse
     * @param callback Optional progress callback to report progress
     * @return Found trackers
     * @throws IOException      I/O errors
     * @throws RuntimeException Non I/O errors
     */
    @NonNull
    private Set<TrackerLibrary> findTrackers(String apk, AnalysisProgressCallback callback)
            throws IOException, InterruptedException {
        // Build trie once, reuse for all lookups
        SignatureTrie trie = getSignatureTrie();

        // Collect DEX file entries
        List<String> dexEntries = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".dex")) {
                    dexEntries.add(entry.getName());
                }
            }
        }

        int totalDex = dexEntries.size();

        // For single DEX, skip thread pool overhead
        if (totalDex == 1) {
            if (callback != null) {
                callback.onProgress(1, 1);
            }
            return processSingleDex(apk, dexEntries.get(0), trie);
        }

        // Parallel processing with bounded concurrency
        Set<TrackerLibrary> trackers = ConcurrentHashMap.newKeySet();
        AtomicInteger processedCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_DEX);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (String dexEntry : dexEntries) {
                futures.add(executor.submit(() -> {
                    try {
                        Set<TrackerLibrary> dexTrackers = processSingleDex(apk, dexEntry, trie);
                        trackers.addAll(dexTrackers);

                        // Report progress
                        int processed = processedCount.incrementAndGet();
                        if (callback != null) {
                            callback.onProgress(processed, totalDex);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to process " + dexEntry, e);
                    }
                }));
            }

            // Wait for all DEX files to be processed
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    throw new IOException("DEX processing failed", e);
                }
            }
        } finally {
            executor.shutdownNow();
        }

        return trackers;
    }

    /**
     * Performs tracker library analysis on an app (no caching).
     * Scans both base APK and split APKs (for App Bundles).
     * This method should be called through TrackerAnalysisManager for proper
     * caching and thread safety.
     *
     * @param packageName The package name of the app to analyse
     * @return A string with the analysis results
     * @throws AnalysisException In case something goes wrong
     */
    public String analyseApp(String packageName) throws AnalysisException {
        try {
            PackageInfo pkg = mContext.getPackageManager().getPackageInfo(packageName, 0);

            // Collect all APK paths (base + splits)
            List<String> apkPaths = new ArrayList<>();
            apkPaths.add(pkg.applicationInfo.publicSourceDir);

            // Add split APKs if present (App Bundles)
            String[] splits = pkg.applicationInfo.splitSourceDirs;
            if (splits != null) {
                for (String split : splits) {
                    apkPaths.add(split);
                }
            }

            Set<TrackerLibrary> trackers = new HashSet<>();
            for (String apk : apkPaths) {
                trackers.addAll(findTrackers(apk, mProgressCallback));
            }

            final List<TrackerLibrary> sortedTrackers = new ArrayList<>(trackers);
            Collections.sort(sortedTrackers);

            if (!sortedTrackers.isEmpty()) {
                return "\n• " + TextUtils.join("\n• ", sortedTrackers);
            } else {
                return mContext.getString(R.string.none);
            }

        } catch (OutOfMemoryError e) {
            throw new AnalysisException(mContext.getString(R.string.tracking_detection_failed_ram));
        } catch (IOException | PackageManager.NameNotFoundException | InterruptedException e) {
            throw new AnalysisException(mContext.getString(R.string.tracking_detection_failed));
        } catch (Throwable e) {
            if (Rule.isSystem(packageName, mContext))
                throw new AnalysisException(mContext.getString(R.string.tracking_detection_failed));
            else
                throw new AnalysisException(mContext.getString(R.string.tracking_detection_failed_report));
        }
    }
}
