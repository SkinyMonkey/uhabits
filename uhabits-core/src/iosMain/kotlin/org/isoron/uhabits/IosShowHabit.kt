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

@file:OptIn(ExperimentalForeignApi::class)

package org.isoron.uhabits

import kotlinx.cinterop.ExperimentalForeignApi
import org.isoron.platform.gui.Canvas
import org.isoron.platform.gui.IosCanvas
import org.isoron.platform.gui.IosImage
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.IosLocalDateFormatter
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.ui.callbacks.CheckMarkDialogCallback
import org.isoron.uhabits.core.ui.callbacks.NumberPickerCallback
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitState
import org.isoron.uhabits.core.ui.views.BarChart
import org.isoron.uhabits.core.ui.views.DarkTheme
import org.isoron.uhabits.core.ui.views.HistoryChart
import org.isoron.uhabits.core.ui.views.LightTheme
import org.isoron.uhabits.core.ui.views.OnDateClickedListener
import org.isoron.uhabits.core.ui.views.Theme
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGImageRelease
import platform.UIKit.UIImage
import platform.UIKit.UIImageOrientation
import kotlin.math.roundToInt

class ScorePoint(val unixSeconds: Double, val value: Double)
class StreakItem(val start: String, val end: String, val length: Int)
class TargetRow(val interval: Int, val value: Double, val target: Double)

/** One month of the punch card: counts per weekday, ordered from the first weekday. */
class FrequencyRow(val month: String, val counts: List<Int>)

/** Flat, Swift-friendly view of [ShowHabitState]. Colors are "#RRGGBB". */
class ShowHabitSnapshot(
    val state: ShowHabitState,
    val title: String,
    val question: String,
    val frequencyText: String,
    val targetText: String,
    val reminderText: String,
    val category: String,
    val notes: String,
    val isNumerical: Boolean,
    val color: String,
    val cardBackground: String,
    val appBackground: String,
    val textColor: String,
    val mediumTextColor: String,
    val lowContrastColor: String,
    val scoreToday: Double,
    val scoreMonthDiff: Double,
    val scoreYearDiff: Double,
    val totalCount: Long,
    val scoreSpinner: Int,
    val barSpinner: Int,
    /** Newest first, as the presenter returns them. */
    val scores: List<ScorePoint>,
    val targets: List<TargetRow>,
    val streaks: List<StreakItem>,
    val weekdays: List<String>,
    val frequency: List<FrequencyRow>
)

/**
 * Backs the iOS show-habit screen with the shared [ShowHabitPresenter].
 * [onChange] fires after any command (e.g. a toggle from the history chart) or spinner change;
 * [onEntryPopup] asks Swift to show the list's entry sheet (checkmark or number, plus notes) for a day.
 * Call [close] when done.
 */
