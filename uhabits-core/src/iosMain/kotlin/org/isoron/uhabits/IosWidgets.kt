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
import org.isoron.platform.gui.IosCanvas
import org.isoron.platform.gui.IosImage
import org.isoron.platform.time.IosLocalDateFormatter
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.DATABASE_FILENAME
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.NotificationTray
import org.isoron.uhabits.core.ui.screens.habits.show.views.HistoryCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.ScoreCardPresenter
import org.isoron.uhabits.core.ui.views.DarkTheme
import org.isoron.uhabits.core.ui.views.HistoryChart
import org.isoron.uhabits.core.ui.views.LightTheme
import org.isoron.uhabits.core.ui.views.toShortString
import org.isoron.uhabits.core.ui.widgets.WidgetBehavior
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGImageRelease
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIImage
import platform.UIKit.UIImageOrientation

/** One habit as the widgets draw it. [scores] are oldest first, [streaks] longest first. */
class WidgetHabit internal constructor(habit: Habit, prefs: Preferences, today: LocalDate) {
    val id: Long = habit.id!!
    val name = habit.name
    val color = HabitColor(habit.color.paletteIndex)
    val isNumerical = habit.isNumerical
    val unit = habit.unit
    val todayValue = habit.computedEntries.get(today).value
    val isCompletedToday = habit.isCompletedToday()

    /** Numerical habits: today's value as the list screen prints it. */
    val numberLabel = (todayValue / 1000.0).toShortString()
    val score = habit.scores[today].value

    /** Same buckets as the show-habit score card (its spinner preference), like Android's ScoreWidget. */
    val scores: List<Double> = ScoreCardPresenter
        .buildState(habit, prefs.firstWeekdayInt, prefs.scoreCardSpinnerPosition, LightTheme())
        .scores.take(MAX_SCORES).map { it.value }.reversed()
    val streaks: List<StreakItem> = habit.streaks.getBest(MAX_STREAKS).map {
        StreakItem(it.start.shortString(), it.end.shortString(), it.length)
    }

    private companion object {
        const val MAX_SCORES = 24
        const val MAX_STREAKS = 5
        val formatter = IosLocalDateFormatter()
        fun LocalDate.shortString() = "${formatter.shortMonthName(this)} $day"
    }
}

/**
 * Data for the WidgetKit extension, the counterpart of Android's BaseWidget + WidgetBehavior.
 * Each request (timeline, configuration query, toggle) opens its own short-lived graph on the shared
 * database and calls [close] when done. Commands run synchronously: the extension may exit right after.
 */
class IosWidgets(resourceRoot: String, userRoot: String, private val defaults: NSUserDefaults) {
    val graph = IosAppGraph(resourceRoot, userRoot, Preferences(UserDefaultsStorage(defaults)))

    private val runner = object : CommandRunner(graph.taskRunner) {
        override fun run(command: Command) {
            command.run()
            notifyListeners(command)
        }
    }

    // ponytail: no-op tray; iOS reminders are weekly requests owned by the app process (IosReminders)
    private val tray = NotificationTray(
        graph.taskRunner,
        runner,
        graph.preferences,
        object : NotificationTray.SystemTray {
            override fun removeNotification(notificationId: Int) {}
            override fun showNotification(habit: Habit, notificationId: Int, date: LocalDate, reminderTime: Long) {}
            override fun log(msg: String) {}
        }
    )
    private val behavior = WidgetBehavior(graph.habitList, runner, tray, graph.preferences)

    /** Active habits in list order. */
    fun habits(): List<WidgetHabit> {
        val today = getToday()
        return graph.habitList.filter { !it.isArchived }.map { WidgetHabit(it, graph.preferences, today) }
    }

    fun habit(id: Long): WidgetHabit? = graph.habitList.getById(id)?.let { WidgetHabit(it, graph.preferences, getToday()) }

    /**
     * Toggles today's checkmark like a tap on Android's checkmark widget (WidgetBehavior.onToggleRepetition).
     * Numerical habits need a number, so they are left alone. Returns whether anything was written.
     */
    fun toggle(id: Long): Boolean {
        val habit = graph.habitList.getById(id) ?: return false
        if (habit.isNumerical) return false
        setToday(todayFor(graph.preferences))
        behavior.onToggleRepetition(habit, getToday())
        defaults.setInteger(defaults.integerForKey(DATA_VERSION_KEY) + 1, DATA_VERSION_KEY)
        return true
    }

