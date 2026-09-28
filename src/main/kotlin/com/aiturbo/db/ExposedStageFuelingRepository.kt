package com.aiturbo.db

import java.math.BigDecimal
import java.sql.SQLException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * The only place that talks to the stage database. Read-only by construction:
 * the Exposed DSL statements built here are all lookups, the table names come
 * from the [FuelingSource] enum and the only user-controlled value, the GUID,
 * is always a bound parameter (FR-10).
 *
 * The three main lookups match case-insensitively (`lower(fueling_id) = ?`):
 * the stage tables store non-lowercase ids (R-3 confirmed by the live smoke),
 * and the bound value is the canonical lowercase GUID, so the predicate is
 * evaluated once against the value side.
 *
 * One `transaction` (one connection) serves a whole lookup, so the application
 * starts with the stage database absent and a dead stage host cannot hang a
 * request: the `Database` carries the configured connect/login/socket timeouts
 * (NFR-04). Any [SQLException] becomes [DatabaseUnavailableException] — the
 * credentials live in the connection properties only and are never logged.
 */
class ExposedStageFuelingRepository(private val db: Database) : StageFuelingRepository {

    override fun findByFuelingId(fuelingId: String): FuelingLookupResult = try {
        transaction(db) {
            val matches = FuelingSource.entries.mapNotNull { source -> readFueling(source, fuelingId) }
            val related = if (matches.isEmpty()) RelatedRows() else readRelated(fuelingId)
            FuelingLookupResult(fuelingId = fuelingId, matches = matches, related = related)
        }
    } catch (e: ExposedSQLException) {
        throw DatabaseUnavailableException(
            "Failed to read fueling $fuelingId from the stage database: ${e.message}",
            e,
        )
    } catch (e: SQLException) {
        throw DatabaseUnavailableException(
            "Failed to read fueling $fuelingId from the stage database: ${e.message}",
            e,
        )
    }

    /** The table objects come from [FuelingSource], never from user input. */
    private fun readFueling(source: FuelingSource, fuelingId: String): FuelingMatch? {
        val table = TABLES.getValue(source)
        // R-3 fallback: the stage stores non-lowercase ids, the bound GUID is already canonical lowercase.
        return table.selectAll()
            .where { table.fuelingId.lowerCase() eq fuelingId }
            .firstOrNull()
            ?.let { FuelingMatch(source, it.toFuelingRecord(table)) }
    }

    private fun readRelated(fuelingId: String): RelatedRows = RelatedRows(
        events = PartnerFuelingEventsTable.selectAll()
            .where { PartnerFuelingEventsTable.fuelingId eq fuelingId }
            .orderBy(PartnerFuelingEventsTable.createdAt to SortOrder.DESC_NULLS_LAST)
            .map { it.toPartnerFuelingEvent() },
        feedback = FuelingFeedbackTable.selectAll()
            .where { FuelingFeedbackTable.fuelingId eq fuelingId }
            .orderBy(FuelingFeedbackTable.requestedAt to SortOrder.DESC_NULLS_LAST)
            .map { it.toFuelingFeedback() },
        tokens = BelkaTokensTable.selectAll()
            .where { BelkaTokensTable.fuelingId eq fuelingId }
            .orderBy(BelkaTokensTable.createdAt to SortOrder.DESC_NULLS_LAST)
            .map { it.toBelkaToken() },
    )

    private companion object {
        val TABLES = mapOf(
            FuelingSource.FUELINGS to FuelingsTable,
            FuelingSource.ARCHIVE to FuelingsArchiveTable,
            FuelingSource.DROP to FuelingsDropTable,
        )
    }
}

/** The 22 mapped columns of `fuelings` / `fuelings_archive` / `fuelings_drop`. */
private fun ResultRow.toFuelingRecord(table: FuelingsColumns): FuelingRecord = FuelingRecord(
    fuelingId = this[table.fuelingId],
    vendorFuelingOrderId = this[table.vendorFuelingOrderId],
    userId = this[table.userId],
    status = this[table.status],
    amount = this[table.amount],
    actualAmount = this[table.actualAmount],
    discountFuelPrice = this[table.discountFuelPrice],
    vendorFuelPrice = this[table.vendorFuelPrice],
    fuelType = this[table.fuelType],
    gasStationId = this[table.gasStationId],
    gasPumpId = this[table.gasPumpId],
    refuelingGunId = this[table.refuelingGunId],
    fuelReservationKey = this[table.fuelReservationKey],
    fuelingType = this[table.fuelingType],
    fuelingPaymentType = this[table.fuelingPaymentType],
    failedReason = this[table.failedReason],
    fueledOrders = this[table.fueledOrders],
    extra = this[table.extra],
    createdAt = epochMillisOrNull(this[table.createdAt]),
    updatedAt = epochMillisOrNull(this[table.updatedAt]),
    finishedAt = epochMillisOrNull(this[table.finishedAt]),
    // The stage column is TEXT (ISO-8601), not an epoch: read it as stored.
    vendorTransactionDate = this[table.vendorTransactionDate],
)

private fun ResultRow.toPartnerFuelingEvent(): PartnerFuelingEvent = PartnerFuelingEvent(
    eventId = this[PartnerFuelingEventsTable.eventId],
    fuelingId = this[PartnerFuelingEventsTable.fuelingId],
    partnerId = this[PartnerFuelingEventsTable.partnerId],
    eventName = this[PartnerFuelingEventsTable.eventName],
    deliveryStatus = this[PartnerFuelingEventsTable.deliveryStatus],
    data = this[PartnerFuelingEventsTable.data],
    createdAt = epochMillisOrNull(this[PartnerFuelingEventsTable.createdAt]),
    updatedAt = epochMillisOrNull(this[PartnerFuelingEventsTable.updatedAt]),
)

private fun ResultRow.toFuelingFeedback(): FuelingFeedback = FuelingFeedback(
    fuelingFeedbackId = this[FuelingFeedbackTable.fuelingFeedbackId],
    userId = this[FuelingFeedbackTable.userId],
    fuelingId = this[FuelingFeedbackTable.fuelingId],
    reasonId = this[FuelingFeedbackTable.reasonId],
    reasonMessage = this[FuelingFeedbackTable.reasonMessage],
    requestedAt = epochMillisOrNull(this[FuelingFeedbackTable.requestedAt]),
)

private fun ResultRow.toBelkaToken(): BelkaToken = BelkaToken(
    token = this[BelkaTokensTable.token],
    fuelingId = this[BelkaTokensTable.fuelingId],
    createdAt = epochMillisOrNull(this[BelkaTokensTable.createdAt]),
    errorType = this[BelkaTokensTable.errorType],
)

/**
 * Converts a numeric epoch-millisecond column value to epoch milliseconds
 * defensively: an absent value, or one the conversion cannot represent,
 * becomes `null` (rendered `-`) rather than failing the lookup. Text columns
 * are read as [String] and never passed through here.
 */
internal fun epochMillisOrNull(value: BigDecimal?): Long? =
    value?.let { runCatching { it.toLong() }.getOrNull() }
