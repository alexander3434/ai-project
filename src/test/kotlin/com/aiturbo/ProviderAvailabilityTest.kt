package com.aiturbo

import com.aiturbo.llm.LlmTarget
import com.aiturbo.llm.ProviderAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderAvailabilityTest {

    @Test
    fun `an unconfigured deepseek target is reported with the 503 text`() {
        val availability = ProviderAvailability(deepseekConfigured = false)

        assertEquals(
            "DeepSeek API key is not configured",
            availability.unavailableReason(LlmTarget.DEEPSEEK),
        )
    }

    @Test
    fun `a configured deepseek target is available`() {
        assertNull(ProviderAvailability(deepseekConfigured = true).unavailableReason(LlmTarget.DEEPSEEK))
    }

    @Test
    fun `the local target is always available`() {
        assertNull(ProviderAvailability(deepseekConfigured = false).unavailableReason(LlmTarget.LOCAL))
        assertNull(ProviderAvailability(deepseekConfigured = true).unavailableReason(LlmTarget.LOCAL))
    }

    @Test
    fun `the value carries a boolean only, no key material`() {
        val availability = ProviderAvailability(deepseekConfigured = false)

        assertFalse(availability.deepseekConfigured)
        assertTrue(!availability.toString().contains("key", ignoreCase = true), availability.toString())
    }
}
