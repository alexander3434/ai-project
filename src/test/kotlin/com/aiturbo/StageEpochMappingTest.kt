package com.aiturbo

import com.aiturbo.db.epochMillisOrNull
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The numeric-column → epoch-millis mapping never throws: a value the stage
 * schema does not really store as an integer epoch degrades (or is truncated)
 * instead of failing the whole lookup — regression for the reported
 * "Bad value for type long" bug (TEXT column read as an epoch).
 */
class StageEpochMappingTest {

    @Test
    fun `an absent value maps to null`() {
        assertEquals(null, epochMillisOrNull(null))
    }

    @Test
    fun `a whole millisecond value maps to the epoch value`() {
        assertEquals(EPOCH_2026_09_21_143211, epochMillisOrNull(BigDecimal(EPOCH_2026_09_21_143211)))
    }

    @Test
    fun `a fractional value never throws and keeps the milliseconds`() {
        assertEquals(1_738_332_765_113L, epochMillisOrNull(BigDecimal("1738332765113.5")))
    }
}