class IosShowHabit(
    private val graph: IosAppGraph,
    habitId: Long,
    private val onChange: () -> Unit,
    private val onEntryPopup: (EntryRequest) -> Unit
) : ShowHabitPresenter.Screen, CommandRunner.Listener {
    val habit: Habit? = graph.habitList.getById(habitId)
    private val presenter = habit?.let {
        ShowHabitPresenter(it, graph.habitList, graph.preferences, this, graph.commandRunner)
    }
    private val formatter = IosLocalDateFormatter()
    private var history: HistoryChart? = null

    init {
        graph.commandRunner.addListener(this)
    }

    fun close() {
        graph.commandRunner.removeListener(this)
    }

    private fun theme(dark: Boolean): Theme = if (dark) DarkTheme() else LightTheme()

    fun snapshot(dark: Boolean): ShowHabitSnapshot? {
        val habit = habit ?: return null
        val theme = theme(dark)
        val s = ShowHabitPresenter.buildState(habit, graph.preferences, theme)
        val first = graph.preferences.firstWeekday
        val weekdays = (0..6).map { DayOfWeek.entries[(first.daysSinceSunday + it) % 7] }
        val sub = s.subtitle
        return ShowHabitSnapshot(
            state = s,
            title = s.title,
            question = sub.question,
            frequencyText = formatFrequency(sub.frequency),
            targetText = if (sub.isNumerical) {
                val sign = if (sub.targetType == NumericalHabitType.AT_LEAST) "≥" else "≤"
                "$sign ${formatNumber(sub.targetValue)} ${sub.unit}".trim()
            } else {
                ""
            },
            reminderText = sub.reminder?.let { "${it.hour}:${it.minute.toString().padStart(2, '0')}" } ?: "Off",
            category = sub.category,
            notes = s.notes.description,
            isNumerical = s.isNumerical,
            color = theme.color(s.color).toHex(),
            cardBackground = theme.cardBackgroundColor.toHex(),
            appBackground = theme.appBackgroundColor.toHex(),
            textColor = theme.highContrastTextColor.toHex(),
            mediumTextColor = theme.mediumContrastTextColor.toHex(),
            lowContrastColor = theme.lowContrastTextColor.toHex(),
            scoreToday = s.overview.scoreToday.toDouble(),
            scoreMonthDiff = s.overview.scoreMonthDiff.toDouble(),
            scoreYearDiff = s.overview.scoreYearDiff.toDouble(),
            totalCount = s.overview.totalCount,
            scoreSpinner = s.scores.spinnerPosition,
            barSpinner = if (s.isNumerical) s.bar.numericalSpinnerPosition else s.bar.boolSpinnerPosition,
            scores = s.scores.scores.map { ScorePoint(it.date.unixTime / 1000.0, it.value) },
            targets = s.target.intervals.indices.map {
                TargetRow(s.target.intervals[it], s.target.values[it], s.target.targets[it])
            },
            streaks = s.streaks.bestStreaks.map {
                StreakItem(formatShort(it.start), formatShort(it.end), it.length)
            },
            weekdays = weekdays.map { formatter.narrowWeekdayName(it) },
            frequency = s.frequency.frequency.entries
                .sortedByDescending { it.key }
                .map { (month, counts) ->
                    // computeWeekdayFrequency indexes Saturday as 0, Sunday as 1, ...
                    FrequencyRow(
                        "${formatter.shortMonthName(month)} ${month.year}",
                        weekdays.map { counts[(it.daysSinceSunday + 1) % 7] }
                    )
                }
        )
    }

    /** Draws the shared HistoryChart; the chart is kept so [tapHistory] can map a point to a date. */
    fun renderHistory(snapshot: ShowHabitSnapshot, width: Double, height: Double, scale: Double): UIImage {
        val h = snapshot.state.history
        val chart = HistoryChart(
            dateFormatter = formatter,
            firstWeekday = h.firstWeekday,
            paletteColor = h.color,
            series = h.series,
            defaultSquare = h.defaultSquare,
            notesIndicators = h.notesIndicators,
            theme = h.theme,
            today = h.today,
            onDateClickedListener = presenter?.historyCardPresenter ?: object : OnDateClickedListener {}
        )
        history = chart
        return render(width, height, scale) { chart.draw(it) }
    }

    fun renderBar(snapshot: ShowHabitSnapshot, width: Double, height: Double, scale: Double): UIImage {
        val b = snapshot.state.bar
        val chart = BarChart(b.theme, formatter).apply {
            series = mutableListOf(b.entries.map { it.value / 1000.0 })
            colors = mutableListOf(theme.color(b.color.paletteIndex))
            axis = b.entries.map { it.date }
        }
        return render(width, height, scale) { chart.draw(it) }
    }

    /**
     * [x], [y] in points within the last rendered history image. Same as Android: with short-toggle on a
     * tap toggles and a long press opens the entry popup, otherwise the reverse. Numerical habits always
     * get the popup.
     */
    fun tapHistory(x: Double, y: Double) {
        history?.onClick(x, y)
    }

    fun longPressHistory(x: Double, y: Double) {
        history?.onLongClick(x, y)
    }

    fun setScoreSpinner(position: Int) {
        presenter?.scoreCardPresenter?.onSpinnerPosition(position)
    }

    fun setBarSpinner(position: Int) {
        val p = presenter ?: return
        if (p.habit.isNumerical) {
            p.barCardPresenter.onNumericalSpinnerPosition(position)
        } else {
            p.barCardPresenter.onBoolSpinnerPosition(position)
        }
    }

    private fun render(width: Double, height: Double, scale: Double, draw: (Canvas) -> Unit): UIImage {
        val image = IosImage((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
        draw(IosCanvas(image, scale))
        val cg = CGBitmapContextCreateImage(image.context)
        val ui = UIImage.imageWithCGImage(cg, scale, UIImageOrientation.UIImageOrientationUp)
        CGImageRelease(cg)
        return ui
    }

    private fun formatShort(date: org.isoron.platform.time.LocalDate) =
        "${formatter.shortMonthName(date)} ${date.day}, ${date.year}"

    override fun onCommandFinished(command: Command) = onChange()
    override fun refresh() = onChange()
    override fun updateWidgets() {}
    override fun showFeedback() {}
    override fun showHistoryEditorDialog(listener: OnDateClickedListener) {}

    override fun showNumberPopup(value: Double, notes: String, callback: NumberPickerCallback) {
        entryPopup((value * 1000).roundToInt(), notes, callback, null)
    }

    override fun showCheckmarkPopup(selectedValue: Int, notes: String, color: PaletteColor, callback: CheckMarkDialogCallback) {
        entryPopup(selectedValue, notes, null, callback)
    }

    private fun entryPopup(value: Int, notes: String, number: NumberPickerCallback?, check: CheckMarkDialogCallback?) {
        val h = habit ?: return
        val p = graph.preferences
        onEntryPopup(
            EntryRequest(
                h.name, HabitColor(h.color.paletteIndex), h.isNumerical, value, notes, h.unit,
                p.isSkipEnabled, p.areQuestionMarksEnabled, number, check
            )
        )
    }
}

private fun org.isoron.platform.gui.Color.toHex(): String {
    fun c(v: Double) = (v * 255).toInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#${c(red)}${c(green)}${c(blue)}"
}

private fun formatNumber(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else ((v * 100).toLong() / 100.0).toString()

// Mirrors Android's formatFrequency (English only)
private fun formatFrequency(f: Frequency): String = when {
    f.numerator == 1 && f.denominator == 1 -> "Every day"
    f.numerator == 1 && f.denominator == 7 -> "Every week"
    f.numerator == 1 && f.denominator == 30 -> "Every month"
    f.denominator == 7 -> "${f.numerator} times per week"
    f.denominator == 30 || f.denominator == 31 -> "${f.numerator} times per month"
    f.numerator == 1 -> "Every ${f.denominator} days"
    else -> "${f.numerator} times in ${f.denominator} days"
}

/** Debug seed: checks a pattern of past days on [habitId] and adds a numerical "Run" habit with history. */
fun IosAppGraph.seedHistory(habitId: Long) {
    val today = getToday()
    habitList.getById(habitId)?.let { habit ->
        for (i in 1..120) if (i % 3 != 0 && i % 7 != 5) {
            CreateRepetitionCommand(habitList, habit, today.minus(i), Entry.YES_MANUAL, "").run()
        }
    }
    val run = modelFactory.buildHabit().apply {
        name = "Run"
        question = "How many km did you run?"
        color = PaletteColor(11)
        type = HabitType.NUMERICAL
        description = "Morning runs around the park."
        unit = "km"
        targetValue = 5.0
        frequency = Frequency.DAILY
    }
    CreateHabitCommand(modelFactory, habitList, run).run()
    val saved = habitList.first { it.name == "Run" } // the command stores a copy
    for (i in 0..90) if (i % 4 != 2) {
        CreateRepetitionCommand(habitList, saved, today.minus(i), 1000 * (2 + (i * 7) % 6), "").run()
    }
}
