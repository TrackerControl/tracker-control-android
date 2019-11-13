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

package net.kollnig.missioncontrol.main;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.support.v7.app.AlertDialog;
import android.widget.Switch;

import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.R;

abstract class BlockingConfirmDialog {
	private Context c;
	private BlocklistController w;
	private Switch switchBlockAll;

	BlockingConfirmDialog (Context c, BlocklistController w, Switch switchBlockAll) {
		this.c = c;
		this.w = w;
		this.switchBlockAll = switchBlockAll;
	}

	void confirmBlocking () {
		// Check if user has ad settings
		final boolean hasAdSettings = Common.hasAdSettings(c);
		final Intent adIntent = Common.adSettings();

		String message;
		if (hasAdSettings) {
			message = c.getString(R.string.confirm_blocking_has_playservices);
		} else {
			message = c.getString(R.string.confirm_blocking_no_playservices);
		}

		// Show dialog
		AlertDialog.Builder builder = new AlertDialog.Builder(c);
		builder.setMessage(message)
				.setTitle(R.string.confirm_blocking_title);
		builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
			public void onClick (DialogInterface dialog, int id) {
				if (hasAdSettings) {
					c.startActivity(adIntent);
				}
				switchBlockAll.setChecked(false);
			}
		});
		builder.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
			@Override
			public void onClick (DialogInterface dialog, int i) {
				blockAll();
				switchBlockAll.setChecked(true);
			}
		});
		AlertDialog dialog = builder.create();
		dialog.show();
	}

	abstract void blockAll ();
}
