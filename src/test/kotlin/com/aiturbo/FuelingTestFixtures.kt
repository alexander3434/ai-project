package com.aiturbo

import com.aiturbo.db.BelkaToken
import com.aiturbo.db.DatabaseUnavailableException
import com.aiturbo.db.FuelingFeedback
import com.aiturbo.db.FuelingLookupResult
import com.aiturbo.db.FuelingMatch
import com.aiturbo.db.FuelingRecord
import com.aiturbo.db.FuelingSource
import com.aiturbo.db.PartnerFuelingEvent
import com.aiturbo.db.RelatedRows
import com.aiturbo.db.StageFuelingRepository
import java.math.BigDecimal

/** The example order id from the specification and the README. */
const val SAMPLE_FUELING_ID = "5e12bef2-2f78-48f0-aab5-ccb6bfeb8469"

/** A well-formed GUID that is not in the stage data. */
const val UNKNOWN_FUELING_ID = "00000000-0000-4000-8000-000000000000"

// Epoch-millisecond fixtures inside the plausibility window (2026-09-21, UTC).
const val EPOCH_2026_09_21_143211 = 1_790_001_131_000L
const val EPOCH_2026_09_21_143205 = 1_790_001_125_000L
const val EPOCH_2026_09_21_143241 = 1_790_001_161_000L
const val EPOCH_2026_09_21_143300 = 1_790_001_180_000L
const val EPOCH_2026_09_21_143212 = 1_790_001_132_000L

/** Outside the window: 1973 and year 2286 — rendered as `<n> (raw)`. */
const val EPOCH_BEFORE_WINDOW = 123_456_789L
const val EPOCH_AFTER_WINDOW = 9_999_999_999_999L

/** The `vendor_transaction_date` text value as the stage stores it (TEXT, not an epoch). */
const val VENDOR_TRANSACTION_DATE_TEXT = "2025-01-31T14:12:45.305Z"

/** A full fueling row with every documented column set (AC: 2 events / 1 token / 0 feedback). */
fun sampleFuelingRecord(
    fuelingId: String = SAMPLE_FUELING_ID,
    status: String = "SUCCESS",
    extra: String = """{"source":"stage"}""",
): FuelingRecord = FuelingRecord(
    fuelingId = fuelingId,
    vendorFuelingOrderId = "VFO-778812",
    userId = "user-42",
    status = status,
    amount = BigDecimal("45.00"),
    actualAmount = BigDecimal("45.00"),
    discountFuelPrice = BigDecimal("1.50"),
    vendorFuelPrice = BigDecimal("44.90"),
    fuelType = "AI-95",
    gasStationId = "21",
    gasPumpId = "12",
    refuelingGunId = "3",
    fuelReservationKey = "reserve-abc",
    fuelingType = "FULL",
    fuelingPaymentType = "CARD",
    failedReason = null,
    fueledOrders = """{"orders":[{"orderId":"order-1"}]}""",
    extra = extra,
    createdAt = EPOCH_2026_09_21_143211,
    updatedAt = EPOCH_2026_09_21_143241,
    finishedAt = EPOCH_2026_09_21_143241,
    vendorTransactionDate = VENDOR_TRANSACTION_DATE_TEXT,
)

/** All related rows empty — a normal result (FR-06). */
fun emptyRelatedRows(): RelatedRows = RelatedRows()

/** 2 partner events (newest first), 0 feedback rows, 1 belka token. */
fun sampleRelatedRows(): RelatedRows = RelatedRows(
    events = listOf(
        PartnerFuelingEvent(
            eventId = "evt-2",
            fuelingId = SAMPLE_FUELING_ID,
            partnerId = "partner-9",
            eventName = "FUELING_DELIVERED",
            deliveryStatus = "DELIVERED",
            data = """{"attempt":2}""",
            createdAt = EPOCH_2026_09_21_143300,
            updatedAt = EPOCH_2026_09_21_143300,
        ),
        PartnerFuelingEvent(
            eventId = "evt-1",
            fuelingId = SAMPLE_FUELING_ID,
            partnerId = "partner-9",
            eventName = "FUELING_ACCEPTED",
            deliveryStatus = "SENT",
            data = null,
            createdAt = EPOCH_2026_09_21_143205,
            updatedAt = EPOCH_2026_09_21_143205,
        ),
    ),
    feedback = emptyList(),
    tokens = listOf(
        BelkaToken(
            token = "belka-token-1",
            fuelingId = SAMPLE_FUELING_ID,
            createdAt = EPOCH_2026_09_21_143212,
            errorType = null,
        ),
    ),
)

/** A lookup result with [matches] (default: the example order in `fuelings_archive`). */
fun sampleLookupResult(
    source: FuelingSource = FuelingSource.ARCHIVE,
    record: FuelingRecord = sampleFuelingRecord(),
    related: RelatedRows = sampleRelatedRows(),
    fuelingId: String = record.fuelingId,
): FuelingLookupResult = FuelingLookupResult(
    fuelingId = fuelingId,
    matches = listOf(FuelingMatch(source = source, record = record)),
    related = related,
)

/** A record whose epoch columns are outside the plausibility window (raw rendering). */
fun outOfWindowRecord(): FuelingRecord = FuelingRecord(
    fuelingId = SAMPLE_FUELING_ID,
    status = "FAILED",
    extra = "{\"raw\":true}",
    createdAt = EPOCH_BEFORE_WINDOW,
    updatedAt = EPOCH_AFTER_WINDOW,
)

/**
 * Seedable fake of [StageFuelingRepository] for the offline suite: it records
 * every call, can be switched into an unavailable mode and returns either a
 * fixed result or one computed per lookup.
 */
class FakeStageFuelingRepository(
    result: FuelingLookupResult = sampleLookupResult(),
) : StageFuelingRepository {

    /** Canonical GUIDs the repository was asked for, in order. */
    val calls = mutableListOf<String>()

    /** When set, every lookup throws [DatabaseUnavailableException]. */
    var unavailableReason: String? = null

    private var fixed: FuelingLookupResult = result
    private var provider: ((String) -> FuelingLookupResult)? = null

    fun seed(result: FuelingLookupResult) {
        fixed = result
        provider = null
    }

    fun seed(provider: (String) -> FuelingLookupResult) {
        this.provider = provider
    }

    override fun findByFuelingId(fuelingId: String): FuelingLookupResult {
        calls += fuelingId
        unavailableReason?.let { throw DatabaseUnavailableException("stage lookup failed: $it") }
        return provider?.invoke(fuelingId) ?: fixed
    }
}
