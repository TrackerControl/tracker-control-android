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

import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import eu.faircode.netguard.Rule;
import lanchon.multidexlib2.BasicDexFileNamer;
import lanchon.multidexlib2.DuplicateEntryNameException;
import lanchon.multidexlib2.DuplicateTypeException;
import lanchon.multidexlib2.EmptyMultiDexContainerException;
import lanchon.multidexlib2.MultiDexDetectedException;
import lanchon.multidexlib2.MultiDexIO;

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
     * Does the tracker library analysis.
     * <p>
     * Matches class names of the app to be analysed against the Exodus tracker database, which
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
    private static Set<TrackerLibrary> findTrackers(Context c, String apk, AnalysisProgressCallback callback) throws IOException, RuntimeException {
        DexFile dx = MultiDexIO.readDexFile(true, new File(apk), new BasicDexFileNamer(), null, null);

        String[] Sign = c.getResources().getStringArray(R.array.trackers);
        String[] Names = c.getResources().getStringArray(R.array.tname);
        String[] Web = c.getResources().getStringArray(R.array.tweb);
        Set<TrackerLibrary> trackers = new HashSet<>();

        // Get the classes as a collection to determine total count
        Collection<? extends ClassDef> classes = dx.getClasses();
        int totalClasses = classes.size();
        int processedClasses = 0;
        int lastReportedPercent = -1;

        for (ClassDef classDef : classes) {
            processedClasses++;
            
            // Report progress (limit updates to avoid UI flooding)
            if (callback != null) {
                int currentPercent = (processedClasses * 100) / totalClasses;
                if (currentPercent != lastReportedPercent) {
                    lastReportedPercent = currentPercent;
                    callback.onProgress(processedClasses, totalClasses);
                }
            }
            
            String className = classDef.getType();
            className = className.replace('/', '.');
            className = className.substring(1, className.length() - 1);

            if (className.length() > 8) {
                if (className.contains(".")) {
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
        }

        return trackers;
    }

    /**
     * Performs tracker library analysis on an app (no caching).
     * This method should be called through TrackerAnalysisManager for proper caching and thread safety.
     *
     * @param packageName The package name of the app to analyse
     * @return A string with the analysis results
     * @throws AnalysisException In case something goes wrong
     */
    public String analyseApp(String packageName) throws AnalysisException {
        try {
            PackageInfo pkg = mContext.getPackageManager().getPackageInfo(packageName, 0);
            String apk = pkg.applicationInfo.publicSourceDir;
            
            Set<TrackerLibrary> trackers = findTrackers(mContext, apk, mProgressCallback);
            
            final List<TrackerLibrary> sortedTrackers = new ArrayList<>(trackers);
            Collections.sort(sortedTrackers);
            
            if (sortedTrackers.size() > 0) {
                return "\n• " + TextUtils.join("\n• ", sortedTrackers);
            } else {
                return mContext.getString(R.string.none);
            }
            
        } catch (Throwable e) {
            if (e instanceof EmptyMultiDexContainerException
                    || e instanceof MultiDexDetectedException
                    || e instanceof DuplicateTypeException
                    || e instanceof DuplicateEntryNameException
                    || e instanceof PackageManager.NameNotFoundException
                    || Rule.isSystem(packageName, mContext))
                throw new AnalysisException(mContext.getString(R.string.tracking_detection_failed));
            else if (e instanceof OutOfMemoryError)
                throw new AnalysisException(mContext.getString(R.string.tracking_detection_failed_ram));
            else
                throw new AnalysisException(mContext.getString(R.string.tracking_detection_failed_report));
        }
    }
    
    /**
     * @deprecated Use TrackerAnalysisManager.analyse() instead for proper caching and thread safety
     */
    @Deprecated
    public String analyse(String packageName) throws AnalysisException {
        return analyseApp(packageName);
    }
}
