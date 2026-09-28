package com.aiturbo.db

import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/** The database is unreachable or the query failed — mapped to 503 on the routes. */
class DatabaseUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A row of the `users` table:
 * [id] — auto-incremented by the database sequence on every insert,
 * [data] — local date and time in the requested region ("2026-09-14 19:45:03"),
 * [time] — current local time in the region ("19:45:03"),
 * [createdAt] — the moment the user asked (database `now()` default).
 */
@Serializable
data class WeatherRecord(
    val id: Int,
    val data: String,
    val time: String? = null,
    val createdAt: String? = null,
)

interface WeatherRecordRepository {

    /**
     * Inserts a weather request record and returns the generated id,
     * or -1 when the UNIQUE conflict on [WeatherRecord.data] could not
     * be resolved within the retry budget.
     */
    fun save(at: LocalDateTime): Int

    /** Returns the most recent records, newest first. */
    fun recent(limit: Int): List<WeatherRecord>
}
