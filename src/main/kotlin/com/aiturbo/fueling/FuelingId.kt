package com.aiturbo.fueling

/**
 * The fueling order id contract (FR-03): a canonical GUID/UUID in the
 * `8-4-4-4-12` hex form, case-insensitive, surrounding whitespace ignored.
 * No version/variant check — real stage rows may contain any UUID version
 * (ASM-03).
 */
object FuelingId {

    val PATTERN: Regex =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * Trims [raw] and returns the canonical lowercase GUID, or null for
     * anything that is not a canonical GUID (braces, `urn:uuid:`, compact
     * 32-hex, blank, wrong group lengths, non-hex).
     *
     * `java.util.UUID.fromString` is deliberately not used: it accepts short
     * groups such as `1-1-1-1-1`, which the contract rejects.
     */
    fun canonicalize(raw: String): String? {
        val trimmed = raw.trim()
        return if (PATTERN.matches(trimmed)) trimmed.lowercase() else null
    }
}

/** The extracted value is not a canonical GUID — no lookup was performed (FR-04). */
class InvalidFuelingIdException(message: String) : RuntimeException(message)

/** The stage lookup could not be performed: unreachable, auth failure or timeout (FR-12). */
class StageDatabaseUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
