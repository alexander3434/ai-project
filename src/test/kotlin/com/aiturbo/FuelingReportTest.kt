package com.aiturbo

import com.aiturbo.db.BelkaToken
import com.aiturbo.db.FuelingFeedback
import com.aiturbo.db.FuelingLookupResult
import com.aiturbo.db.FuelingMatch
import com.aiturbo.db.FuelingRecord
import com.aiturbo.db.FuelingSource
import com.aiturbo.db.PartnerFuelingEvent
import com.aiturbo.db.RelatedRows
import com.aiturbo.fueling.FuelingReport
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FuelingReportTest {

    @Test
    fun `the found header names the source tables and every record field is present`() {
        val text = FuelingReport.render(sampleLookupResult(source = FuelingSource.ARCHIVE))

        assertTrue(text.startsWith("НАЙДЕНО: 1 (fuelings_archive)"), text.lineSequence().first())
        assertTrue(text.contains("record[1] table=fuelings_archive"), text)

        val fields = listOf(
            "  fueling_id=$SAMPLE_FUELING_ID",
            "  vendor_fueling_order_id=VFO-778812",
            "  user_id=user-42",
            "  status=SUCCESS",
            "  amount=45.00",
            "  actual_amount=45.00",
            "  discount_fuel_price=1.50",
            "  vendor_fuel_price=44.90",
            "  fuel_type=AI-95",
            "  gas_station_id=21",
            "  gas_pump_id=12",
            "  refueling_gun_id=3",
            "  fuel_reservation_key=reserve-abc",
            "  fueling_type=FULL",
            "  fueling_payment_type=CARD",
            "  failed_reason=-",
            """  fueled_orders={"orders":[{"orderId":"order-1"}]}""",
            """  extra={"source":"stage"}""",
            "  created_at=2026-09-21 14:32:11 UTC",
            "  updated_at=2026-09-21 14:32:41 UTC",
            "  finished_at=2026-09-21 14:32:41 UTC",
            "  vendor_transaction_date=$VENDOR_TRANSACTION_DATE_TEXT",
        )
        fields.forEach { assertTrue(text.contains(it), "missing '$it' in:\n$text") }
        assertFalse(Regex("""\b\d{13}\b""").containsMatchIn(text), "no raw epoch millis may leak:\n$text")
    }

    @Test
    fun `the related blocks carry totals, shown counts and readable times`() {
        val text = FuelingReport.render(sampleLookupResult())

        assertTrue(text.contains("events: total=2 shown=2"), text)
        assertTrue(text.contains("feedback: total=0 shown=0"), text)
        assertTrue(text.contains("belka_tokens: total=1 shown=1"), text)
        assertTrue(text.contains("  event[1] event_id=evt-2 partner_id=partner-9"), text)
        assertTrue(text.contains("event_name=FUELING_DELIVERED delivery_status=DELIVERED"), text)
        assertTrue(text.contains("created_at=2026-09-21 14:33:00 UTC"), text)
        assertTrue(text.contains("""data={"attempt":2}"""), text)
        assertTrue(text.contains("  event[2] event_id=evt-1"), text)
        assertTrue(text.contains("data=-"), text)
        assertTrue(text.contains("  token[1] token=belka-token-1 error_type=- created_at=2026-09-21 14:32:12 UTC"), text)
    }

    @Test
    fun `a feedback row renders its columns`() {
        val related = RelatedRows(
            feedback = listOf(
                FuelingFeedback(
                    fuelingFeedbackId = "fb-1",
                    userId = "user-42",
                    fuelingId = SAMPLE_FUELING_ID,
                    reasonId = "reason-7",
                    reasonMessage = "не дошло топливо",
                    requestedAt = EPOCH_2026_09_21_143300,
                )
            )
        )

        val text = FuelingReport.render(sampleLookupResult(related = related))

        assertTrue(text.contains("feedback: total=1 shown=1"), text)
        assertTrue(
            text.contains(
                "  feedback[1] fueling_feedback_id=fb-1 user_id=user-42 reason_id=reason-7 " +
                    "reason_message=не дошло топливо requested_at=2026-09-21 14:33:00 UTC"
            ),
            text,
        )
    }

    @Test
    fun `null values render as a dash`() {
        val record = FuelingRecord(fuelingId = SAMPLE_FUELING_ID)

        val text = FuelingReport.render(sampleLookupResult(record = record, related = emptyRelatedRows()))

        assertTrue(text.contains("  vendor_fueling_order_id=-"), text)
        assertTrue(text.contains("  status=-"), text)
        assertTrue(text.contains("  amount=-"), text)
        assertTrue(text.contains("  fueled_orders=-"), text)
        assertTrue(text.contains("  extra=-"), text)
        assertTrue(text.contains("  created_at=-"), text)
        assertTrue(text.contains("  vendor_transaction_date=-"), text)
    }

    @Test
    fun `the text vendor_transaction_date renders as stored, not as an epoch`() {
        val text = FuelingReport.render(sampleLookupResult())

        assertTrue(text.contains("  vendor_transaction_date=$VENDOR_TRANSACTION_DATE_TEXT"), text)
        assertFalse(
            text.contains("vendor_transaction_date=2025-01-31 14:12:45 UTC"),
            "a text column must not be converted as an epoch:\n$text",
        )
    }

    @Test
    fun `a not found result names every searched table`() {
        val text = FuelingReport.render(FuelingLookupResult(fuelingId = UNKNOWN_FUELING_ID))

        assertEquals(
            "НЕ НАЙДЕНО: заказ $UNKNOWN_FUELING_ID отсутствует в fuelings, fuelings_archive, fuelings_drop " +
                "(stage, база fueling).",
            text,
        )
    }

    @Test
    fun `epoch values outside the plausibility window stay raw`() {
        val text = FuelingReport.render(
            sampleLookupResult(record = outOfWindowRecord(), related = emptyRelatedRows())
        )

        assertTrue(text.contains("created_at=$EPOCH_BEFORE_WINDOW (raw)"), text)
        assertTrue(text.contains("updated_at=$EPOCH_AFTER_WINDOW (raw)"), text)
        assertTrue(text.contains("finished_at=-"), text)
    }

    @Test
    fun `formatEpochMillis converts the window boundaries and rejects values outside it`() {
        assertEquals("-", FuelingReport.formatEpochMillis(null))
        assertEquals("2000-01-01 00:00:00 UTC", FuelingReport.formatEpochMillis(946_684_800_000L))
        assertEquals("2100-01-01 00:00:00 UTC", FuelingReport.formatEpochMillis(4_102_444_800_000L))
        assertEquals("946684799999 (raw)", FuelingReport.formatEpochMillis(946_684_799_999L))
        assertEquals("4102444800001 (raw)", FuelingReport.formatEpochMillis(4_102_444_800_001L))
    }

    @Test
    fun `a large jsonb field is capped with the truncation marker`() {
        val huge = "x".repeat(5_000)

        val text = FuelingReport.render(
            sampleLookupResult(record = sampleFuelingRecord(extra = huge), related = emptyRelatedRows())
        )

        assertTrue(text.contains("…[truncated, 5000 chars total]"), text.takeLast(200))
        assertTrue(text.contains("  extra=" + "x".repeat(FuelingReport.MAX_JSONB_CHARS)), text.take(20_000))
        assertFalse(text.contains("x".repeat(FuelingReport.MAX_JSONB_CHARS + 1)), "the raw payload must be cut")
    }

    @Test
    fun `related rows are capped per table while the real totals are stated`() {
        val events = List(FuelingReport.MAX_RENDERED_RELATED_ROWS + 10) { index ->
            PartnerFuelingEvent(
                eventId = "evt-$index",
                eventName = "EVENT_$index",
                createdAt = EPOCH_2026_09_21_143300,
            )
        }
        val tokens = List(3) { index -> BelkaToken(token = "token-$index") }
        val related = RelatedRows(events = events, tokens = tokens)

        val text = FuelingReport.render(sampleLookupResult(related = related))

        assertTrue(text.contains("events: total=60 shown=50"), text)
        assertTrue(text.contains("event[50] event_id=evt-49"), text)
        assertFalse(text.contains("event[51]"), text)
        assertTrue(text.contains("belka_tokens: total=3 shown=3"), text)
        assertTrue(text.contains("token[3] token=token-2"), text)
    }

    @Test
    fun `several matches are labelled and rendered in query order`() {
        val result = FuelingLookupResult(
            fuelingId = SAMPLE_FUELING_ID,
            matches = listOf(
                FuelingMatch(FuelingSource.DROP, sampleFuelingRecord()),
                FuelingMatch(FuelingSource.FUELINGS, sampleFuelingRecord()),
                FuelingMatch(FuelingSource.ARCHIVE, sampleFuelingRecord()),
            ),
            related = emptyRelatedRows(),
        )

        val text = FuelingReport.render(result)

        assertTrue(text.startsWith("НАЙДЕНО: 3 (fuelings, fuelings_archive, fuelings_drop)"), text.lineSequence().first())
        assertTrue(text.contains("record[1] table=fuelings"), text)
        assertTrue(text.contains("record[2] table=fuelings_archive"), text)
        assertTrue(text.contains("record[3] table=fuelings_drop"), text)
    }

    @Test
    fun `rendering is deterministic and never touches a clock`() {
        val result = sampleLookupResult()

        assertEquals(FuelingReport.render(result), FuelingReport.render(result))
    }

    @Test
    fun `amounts render without scientific notation or grouping`() {
        val record = FuelingRecord(
            fuelingId = SAMPLE_FUELING_ID,
            amount = BigDecimal("1234567.89"),
            actualAmount = BigDecimal("0.00"),
        )

        val text = FuelingReport.render(sampleLookupResult(record = record, related = emptyRelatedRows()))

        assertTrue(text.contains("  amount=1234567.89"), text)
        assertTrue(text.contains("  actual_amount=0.00"), text)
    }
}
