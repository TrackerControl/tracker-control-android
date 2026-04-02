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

import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

import net.kollnig.missioncontrol.R;

public class FragmentSettings extends PreferenceFragmentCompat
        implements PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);

        setIconSpaceReservedRecursively(getPreferenceScreen(), false);

        Preference doh = findPreference("doh_enabled");
        if (doh != null) {
            doh.setSummary(doh.getSummary() + " (" + getString(R.string.warning_beta) + ")");
        }
    }

    private void setIconSpaceReservedRecursively(PreferenceGroup group, boolean reserved) {
        group.setIconSpaceReserved(reserved);
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            pref.setIconSpaceReserved(reserved);
            if (pref instanceof PreferenceGroup) {
                setIconSpaceReservedRecursively((PreferenceGroup) pref, reserved);
            }
        }
    }

    @Override
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat caller, PreferenceScreen pref) {
        FragmentSettings fragment = new FragmentSettings();
        Bundle args = new Bundle();
        args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.getKey());
        fragment.setArguments(args);

        requireActivity().getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings_container, fragment)
                .addToBackStack(pref.getKey())
                .commit();
        return true;
    }
}
