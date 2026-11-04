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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import org.isoron.platform.gui.registerFonts
import org.isoron.platform.io.IosDatabaseOpener
import org.isoron.platform.io.IosFileOpener
import org.isoron.platform.io.format
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.setVersion
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.DATABASE_FILENAME
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.database.UnsupportedDatabaseVersionException
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.CoroutineTaskRunner
import org.isoron.uhabits.core.tasks.TaskRunner

/**
 * The iOS object graph, the counterpart of Android's HabitsApplication + HabitsApplicationComponent.
 * All calls are synchronous and meant for the main thread.
 * Commands run through [commandRunner] (async: main + IO dispatchers); listen to it to refresh the UI.
 * The list screen's glue is [IosListHabits].
 *
 * @param resourceRoot bundled copy of uhabits-core/assets/main (contains migrations/)
 * @param userRoot writable directory for the database, e.g. the app's Documents folder
 */
class IosAppGraph(resourceRoot: String, userRoot: String) {
    val modelFactory: SQLModelFactory
    val habitList: HabitList
    val files: IosFileOpener
    val preferences = Preferences(UserDefaultsStorage())
    val taskRunner: TaskRunner = CoroutineTaskRunner(Dispatchers.Main, Dispatchers.IO)
    val commandRunner = CommandRunner(taskRunner)

    init {
        setToday(todayFor(preferences))
        registerFonts("$resourceRoot/fonts")
        files = IosFileOpener(listOf(resourceRoot), userRoot)
        val db = IosDatabaseOpener().open("$userRoot/$DATABASE_FILENAME")
        // Same as HabitsDatabaseOpener.onCreate: a fresh database starts at version 8
        when (db.getVersion()) {
            0 -> db.setVersion(8)
            in 1..7 -> throw UnsupportedDatabaseVersionException()
        }
        runBlocking {
            db.migrateTo(DATABASE_VERSION) { v ->
                files.openResourceFile("migrations/${format("%02d.sql", v)}").lines().joinToString("\n")
            }
        }
        modelFactory = SQLModelFactory(db)
        habitList = modelFactory.buildHabitList()
        for (h in habitList) h.recompute()
    }
}
