package com.aiturbo

import com.aiturbo.time.TimeService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeServiceTest {

    // 2026-09-09T12:00:00Z — a Wednesday, when Moscow is UTC+3 and New York is UTC-4 (EDT)
    private val clock = Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC)
    private val service = TimeService(clock)

    @Test
    fun `returns Moscow local time`() {
        val response = service.timeFor("Moscow", ZoneId.of("Europe/Moscow"))

        assertEquals("Moscow", response.location)
        assertEquals("Europe/Moscow", response.timezone)
        assertEquals("2026-09-09", response.date)
        assertEquals("15:00:00", response.time)
        assertEquals("+03:00", response.utcOffset)
        assertEquals("Wednesday", response.dayOfWeek)
    }

    @Test
    fun `returns New York local time with daylight saving offset`() {
        val response = service.timeFor("New York", ZoneId.of("America/New_York"))

        assertEquals("08:00:00", response.time)
        assertEquals("-04:00", response.utcOffset)
    }

    @Test
    fun `full dateTime carries the zone offset`() {
        val response = service.timeFor("Tokyo", ZoneId.of("Asia/Tokyo"))

        assertEquals("2026-09-09T21:00:00+09:00", response.dateTime)
    }

    @Test
    fun `crosses the date boundary when the zone is far east`() {
        val response = service.timeFor("Auckland", ZoneId.of("Pacific/Auckland"))

        assertEquals("2026-09-10", response.date) // 12:00Z + 12h = next day
        assertEquals("00:00:00", response.time)
    }

    @Test
    fun `nowIn returns the current local date and time in the zone`() {
        val now = service.nowIn(ZoneId.of("Europe/Moscow"))

        assertEquals(ZoneId.of("Europe/Moscow"), now.zone)
        assertEquals("2026-09-09T15:00", now.toLocalDateTime().toString())
    }
}
