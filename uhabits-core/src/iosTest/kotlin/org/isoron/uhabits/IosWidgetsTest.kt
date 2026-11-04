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
import kotlinx.cinterop.toKString
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.HabitType
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosWidgetsTest {
    private val assets = getenv("UHABITS_ASSETS")!!.toKString() + "/main"

    private fun tempDir(): String {
        val dir = NSTemporaryDirectory() + "uhabits-" + NSUUID().UUIDString
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        return dir
    }

    private fun suiteName() = "test-" + NSUUID().UUIDString
    private fun suite() = NSUserDefaults(suiteName = suiteName())

    /** An app graph with a yes/no habit checked 1 and 2 days ago, plus an archived and a numerical habit. */
    private fun appGraph(dir: String) = IosAppGraph(assets, dir).apply {
        fun add(name: String, setup: org.isoron.uhabits.core.models.Habit.() -> Unit = {}) {
            CreateHabitCommand(modelFactory, habitList, modelFactory.buildHabit().apply { this.name = name; setup() }).run()
        }
        add("Meditate")
        add("Old") { isArchived = true }
        add("Run") { type = HabitType.NUMERICAL; unit = "km"; targetValue = 5.0 }
        val meditate = habitList.first { it.name == "Meditate" }
        for (d in 1..2) CreateRepetitionCommand(habitList, meditate, getToday().minus(d), Entry.YES_MANUAL, "").run()
    }

    @Test
    fun snapshotListsActiveHabitsWithScoresAndStreaks() {
        val dir = tempDir()
        appGraph(dir)
        val widgets = IosWidgets(assets, dir, suite())
        val habits = widgets.habits()
        assertEquals(listOf("Meditate", "Run"), habits.map { it.name })
        val meditate = habits[0]
        assertFalse(meditate.isCompletedToday)
        assertEquals(Entry.UNKNOWN, meditate.todayValue)
        assertTrue(meditate.score > 0)
        assertTrue(meditate.scores.isNotEmpty())
        assertEquals(2, meditate.streaks.first().length)
        assertEquals("km", habits[1].unit)
        assertEquals("Meditate", widgets.habit(meditate.id)?.name)
        assertNotNull(widgets.renderHistory(meditate.id, 300.0, 150.0, 2.0, dark = true))
        widgets.close()
    }

    @Test
    fun toggleWritesThroughToTheAppGraph() {
        val dir = tempDir()
        val app = appGraph(dir)
        val groupId = suiteName()
        val group = NSUserDefaults(suiteName = groupId)
        var changes = 0
        val sync = IosWidgetSync(app, groupId) { changes++ }
        assertFalse(sync.syncFromWidgets())

        val widgets = IosWidgets(assets, dir, group)
        val meditate = widgets.habits().first { it.name == "Meditate" }
        val run = widgets.habits().first { it.name == "Run" }
        assertTrue(widgets.toggle(meditate.id))
        assertFalse(widgets.toggle(run.id)) // numerical habits need a number
        widgets.close()

        // A fresh extension request sees the write
        val again = IosWidgets(assets, dir, group)
        assertTrue(again.habit(meditate.id)!!.isCompletedToday)
        assertEquals(Entry.YES_MANUAL, again.habit(meditate.id)!!.todayValue)
        again.close()

        // The app only notices after reloading
        val before = changes
        assertTrue(sync.syncFromWidgets())
        assertTrue(app.habitList.getById(meditate.id)!!.isCompletedToday())
        assertEquals(before + 1, changes)
        assertFalse(sync.syncFromWidgets())
    }

    @Test
    fun preferencesAreMirroredIntoTheGroup() {
        val dir = tempDir()
        val app = appGraph(dir)
        val source = suite()
        val groupId = suiteName()
        val group = NSUserDefaults(suiteName = groupId)
        source.setBool(true, "pref_skip_enabled")
        source.setObject("2", "pref_first_weekday")
        source.setObject("ignored", "other_key")
        group.setObject("1", "pref_stale")
        var changes = 0
        val sync = IosWidgetSync(app, groupId) { changes++ }
        changes = 0

        sync.mirrorPreferences(source)
        assertTrue(group.boolForKey("pref_skip_enabled"))
        assertEquals("2", group.stringForKey("pref_first_weekday"))
        assertEquals(null, group.objectForKey("other_key"))
        assertEquals(null, group.objectForKey("pref_stale"))
        assertEquals(1, changes)
        sync.mirrorPreferences(source)
        assertEquals(1, changes)

        val widgets = IosWidgets(assets, dir, group)
        assertTrue(widgets.graph.preferences.isSkipEnabled)
        widgets.close()
    }

    @Test
    fun databaseMovesIntoTheSharedFolderOnce() {
        val docs = tempDir()
        val group = tempDir()
        appGraph(docs).modelFactory.database.close()
        assertTrue(IosWidgets.migrateDatabase(docs, group))
        val fm = NSFileManager.defaultManager
        assertFalse(fm.fileExistsAtPath("$docs/uhabits.db"))
        assertTrue(fm.fileExistsAtPath("$group/uhabits.db"))
        assertFalse(IosWidgets.migrateDatabase(docs, group))
        assertEquals(listOf("Meditate", "Run"), IosWidgets(assets, group, suite()).habits().map { it.name })
        // No group container in the test process: falls back to the legacy folder
        assertEquals(docs, IosWidgets.sharedUserRoot("group.invalid.test", docs))
    }
}
