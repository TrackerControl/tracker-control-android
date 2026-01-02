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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import eu.faircode.netguard.Rule;

public class TrackerLibraryAnalyser {
    private final Context mContext;
    private AnalysisProgressCallback mProgressCallback;

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
     * Does the tracker library analysis, processing DEX files one at a time to
     * reduce memory usage.
     * <p>
     * Matches class names of the app to be analysed against the Exodus tracker
     * database, which
     * contains information on known class of tracker libraries.
     *
     * @param c        Context
     * @param apk      Path to apk to analyse
     * @param callback Optional progress callback to report progress
     * @return Found trackers
     * @throws IOException      I/O errors
     * @throws RuntimeException Non I/O errors
     */
    @NonNull
    private static Set<TrackerLibrary> findTrackers(Context c, String apk, AnalysisProgressCallback callback)
            throws IOException, RuntimeException {
        String[] Sign = c.getResources().getStringArray(R.array.trackers);
        String[] Names = c.getResources().getStringArray(R.array.tname);
        String[] Web = c.getResources().getStringArray(R.array.tweb);
        Set<TrackerLibrary> trackers = new HashSet<>();

        // Count DEX files for progress
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
        int processedDex = 0;

        // Process each DEX file individually to reduce memory usage
        for (String dexEntry : dexEntries) {
            processedDex++;

            // Report progress per DEX file
            if (callback != null) {
                callback.onProgress(processedDex, totalDex);
            }

            // Load and process one DEX at a time
            DexFile dx = DexFileFactory.loadDexEntry(new File(apk), dexEntry, true, Opcodes.getDefault()).getDexFile();
            for (ClassDef classDef : dx.getClasses()) {
                String className = classDef.getType();
                className = className.replace('/', '.');
                className = className.substring(1, className.length() - 1);

                if (className.length() > 8 && className.contains(".")) {
                    for (int Signz = 0; Signz < Sign.length; Signz++) {
                        if (className.contains(Sign[Signz])) {
                            if (Names[Signz].startsWith("µ?")) // exclude "good" trackers
                                continue;

                            trackers.add(new TrackerLibrary(Names[Signz], Web[Signz], Signz, Sign[Signz]));
                            break;
                        }
                    }
                }
            }
            // DEX is eligible for GC here before loading next one
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
                trackers.addAll(findTrackers(mContext, apk, mProgressCallback));
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
        } catch (IOException | PackageManager.NameNotFoundException e) {
            throw new AnalysisException(mContext.getString(R.string.tracking_detection_failed));
        } catch (Throwable e) {
            if (Rule.isSystem(packageName, mContext))
                throw new AnalysisException(mContext.getString(R.string.tracking_detection_failed));
            else
                throw new AnalysisException(mContext.getString(R.string.tracking_detection_failed_report));
        }
    }
}
