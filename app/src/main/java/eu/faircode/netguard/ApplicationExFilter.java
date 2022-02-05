/*
 * This file is from NetGuard.
 *
 * NetGuard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NetGuard is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2015–2020 by Marcel Bokhorst (M66B), Konrad
 * Kollnig (University of Oxford)
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
        String stackTrace = crashReportData.getString(ReportField.STACK_TRACE);
        if (stackTrace == null)
            return true;

        return !stackTrace.contains("Context.startForegroundService() did not then call Service.startForeground()");
    }
}
