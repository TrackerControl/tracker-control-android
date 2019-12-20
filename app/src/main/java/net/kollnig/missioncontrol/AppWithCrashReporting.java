/*
 * Copyright (C) 2019 Konrad Kollnig, University of Oxford
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 */

package net.kollnig.missioncontrol;

import android.app.Application;
import android.content.Context;

import org.acra.ACRA;
import org.acra.ReportField;
import org.acra.annotation.AcraCore;
import org.acra.annotation.AcraDialog;
import org.acra.annotation.AcraMailSender;

@AcraCore(buildConfigClass = BuildConfig.class,
		reportContent = { // limit collected data
				ReportField.REPORT_ID,
				ReportField.ANDROID_VERSION,
				ReportField.BUILD_CONFIG,
				ReportField.STACK_TRACE,
				ReportField.USER_COMMENT,
				ReportField.USER_EMAIL,
				ReportField.USER_APP_START_DATE,
				ReportField.USER_CRASH_DATE
		})
@AcraMailSender(mailTo = "tc@kollnig.net")
@AcraDialog(resText = R.string.crash_dialog_text,
		resCommentPrompt = R.string.crash_dialog_comment)
public class AppWithCrashReporting extends Application {
	@Override
	protected void attachBaseContext (Context base) {
		super.attachBaseContext(base);

		// The following line triggers the initialization of ACRA
		ACRA.init(this);
	}
}