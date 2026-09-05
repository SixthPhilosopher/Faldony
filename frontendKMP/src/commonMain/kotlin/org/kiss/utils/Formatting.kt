package org.kiss.utils

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.until

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private val localZone: TimeZone = TimeZone.currentSystemDefault()

/** "Aug 27, 2026" in the user's local time zone. */
fun formatDate(instant: Instant): String {
    val dt = instant.toLocalDateTime(localZone)
    return "${MONTHS[dt.monthNumber - 1]} ${dt.dayOfMonth}, ${dt.year}"
}

/** "Aug 27, 2026, 14:35" in the user's local time zone. */
fun formatDateTime(instant: Instant): String {
    val dt = instant.toLocalDateTime(localZone)
    val hh = dt.hour.toString().padStart(2, '0')
    val mm = dt.minute.toString().padStart(2, '0')
    return "${formatDate(instant)}, $hh:$mm"
}

/** "42s", "2m 31s", "1h 5m" — wall-clock elapsed since [since]. */
fun formatElapsed(since: Instant): String {
    val seconds = since.until(Clock.System.now(), DateTimeUnit.SECOND).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
    }
}