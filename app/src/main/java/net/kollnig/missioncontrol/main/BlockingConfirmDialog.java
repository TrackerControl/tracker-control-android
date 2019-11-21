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
import android.widget.Switch;

import net.kollnig.missioncontrol.R;

import androidx.appcompat.app.AlertDialog;

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
		String message;
		message = c.getString(R.string.confirm_blocking);

		// Show dialog
		AlertDialog.Builder builder = new AlertDialog.Builder(c);
		builder.setMessage(message)
				.setTitle(R.string.confirm_blocking_title);
		builder.setPositiveButton(R.string.yes, (dialog, id) -> {
			blockAll();
			switchBlockAll.setChecked(true);
		});
		builder.setNegativeButton(R.string.no, (dialog, i) -> {
			switchBlockAll.setChecked(false);
		});
		AlertDialog dialog = builder.create();
		dialog.show();
	}

	abstract void blockAll ();
}
