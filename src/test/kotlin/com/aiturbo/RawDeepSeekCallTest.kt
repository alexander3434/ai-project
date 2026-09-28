package com.aiturbo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Static guard for FR-01: every DeepSeek call goes through Koog, so no source
 * file may build a raw HTTP call to the provider itself.
 */
class RawDeepSeekCallTest {

    private val sources: List<File> = File("src/main/kotlin")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sorted()
        .toList()

    private fun hits(needle: String): List<String> = sources.flatMap { file ->
        file.readLines().withIndex()
            .filter { (_, line) -> line.contains(needle) }
            .map { (index, line) -> "${file.path}:${index + 1}: ${line.trim()}" }
    }

    private fun hitsIn(path: String, needle: String): List<String> =
        hits(needle).filter { it.startsWith("$path:") }

    @Test
    fun `no production source builds a raw authorization header`() {
        val offenders = hits("\"Bearer ")
        assertTrue(offenders.isEmpty(), "raw authorization header found:\n${offenders.joinToString("\n")}")
    }

    @Test
    fun `chat completions is mentioned only by the Koog client and the wiring`() {
        val allowed = listOf(
            "src/main/kotlin/com/aiturbo/llm/DeepSeekLlm.kt",
            "src/main/kotlin/com/aiturbo/Application.kt",
        )
        val offenders = hits("chat/completions")
            .filterNot { hit -> allowed.any { hit.startsWith("$it:") } }

        assertTrue(
            offenders.isEmpty(),
            "chat/completions outside $allowed:\n${offenders.joinToString("\n")}",
        )
    }

    @Test
    fun `the raw DTOs of the removed direct HTTP call are gone`() {
        val rawDtos = listOf(
            "class ChatMessage",
            "class ChatCompletionRequest",
            "class ChatCompletionResponse",
            "data class Choice",
            "class ResponseFormat",
        )
        val offenders = rawDtos.flatMap { hits(it) }

        assertTrue(offenders.isEmpty(), "raw DeepSeek DTOs still present:\n${offenders.joinToString("\n")}")
    }

    @Test
    fun `the time zone resolver uses the prompt executor and no http client`() {
        val resolver = "src/main/kotlin/com/aiturbo/time/LlmTimeZoneResolver.kt"
        val offenders = hitsIn(resolver, "io.ktor") +
            hitsIn(resolver, "HttpClient") +
            hitsIn(resolver, "Authorization") +
            hitsIn(resolver, "Bearer")

        assertTrue(offenders.isEmpty(), "the resolver still talks HTTP directly:\n${offenders.joinToString("\n")}")
    }
}
