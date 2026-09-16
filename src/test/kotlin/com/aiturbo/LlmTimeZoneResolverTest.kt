package com.aiturbo

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import com.aiturbo.time.LlmTimeZoneResolver
import com.aiturbo.time.parseZoneContent
import com.aiturbo.weather.deepseekModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class ZoneToolArgs(
    @property:LLMDescription("Город или регион")
    val location: String,
)

private class ZoneWeatherToolStub : SimpleTool<ZoneToolArgs>(
    argsType = typeToken<ZoneToolArgs>(),
    name = "get_weather",
    description = "Возвращает текущую погоду и местное время",
) {
    override suspend fun execute(args: ZoneToolArgs): String = args.location
}

private fun zoneAssistant(vararg parts: MessagePart.ResponsePart) =
    Message.Assistant(parts = parts.toList(), metaInfo = ResponseMetaInfo.Empty)

private fun textAnswer(text: String): Message.Assistant = zoneAssistant(MessagePart.Text(text))

/** Fake Koog executor that records the prompt and can answer or fail. */
private class RecordingZoneExecutor(
    private val response: Message.Assistant = textAnswer(""),
    private val failure: Throwable? = null,
) : PromptExecutor() {

    var calls = 0
        private set
    var lastPrompt: Prompt? = null
        private set
    var lastTools: List<ToolDescriptor>? = null
        private set

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        calls++
        lastPrompt = prompt
        lastTools = tools
        failure?.let { throw it }
        return response
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow { }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        ModerationResult(isHarmful = false, categories = emptyMap())

    override fun close() {}
}

class LlmTimeZoneResolverTest {

    private val model = deepseekModel("deepseek-chat")
    private val descriptor = ZoneWeatherToolStub().descriptor

    private fun resolver(
        executor: PromptExecutor,
        apiKeyConfigured: Boolean = true,
        provider: () -> List<ToolDescriptor> = { listOf(descriptor) },
    ) = LlmTimeZoneResolver(
        promptExecutor = executor,
        model = model,
        toolDescriptorsProvider = provider,
        apiKeyConfigured = apiKeyConfigured,
    )

    @Test
    fun `parses the time zone returned by the LLM`() = runTest {
        val executor = RecordingZoneExecutor(textAnswer("""{"timezone":"Europe/Paris"}"""))

        assertEquals(ZoneId.of("Europe/Paris"), resolver(executor).resolve("Paris"))
        assertEquals(1, executor.calls)
    }

    @Test
    fun `handles markdown fences around the JSON`() = runTest {
        val executor = RecordingZoneExecutor(textAnswer("```json\n{\"timezone\":\"Asia/Tokyo\"}\n```"))

        assertEquals(ZoneId.of("Asia/Tokyo"), resolver(executor).resolve("Tokyo"))
    }

    @Test
    fun `returns null when the LLM does not know the location`() = runTest {
        val executor = RecordingZoneExecutor(textAnswer("""{"timezone":null}"""))

        assertNull(resolver(executor).resolve("Atlantis"))
    }

    @Test
    fun `returns null on a malformed LLM response`() = runTest {
        val executor = RecordingZoneExecutor(textAnswer("not json"))

        assertNull(resolver(executor).resolve("Atlantis"))
    }

    @Test
    fun `returns null when the DeepSeek call fails`() = runTest {
        val executor = RecordingZoneExecutor(
            failure = KoogHttpClientException(
                clientName = "deepseek",
                statusCode = 401,
                errorBody = """{"error":"invalid api key"}""",
                message = "LLM request failed",
                cause = null,
            ),
        )

        assertNull(resolver(executor).resolve("Atlantis"))
        assertEquals(1, executor.calls)
    }

    @Test
    fun `returns null without calling the API when the key is not configured`() = runTest {
        val executor = RecordingZoneExecutor(textAnswer("""{"timezone":"Europe/Paris"}"""))

        assertNull(resolver(executor, apiKeyConfigured = false).resolve("Atlantis"))
        assertEquals(0, executor.calls)
    }

    @Test
    fun `rethrows cancellation`() = runTest {
        val executor = RecordingZoneExecutor(failure = CancellationException("cancelled"))

        assertFailsWith<CancellationException> { resolver(executor).resolve("Atlantis") }
    }

    @Test
    fun `sends the fixed prompt with no tool choice and the registry descriptors`() = runTest {
        val executor = RecordingZoneExecutor(textAnswer("""{"timezone":"Africa/Nairobi"}"""))

        resolver(executor).resolve("Kisumu")

        val prompt = executor.lastPrompt!!
        assertEquals("time-zone-resolution", prompt.id)
        assertEquals(0.0, prompt.params.temperature)
        assertEquals(64, prompt.params.maxTokens)
        assertEquals(LLMParams.ToolChoice.None, prompt.params.toolChoice)
        assertEquals(2, prompt.messages.size)
        assertTrue(prompt.messages.first().textContent().contains("return ONLY a JSON object"), prompt.messages.first().textContent())
        assertEquals("Location: Kisumu", prompt.messages.last().textContent())

        assertEquals(listOf("get_weather"), executor.lastTools!!.map { it.name })
    }

    @Test
    fun `resolves the descriptors lazily and only once`() = runTest {
        var providerCalls = 0
        val executor = RecordingZoneExecutor(textAnswer("""{"timezone":"Africa/Nairobi"}"""))
        val resolver = resolver(executor, provider = {
            providerCalls++
            listOf(descriptor)
        })

        resolver.resolve("Kisumu")
        resolver.resolve("Kisumu")

        assertEquals(1, providerCalls)
        assertEquals(2, executor.calls)
    }

    @Test
    fun `an answer with only a tool call is not executed and yields null`() = runTest {
        val executor = RecordingZoneExecutor(
            zoneAssistant(MessagePart.Tool.Call(id = "call-1", tool = "get_weather", args = """{"location":"Kisumu"}"""))
        )

        assertNull(resolver(executor).resolve("Kisumu"))
    }

    @Test
    fun `concatenates several text parts before parsing`() = runTest {
        val executor = RecordingZoneExecutor(
            zoneAssistant(MessagePart.Text("""{"timezone":"""), MessagePart.Text(""""Europe/Paris"}"""))
        )

        assertEquals(ZoneId.of("Europe/Paris"), resolver(executor).resolve("Paris"))
    }

    @Test
    fun `parseZoneContent parses plain JSON, fences and rejects the rest`() {
        assertEquals(ZoneId.of("Europe/Paris"), parseZoneContent("""{"timezone":"Europe/Paris"}"""))
        assertEquals(ZoneId.of("Asia/Tokyo"), parseZoneContent("```json\n{\"timezone\":\"Asia/Tokyo\"}\n```"))
        assertNull(parseZoneContent("""{"timezone":null}"""))
        assertNull(parseZoneContent("not json"))
        assertNull(parseZoneContent("""{"timezone":"Not/AZone"}"""))
        assertNull(parseZoneContent(""))
        assertNull(parseZoneContent(null))
    }
}
