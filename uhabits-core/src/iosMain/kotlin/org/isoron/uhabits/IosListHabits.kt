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

import org.isoron.platform.gui.Color
import org.isoron.platform.io.FileOpener
import org.isoron.platform.time.IosLocalDateFormatter
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.commands.ArchiveHabitsCommand
import org.isoron.uhabits.core.commands.ChangeHabitColorCommand
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.commands.DeleteHabitsCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.commands.UnarchiveHabitsCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.TaskRunner
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.ui.callbacks.CheckMarkDialogCallback
import org.isoron.uhabits.core.ui.callbacks.NumberPickerCallback
import org.isoron.uhabits.core.ui.callbacks.OnColorPickedCallback
import org.isoron.uhabits.core.ui.callbacks.OnConfirmedCallback
import org.isoron.uhabits.core.ui.screens.habits.list.HabitCardState
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsMenuBehavior
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsPresenter
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsSelectionMenuBehavior
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsState
import org.isoron.uhabits.core.ui.views.DarkTheme
import org.isoron.uhabits.core.ui.views.LightTheme
import org.isoron.uhabits.core.ui.views.toShortString
import kotlin.math.roundToInt

private val lightTheme = LightTheme()
private val darkTheme = DarkTheme()

private fun Color.hex() = "#" + listOf(red, green, blue).joinToString("") {
    (it * 255).roundToInt().toString(16).padStart(2, '0')
}

/** Theme-adjusted "#rrggbb" of a palette colour, light and dark, like Android's theme.color(). */
class HabitColor(val index: Int) {
    val light = lightTheme.color(index).hex()
    val dark = darkTheme.color(index).hex()
}

/** Swift-facing view of one [HabitCardState]. Day index 0 is today, 1 yesterday, and so on. */
class HabitRow internal constructor(private val card: HabitCardState) {
    val id = card.id
    val name = card.name
    val color = HabitColor(card.color.paletteIndex)
    val isNumerical = card.isNumerical
    val isArchived = card.archived
    val score = card.score
    val unit = card.unit
    val category = card.category

    fun value(day: Int): Int = card.checkmarks.getOrElse(day) { Entry.UNKNOWN }
    fun completed(day: Int): Boolean = card.completed.getOrElse(day) { false }
    fun hasNotes(day: Int): Boolean = card.notes.getOrElse(day) { "" }.isNotBlank()

    /** Numeric habits: the value as Android's NumberButton prints it. */
    fun numberLabel(day: Int): String = (value(day) / 1000.0).toShortString()
}

/** A list row: either a category header ([category] set) or a habit ([habit] set). */
class ListRow internal constructor(val key: String, val habit: HabitRow?, val category: String?)

class ListSnapshot internal constructor(
    val rows: List<ListRow>,
    val hasHabits: Boolean,
    val isSortable: Boolean,
    val primaryOrder: String,
    val showArchived: Boolean,
    val showCompleted: Boolean,
    /** Android renames "Hide completed" to "Hide entered" when skip or question marks are on. */
    val hideEnteredLabel: Boolean,
    val isSequenceReversed: Boolean,
    val areQuestionMarksEnabled: Boolean,
    val weekdayHeaders: List<String>,
    val dayHeaders: List<String>
)

/** Pending entry dialog (Android's CheckmarkDialog / NumberDialog). Call one save method, or nothing to cancel. */
class EntryRequest internal constructor(
    val habitName: String,
    val color: HabitColor,
    val isNumerical: Boolean,
    val value: Int,
    val notes: String,
    val unit: String,
    val isSkipEnabled: Boolean,
    val areQuestionMarksEnabled: Boolean,
    private val onNumber: NumberPickerCallback?,
    private val onCheckmark: CheckMarkDialogCallback?
) {
    val number: Double get() = value / 1000.0
    fun saveCheckmark(value: Int, notes: String) {
        onCheckmark?.onNotesSaved(value, notes)
    }
    fun saveNumber(value: Double, notes: String) {
        onNumber?.onNumberPicked(value, notes)
    }
    fun saveNumberSkip(notes: String) = saveNumber(Entry.SKIP / 1000.0, notes)
    fun saveNumberUnknown(notes: String) = saveNumber(Entry.UNKNOWN / 1000.0, notes)
}

class TextRequest internal constructor(val current: String, private val done: (String) -> Unit) {
    fun confirm(text: String) = done(text.trim())
}

