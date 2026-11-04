package org.isoron.uhabits

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.createTestFileOpener
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.CoroutineTaskRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IosListHabitsTest {
    private lateinit var habitList: HabitList
    private lateinit var prefs: Preferences
    private lateinit var list: IosListHabits
    private var changes = 0

    private fun setUp() = runTest {
        val factory = SQLModelFactory(TestDatabaseHelper.createEmptyDatabase())
        habitList = factory.buildHabitList()
        val taskRunner = CoroutineTaskRunner(UnconfinedTestDispatcher(), UnconfinedTestDispatcher())
        prefs = Preferences(MemoryStorage())
        list = IosListHabits(factory, habitList, CommandRunner(taskRunner), taskRunner, prefs, createTestFileOpener())
        list.onChange = { changes++ }
    }

    private fun rows() = list.snapshot!!.rows.mapNotNull { it.habit }
    private fun row(name: String) = rows().first { it.name == name }

    private fun create(name: String, numerical: Boolean = false, category: String = ""): Long {
        list.createDraft(numerical).apply {
            this.name = name
            this.category = category
            if (numerical) {
                unit = "km"
                targetValue = 5.0
            }
        }.save()
        return habitList.first { it.name == name }.id!!
    }

    @Test
    fun toggleCycles() {
        setUp()
        val id = create("Meditate")
        val today = { habitList.getById(id)!!.computedEntries.get(getToday()).value }

        // Default: short toggle off, so a long press toggles
        list.onLongPressDay(id, 0)
        assertEquals(Entry.YES_MANUAL, today())
        assertEquals(Entry.YES_MANUAL, row("Meditate").value(0))
        list.onLongPressDay(id, 0)
        assertEquals(Entry.NO, today())

        // With skip and question marks: NO -> UNKNOWN -> YES -> SKIP -> NO, on tap
        prefs.isShortToggleEnabled = true
        prefs.isSkipEnabled = true
        prefs.areQuestionMarksEnabled = true
        val seen = (1..4).map {
            list.onClickDay(id, 0)
            today()
        }
        assertEquals(listOf(Entry.UNKNOWN, Entry.YES_MANUAL, Entry.SKIP, Entry.NO), seen)
    }

    @Test
    fun numericValue() {
        setUp()
        val id = create("Run", numerical = true)
        var request: EntryRequest? = null
        list.onEntryPopup = { request = it }
        list.onClickDay(id, 1)
        assertNotNull(request).saveNumber(7.5, "windy")
        val entry = habitList.getById(id)!!.computedEntries.get(getToday().minus(1))
        assertEquals(7500, entry.value)
        assertEquals("windy", entry.notes)
        assertEquals("7.5", row("Run").numberLabel(1))
        assertTrue(row("Run").completed(1))
    }

    @Test
    fun editFrequencyAndReminder() {
        setUp()
        val id = create("Exercise")
        list.draft(id).apply {
            freqNum = 3
            freqDen = 7
            hasReminder = true
            reminderHour = 7
            reminderMinute = 15
            reminderDays = 0b0000011 // Saturday + Sunday
        }.save()
        val h = habitList.getById(id)!!
        assertEquals(Frequency(3, 7), h.frequency)
        assertEquals(7, h.reminder!!.hour)
        assertEquals(15, h.reminder!!.minute)
        assertEquals(3, h.reminder!!.days.toInteger())
        assertEquals(3, list.draft(id).freqNum)
    }

    @Test
    fun archiveAndCategories() {
        setUp()
        val a = create("A")
        create("B", category = "Work")
        val messages = mutableListOf<String>()
        list.onMessage = { messages.add(it) }

        assertEquals(listOf("A", "Work", "B"), list.snapshot!!.rows.map { it.habit?.name ?: it.category })

        list.select(a)
        list.selection.onArchiveHabits()
        assertTrue(habitList.getById(a)!!.isArchived)
        assertEquals(listOf("B"), rows().map { it.name })
        assertEquals("Habit archived", messages.last())

        list.menu.onToggleShowArchived()
        assertEquals(listOf("A", "B"), rows().map { it.name })
        list.select(a)
        assertTrue(list.selection.canUnarchive())
        list.selection.onUnarchiveHabits()
        assertTrue(!habitList.getById(a)!!.isArchived)

        // Change category through the request handed to Swift
        list.onCategoryInput = { it.confirm("Work") }
        list.select(a)
        list.selection.onChangeCategory()
        assertEquals("Work", habitList.getById(a)!!.category)
    }

    @Test
    fun deleteWithConfirmation() {
        setUp()
        val a = create("A")
        create("B")
        var confirm: ConfirmRequest? = null
        list.onConfirmDelete = { confirm = it }
        list.select(a)
        list.selection.onDeleteHabits()
        assertNotNull(habitList.getById(a)) // nothing until confirmed
        assertEquals(1, confirm!!.count)
        confirm!!.confirm()
        assertNull(habitList.getById(a))
        assertEquals(listOf("B"), rows().map { it.name })
    }

    @Test
    fun moveRowAcrossCategory() {
        setUp()
        create("A")
        create("B", category = "Work")
        // rows: A, [Work], B. Drag A to the end: it lands after B and joins "Work"
        list.moveRow(0, 3)
        assertEquals("Work", habitList.first { it.name == "A" }.category)
        assertEquals(listOf("Work", "B", "A"), list.snapshot!!.rows.map { it.habit?.name ?: it.category })
    }
}
