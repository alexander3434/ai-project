package com.aiturbo

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.aiturbo.log.renderAssistant
import com.aiturbo.log.renderJson
import com.aiturbo.log.renderMessages
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceFormatsTest {

    private fun user(text: String) = Message.User(text, RequestMetaInfo.Empty)

    private fun assistant(vararg parts: MessagePart.ResponsePart) =
        Message.Assistant(parts = parts.toList(), metaInfo = ResponseMetaInfo.Empty)

    @Test
    fun `renderMessages renders system and user messages on one line`() {
        val rendered = renderMessages(listOf(user("Привет"), user("Мир")))

        assertEquals("""[user: "Привет", user: "Мир"]""", rendered)
    }

    @Test
    fun `renderMessages escapes newlines and quotes`() {
        val rendered = renderMessages(listOf(user("line1\nline2 \"quoted\"")))

        assertEquals("""[user: "line1\nline2 \"quoted\""]""", rendered)
        assertTrue(!rendered.contains("\n"), rendered)
    }

    @Test
    fun `renderAssistant renders a text-only answer`() {
        val rendered = renderAssistant(assistant(MessagePart.Text("Сейчас облачно")))

        assertEquals("""text="Сейчас облачно" tool_calls=[]""", rendered)
    }

    @Test
    fun `renderAssistant renders a tool-call-only answer`() {
        val rendered = renderAssistant(
            assistant(
                MessagePart.Tool.Call(
                    id = "call-1",
                    tool = "get_weather",
                    args = """{"location":"Москва"}""",
                )
            )
        )

        assertEquals(
            """text="" tool_calls=[{"name":"get_weather","args":"{\"location\":\"Москва\"}"}]""",
            rendered,
        )
    }

    @Test
    fun `renderAssistant renders a mixed answer`() {
        val rendered = renderAssistant(
            assistant(
                MessagePart.Text("Проверяю"),
                MessagePart.Tool.Call(id = "call-1", tool = "get_weather", args = """{"location":"Berlin"}"""),
            )
        )

        assertEquals(
            """text="Проверяю" tool_calls=[{"name":"get_weather","args":"{\"location\":\"Berlin\"}"}]""",
            rendered,
        )
    }

    @Test
    fun `renderJson returns compact single-line JSON`() {
        val obj: JsonObject = buildJsonObject {
            put("answer", "готово")
            put("status", 200)
        }

        assertEquals("""{"answer":"готово","status":200}""", renderJson(obj))
    }
}
