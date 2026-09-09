package com.aiturbo.time

import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Serializable
data class TimeResponse(
    val location: String,
    val timezone: String,
    val date: String,
    val time: String,
    val dateTime: String,
    val utcOffset: String,
    val dayOfWeek: String,
)

/**
 * Computes the current local time in a given time zone.
 * Uses an injectable [Clock] so tests can freeze time.
 */
class TimeService(private val clock: Clock) {

    fun timeFor(location: String, zoneId: ZoneId): TimeResponse {
        val now = ZonedDateTime.now(clock.withZone(zoneId))
        return TimeResponse(
            location = location,
            timezone = zoneId.id,
            date = now.format(DateTimeFormatter.ISO_LOCAL_DATE),
            time = now.format(DateTimeFormatter.ISO_LOCAL_TIME),
            dateTime = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            utcOffset = now.format(DateTimeFormatter.ofPattern("xxx")),
            dayOfWeek = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
        )
    }
}
