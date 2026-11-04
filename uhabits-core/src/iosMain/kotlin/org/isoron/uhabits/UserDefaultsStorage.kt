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

import org.isoron.uhabits.core.preferences.Preferences
import platform.Foundation.NSUserDefaults

/** Preferences storage on NSUserDefaults, the counterpart of Android's SharedPreferencesStorage. */
class UserDefaultsStorage(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) :
    Preferences.Storage {
    private fun has(key: String) = defaults.objectForKey(key) != null

    override fun clear() {
        defaults.dictionaryRepresentation().keys.forEach { defaults.removeObjectForKey(it as String) }
    }

    override fun getBoolean(key: String, defValue: Boolean) = if (has(key)) defaults.boolForKey(key) else defValue
    override fun getInt(key: String, defValue: Int) = if (has(key)) defaults.integerForKey(key).toInt() else defValue
    override fun getLong(key: String, defValue: Long) = if (has(key)) defaults.integerForKey(key) else defValue
    override fun getString(key: String, defValue: String) = defaults.stringForKey(key) ?: defValue
    override fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, key)
    override fun putInt(key: String, value: Int) = defaults.setInteger(value.toLong(), key)
    override fun putLong(key: String, value: Long) = defaults.setInteger(value, key)
    override fun putString(key: String, value: String) = defaults.setObject(value, key)
    override fun remove(key: String) = defaults.removeObjectForKey(key)
}
