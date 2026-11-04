package org.isoron.uhabits

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.ZipReader
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.platform.time.todayAt
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosAppFeaturesTest {
    private val assets = getenv("UHABITS_ASSETS")!!.toKString()

    private fun tempDir(): String {
        val dir = NSTemporaryDirectory() + "uhabits-" + NSUUID().UUIDString
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        return dir
    }

    private fun newGraph() = IosAppGraph("$assets/main", tempDir())

    private fun IosAppGraph.addHabit(name: String, checkedDaysAgo: List<Int>) {
        val h = modelFactory.buildHabit().apply { this.name = name }
        habitList.add(h)
        val today = getToday()
        for (d in checkedDaysAgo) h.originalEntries.add(Entry(today.minus(d), Entry.YES_MANUAL, "note $d"))
        h.recompute()
    }

    private fun IosAppGraph.snapshot() = habitList.associate { h ->
        h.name to h.originalEntries.getKnown().map { Triple(it.date, it.value, it.notes) }.sortedBy { it.first.unixTime }
    }

    @Test
    fun exportedDatabaseImportsIntoFreshDatabase() {
        val source = newGraph()
        source.addHabit("Meditate", listOf(0, 1, 5))
        source.addHabit("Run", listOf(2))
        val backup = IosImportExport(source).exportDB(tempDir())

        val target = newGraph()
        var notified = 0
        target.commandRunner.addListener(object : org.isoron.uhabits.core.commands.CommandRunner.Listener {
            override fun onCommandFinished(command: org.isoron.uhabits.core.commands.Command) {
                notified++
            }
        })
        IosImportExport(target).importFile(backup)

        assertEquals(source.snapshot(), target.snapshot())
        assertEquals(3, target.snapshot()["Meditate"]!!.size)
        assertEquals(1, notified)
    }

    @Test
    fun importRejectsUnknownFile() = runTest {
        val path = tempDir() + "/junk.txt"
        org.isoron.platform.io.IosUserFile(path).writeString("not a backup")
        assertFailsWith<IllegalArgumentException> { IosImportExport(newGraph()).importFile(path) }
    }

    @Test
    fun csvExportWritesZipWithExpectedEntries() = runTest {
        val graph = newGraph()
        graph.addHabit("Meditate", listOf(0, 3))
        graph.addHabit("Read, books", listOf(1))
        val path = IosImportExport(graph).exportCSV(tempDir())
        assertTrue(path.endsWith("Loop Habits CSV ${getToday().toCSVString()}.zip"))

        val bytes = org.isoron.platform.io.IosUserFile(path).readBytes(Int.MAX_VALUE)
        assertTrue(bytes.isNotEmpty())
        val entries = ZipReader(bytes).entries().associate { it.name to it.content }
        assertEquals(
            setOf(
                "Habits.csv",
                "Scores.csv",
                "Checkmarks.csv",
                "001 Meditate/Scores.csv",
                "001 Meditate/Checkmarks.csv",
                "002 Read books/Scores.csv",
                "002 Read books/Checkmarks.csv"
            ),
            entries.keys
        )
        assertTrue(entries["Habits.csv"]!!.contains("Meditate"))
        assertTrue(entries["001 Meditate/Checkmarks.csv"]!!.contains("${getToday().toCSVString()},YES_MANUAL,note 0"))
    }

    @Test
    fun reminderTriggersMapWeekdaysToCalendarNumbers() {
        val graph = newGraph()
        val habit = graph.modelFactory.buildHabit().apply { name = "Stretch" }
        graph.habitList.add(habit)
        assertEquals(emptyList(), reminderTriggers(habit))

        // WeekdayList index 0 = Saturday, 1 = Sunday, 2 = Monday ... 6 = Friday
        val days = booleanArrayOf(true, true, true, false, false, false, true)
        habit.reminder = Reminder(8, 30, WeekdayList(days))
        val triggers = reminderTriggers(habit)
        assertEquals(listOf(7, 1, 2, 6), triggers.map { it.weekday })
        assertTrue(triggers.all { it.hour == 8 && it.minute == 30 && it.habitId == habit.id })
        assertEquals("reminder-${habit.id}-1", triggers[1].identifier)

        habit.reminder = Reminder(21, 5, WeekdayList.EVERY_DAY)
        assertEquals((1..7).toList(), reminderTriggers(habit).map { it.weekday }.sorted())

        habit.isArchived = true
        assertEquals(emptyList(), reminderTriggers(habit))
    }

    @Test
    fun midnightDelayMovesTodayBack() {
        val day = LocalDate(2025, 3, 10)
        val at = { hour: Int, minute: Int -> day.unixTime + hour * 3600_000L + minute * 60_000L }
        assertEquals(day, todayAt(at(2, 59), 0, 0))
        assertEquals(day.minus(1), todayAt(at(2, 59), 3, 0))
        assertEquals(day, todayAt(at(3, 0), 3, 0))
        assertEquals(day.minus(1), todayAt(at(0, 10), 0, 30))

        val prefs = Preferences(MemoryStorage())
        assertEquals(0, prefs.midnightDelayHours)
        prefs.isMidnightDelayEnabled = true
        assertEquals(Preferences.MIDNIGHT_DELAY_HOURS, prefs.midnightDelayHours)
        assertEquals(org.isoron.platform.time.computeToday(3, 0), todayFor(prefs))
    }
}
