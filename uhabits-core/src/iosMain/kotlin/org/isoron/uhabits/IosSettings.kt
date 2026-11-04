/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.computeToday
import org.isoron.uhabits.core.preferences.Preferences

/** "Today" honoring the midnight-delay preference, same as Android's MidnightTimer. */
fun todayFor(prefs: Preferences): LocalDate = computeToday(prefs.midnightDelayHours, 0)

/**
 * Settings that the shared [Preferences] can read but not write (first weekday), or that are iOS-only (snooze).
 * Same storage and keys as [Preferences].
 */
class IosSettings(private val storage: Preferences.Storage = UserDefaultsStorage()) {
    /** 1 = Sunday ... 7 = Saturday, -1 = follow the locale. Stored as a string, like Android's ListPreference. */
    var firstWeekday: Int
        get() = storage.getString("pref_first_weekday", "-1").toIntOrNull() ?: -1
        set(value) = if (value < 0) storage.remove("pref_first_weekday") else storage.putString("pref_first_weekday", value.toString())

    var snoozeMinutes: Int
        get() = storage.getInt("pref_snooze_interval", 15)
        set(value) = storage.putInt("pref_snooze_interval", value)
}