class ColorRequest internal constructor(val current: Int, private val done: OnColorPickedCallback) {
    fun pick(index: Int) = done.onColorPicked(PaletteColor(index.coerceIn(0, 19)))
}

class ConfirmRequest internal constructor(val count: Int, private val done: OnConfirmedCallback) {
    fun confirm() = done.onConfirmed()
}

/**
 * Edit-habit form state, the counterpart of Android's EditHabitActivity fields.
 * [reminderDays] is a packed [WeekdayList] (bit 0 = Saturday, bit 1 = Sunday, ..., bit 6 = Friday).
 */
class HabitDraft internal constructor(private val owner: IosListHabits, val id: Long) {
    val isNew get() = id < 0
    var isNumerical = false
    var name = ""
    var question = ""
    var notes = "" // Habit.description; "description" clashes with NSObject in Swift
    var category = ""
    var colorIndex = 11
    var freqNum = 1
    var freqDen = 1
    var targetValue = 0.0
    var targetAtMost = false
    var unit = ""
    var hasReminder = false
    var reminderHour = 8
    var reminderMinute = 0
    var reminderDays = WeekdayList.EVERY_DAY.toInteger()

    fun color() = HabitColor(colorIndex)

    /** Runs CreateHabitCommand or EditHabitCommand through the CommandRunner. */
    fun save() = owner.save(this)
}

/**
 * Glue between the shared list-screen behaviors and SwiftUI, mirroring Android's ListHabitsScreen
 * and HabitCardListAdapter. Swift sets the `on*` callbacks; [onChange] fires after every command
 * (from a CommandRunner listener) and after menu changes. All calls are for the main thread.
 */
