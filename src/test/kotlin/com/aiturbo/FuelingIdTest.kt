package com.aiturbo

import com.aiturbo.fueling.FuelingId
import com.aiturbo.fueling.InvalidFuelingIdException
import com.aiturbo.fueling.StageDatabaseUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FuelingIdTest {

    private val example = "5e12bef2-2f78-48f0-aab5-ccb6bfeb8469"

    @Test
    fun `a canonical lowercase guid is accepted unchanged`() {
        assertEquals(example, FuelingId.canonicalize(example))
    }

    @Test
    fun `an uppercase guid is accepted and lowercased`() {
        assertEquals(example, FuelingId.canonicalize(example.uppercase()))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(example, FuelingId.canonicalize("  $example\t"))
        assertEquals(example, FuelingId.canonicalize("\n$example "))
    }

    @Test
    fun `any uuid version is accepted - there is no version check`() {
        assertEquals("00000000-0000-0000-0000-000000000000", FuelingId.canonicalize("00000000-0000-0000-0000-000000000000"))
        assertEquals("ffffffff-ffff-ffff-ffff-ffffffffffff", FuelingId.canonicalize("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF"))
    }

    @Test
    fun `the pattern matches the canonical form`() {
        assertEquals(example, FuelingId.PATTERN.find(example)?.value)
    }

    @Test
    fun `a compact 32 character id is rejected`() {
        assertNull(FuelingId.canonicalize(example.replace("-", "")))
    }

    @Test
    fun `braced and urn forms are rejected`() {
        assertNull(FuelingId.canonicalize("{$example}"))
        assertNull(FuelingId.canonicalize("urn:uuid:$example"))
    }

    @Test
    fun `blank values are rejected`() {
        assertNull(FuelingId.canonicalize(""))
        assertNull(FuelingId.canonicalize("   "))
    }

    @Test
    fun `short and malformed groups are rejected`() {
        assertNull(FuelingId.canonicalize("1-1-1-1-1"))
        assertNull(FuelingId.canonicalize("5e12bef2-2f78-48f0-aab5-ccb6bfeb846"))
        assertNull(FuelingId.canonicalize("5e12bef22-2f78-48f0-aab5-ccb6bfeb8469"))
        assertNull(FuelingId.canonicalize("5e12bef2-2f78-48f0-aab5-ccb6bfeb8469-extra"))
    }

    @Test
    fun `a numeric non-guid id is rejected`() {
        assertNull(FuelingId.canonicalize("12345"))
    }

    @Test
    fun `non-hex characters are rejected`() {
        assertNull(FuelingId.canonicalize("5e12bef2-2f78-48f0-aab5-ccb6bfeb846z"))
        assertNull(FuelingId.canonicalize("здесь-guid-нет-совсем-000000000000"))
    }

    @Test
    fun `the exceptions carry the design constructors`() {
        val invalid = InvalidFuelingIdException("не GUID")
        assertEquals("не GUID", invalid.message)

        val cause = IllegalStateException("connection refused")
        val unavailable = StageDatabaseUnavailableException("stage недоступен", cause)
        assertEquals("stage недоступен", unavailable.message)
        assertEquals(cause, unavailable.cause)

        val withoutCause = StageDatabaseUnavailableException("stage недоступен")
        assertNull(withoutCause.cause)
    }

    @Test
    fun `canonicalize never throws`() {
        val weird = listOf("", " ", "-", "----", "5e12bef2", "0".repeat(36), "☃")
        weird.forEach { assertNull(FuelingId.canonicalize(it), it) }
    }

    @Test
    fun `the exceptions are runtime exceptions`() {
        assertFailsWith<InvalidFuelingIdException> { throw InvalidFuelingIdException("x") }
        assertFailsWith<StageDatabaseUnavailableException> { throw StageDatabaseUnavailableException("y") }
    }
}
