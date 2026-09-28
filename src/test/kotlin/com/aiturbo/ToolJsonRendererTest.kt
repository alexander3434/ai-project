package com.aiturbo

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.aiturbo.tools.ToolJsonRenderer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class EchoArgs(
    @property:LLMDescription("Город или регион, например 'Москва' или 'Berlin'")
    val location: String,
)

private class EchoTool : SimpleTool<EchoArgs>(
    argsType = typeToken<EchoArgs>(),
    name = "echo",
    description = "Возвращает переданный город",
) {
    override suspend fun execute(args: EchoArgs): String = args.location
}

class ToolJsonRendererTest {

    private val renderer = ToolJsonRenderer()
    private val descriptor = EchoTool().descriptor

    @Test
    fun `renders the OpenAI wire shape`() {
        val wire = renderer.toWireJson(descriptor)

        assertEquals("function", wire["type"]?.jsonPrimitive?.content)
        val function = wire["function"]!!.jsonObject
        assertEquals("echo", function["name"]?.jsonPrimitive?.content)
        assertEquals("Возвращает переданный город", function["description"]?.jsonPrimitive?.content)
        assertTrue(function["parameters"] is kotlinx.serialization.json.JsonObject)
    }

    @Test
    fun `renders the required location parameter`() {
        val parameters = renderer.toWireJson(descriptor)["function"]!!.jsonObject["parameters"]!!.jsonObject

        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
        val properties = parameters["properties"]!!.jsonObject
        assertTrue(properties.containsKey("location"), properties.toString())
        assertEquals(
            "string",
            properties["location"]!!.jsonObject["type"]?.jsonPrimitive?.content,
        )
        assertEquals(
            listOf("location"),
            parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `renderAll renders the descriptors as one line`() {
        val line = renderer.renderAll(listOf(descriptor))

        assertTrue(line.startsWith("""[{"type":"function","function":{"""), line)
        assertTrue(line.endsWith("}]"), line)
        assertTrue(!line.contains("\n"), line)
    }

    @Test
    fun `renderAll returns an empty array for no tools and keeps the input order`() {
        assertEquals("[]", renderer.renderAll(emptyList()))

        val tools = listOf(ToolRegistry.builder().tool(EchoTool()).build().tools.first().descriptor)

        val line = renderer.renderAll(tools)
        assertTrue(line.contains("\"name\":\"echo\""), line)
        assertEquals(1, tools.size)
    }
}
