package com.aiturbo.time

import com.aiturbo.DeepseekConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.ZoneId

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ResponseFormat(val type: String)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val response_format: ResponseFormat = ResponseFormat("json_object"),
    val temperature: Double = 0.0,
    val max_tokens: Int = 64,
)

@Serializable
data class ChatCompletionResponse(val choices: List<Choice> = emptyList())

@Serializable
data class Choice(val message: ChatMessage? = null)

@Serializable
data class ZoneGuess(val timezone: String? = null)

private val SYSTEM_PROMPT = """
    You are a precise geolocation assistant. Given a location (city, region or country),
    return ONLY a JSON object with a single key "timezone" whose value is the IANA time zone
    identifier, for example {"timezone":"Europe/Paris"}.
    If the location is unknown or ambiguous, return {"timezone":null}.
    Do not output anything except the JSON.
""".trimIndent()

/**
 * Resolves a free-form location into an IANA time zone id by asking an LLM over HTTP
 * (DeepSeek by default). Returns null when the location is unknown or the call fails.
 */
class LlmTimeZoneResolver(
    private val client: HttpClient,
    private val config: DeepseekConfig,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TimeZoneResolver {

    private val logger = LoggerFactory.getLogger(LlmTimeZoneResolver::class.java)

    override suspend fun resolve(location: String): ZoneId? {
        if (config.apiKey.isBlank()) {
            logger.warn("DeepSeek API key is not configured, skipping LLM time zone resolution")
            return null
        }
        try {
            val response: HttpResponse = client.post("${config.baseUrl.trimEnd('/')}/chat/completions") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                setBody(
                    ChatCompletionRequest(
                        model = config.model,
                        messages = listOf(
                            ChatMessage("system", SYSTEM_PROMPT),
                            ChatMessage("user", "Location: $location"),
                        ),
                    )
                )
            }

            if (!response.status.isSuccess()) {
                logger.warn("LLM request failed with HTTP ${response.status.value}: ${response.bodyAsText()}")
                return null
            }

            val content = response.body<ChatCompletionResponse>().choices.firstOrNull()?.message?.content
            return parseZone(content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("LLM time zone resolution failed: ${e.message}")
            return null
        }
    }

    private fun parseZone(content: String?): ZoneId? {
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
}
