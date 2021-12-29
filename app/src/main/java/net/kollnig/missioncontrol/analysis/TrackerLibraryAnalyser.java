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
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.StaticTracker;

import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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
    private static final int EXODUS_DATABASE_VERSION = 423; // eof422, see https://bitbucket.org/oF2pks/fdroid-classyshark3xodus/commits/

    public TrackerLibraryAnalyser(Context mContext) {
        this.mContext = mContext;

        int current = getPrefs().getInt("version", Integer.MIN_VALUE);
        if (current < EXODUS_DATABASE_VERSION)
            getPrefs().edit().clear().putInt("version", EXODUS_DATABASE_VERSION).apply();
    }

    public String analyse(String mAppId) throws AnalysisException {
        String trackerString;

        try {
            Set<StaticTracker> trackers;
            PackageInfo pkg = mContext.getPackageManager().getPackageInfo(mAppId, 0);

            // Try to load cached result
            SharedPreferences prefs = getPrefs();
            int analysedCode = prefs.getInt("versioncode_" + mAppId, Integer.MIN_VALUE);

            if (pkg.versionCode > analysedCode) {
                String apk = pkg.applicationInfo.publicSourceDir;
                trackers = findTrackers(mContext, apk);

                final List<StaticTracker> sortedTrackers = new ArrayList<>(trackers);
                Collections.sort(sortedTrackers);

                if (sortedTrackers.size() > 0)
                    trackerString = "\n• " + TextUtils.join("\n• ", sortedTrackers);
                else
                    trackerString = mContext.getString(R.string.none);

                // Cache results
                prefs.edit()
                        .putInt("versioncode_" + mAppId, pkg.versionCode)
                        .putString("trackers_" + mAppId, trackerString)
                        .apply();
            } else
                trackerString = prefs.getString("trackers_" + mAppId, null);

        } catch (Throwable e) {
            if (e instanceof EmptyMultiDexContainerException
                    || e instanceof MultiDexDetectedException
                    || e instanceof DuplicateTypeException
                    || e instanceof DuplicateEntryNameException
                    || e instanceof PackageManager.NameNotFoundException)
                throw new AnalysisException(mContext.getString(R.string.tracking_detection_failed));
            else if (e instanceof OutOfMemoryError)
                throw new AnalysisException(mContext.getString(R.string.tracking_detection_failed_ram));
            else
                throw new AnalysisException(mContext.getString(R.string.tracking_detection_failed_report));
        }

        return trackerString;
    }

    private SharedPreferences getPrefs() {
        return mContext.getSharedPreferences("library_analysis", Context.MODE_PRIVATE);
    }

    @NonNull
    private static Set<StaticTracker> findTrackers(Context c, String apk) throws IOException, RuntimeException {
        DexFile dx = MultiDexIO.readDexFile(true, new File(apk), new BasicDexFileNamer(), null, null);

        String[] Sign = c.getResources().getStringArray(R.array.trackers);
        String[] Names = c.getResources().getStringArray(R.array.tname);
        String[] Web = c.getResources().getStringArray(R.array.tweb);
        Set<StaticTracker> trackers = new HashSet<>();

        for (ClassDef classDef: dx.getClasses()) {
            String className = classDef.getType();
            className = className.replace('/', '.');
            className = className.substring( 1, className.length() - 1 );

            if (className.length() > 8) {
                if (className.contains(".")){
                    for (int Signz = 0; Signz < Sign.length; Signz++) {
                        if (className.contains(Sign[Signz])) {
                            trackers.add(new StaticTracker(Names[Signz], Web[Signz], Signz, Sign[Signz]));
                            break;
                        }
                    }
                }
            }
        }

        return trackers;
    }
}
