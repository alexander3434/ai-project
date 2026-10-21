package com.aiturbo

import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import com.aiturbo.llm.StreamedAssistant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamedAssistantTest {

    private fun assistantOf(frames: List<StreamFrame>) = StreamedAssistant().apply {
        frames.forEach { accept(it) }
    }

    @Test
    fun `deltas are concatenated in order and become the text part`() {
        val assistant = assistantOf(
            listOf(
                StreamFrame.TextDelta("Сейчас "),
                StreamFrame.TextDelta("в Москве "),
                StreamFrame.TextDelta("+12 °C"),
                StreamFrame.End(),
            ),
        )

        assertEquals("Сейчас в Москве +12 °C", assistant.text())
        assertEquals(listOf(MessagePart.Text("Сейчас в Москве +12 °C")), assistant.toMessage().parts)
    }

    @Test
    fun `a tool call is assembled next to the text`() {
        val assistant = assistantOf(
            listOf(
                StreamFrame.TextDelta("Проверяю "),
                StreamFrame.ToolCallComplete(id = "call-1", name = "get_weather", content = """{"city":"Москва"}"""),
                StreamFrame.TextDelta("заказ"),
                StreamFrame.End(),
            ),
        )

        assertEquals("Проверяю заказ", assistant.text())
        assertEquals(
            listOf(
                MessagePart.Text("Проверяю заказ"),
                MessagePart.Tool.Call(id = "call-1", tool = "get_weather", args = """{"city":"Москва"}"""),
            ),
            assistant.toMessage().parts,
        )
    }

    @Test
    fun `a trailing TextComplete never double-counts`() {
        val assistant = assistantOf(
            listOf(
                StreamFrame.TextDelta("Москва"),
                StreamFrame.TextDelta(", +12 °C"),
                StreamFrame.TextComplete("Москва, +12 °C"),
                StreamFrame.End(),
            ),
        )

        assertEquals("Москва, +12 °C", assistant.text())
        assertEquals(listOf(MessagePart.Text("Москва, +12 °C")), assistant.toMessage().parts)
    }

    @Test
    fun `tool call deltas alone do not become a tool call`() {
        val assistant = assistantOf(
            listOf(
                StreamFrame.ToolCallDelta(id = "call-1", name = "get_weather", content = """{"city":"""),
                StreamFrame.End(),
            ),
        )

        assertEquals("", assistant.text())
        assertEquals(emptyList(), assistant.toMessage().parts)
    }

    @Test
    fun `an empty stream yields an empty message`() {
        val assistant = assistantOf(emptyList())

        assertEquals("", assistant.text())
        assertEquals(emptyList(), assistant.toMessage().parts)
        assertTrue(assistant.toMessage().parts.isEmpty())
    }

    @Test
    fun `accepting the same frame twice accumulates twice`() {
        val assistant = assistantOf(listOf(StreamFrame.TextDelta("да"), StreamFrame.TextDelta("да")))

        assertEquals("дада", assistant.text())
    }
}
