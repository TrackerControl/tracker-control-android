package net.kollnig.missioncontrol.analysis;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class TrackerAnalysisWorker extends Worker {
    public static final String KEY_PACKAGE_NAME = "package_name";
    public static final String KEY_RESULT = "result";
    public static final String KEY_ERROR = "error";

    public TrackerAnalysisWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String packageName = getInputData().getString(KEY_PACKAGE_NAME);
        if (packageName == null) {
            return Result.failure();
        }

        try {
            // We use the Manager's method to ensure all listeners are notified
            // and internal state is updated, ensuring the UI stays in sync.
            final int[] lastProgress = { -1 };

            String result = TrackerAnalysisManager.getInstance(getApplicationContext())
                    .runAnalysisFromWorker(packageName, (current, total) -> {
                        // Update WorkManager progress
                        int percent = (int) ((current / (float) total) * 100);
                        if (percent != lastProgress[0]) {
                            lastProgress[0] = percent;
                            setProgressAsync(new Data.Builder()
                                    .putInt("progress", percent)
                                    .build());
                        }
                    });

            return Result.success(new Data.Builder()
                    .putString(KEY_RESULT, result)
                    .build());
        } catch (Exception e) {
            return Result.failure(new Data.Builder()
                    .putString(KEY_ERROR, e.getMessage())
                    .build());
        }
    }
}