class IosListHabits(
    private val modelFactory: ModelFactory,
    private val habitList: HabitList,
    private val commandRunner: CommandRunner,
    taskRunner: TaskRunner,
    private val prefs: Preferences,
    files: FileOpener
) : CommandRunner.Listener,
    ListHabitsBehavior.Screen,
    ListHabitsMenuBehavior.Screen,
    ListHabitsMenuBehavior.Adapter,
    ListHabitsSelectionMenuBehavior.Screen,
    ListHabitsSelectionMenuBehavior.Adapter {

    constructor(graph: IosAppGraph) : this(
        graph.modelFactory,
        graph.habitList,
        graph.commandRunner,
        graph.taskRunner,
        graph.preferences,
        graph.files
    )

    var onChange: (ListSnapshot) -> Unit = {}
    var onMessage: (String) -> Unit = {}
    var onEntryPopup: (EntryRequest) -> Unit = {}
    var onCategoryInput: (TextRequest) -> Unit = {}
    var onColorPicker: (ColorRequest) -> Unit = {}
    var onConfirmDelete: (ConfirmRequest) -> Unit = {}
    var onEditHabit: (HabitDraft) -> Unit = {}
    var onSelectHabitType: () -> Unit = {}

    var snapshot: ListSnapshot? = null
        private set

    private var filter: HabitMatcher? = null
    private var lastState: ListHabitsState? = null
    private val selected = LinkedHashSet<Long>()
    private val formatter = IosLocalDateFormatter()

    override var primaryOrder: HabitList.Order = prefs.defaultPrimaryOrder
        set(value) {
            field = value
            prefs.defaultPrimaryOrder = value
        }
    override var secondaryOrder: HabitList.Order = prefs.defaultSecondaryOrder
        set(value) {
            field = value
            prefs.defaultSecondaryOrder = value
        }

    val behavior = ListHabitsBehavior(
        habitList,
        object : ListHabitsBehavior.DirFinder {
            override fun getCSVOutputDir() = files.openUserFile("CSV")
        },
        taskRunner,
        this,
        commandRunner,
        prefs,
        object : ListHabitsBehavior.BugReporter {
            override fun dumpBugReportToFile() {}
            override fun getBugReport() = ""
        }
    )
    val selection = ListHabitsSelectionMenuBehavior(habitList, this, this, commandRunner)

    // ponytail: iOS follows the system appearance, so the night-mode menu item is not offered
    private val themeSwitcher = object : ThemeSwitcher(prefs) {
        override fun applyDarkTheme() {}
        override fun applyLightTheme() {}
        override fun applyPureBlackTheme() {}
        override fun getSystemTheme() = THEME_AUTOMATIC
        override val currentTheme = lightTheme
    }

    // Last: its init calls setFilter + refresh, which need everything above
    val menu = ListHabitsMenuBehavior(this, this, prefs, themeSwitcher)

    init {
        commandRunner.addListener(this)
    }

    // --- Rows -------------------------------------------------------------------------------

    override fun refresh() {
        setToday(todayFor(prefs)) // ponytail: re-read on each refresh instead of a midnight timer
        val state = ListHabitsPresenter.buildState(
            allHabits = habitList,
            filter = filter,
            primaryOrder = primaryOrder,
            secondaryOrder = secondaryOrder,
            checkmarkCount = MAX_CHECKMARK_COUNT,
            theme = lightTheme
        )
        selected.retainAll(state.items.map { it.id }.toSet())
        publish(state)
    }

    private fun publish(state: ListHabitsState, rows: List<ListRow> = buildRows(state)) {
        lastState = state
        val today = getToday()
        val days = (0 until MAX_CHECKMARK_COUNT).map { today.minus(it) }
        val snap = ListSnapshot(
            rows = rows,
            hasHabits = state.hasHabits,
            isSortable = state.isSortable,
            primaryOrder = state.primaryOrder.name,
            showArchived = prefs.showArchived,
            showCompleted = prefs.showCompleted,
            hideEnteredLabel = prefs.areQuestionMarksEnabled || prefs.isSkipEnabled,
            isSequenceReversed = prefs.isCheckmarkSequenceReversed,
            areQuestionMarksEnabled = prefs.areQuestionMarksEnabled,
            weekdayHeaders = days.map { formatter.shortWeekdayName(it).uppercase() },
            dayHeaders = days.map { it.day.toString() }
        )
        snapshot = snap
        onChange(snap)
    }

    /** Same grouping as Android's HabitCardListAdapter.buildRows: a header before each category run. */
    private fun buildRows(state: ListHabitsState): List<ListRow> {
        val rows = mutableListOf<ListRow>()
        var lastHeader: String? = null
        for (card in state.items) {
            if (card.category.isNotBlank() && card.category != lastHeader) {
                rows.add(ListRow("c:${card.category}", null, card.category))
                lastHeader = card.category
            }
            rows.add(ListRow("h:${card.id}", HabitRow(card), null))
        }
        return rows
    }

    override fun onCommandFinished(command: Command) {
        message(command)?.let(onMessage)
        refresh()
    }

    // Android's ListHabitsScreen.getExecuteString
    private fun message(command: Command): String? = when (command) {
        is ArchiveHabitsCommand -> plural(command.selected.size, "archived")
        is UnarchiveHabitsCommand -> plural(command.selected.size, "unarchived")
        is DeleteHabitsCommand -> plural(command.selected.size, "deleted")
        is ChangeHabitColorCommand -> plural(command.selected.size, "changed")
        is EditHabitCommand -> plural(1, "changed")
        is CreateHabitCommand -> "Habit created"
        else -> null
    }

    private fun plural(n: Int, verb: String) = if (n == 1) "Habit $verb" else "$n habits $verb"

    // --- Checkmarks -------------------------------------------------------------------------

    /** Tap on a day button. Mirrors CheckmarkButtonView/NumberButtonView.onClick. */
    fun onClickDay(habitId: Long, day: Int) {
        val habit = habitList.getById(habitId) ?: return
        if (!habit.isNumerical && prefs.isShortToggleEnabled) toggle(habit, day) else onEditDay(habitId, day)
    }

    /** Long press on a day button. Mirrors CheckmarkButtonView.onLongClick. */
    fun onLongPressDay(habitId: Long, day: Int) {
        val habit = habitList.getById(habitId) ?: return
        if (!habit.isNumerical && !prefs.isShortToggleEnabled) toggle(habit, day) else onEditDay(habitId, day)
    }

    /** Opens the entry dialog via [onEntryPopup] (ListHabitsBehavior.onEdit). */
    fun onEditDay(habitId: Long, day: Int) {
        editingHabitId = habitId
        behavior.onEdit(habitId, getToday().minus(day), 0f, 0f)
    }

    private fun toggle(habit: Habit, day: Int) {
        val date = getToday().minus(day)
        val entry = habit.computedEntries.get(date)
        val next = Entry.nextToggleValue(entry.value, prefs.isSkipEnabled, prefs.areQuestionMarksEnabled)
        behavior.onToggle(habit.id!!, date, next, entry.notes, 0f, 0f)
    }

    // --- Selection --------------------------------------------------------------------------

    fun setSelected(ids: List<Long>) {
        selected.clear()
        selected.addAll(ids)
    }

    /** Selects one habit; for single-row context-menu actions. */
    fun select(habitId: Long) = setSelected(listOf(habitId))

    override fun clearSelection() = selected.clear()

    override fun getSelected(): List<Habit> = selected.mapNotNull { habitList.getById(it) }

    override fun performRemove(selected: List<Habit>) {
        val state = lastState ?: return
        val ids = selected.mapNotNull { it.id }.toSet()
        publish(state.copy(items = state.items.filter { it.id !in ids }))
    }

    /**
     * Drag-and-drop, with SwiftUI's onMove indices over [ListSnapshot.rows].
     * Same rules as Android's HabitCardListAdapter.onDragEnd: the habit takes the category of the
     * header above it and is placed after the habit above (or before the one below).
     */
    fun moveRow(from: Int, to: Int) {
        val state = lastState ?: return
        val rows = snapshot?.rows?.toMutableList() ?: return
        val row = rows.getOrNull(from) ?: return
        val habit = row.habit ?: return
        rows.removeAt(from)
        val k = (if (to > from) to - 1 else to).coerceIn(0, rows.size)
        rows.add(k, row)
        val category = rows.subList(0, k).lastOrNull { it.category != null }?.category ?: ""
        val above = rows.getOrNull(k - 1)?.habit
        val below = rows.getOrNull(k + 1)?.habit
        publish(state, rows) // optimistic, like Android's notifyItemMoved; the command's refresh follows
        when {
            above != null -> behavior.reorderHabit(habit.id, above.id, false, category)
            below != null -> behavior.reorderHabit(habit.id, below.id, true, category)
            else -> behavior.reorderHabit(habit.id, null, false, category)
        }
    }

    // --- Screens (requests handed to Swift) -------------------------------------------------

    override fun showNumberPopup(value: Double, notes: String, callback: NumberPickerCallback) {
        val habit = pendingEntryHabit ?: return
        onEntryPopup(entryRequest(habit, (value * 1000).roundToInt(), notes, callback, null))
    }

    override fun showCheckmarkPopup(
        selectedValue: Int,
        notes: String,
        color: PaletteColor,
        callback: CheckMarkDialogCallback
    ) {
        val habit = pendingEntryHabit ?: return
        onEntryPopup(entryRequest(habit, selectedValue, notes, null, callback))
    }

    // ListHabitsBehavior.Screen's popups do not say which habit they are for, so remember it here
    private val pendingEntryHabit: Habit?
        get() = editingHabitId?.let { habitList.getById(it) }
    private var editingHabitId: Long? = null

    private fun entryRequest(
        habit: Habit,
        value: Int,
        notes: String,
        number: NumberPickerCallback?,
        check: CheckMarkDialogCallback?
    ) = EntryRequest(
        habit.name,
        HabitColor(habit.color.paletteIndex),
        habit.isNumerical,
        value,
        notes,
        habit.unit,
        prefs.isSkipEnabled,
        prefs.areQuestionMarksEnabled,
        number,
        check
    )

    override fun showCategoryInput(current: String, onConfirmed: (String) -> Unit) =
        onCategoryInput(TextRequest(current, onConfirmed))

    override fun showColorPicker(defaultColor: PaletteColor, callback: OnColorPickedCallback) =
        onColorPicker(ColorRequest(defaultColor.paletteIndex, callback))

    override fun showDeleteConfirmationScreen(callback: OnConfirmedCallback, quantity: Int) =
        onConfirmDelete(ConfirmRequest(quantity, callback))

    override fun showEditHabitsScreen(selected: List<Habit>) {
        selected.firstOrNull()?.id?.let { onEditHabit(draft(it)) }
    }

    override fun showSelectHabitTypeDialog() = onSelectHabitType()

    override fun showMessage(m: ListHabitsBehavior.Message) = onMessage(m.name)

    override fun setFilter(matcher: HabitMatcher) {
        filter = matcher
    }

    // Navigation and features owned by other screens (show habit, settings, about, export, etc.)
    override fun showHabitScreen(habitId: Long) {}
    override fun showIntroScreen() {}
    override fun showSendBugReportToDeveloperScreen(log: String) {}
    override fun showSendFileScreen(filename: String) {}
    override fun showConfetti(color: PaletteColor, x: Float, y: Float) {}
    override fun applyTheme() {}
    override fun showAboutScreen() {}
    override fun showFAQScreen() {}
    override fun showSettingsScreen() {}

    // --- Edit habit -------------------------------------------------------------------------

    fun createDraft(isNumerical: Boolean) = HabitDraft(this, -1).apply { this.isNumerical = isNumerical }

    fun draft(habitId: Long): HabitDraft {
        val h = habitList.getById(habitId) ?: return createDraft(false)
        return HabitDraft(this, habitId).apply {
            isNumerical = h.isNumerical
            name = h.name
            question = h.question
            notes = h.description
            category = h.category
            colorIndex = h.color.paletteIndex
            freqNum = h.frequency.numerator
            freqDen = h.frequency.denominator
            targetValue = h.targetValue
            targetAtMost = h.targetType == NumericalHabitType.AT_MOST
            unit = h.unit
            h.reminder?.let {
                hasReminder = true
                reminderHour = it.hour
                reminderMinute = it.minute
                reminderDays = it.days.toInteger()
            }
        }
    }

    /** Same as EditHabitActivity.save. */
    internal fun save(d: HabitDraft) {
        val habit = modelFactory.buildHabit()
        if (!d.isNew) habit.copyFrom(habitList.getById(d.id) ?: return)
        habit.name = d.name.trim()
        habit.question = d.question.trim()
        habit.description = d.notes.trim()
        habit.category = d.category.trim()
        habit.color = PaletteColor(d.colorIndex.coerceIn(0, 19))
        val days = WeekdayList(d.reminderDays).let { if (it.isEmpty) WeekdayList.EVERY_DAY else it }
        habit.reminder = if (d.hasReminder) Reminder(d.reminderHour, d.reminderMinute, days) else null
        habit.frequency = Frequency(d.freqNum, d.freqDen)
        if (d.isNumerical) {
            habit.targetValue = d.targetValue
            habit.targetType = if (d.targetAtMost) NumericalHabitType.AT_MOST else NumericalHabitType.AT_LEAST
            habit.unit = d.unit.trim()
        }
        habit.type = if (d.isNumerical) HabitType.NUMERICAL else HabitType.YES_NO
        commandRunner.run(
            if (d.isNew) {
                CreateHabitCommand(modelFactory, habitList, habit)
            } else {
                EditHabitCommand(habitList, d.id, habit)
            }
        )
    }

    // --- Debug ------------------------------------------------------------------------------

    /**
     * Debug seed for screenshots (launch arguments -seed / -seedFull); only when the DB is empty.
     * Runs commands synchronously, then refreshes.
     */
    fun seed(full: Boolean) {
        if (!habitList.isEmpty) return
        fun add(name: String, color: Int, setup: Habit.() -> Unit = {}): Habit {
            val model = modelFactory.buildHabit().apply {
                this.name = name
                this.color = PaletteColor(color)
                setup()
            }
            CreateHabitCommand(modelFactory, habitList, model).run()
            return habitList.first { it.uuid == model.uuid }
        }
        val today = getToday()
        val meditate = add("Meditate", 8)
        add("Exercise", 0) { frequency = Frequency.THREE_TIMES_PER_WEEK }
        CreateRepetitionCommand(habitList, meditate, today, Entry.YES_MANUAL, "").run()
        if (full) {
            for (d in listOf(1, 2, 4, 5, 7, 8, 9)) {
                CreateRepetitionCommand(habitList, meditate, today.minus(d), Entry.YES_MANUAL, "").run()
            }
            val run = add("Run", 11) {
                type = HabitType.NUMERICAL
                unit = "km"
                targetValue = 5.0
                frequency = Frequency.WEEKLY
                category = "Fitness"
            }
            for ((d, km) in listOf(0 to 5.2, 2 to 3.0, 3 to 7.5, 6 to 12.0)) {
                CreateRepetitionCommand(habitList, run, today.minus(d), (km * 1000).roundToInt(), "").run()
            }
            val read = add("Read", 13) {
                question = "Did you read today?"
                category = "Learning"
                reminder = Reminder(21, 30, WeekdayList.EVERY_DAY)
            }
            CreateRepetitionCommand(habitList, read, today.minus(1), Entry.YES_MANUAL, "Chapter 3").run()
            add("Old habit", 18) { isArchived = true }
        }
        refresh()
    }

    companion object {
        /** Entries computed per row; the view shows as many as fit. */
        const val MAX_CHECKMARK_COUNT = 30
    }
}
