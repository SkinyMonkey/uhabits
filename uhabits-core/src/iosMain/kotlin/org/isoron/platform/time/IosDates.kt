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

package org.isoron.platform.time

import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.defaultTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeZoneForSecondsFromGMT

actual fun computeToday(hourOffset: Int, minuteOffset: Int): LocalDate {
    val now = NSDate()
    val nowMillis = (now.timeIntervalSince1970 * 1000).toLong()
    val localMillis = nowMillis + NSTimeZone.defaultTimeZone.secondsFromGMTForDate(now) * 1000L
    return todayAt(localMillis, hourOffset, minuteOffset)
}

/** The habit "day" containing [localMillis] (wall-clock ms since epoch), when days start at hourOffset:minuteOffset. */
fun todayAt(localMillis: Long, hourOffset: Int, minuteOffset: Int): LocalDate {
    val offsetMillis = hourOffset * 3600000L + minuteOffset * 60000L
    val daysSinceEpoch = (localMillis - offsetMillis).floorDiv(86400000L)
    return LocalDate((daysSinceEpoch - 10957).toInt())
}

actual fun getFirstWeekdayNumberAccordingToLocale(): Int =
    NSCalendar.currentCalendar.firstWeekday.toInt()

class IosLocalDateFormatter(private val locale: NSLocale = NSLocale.currentLocale) : LocalDateFormatter {
    private fun fmt(pattern: String, date: LocalDate): String {
        val f = NSDateFormatter()
        f.locale = locale
        f.timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
        f.setLocalizedDateFormatFromTemplate(pattern)
        return f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(date.unixTime / 1000.0))
    }

    // 2000-01-02 was a Sunday
    private fun sample(weekday: DayOfWeek) = LocalDate(2000, 1, 2 + weekday.daysSinceSunday)

    override fun narrowWeekdayName(weekday: DayOfWeek) = narrowWeekdayName(sample(weekday))
    override fun narrowWeekdayName(date: LocalDate) = fmt("EEEEE", date)
    override fun shortWeekdayName(weekday: DayOfWeek) = shortWeekdayName(sample(weekday))
    override fun shortWeekdayName(date: LocalDate) = fmt("EEE", date)
    override fun shortMonthName(date: LocalDate) = fmt("LLL", date)
    override fun longWeekdayName(weekday: DayOfWeek) = fmt("EEEE", sample(weekday))
    override fun longMonthName(date: LocalDate) = fmt("LLLL", date)
}
