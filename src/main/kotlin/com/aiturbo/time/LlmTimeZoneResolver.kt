package com.aiturbo.time

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.ZoneId

@Serializable
data class ZoneGuess(val timezone: String? = null)

private val SYSTEM_PROMPT = """
    You are a precise geolocation assistant. Given a location (city, region or country),
    return ONLY a JSON object with a single key "timezone" whose value is the IANA time zone
    identifier, for example {"timezone":"Europe/Paris"}.
    If the location is unknown or ambiguous, return {"timezone":null}.
    Do not output anything except the JSON.
""".trimIndent()

private val json = Json { ignoreUnknownKeys = true }

/**
 * Resolves a free-form location into an IANA time zone id by asking the LLM through
 * Koog (the same executor the weather agent uses — no raw HTTP caller is left).
 * Returns null when the location is unknown or the call fails.
 */
class LlmTimeZoneResolver(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val toolDescriptorsProvider: () -> List<ToolDescriptor>,
    private val apiKeyConfigured: Boolean = true,
) : TimeZoneResolver {

    private val logger = LoggerFactory.getLogger(LlmTimeZoneResolver::class.java)

    // Resolved on first use: the registry depends on the tool, which depends on this resolver.
    private val toolDescriptors: List<ToolDescriptor> by lazy { toolDescriptorsProvider() }

    override suspend fun resolve(location: String): ZoneId? {
        if (!apiKeyConfigured) {
            logger.warn("DeepSeek API key is not configured, skipping LLM time zone resolution")
            return null
        }
        return try {
            val prompt = Prompt.build(
                id = "time-zone-resolution",
                params = LLMParams(
                    temperature = 0.0,
                    maxTokens = 64,
                    toolChoice = LLMParams.ToolChoice.None,
                ),
            ) {
                system(SYSTEM_PROMPT)
                user("Location: $location")
            }

            val response = promptExecutor.execute(prompt, model, toolDescriptors)
            parseZoneContent(response.textContent())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("LLM time zone resolution failed: ${e.message}")
            null
        }
    }
}

/**
 * Reads `{"timezone":"<IANA id>"}` out of the model's answer (code fences are
 * tolerated). Anything malformed or unknown yields null.
 */
internal fun parseZoneContent(content: String?): ZoneId? {
    if (content.isNullOrBlank()) return null
    val cleaned = content.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    val guess = runCatching { json.decodeFromString<ZoneGuess>(cleaned) }.getOrNull() ?: return null
    val zoneId = guess.timezone?.trim() ?: return null
    return runCatching { ZoneId.of(zoneId) }.getOrNull()
}
