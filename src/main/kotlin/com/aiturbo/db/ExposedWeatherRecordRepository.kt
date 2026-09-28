package com.aiturbo.db

import java.sql.SQLException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

internal val DATA_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
internal val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
private const val MAX_INSERT_ATTEMPTS = 3

/**
 * Stores weather request records in the Postgres `users` table through Exposed.
 * `Database.connect` is lazy and every call opens its own transaction
 * (connection), so the application starts fine even when the database is down.
 */
class ExposedWeatherRecordRepository(private val db: Database) : WeatherRecordRepository {

    private val logger = LoggerFactory.getLogger(ExposedWeatherRecordRepository::class.java)

    override fun save(at: LocalDateTime): Int {
        val id = insertWithRetry(at, MAX_INSERT_ATTEMPTS) { candidate ->
            // One transaction (one connection) per attempt: Postgres aborts a transaction
            // after a constraint violation, so the retry must be a fresh transaction.
            transaction(db) {
                UsersTable.insertReturning(listOf(UsersTable.id)) { row ->
                    row[UsersTable.data] = DATA_FORMAT.format(candidate)
                    row[UsersTable.time] = TIME_FORMAT.format(candidate)
                }.single()[UsersTable.id]
            }
        }
        if (id < 0) {
            logger.warn(
                "Weather record was not saved: UNIQUE conflict on 'data' persisted " +
                    "after $MAX_INSERT_ATTEMPTS attempts",
            )
        }
        return id
    }

    override fun recent(limit: Int): List<WeatherRecord> = try {
        transaction(db) {
            UsersTable.selectAll()
                .orderBy(UsersTable.id to SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    WeatherRecord(
                        id = row[UsersTable.id],
                        data = row[UsersTable.data],
                        time = row[UsersTable.time],
                        createdAt = row[UsersTable.createdAt]?.format(DATA_FORMAT),
                    )
                }
        }
    } catch (e: ExposedSQLException) {
        throw DatabaseUnavailableException("Failed to read weather records: ${e.message}", e)
    } catch (e: SQLException) {
        throw DatabaseUnavailableException("Failed to read weather records: ${e.message}", e)
    }
}

/**
 * Inserts via [insert], retrying with the timestamp shifted by one second while the
 * UNIQUE constraint on `data` is hit (two requests within the same second).
 * Returns -1 when the conflict persists for all [maxAttempts].
 *
 * Exposed wraps the driver's [SQLException] into [ExposedSQLException], so the
 * UNIQUE violation is recognized by walking the cause chain for sqlState `23505`;
 * a direct [SQLException] still matches on the first hop.
 */
internal fun insertWithRetry(
    start: LocalDateTime,
    maxAttempts: Int,
    insert: (LocalDateTime) -> Int,
): Int {
    var candidate = start
    var attempt = 0
    while (true) {
        attempt++
        try {
            return insert(candidate)
        } catch (e: SQLException) {
            if (!e.isUniqueViolation()) {
                throw DatabaseUnavailableException("Failed to save weather record: ${e.message}", e)
            }
            if (attempt >= maxAttempts) return -1
            candidate = candidate.plusSeconds(1)
        }
    }
}

private fun Throwable.isUniqueViolation(): Boolean =
    generateSequence(this) { it.cause }.any { it is SQLException && it.sqlState == UNIQUE_VIOLATION_SQL_STATE }
