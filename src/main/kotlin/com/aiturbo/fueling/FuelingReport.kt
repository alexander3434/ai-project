package com.aiturbo.fueling

import com.aiturbo.db.BelkaToken
import com.aiturbo.db.FuelingFeedback
import com.aiturbo.db.FuelingLookupResult
import com.aiturbo.db.FuelingMatch
import com.aiturbo.db.FuelingRecord
import com.aiturbo.db.FuelingSource
import com.aiturbo.db.PartnerFuelingEvent
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Renders a [FuelingLookupResult] as the compact structured text the model
 * receives: `key=value` field lines with the database vocabulary, Russian
 * prose headers, every documented column always present (a missing value
 * prints `-`), epochs as readable UTC date/time.
 *
 * Pure and clock-free: no I/O, no logging, fully deterministic (D-04).
 */
object FuelingReport {

    const val UTC_SUFFIX = " UTC"
    val UTC: ZoneOffset = ZoneOffset.UTC

    /** Per related table: rows rendered; the real totals are always stated (ASM-09). */
    const val MAX_RENDERED_RELATED_ROWS = 50

    /** Per jsonb field, with the marker `…[truncated, N chars total]`. */
    const val MAX_JSONB_CHARS = 2000

    /** Plausibility window for epoch milliseconds: 2000-01-01 … 2100-01-01 (D-06). */
    private const val MIN_EPOCH_MILLIS = 946_684_800_000L
    private const val MAX_EPOCH_MILLIS = 4_102_444_800_000L

    private const val MISSING = "-"
    private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun render(result: FuelingLookupResult): String {
        if (result.matches.isEmpty()) return notFound(result.fuelingId)

        val matches = result.matches.sortedBy { it.source.ordinal }
        return buildString {
            append("НАЙДЕНО: ").append(matches.size).append(" (")
            append(matches.joinToString(", ") { it.source.tableName }).append(')')
            matches.forEachIndexed { index, match ->
                append('\n').append(renderMatch(index + 1, match))
            }
            append('\n').append(renderEvents(result.related.events))
            append('\n').append(renderFeedback(result.related.feedback))
            append('\n').append(renderTokens(result.related.tokens))
        }
    }

    /**
     * "2026-09-21 14:32:11 UTC" for a plausible epoch-millisecond value, the
     * raw number with `(raw)` outside the window, `-` for null — so no
     * 13-digit value can leak into an answer and no crash can occur (ASM-08).
     */
    internal fun formatEpochMillis(value: Long?): String = when {
        value == null -> MISSING
        value < MIN_EPOCH_MILLIS || value > MAX_EPOCH_MILLIS -> "$value (raw)"
        else -> Instant.ofEpochMilli(value).atZone(UTC).format(TIMESTAMP_FORMAT) + UTC_SUFFIX
    }

    private fun notFound(fuelingId: String): String =
        "НЕ НАЙДЕНО: заказ $fuelingId отсутствует в " +
            FuelingSource.entries.joinToString(", ") { it.tableName } +
            " (stage, база fueling)."

    private fun renderMatch(index: Int, match: FuelingMatch): String {
        val record = match.record
        return buildString {
            append("record[").append(index).append("] table=").append(match.source.tableName)
            append('\n').append(field("fueling_id", text(record.fuelingId)))
            append('\n').append(field("vendor_fueling_order_id", text(record.vendorFuelingOrderId)))
            append('\n').append(field("user_id", text(record.userId)))
            append('\n').append(field("status", text(record.status)))
            append('\n').append(field("amount", decimal(record.amount)))
            append('\n').append(field("actual_amount", decimal(record.actualAmount)))
            append('\n').append(field("discount_fuel_price", decimal(record.discountFuelPrice)))
            append('\n').append(field("vendor_fuel_price", decimal(record.vendorFuelPrice)))
            append('\n').append(field("fuel_type", text(record.fuelType)))
            append('\n').append(field("gas_station_id", text(record.gasStationId)))
            append('\n').append(field("gas_pump_id", text(record.gasPumpId)))
            append('\n').append(field("refueling_gun_id", text(record.refuelingGunId)))
            append('\n').append(field("fuel_reservation_key", text(record.fuelReservationKey)))
            append('\n').append(field("fueling_type", text(record.fuelingType)))
            append('\n').append(field("fueling_payment_type", text(record.fuelingPaymentType)))
            append('\n').append(field("failed_reason", text(record.failedReason)))
            append('\n').append(field("fueled_orders", jsonb(record.fueledOrders)))
            append('\n').append(field("extra", jsonb(record.extra)))
            append('\n').append(field("created_at", formatEpochMillis(record.createdAt)))
            append('\n').append(field("updated_at", formatEpochMillis(record.updatedAt)))
            append('\n').append(field("finished_at", formatEpochMillis(record.finishedAt)))
            // Text column in the stage schema (e.g. 2025-01-31T14:12:45.305Z), printed as stored.
            append('\n').append(field("vendor_transaction_date", text(record.vendorTransactionDate)))
        }
    }

    private fun renderEvents(events: List<PartnerFuelingEvent>): String = buildString {
        append("events: total=").append(events.size)
        append(" shown=").append(shown(events.size))
        events.take(MAX_RENDERED_RELATED_ROWS).forEachIndexed { index, event ->
            append("\n  event[").append(index + 1).append("] event_id=").append(text(event.eventId))
            append(" partner_id=").append(text(event.partnerId))
            append(" event_name=").append(text(event.eventName))
            append(" delivery_status=").append(text(event.deliveryStatus))
            append(" created_at=").append(formatEpochMillis(event.createdAt))
            append(" updated_at=").append(formatEpochMillis(event.updatedAt))
            append(" data=").append(jsonb(event.data))
        }
    }

    private fun renderFeedback(feedback: List<FuelingFeedback>): String = buildString {
        append("feedback: total=").append(feedback.size)
        append(" shown=").append(shown(feedback.size))
        feedback.take(MAX_RENDERED_RELATED_ROWS).forEachIndexed { index, row ->
            append("\n  feedback[").append(index + 1).append("] fueling_feedback_id=")
                .append(text(row.fuelingFeedbackId))
            append(" user_id=").append(text(row.userId))
            append(" reason_id=").append(text(row.reasonId))
            append(" reason_message=").append(text(row.reasonMessage))
            append(" requested_at=").append(formatEpochMillis(row.requestedAt))
        }
    }

    private fun renderTokens(tokens: List<BelkaToken>): String = buildString {
        append("belka_tokens: total=").append(tokens.size)
        append(" shown=").append(shown(tokens.size))
        tokens.take(MAX_RENDERED_RELATED_ROWS).forEachIndexed { index, token ->
            append("\n  token[").append(index + 1).append("] token=").append(text(token.token))
            append(" error_type=").append(text(token.errorType))
            append(" created_at=").append(formatEpochMillis(token.createdAt))
        }
    }

    private fun shown(total: Int): Int = minOf(total, MAX_RENDERED_RELATED_ROWS)

    private fun field(name: String, value: String): String = "  $name=$value"

    private fun text(value: String?): String = value?.takeIf { it.isNotBlank() } ?: MISSING

    private fun decimal(value: BigDecimal?): String = value?.toPlainString() ?: MISSING

    private fun jsonb(value: String?): String {
        val raw = value?.takeIf { it.isNotBlank() } ?: return MISSING
        return if (raw.length <= MAX_JSONB_CHARS) {
            raw
        } else {
            raw.take(MAX_JSONB_CHARS) + "…[truncated, ${raw.length} chars total]"
        }
    }
}
