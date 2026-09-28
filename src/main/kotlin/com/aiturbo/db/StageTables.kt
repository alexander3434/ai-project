package com.aiturbo.db

import org.jetbrains.exposed.v1.core.Table

/**
 * Nominal decimal precision/scale (no DDL is ever issued): deliberately wide so
 * no numeric or epoch-millisecond value is truncated or rounded by the mapping.
 */
private const val DECIMAL_PRECISION = 38
private const val DECIMAL_SCALE = 18

/**
 * The columns shared by `fuelings`, `fuelings_archive` and `fuelings_drop`.
 * Each concrete table instantiates its own columns; the numeric epoch columns
 * are read as [java.math.BigDecimal] and converted defensively by
 * [epochMillisOrNull], and the jsonb columns are carried as raw JSON text.
 */
internal open class FuelingsColumns(name: String) : Table(name) {
    val fuelingId = text("fueling_id")
    val vendorFuelingOrderId = text("vendor_fueling_order_id").nullable()
    val userId = text("user_id").nullable()
    val status = text("status").nullable()
    val amount = decimal("amount", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
    val actualAmount = decimal("actual_amount", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
    val discountFuelPrice = decimal("discount_fuel_price", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
    val vendorFuelPrice = decimal("vendor_fuel_price", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
    val fuelType = text("fuel_type").nullable()
    val gasStationId = text("gas_station_id").nullable()
    val gasPumpId = text("gas_pump_id").nullable()
    val refuelingGunId = text("refueling_gun_id").nullable()
    val fuelReservationKey = text("fuel_reservation_key").nullable()
    val fuelingType = text("fueling_type").nullable()
    val fuelingPaymentType = text("fueling_payment_type").nullable()
    val failedReason = text("failed_reason").nullable()

    /** jsonb, carried as raw JSON text. */
    val fueledOrders = text("fueled_orders").nullable()

    /** jsonb, carried as raw JSON text. */
    val extra = text("extra").nullable()
    val createdAt = decimal("created_at", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
    val updatedAt = decimal("updated_at", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
    val finishedAt = decimal("finished_at", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()

    /** TEXT in the stage schema (e.g. `2025-01-31T14:12:45.305Z`), never an epoch. */
    val vendorTransactionDate = text("vendor_transaction_date").nullable()
}

internal object FuelingsTable : FuelingsColumns("fuelings")
internal object FuelingsArchiveTable : FuelingsColumns("fuelings_archive")
internal object FuelingsDropTable : FuelingsColumns("fuelings_drop")

/** `data` is jsonb and stays raw JSON text. */
internal object PartnerFuelingEventsTable : Table("partner_fueling_events") {
    val eventId = text("event_id").nullable()
    val fuelingId = text("fueling_id")
    val partnerId = text("partner_id").nullable()
    val eventName = text("event_name").nullable()
    val deliveryStatus = text("delivery_status").nullable()
    val data = text("data").nullable()
    val createdAt = decimal("created_at", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
    val updatedAt = decimal("updated_at", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
}

internal object FuelingFeedbackTable : Table("fueling_feedback") {
    val fuelingFeedbackId = text("fueling_feedback_id").nullable()
    val userId = text("user_id").nullable()
    val fuelingId = text("fueling_id")
    val reasonId = text("reason_id").nullable()
    val reasonMessage = text("reason_message").nullable()
    val requestedAt = decimal("requested_at", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
}

internal object BelkaTokensTable : Table("belka_tokens") {
    val token = text("token").nullable()
    val fuelingId = text("fueling_id")
    val createdAt = decimal("created_at", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
    val errorType = text("error_type").nullable()
}
