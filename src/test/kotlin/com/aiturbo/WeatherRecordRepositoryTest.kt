package com.aiturbo

import com.aiturbo.db.DATA_FORMAT
import com.aiturbo.db.DatabaseUnavailableException
import com.aiturbo.db.TIME_FORMAT
import com.aiturbo.db.insertWithRetry
import java.sql.SQLException
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WeatherRecordRepositoryTest {

    private val start = LocalDateTime.of(2026, 9, 14, 19, 45, 3)

    @Test
    fun `retries with the timestamp shifted by one second on a UNIQUE conflict`() {
        var attempt = 0
        val id = insertWithRetry(start, maxAttempts = 3) { candidate ->
            attempt++
            if (attempt == 1) throw SQLException("duplicate key value violates unique constraint", "23505")
            assertEquals(LocalDateTime.of(2026, 9, 14, 19, 45, 4), candidate)
            42
        }

        assertEquals(2, attempt)
        assertEquals(42, id)
    }

    @Test
    fun `retries a UNIQUE violation wrapped by the ORM`() {
        var attempt = 0
        val id = insertWithRetry(start, maxAttempts = 3) { candidate ->
            attempt++
            if (attempt == 1) {
                // Exposed wraps the driver's exception: the sqlState is one hop deeper.
                throw SQLException("wrapped by the ORM", SQLException("duplicate key", "23505"))
            }
            assertEquals(LocalDateTime.of(2026, 9, 14, 19, 45, 4), candidate)
            7
        }

        assertEquals(2, attempt)
        assertEquals(7, id)
    }

    @Test
    fun `returns -1 when the UNIQUE conflict persists for all attempts`() {
        val id = insertWithRetry(start, maxAttempts = 3) {
            throw SQLException("duplicate key value violates unique constraint", "23505")
        }

        assertEquals(-1, id)
    }

    @Test
    fun `wraps non-UNIQUE SQL errors into DatabaseUnavailableException`() {
        assertFailsWith<DatabaseUnavailableException> {
            insertWithRetry(start, maxAttempts = 3) {
                throw SQLException("connection refused", "08001")
            }
        }
    }

    @Test
    fun `formats data and time to fit the varchar columns`() {
        assertEquals("2026-09-14 19:45:03", DATA_FORMAT.format(start))
        assertEquals("19:45:03", TIME_FORMAT.format(start))
        assertEquals(19, DATA_FORMAT.format(start).length)
        assertEquals(8, TIME_FORMAT.format(start).length)
    }
}
