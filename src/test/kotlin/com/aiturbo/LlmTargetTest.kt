package com.aiturbo

import com.aiturbo.llm.LlmTarget
import com.aiturbo.llm.LlmTargetContext
import com.aiturbo.llm.currentLlmTarget
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LlmTargetTest {

    @Test
    fun `an absent or blank model field means deepseek`() {
        assertEquals(LlmTarget.DEEPSEEK, LlmTarget.fromRequest(null))
        assertEquals(LlmTarget.DEEPSEEK, LlmTarget.fromRequest(""))
        assertEquals(LlmTarget.DEEPSEEK, LlmTarget.fromRequest("   "))
    }

    @Test
    fun `local is matched case-insensitively and trimmed`() {
        assertEquals(LlmTarget.LOCAL, LlmTarget.fromRequest("local"))
        assertEquals(LlmTarget.LOCAL, LlmTarget.fromRequest(" LOCAL "))
        assertEquals(LlmTarget.LOCAL, LlmTarget.fromRequest("Local"))
    }

    @Test
    fun `deepseek is matched case-insensitively`() {
        assertEquals(LlmTarget.DEEPSEEK, LlmTarget.fromRequest("deepseek"))
        assertEquals(LlmTarget.DEEPSEEK, LlmTarget.fromRequest("DEEPSEEK"))
    }

    @Test
    fun `an unknown non-blank value is rejected`() {
        assertNull(LlmTarget.fromRequest("gpt-4"))
        assertNull(LlmTarget.fromRequest("ollama"))
        assertNull(LlmTarget.fromRequest("deepseek2"))
    }

    @Test
    fun `the allowed values label is the 400 message body`() {
        assertEquals("model", LlmTarget.FIELD)
        assertEquals("local, deepseek", LlmTarget.ALLOWED_VALUES)
    }

    @Test
    fun `deepseek is the default when no context element is present`() {
        runBlocking {
            assertEquals(LlmTarget.DEEPSEEK, currentLlmTarget())
        }
    }

    @Test
    fun `the selected target is readable through withContext`() {
        runBlocking {
            withContext(LlmTargetContext(LlmTarget.LOCAL)) {
                assertEquals(LlmTarget.LOCAL, currentLlmTarget())
            }
            withContext(LlmTargetContext(LlmTarget.DEEPSEEK)) {
                assertEquals(LlmTarget.DEEPSEEK, currentLlmTarget())
            }
            assertEquals(LlmTarget.DEEPSEEK, currentLlmTarget())
        }
    }
}
