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
 * along with TrackerControl.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 Konrad Kollnig, University of Oxford
 */

package eu.faircode.netguard;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import org.acra.ReportField;
import org.acra.config.CoreConfiguration;
import org.acra.config.ReportingAdministrator;
import org.acra.data.CrashReportData;

@AutoService(ReportingAdministrator.class)
public class ApplicationExFilter implements ReportingAdministrator {
    @Override
    public boolean shouldSendReport(@NonNull Context context, @NonNull CoreConfiguration config, @NonNull CrashReportData crashReportData) {
        return !crashReportData.getString(ReportField.STACK_TRACE).contains("Context.startForegroundService() did not then call Service.startForeground()");
    }
}
