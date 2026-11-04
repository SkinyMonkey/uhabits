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

import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.commands.ChangeHabitColorCommand
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitMatcher
import platform.Foundation.NSDateComponents
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionNone
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNNotificationTrigger
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

/** One repeating weekly notification. [weekday] uses NSCalendar numbering: 1 = Sunday ... 7 = Saturday. */
data class ReminderTrigger(val habitId: Long, val weekday: Int, val hour: Int, val minute: Int) {
    val identifier get() = "reminder-$habitId-$weekday"
}

/** The weekly triggers for a habit's reminder; empty when it has none, is archived or unsaved. */
fun reminderTriggers(habit: Habit): List<ReminderTrigger> {
    val id = habit.id ?: return emptyList()
    val reminder = habit.reminder ?: return emptyList()
    if (habit.isArchived) return emptyList()
    // WeekdayList index 0 = Saturday, 1 = Sunday, ..., 6 = Friday (see NotificationTray.shouldShowReminderToday)
    return reminder.days.toArray().withIndex().filter { it.value }.map { (i, _) ->
        ReminderTrigger(id, if (i == 0) 7 else i, reminder.hour, reminder.minute)
    }
}

/**
 * Local notifications for habit reminders, the iOS counterpart of ReminderScheduler + NotificationTray.
 * Reschedules everything on start and after each command. The notification delegate lives in Swift and
 * forwards actions to [handleAction].
 */
class IosReminders(private val graph: IosAppGraph) : CommandRunner.Listener {
    private val center = UNUserNotificationCenter.currentNotificationCenter()

    init {
        val check = UNNotificationAction.actionWithIdentifier(ACTION_CHECK, "Check", UNNotificationActionOptionNone)
        val later = UNNotificationAction.actionWithIdentifier(ACTION_SNOOZE, "Later", UNNotificationActionOptionNone)
        center.setNotificationCategories(
            setOf(
                UNNotificationCategory.categoryWithIdentifier(
                    CATEGORY,
                    listOf(check, later),
                    emptyList<String>(),
                    UNNotificationCategoryOptionNone
                )
            )
        )
        graph.commandRunner.addListener(this)
    }

    override fun onCommandFinished(command: Command) {
        if (command is CreateRepetitionCommand || command is ChangeHabitColorCommand) return
        scheduleAll()
    }

    fun scheduleAll() {
        val habits = graph.habitList.getFiltered(HabitMatcher.WITH_ALARM).toList()
        val requests = habits.flatMap { h -> reminderTriggers(h).map { request(h, it.identifier, calendarTrigger(it)) } }
        if (requests.isNotEmpty()) {
            center.requestAuthorizationWithOptions(
                UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
            ) { _, _ -> }
        }
        // Replace only our weekly reminders; snoozed one-offs survive until they fire
        center.getPendingNotificationRequestsWithCompletionHandler { pending ->
            val stale = pending.orEmpty().map { (it as UNNotificationRequest).identifier }.filter { it.startsWith("reminder-") }
            center.removePendingNotificationRequestsWithIdentifiers(stale)
            for (r in requests) center.addNotificationRequest(r, null)
            println("[uhabits] reminders: scheduled ${requests.size} weekly notification(s) for ${habits.size} habit(s)")
        }
    }

    /** Debug: prints every pending request. */
    fun logPending() {
        center.getPendingNotificationRequestsWithCompletionHandler { pending ->
            val lines = pending.orEmpty().map { r ->
                r as UNNotificationRequest
                val c = (r.trigger as? UNCalendarNotificationTrigger)?.dateComponents
                val time = c?.let { " weekday=${it.weekday} ${it.hour}:${it.minute.toString().padStart(2, '0')}" } ?: ""
                "${r.identifier} '${r.content.title}'$time"
            }
            println("[uhabits] pending notifications (${lines.size}): $lines")
        }
    }

    /** Called by the notification delegate. Unknown actions (e.g. a plain tap) are ignored. */
    fun handleAction(actionIdentifier: String, habitId: Long) {
        val habit = graph.habitList.getById(habitId) ?: return
        when (actionIdentifier) {
            ACTION_CHECK -> {
                setToday(todayFor(graph.preferences))
                val today = getToday()
                val notes = habit.computedEntries.get(today).notes
                graph.commandRunner.run(CreateRepetitionCommand(graph.habitList, habit, today, Entry.YES_MANUAL, notes))
            }
            ACTION_SNOOZE -> {
                val seconds = IosSettings().snoozeMinutes * 60.0
                val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false)
                center.addNotificationRequest(request(habit, "snooze-$habitId", trigger), null)
            }
        }
    }

    private fun request(habit: Habit, identifier: String, trigger: UNNotificationTrigger): UNNotificationRequest {
        val content = UNMutableNotificationContent().apply {
            setTitle(habit.name)
            setBody(habit.question.ifBlank { habit.description })
            setSound(UNNotificationSound.defaultSound)
            setCategoryIdentifier(CATEGORY)
            setUserInfo(mapOf<Any?, Any?>(HABIT_ID_KEY to habit.id!!))
        }
        return UNNotificationRequest.requestWithIdentifier(identifier, content, trigger)
    }

    private fun calendarTrigger(t: ReminderTrigger): UNNotificationTrigger {
        val components = NSDateComponents().apply {
            weekday = t.weekday.toLong()
            hour = t.hour.toLong()
            minute = t.minute.toLong()
        }
        return UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, repeats = true)
    }

    companion object {
        const val CATEGORY = "HABIT_REMINDER"
        const val ACTION_CHECK = "CHECK"
        const val ACTION_SNOOZE = "SNOOZE"
        const val HABIT_ID_KEY = "habitId"
    }
}
