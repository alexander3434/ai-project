package com.aiturbo

import com.aiturbo.chat.ChatRequestValidator
import com.aiturbo.chat.ChatTurn
import com.aiturbo.chat.ChatTurnRole
import com.aiturbo.plugins.ChatChunkFrame
import com.aiturbo.plugins.ChatDoneFrame
import com.aiturbo.plugins.ChatErrorFrame
import com.aiturbo.plugins.ChatTurnDto
import com.aiturbo.plugins.ChatRequest
import com.aiturbo.plugins.traceJson
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatRequestValidatorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun user(content: String) = ChatTurnDto(role = "user", content = content)

    private fun assistant(content: String) = ChatTurnDto(role = "assistant", content = content)

    @Test
    fun `a valid request passes and maps to the session`() {
        val request = ChatRequest(
            messages = listOf(
                user("  Какая погода в Москве?  "),
                assistant("Сейчас в Москве +12 °C"),
                user("  А завтра?  "),
            ),
        )

        assertNull(ChatRequestValidator.validate(request))
        val session = ChatRequestValidator.toSession(request)
        assertEquals("А завтра?", session.message)
        assertEquals(
            listOf(
                ChatTurn(ChatTurnRole.USER, "Какая погода в Москве?"),
                ChatTurn(ChatTurnRole.ASSISTANT, "Сейчас в Москве +12 °C"),
            ),
            session.history,
        )
    }

    @Test
    fun `a single user message maps to an empty history`() {
        val request = ChatRequest(messages = listOf(user("Привет")))

        assertNull(ChatRequestValidator.validate(request))
        assertEquals(emptyList(), ChatRequestValidator.toSession(request).history)
        assertEquals("Привет", ChatRequestValidator.toSession(request).message)
    }

    @Test
    fun `roles are trimmed and case-insensitive`() {
        val request = ChatRequest(messages = listOf(assistant("ответ"), ChatTurnDto("  USER  ", "вопрос")))

        assertNull(ChatRequestValidator.validate(request))
        assertEquals(
            listOf(ChatTurn(ChatTurnRole.ASSISTANT, "ответ")),
            ChatRequestValidator.toSession(request).history,
        )
    }

    @Test
    fun `an empty message list is rejected with the exact text`() {
        assertEquals(
            "Field 'messages' must not be empty",
            ChatRequestValidator.validate(ChatRequest(messages = emptyList())),
        )
    }

    @Test
    fun `a system role is rejected at its index`() {
        val request = ChatRequest(
            messages = listOf(user("вопрос"), ChatTurnDto(role = "system", content = "инструкции")),
        )

        assertEquals(
            "Field 'messages[1].role' must be one of: user, assistant",
            ChatRequestValidator.validate(request),
        )
    }

    @Test
    fun `an unknown role is rejected at its index`() {
        val request = ChatRequest(messages = listOf(user("вопрос"), ChatTurnDto("tool", "результат")))

        assertEquals(
            "Field 'messages[1].role' must be one of: user, assistant",
            ChatRequestValidator.validate(request),
        )
    }

    @Test
    fun `a blank content is rejected at its index`() {
        val request = ChatRequest(
            messages = listOf(user("вопрос"), assistant("ответ"), user("   ")),
        )

        assertEquals(
            "Field 'messages[2].content' must not be blank",
            ChatRequestValidator.validate(request),
        )
    }

    @Test
    fun `a last message from the assistant is rejected`() {
        val request = ChatRequest(messages = listOf(user("вопрос"), assistant("ответ")))

        assertEquals(
            "The last message must be from the user",
            ChatRequestValidator.validate(request),
        )
    }

    @Test
    fun `a role is checked before the content of the same message`() {
        val request = ChatRequest(messages = listOf(ChatTurnDto(role = "", content = "")))

        assertEquals(
            "Field 'messages[0].role' must be one of: user, assistant",
            ChatRequestValidator.validate(request),
        )
    }

    @Test
    fun `the request tolerates unknown extra fields`() {
        val request = json.decodeFromString<ChatRequest>(
            """{"messages":[{"role":"user","content":"привет","timestamp":1}],"model":"local","extra":true}""",
        )

        assertEquals("local", request.model)
        assertEquals(listOf(user("привет")), request.messages)
        assertNull(ChatRequestValidator.validate(request))
    }

    @Test
    fun `an absent model stays out of the re-encoded trace body and a sent one is kept`() {
        val absent = ChatRequest(messages = listOf(user("привет")))
        val sent = ChatRequest(messages = listOf(user("привет")), model = "local")

        assertEquals("""{"messages":[{"role":"user","content":"привет"}]}""", traceJson.encodeToString(absent))
        assertEquals(
            """{"messages":[{"role":"user","content":"привет"}],"model":"local"}""",
            traceJson.encodeToString(sent),
        )
    }

    @Test
    fun `frame DTOs encode as single-line compact JSON`() {
        val chunk = traceJson.encodeToString(ChatChunkFrame("Сейчас\nв Москве"))
        val done = traceJson.encodeToString(ChatDoneFrame(answer = "готово \"да\"", model = "deepseek"))
        val error = traceJson.encodeToString(ChatErrorFrame(status = 503, error = "LLM provider is unavailable"))

        assertEquals("""{"text":"Сейчас\nв Москве"}""", chunk)
        assertTrue(!chunk.contains("\n"), chunk)
        assertEquals("""{"answer":"готово \"да\"","model":"deepseek"}""", done)
        assertEquals("""{"status":503,"error":"LLM provider is unavailable"}""", error)
    }
}
