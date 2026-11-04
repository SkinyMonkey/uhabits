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

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.IosDatabaseOpener
import org.isoron.platform.io.IosUserFile
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.DATABASE_FILENAME
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.io.GenericImporter
import org.isoron.uhabits.core.io.HabitBullCSVImporter
import org.isoron.uhabits.core.io.HabitsCSVExporter
import org.isoron.uhabits.core.io.LoopDBImporter
import org.isoron.uhabits.core.io.RewireDBImporter
import org.isoron.uhabits.core.io.StandardLogging
import org.isoron.uhabits.core.io.TickmateDBImporter
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager

/** Fired through the command runner after an import, so the list and the reminders refresh. */
object DataImportedCommand : Command {
    override fun run() {}
}

/**
 * Export (CSV zip, full .db backup) and import (GenericImporter: Loop, Rewire, Tickmate, HabitBull).
 * Synchronous; each returns the written file's path. ponytail: runs on the caller's thread, move to the
 * task runner if large imports block the UI noticeably.
 */
class IosImportExport(private val graph: IosAppGraph) {
    @Throws(Exception::class)
    fun exportCSV(outputDir: String): String = runBlocking {
        setToday(todayFor(graph.preferences))
        val bytes = HabitsCSVExporter(graph.habitList, graph.habitList.toList()).writeArchive()
        val file = IosUserFile("$outputDir/Loop Habits CSV ${getToday().toCSVString()}.zip")
        file.writeBytes(bytes)
        file.pathString
    }

    /** Same filename pattern as Android's DatabaseUtils.saveDatabaseCopy. */
    @OptIn(ExperimentalForeignApi::class)
    @Throws(Exception::class)
    fun exportDB(outputDir: String): String {
        val date = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd HHmmss" }.stringFromDate(NSDate())
        val dest = "$outputDir/Loop Habits Backup $date.db"
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(outputDir, true, null, null)
        if (fm.fileExistsAtPath(dest)) fm.removeItemAtPath(dest, null)
        check(fm.copyItemAtPath(graph.files.openUserFile(DATABASE_FILENAME).pathString, dest, null)) { "Could not copy database to $dest" }
        return dest
    }

    /** Imports [path] (migrated in place for Loop backups, so pass a copy). Throws if no importer handles it. */
    @Throws(Exception::class)
    fun importFile(path: String) {
        val file = IosUserFile(path)
        val opener = IosDatabaseOpener()
        val importer = GenericImporter(
            LoopDBImporter(graph.habitList, graph.modelFactory, opener, graph.commandRunner, StandardLogging(), graph.files),
            RewireDBImporter(graph.habitList, graph.modelFactory, opener),
            TickmateDBImporter(graph.habitList, graph.modelFactory, opener),
            HabitBullCSVImporter(graph.habitList, graph.modelFactory, StandardLogging())
        )
        runBlocking {
            require(importer.canHandle(file)) { "File type not recognized" }
            importer.importHabitsFromFile(file)
        }
        graph.commandRunner.notifyListeners(DataImportedCommand)
    }
}
