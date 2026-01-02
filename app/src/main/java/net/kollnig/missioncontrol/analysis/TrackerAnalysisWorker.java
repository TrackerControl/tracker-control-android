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

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.atomic.AtomicInteger;

public class TrackerAnalysisWorker extends Worker {
    public static final String KEY_PACKAGE_NAME = "package_name";
    public static final String KEY_RESULT = "result";
    public static final String KEY_ERROR = "error";
    public static final String KEY_PROGRESS = "progress";

    public TrackerAnalysisWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String packageName = getInputData().getString(KEY_PACKAGE_NAME);
        if (packageName == null) {
            return Result.failure(new Data.Builder()
                    .putString(KEY_ERROR, "No package name provided")
                    .build());
        }

        try {
            Context context = getApplicationContext();
            PackageInfo pkg = context.getPackageManager().getPackageInfo(packageName, 0);

            // Perform analysis with progress reporting
            TrackerLibraryAnalyser analyser = new TrackerLibraryAnalyser(context);
            AtomicInteger lastProgress = new AtomicInteger(-1);
            analyser.setProgressCallback((current, total) -> {
                int percent = (int) ((current / (float) total) * 100);
                if (lastProgress.getAndSet(percent) != percent) {
                    setProgressAsync(new Data.Builder()
                            .putInt(KEY_PROGRESS, percent)
                            .build());
                }
            });

            String result = analyser.analyseApp(packageName);

            // Cache the result
            TrackerAnalysisManager.getInstance(context)
                    .cacheResult(packageName, result, pkg.versionCode);

            return Result.success(new Data.Builder()
                    .putString(KEY_RESULT, result)
                    .build());

        } catch (PackageManager.NameNotFoundException e) {
            return Result.failure(new Data.Builder()
                    .putString(KEY_ERROR, "Package not found: " + packageName)
                    .build());
        } catch (AnalysisException e) {
            return Result.failure(new Data.Builder()
                    .putString(KEY_ERROR, e.getMessage())
                    .build());
        } catch (Exception e) {
            return Result.failure(new Data.Builder()
                    .putString(KEY_ERROR, e.getMessage() != null ? e.getMessage() : "Unknown error")
                    .build());
        }
    }
}
