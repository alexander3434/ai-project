package com.aiturbo.db

import java.math.BigDecimal

/** The three stage tables a fueling order may live in (FR-05). */
enum class FuelingSource(val tableName: String) {
    FUELINGS("fuelings"),
    ARCHIVE("fuelings_archive"),
    DROP("fuelings_drop"),
}

/**
 * One row of `fuelings` / `fuelings_archive` / `fuelings_drop`. All columns
 * listed in the requirements are carried; jsonb columns stay raw JSON text,
 * epoch-millisecond columns stay numeric `Long?` (rendering happens in
 * `FuelingReport`).
 *
 * `vendor_transaction_date` is text in the stage schema (e.g.
 * `2025-01-31T14:12:45.305Z`), not an epoch, so it is carried as stored.
 */
data class FuelingRecord(
    val fuelingId: String,
    val vendorFuelingOrderId: String? = null,
    val userId: String? = null,
    val status: String? = null,
    val amount: BigDecimal? = null,
    val actualAmount: BigDecimal? = null,
    val discountFuelPrice: BigDecimal? = null,
    val vendorFuelPrice: BigDecimal? = null,
    val fuelType: String? = null,
    val gasStationId: String? = null,
    val gasPumpId: String? = null,
    val refuelingGunId: String? = null,
    val fuelReservationKey: String? = null,
    val fuelingType: String? = null,
    val fuelingPaymentType: String? = null,
    val failedReason: String? = null,
    val fueledOrders: String? = null,
    val extra: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val finishedAt: Long? = null,
    val vendorTransactionDate: String? = null,
)

/** One row of `partner_fueling_events` (jsonb `data` as text). */
data class PartnerFuelingEvent(
    val eventId: String? = null,
    val fuelingId: String? = null,
    val partnerId: String? = null,
    val eventName: String? = null,
    val deliveryStatus: String? = null,
    val data: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

/** One row of `fueling_feedback`. */
data class FuelingFeedback(
    val fuelingFeedbackId: String? = null,
    val userId: String? = null,
    val fuelingId: String? = null,
    val reasonId: String? = null,
    val reasonMessage: String? = null,
    val requestedAt: Long? = null,
)

/** One row of `belka_tokens`. */
data class BelkaToken(
    val token: String? = null,
    val fuelingId: String? = null,
    val createdAt: Long? = null,
    val errorType: String? = null,
)

/** A matched fueling row together with the stage table it came from (ASM-04). */
data class FuelingMatch(val source: FuelingSource, val record: FuelingRecord)

/**
 * All related rows of one fueling order, keyed by `fueling_id`. Empty lists are
 * a normal result, not an error (FR-06).
 */
data class RelatedRows(
    val events: List<PartnerFuelingEvent> = emptyList(),
    val feedback: List<FuelingFeedback> = emptyList(),
    val tokens: List<BelkaToken> = emptyList(),
)

/** The full lookup outcome for one canonical GUID ([matches] may be empty). */
data class FuelingLookupResult(
    val fuelingId: String,
    val matches: List<FuelingMatch> = emptyList(),
    val related: RelatedRows = RelatedRows(),
)

/**
 * Read port for one fueling order: the fueling rows from all three stage
 * tables plus the related rows. Blocking, like [WeatherRecordRepository] —
 * callers wrap it in `withContext(Dispatchers.IO)`.
 */
interface StageFuelingRepository {

    /**
     * Reads the fueling by [fuelingId] from `fuelings`, `fuelings_archive` and
     * `fuelings_drop` plus all related rows.
     *
     * @throws DatabaseUnavailableException when the stage database is
     *   unreachable, rejects the credentials or times the query out.
     */
    fun findByFuelingId(fuelingId: String): FuelingLookupResult
}
