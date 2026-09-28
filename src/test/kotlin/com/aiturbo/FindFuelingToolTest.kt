package com.aiturbo

import com.aiturbo.db.DatabaseUnavailableException
import com.aiturbo.db.FuelingLookupResult
import com.aiturbo.db.FuelingMatch
import com.aiturbo.db.FuelingRecord
import com.aiturbo.db.FuelingSource
import com.aiturbo.db.PartnerFuelingEvent
import com.aiturbo.db.RelatedRows
import com.aiturbo.db.StageFuelingRepository
import com.aiturbo.tools.FindFuelingArgs
import com.aiturbo.fueling.InvalidFuelingIdException
import com.aiturbo.fueling.StageDatabaseUnavailableException
import com.aiturbo.log.CallTrace
import com.aiturbo.tools.FindFuelingTool
import com.aiturbo.tools.ToolSpec
import com.aiturbo.tools.ToolSpecLoader
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class FindFuelingToolTest {

    private val spec: ToolSpec = ToolSpecLoader.load(ToolSpecLoader.FUELING_RESOURCE_PATH)

    private fun tool(repository: StageFuelingRepository) = FindFuelingTool(repository, spec)

    @Test
    fun `the descriptor name and description come from the shipped resource`() {
        val descriptor = tool(FakeStageFuelingRepository()).descriptor

        assertEquals("find_fueling", descriptor.name)
        assertEquals(spec.description, descriptor.description)
        assertTrue(descriptor.description.contains("пролив"), descriptor.description)
    }

    @Test
    fun `a valid id is looked up once and logged as found`() = runTest {
        val repository = FakeStageFuelingRepository()

        LogCapture().use { capture ->
            val result = tool(repository).execute(FindFuelingArgs(SAMPLE_FUELING_ID))

            assertTrue(result.startsWith("НАЙДЕНО: 1 (fuelings_archive)"), result)
            assertTrue(result.contains("  fueling_id=$SAMPLE_FUELING_ID"), result)
            assertEquals(listOf(SAMPLE_FUELING_ID), repository.calls)

            val line = capture.lines().single()
            assertEquals(
                "req=- stage=db tool=find_fueling lookup=found records=1 tables=fuelings_archive " +
                    "events=2 tokens=1 feedback=0",
                line,
            )
        }
    }

    @Test
    fun `an uppercase or padded id reaches the repository as the canonical lowercase guid`() = runTest {
        val repository = FakeStageFuelingRepository()

        tool(repository).execute(FindFuelingArgs("  ${SAMPLE_FUELING_ID.uppercase()}  "))

        assertEquals(listOf(SAMPLE_FUELING_ID), repository.calls)
    }

    @Test
    fun `the lookup runs on Dispatchers IO, not on the caller thread`() = runTest {
        val callerThread = Thread.currentThread().name
        var lookupThread: String? = null
        val repository = object : StageFuelingRepository {
            override fun findByFuelingId(fuelingId: String): FuelingLookupResult {
                lookupThread = Thread.currentThread().name
                return sampleLookupResult()
            }
        }

        tool(repository).execute(FindFuelingArgs(SAMPLE_FUELING_ID))

        assertNotEquals(callerThread, lookupThread, "the blocking lookup must be moved off the caller")
    }

    @Test
    fun `the stage=db line carries the correlation id of the context`() = runTest {
        LogCapture().use { capture ->
            withContext(CallTrace("abcd1234")) {
                tool(FakeStageFuelingRepository()).execute(FindFuelingArgs(SAMPLE_FUELING_ID))
            }

            assertTrue(capture.lines().single().startsWith("req=abcd1234 stage=db"), capture.lines().toString())
        }
    }

    @Test
    fun `every match is named in the log line`() = runTest {
        val result = FuelingLookupResult(
            fuelingId = SAMPLE_FUELING_ID,
            matches = listOf(
                FuelingMatch(FuelingSource.FUELINGS, sampleFuelingRecord()),
                FuelingMatch(FuelingSource.DROP, sampleFuelingRecord()),
            ),
            related = sampleRelatedRows(),
        )

        LogCapture().use { capture ->
            val text = tool(FakeStageFuelingRepository(result)).execute(FindFuelingArgs(SAMPLE_FUELING_ID))

            assertTrue(text.startsWith("НАЙДЕНО: 2 (fuelings, fuelings_drop)"), text.lineSequence().first())
            assertEquals(
                "req=- stage=db tool=find_fueling lookup=found records=2 tables=fuelings,fuelings_drop " +
                    "events=2 tokens=1 feedback=0",
                capture.lines().single(),
            )
        }
    }

    @Test
    fun `a capped related list is marked in the log line but not in the text totals`() = runTest {
        val related = RelatedRows(
            events = List(80) { index ->
                PartnerFuelingEvent(eventId = "evt-$index", createdAt = EPOCH_2026_09_21_143300)
            }
        )

        LogCapture().use { capture ->
            val text = tool(FakeStageFuelingRepository(sampleLookupResult(related = related)))
                .execute(FindFuelingArgs(SAMPLE_FUELING_ID))

            assertTrue(text.contains("events: total=80 shown=50"), text)
            assertTrue(capture.lines().single().contains("events=80"), capture.lines().single())
            assertTrue(capture.lines().single().endsWith("capped=true"), capture.lines().single())
        }
    }

    @Test
    fun `a not found lookup names the searched tables and returns a normal result`() = runTest {
        val repository = FakeStageFuelingRepository(FuelingLookupResult(fuelingId = UNKNOWN_FUELING_ID))

        LogCapture().use { capture ->
            val text = tool(repository).execute(FindFuelingArgs(UNKNOWN_FUELING_ID))

            assertTrue(text.startsWith("НЕ НАЙДЕНО: заказ $UNKNOWN_FUELING_ID"), text)
            assertTrue(text.contains("fuelings, fuelings_archive, fuelings_drop"), text)
            assertEquals(listOf(UNKNOWN_FUELING_ID), repository.calls)
            assertEquals(
                "req=- stage=db tool=find_fueling lookup=not_found records=0 " +
                    "tables=fuelings,fuelings_archive,fuelings_drop",
                capture.lines().single(),
            )
        }
    }

    @Test
    fun `an invalid id is rejected before any lookup and writes no db line`() = runTest {
        val repository = FakeStageFuelingRepository()

        LogCapture().use { capture ->
            val error = assertFailsWith<InvalidFuelingIdException> {
                tool(repository).execute(FindFuelingArgs("12345"))
            }

            assertTrue(error.message!!.contains("12345"), error.message)
            assertTrue(error.message!!.contains("не является корректным GUID"), error.message)
            assertEquals(emptyList(), repository.calls)
            assertEquals(emptyList(), capture.lines())
        }
    }

    @Test
    fun `an empty id is rejected before any lookup`() = runTest {
        val repository = FakeStageFuelingRepository()

        assertFailsWith<InvalidFuelingIdException> { tool(repository).execute(FindFuelingArgs("   ")) }

        assertEquals(emptyList(), repository.calls)
    }

    @Test
    fun `a text vendor_transaction_date is found and rendered as stored`() = runTest {
        // Regression: the stage column is TEXT, not numeric — reading it as an
        // epoch used to throw and degrade the whole lookup to "unavailable".
        val record = FuelingRecord(
            fuelingId = SAMPLE_FUELING_ID,
            vendorTransactionDate = VENDOR_TRANSACTION_DATE_TEXT,
        )
        val repository = FakeStageFuelingRepository(
            sampleLookupResult(record = record, related = emptyRelatedRows()),
        )

        LogCapture().use { capture ->
            val text = tool(repository).execute(FindFuelingArgs(SAMPLE_FUELING_ID))

            assertTrue(text.startsWith("НАЙДЕНО: 1 (fuelings_archive)"), text)
            assertTrue(text.contains("  vendor_transaction_date=$VENDOR_TRANSACTION_DATE_TEXT"), text)
            assertEquals(listOf(SAMPLE_FUELING_ID), repository.calls)
            assertEquals(
                "req=- stage=db tool=find_fueling lookup=found records=1 tables=fuelings_archive " +
                    "events=0 tokens=0 feedback=0",
                capture.lines().single(),
            )
        }
    }

    @Test
    fun `an unavailable stage database is logged and rethrown as the stage failure`() = runTest {
        val repository = FakeStageFuelingRepository().apply { unavailableReason = "connection refused" }

        LogCapture().use { capture ->
            val error = assertFailsWith<StageDatabaseUnavailableException> {
                tool(repository).execute(FindFuelingArgs(SAMPLE_FUELING_ID))
            }

            assertTrue(error.message!!.contains("временно недоступны"), error.message)
            assertTrue(error.cause is DatabaseUnavailableException, error.cause?.toString())
            val line = capture.lines().single()
            assertEquals(
                "req=- stage=db tool=find_fueling lookup=unavailable " +
                    "reason=\"stage lookup failed: connection refused\"",
                line,
            )
        }
    }
}
