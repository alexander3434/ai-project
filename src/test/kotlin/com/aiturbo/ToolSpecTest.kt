package com.aiturbo

import com.aiturbo.tools.ToolSpec
import com.aiturbo.tools.ToolSpecLoader
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolSpecTest {

    @Test
    fun `the shipped resource describes the get_weather tool`() {
        val spec = ToolSpecLoader.load()

        assertEquals("get_weather", spec.name)
        assertTrue(spec.description.isNotBlank(), spec.description)
        assertTrue(spec.description.contains("погоду"), spec.description)

        val parameters = spec.parameters
        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
        val location = parameters["properties"]!!.jsonObject["location"]!!.jsonObject
        assertEquals("string", location["type"]?.jsonPrimitive?.content)
        assertTrue(location["description"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals(listOf("location"), parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `loading the shipped resource writes one info line`() {
        LogCapture("com.aiturbo.tools.ToolSpecLoader").use { capture ->
            ToolSpecLoader.load()

            val lines = capture.lines()
            assertEquals(1, lines.size, lines.toString())
            assertTrue(lines.single().startsWith("Tool spec loaded: name=get_weather"), lines.single())
        }
    }

    @Test
    fun `a malformed resource fails fast naming the path`() {
        val error = assertFailsWith<IllegalStateException> {
            ToolSpecLoader.load("tools/invalid-tool.json")
        }

        assertTrue(error.message!!.contains("tools/invalid-tool.json"), error.message)
        assertTrue(error.cause is Exception, error.cause?.toString())
    }

    @Test
    fun `a missing resource fails fast naming the path`() {
        val error = assertFailsWith<IllegalStateException> {
            ToolSpecLoader.load("tools/does-not-exist.json")
        }

        assertTrue(error.message!!.contains("tools/does-not-exist.json"), error.message)
    }

    @Test
    fun `a blank name or description is rejected`() {
        val blankName = assertFailsWith<IllegalStateException> {
            ToolSpecLoader.load("tools/blank-name-tool.json")
        }
        val blankDescription = assertFailsWith<IllegalStateException> {
            ToolSpecLoader.load("tools/blank-description-tool.json")
        }

        assertTrue(blankName.message!!.contains("name"), blankName.message)
        assertTrue(blankDescription.message!!.contains("description"), blankDescription.message)
    }

    @Test
    fun `a spec can be built in code without the resource loader`() {
        val spec = ToolSpec(name = "custom", description = "custom tool", parameters = ToolSpecLoader.load().parameters)

        assertEquals("custom", spec.name)
    }

    @Test
    fun `the shipped fueling resource describes the find_fueling tool`() {
        val spec = ToolSpecLoader.load(ToolSpecLoader.FUELING_RESOURCE_PATH)

        assertEquals("find_fueling", spec.name)
        assertTrue(spec.description.contains("пролив"), spec.description)
        assertTrue(spec.description.contains("fuelings_archive"), spec.description)

        val parameters = spec.parameters
        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
        val orderId = parameters["properties"]!!.jsonObject["orderId"]!!.jsonObject
        assertEquals("string", orderId["type"]?.jsonPrimitive?.content)
        assertTrue(orderId["description"]!!.jsonPrimitive.content.contains("GUID"), orderId.toString())
        assertEquals(listOf("orderId"), parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `loading the fueling resource writes one info line naming it`() {
        LogCapture("com.aiturbo.tools.ToolSpecLoader").use { capture ->
            ToolSpecLoader.load(ToolSpecLoader.FUELING_RESOURCE_PATH)

            val lines = capture.lines()
            assertEquals(1, lines.size, lines.toString())
            assertTrue(lines.single().startsWith("Tool spec loaded: name=find_fueling"), lines.single())
        }
    }

    @Test
    fun `the default resource path and the weather resource are unchanged`() {
        assertEquals("tools/get-weather-tool.json", ToolSpecLoader.RESOURCE_PATH)
        assertEquals("get_weather", ToolSpecLoader.load().name)
    }
}