    /** The shared HistoryChart, like Android's HistoryWidget; sizes in points. */
    fun renderHistory(id: Long, width: Double, height: Double, scale: Double, dark: Boolean): UIImage? {
        val habit = graph.habitList.getById(id) ?: return null
        val theme = if (dark) DarkTheme() else LightTheme()
        val s = HistoryCardPresenter.buildState(habit, graph.preferences.firstWeekday, theme)
        val chart = HistoryChart(
            dateFormatter = IosLocalDateFormatter(),
            firstWeekday = s.firstWeekday,
            paletteColor = s.color,
            series = s.series,
            defaultSquare = s.defaultSquare,
            notesIndicators = s.notesIndicators,
            theme = s.theme,
            today = s.today
        )
        val image = IosImage((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
        chart.draw(IosCanvas(image, scale))
        val cg = CGBitmapContextCreateImage(image.context)
        val ui = UIImage.imageWithCGImage(cg, scale, UIImageOrientation.UIImageOrientationUp)
        CGImageRelease(cg)
        return ui
    }

    /** So Swift can schedule the next timeline at the real start of "tomorrow". */
    val midnightDelayHours: Int get() = graph.preferences.midnightDelayHours

    fun close() = graph.modelFactory.database.close()

    companion object {
        /**
         * The app group shared with the widgets: "group." + the app's bundle id. Derived at runtime so the
         * bundle prefix can change per developer team (UHABITS_BUNDLE_PREFIX build setting, also used by the
         * entitlements). The widget extension's bundle id is the app's plus ".widgets".
         */
        val GROUP_ID: String
            get() = "group." + (NSBundle.mainBundle.bundleIdentifier ?: "org.isoron.uhabits.ios").removeSuffix(".widgets")
        internal const val DATA_VERSION_KEY = "widget_data_version"
        private val DB_FILES = listOf("", "-journal", "-wal", "-shm").map { DATABASE_FILENAME + it }

        /**
         * The directory where both the app and the widgets open the database: the app-group container.
         * On first use, moves an existing database out of [legacyRoot] (the app's Documents folder).
         * Falls back to [legacyRoot] when the app group is unavailable (no entitlement); widgets then see no data.
         */
        fun sharedUserRoot(groupId: String, legacyRoot: String): String {
            val group = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(groupId)?.path
                ?: return legacyRoot
            migrateDatabase(legacyRoot, group)
            return group
        }

        /** Moves the database files from [from] to [to], unless [to] already has a database. */
        fun migrateDatabase(from: String, to: String): Boolean {
            val fm = NSFileManager.defaultManager
            if (fm.fileExistsAtPath("$to/$DATABASE_FILENAME") || !fm.fileExistsAtPath("$from/$DATABASE_FILENAME")) return false
            for (name in DB_FILES) {
                if (!fm.fileExistsAtPath("$from/$name")) continue
                check(fm.moveItemAtPath("$from/$name", "$to/$name", null)) { "Could not move $name to $to" }
            }
            println("[uhabits] moved database from $from to $to")
            return true
        }

        /** The app-group defaults (the extension's copy of the preferences, see [IosWidgetSync]). */
        fun groupDefaults(groupId: String): NSUserDefaults = NSUserDefaults(suiteName = groupId)
    }
}

/**
 * App side of the widgets: keeps the app-group copy of the preferences current, says when timelines
 * are stale ([onChange]; hook WidgetCenter.reloadAllTimelines to it) and picks up widget toggles.
 */
class IosWidgetSync(
    private val graph: IosAppGraph,
    private val groupId: String,
    private val onChange: () -> Unit
) : CommandRunner.Listener {
    private val group = IosWidgets.groupDefaults(groupId)
    private var seenVersion = group.integerForKey(IosWidgets.DATA_VERSION_KEY)

    init {
        graph.commandRunner.addListener(this)
        mirrorPreferences(NSUserDefaults.standardUserDefaults)
    }

    override fun onCommandFinished(command: Command) = onChange()

    /** Copies the pref_* keys of [from] into the app group, where the extension reads them. Call after settings change. */
    fun mirrorPreferences(from: NSUserDefaults) {
        // The group's own domain only: lookups on a suite also fall back to the app's domain
        fun prefs(d: Map<Any?, *>?) = d.orEmpty().filterKeys { (it as String).startsWith("pref_") }
        val old = prefs(group.persistentDomainForName(groupId))
        val new = prefs(from.dictionaryRepresentation())
        val stale = new.filter { (k, v) -> !same(old[k], v) }
        val removed = old.keys - new.keys
        if (stale.isEmpty() && removed.isEmpty()) return
        for (k in removed) group.removeObjectForKey(k as String)
        for ((k, v) in stale) group.setObject(v, k as String)
        onChange()
    }

    // A bool read back from the plist bridges as the number 1, not as true
    private fun same(a: Any?, b: Any?): Boolean {
        fun num(x: Any?) = when (x) {
            is Boolean -> if (x) 1.0 else 0.0
            is Number -> x.toDouble()
            else -> null
        }
        return a == b || (num(a) != null && num(a) == num(b))
    }

    /** Reloads the habits from disk if a widget wrote since the last call. Call when the app becomes active. */
    fun syncFromWidgets(): Boolean {
        val version = group.integerForKey(IosWidgets.DATA_VERSION_KEY)
        if (version == seenVersion) return false
        seenVersion = version
        setToday(todayFor(graph.preferences))
        (graph.habitList as SQLiteHabitList).reload()
        for (h in graph.habitList) h.recompute()
        graph.commandRunner.notifyListeners(DataImportedCommand)
        return true
    }
}
