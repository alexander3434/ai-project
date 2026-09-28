package com.aiturbo.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Renders tool descriptors in the OpenAI wire shape Koog sends to DeepSeek
 * (`{"type":"function","function":{"name":…,"description":…,"parameters":…}}`).
 * The parameters block comes from Koog's own schema generator, so what the logs
 * show cannot drift from what leaves the process (FR-02, FR-04).
 */
class ToolJsonRenderer(
    private val schemaGenerator: OpenAICompatibleToolDescriptorSchemaGenerator =
        OpenAICompatibleToolDescriptorSchemaGenerator(),
) {

    fun toWireJson(tool: ToolDescriptor): JsonObject = buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", schemaGenerator.generate(tool))
            },
        )
    }

    /** The whole list as one line; `[]` when there are no tools. */
    fun renderAll(tools: List<ToolDescriptor>): String =
        if (tools.isEmpty()) {
            "[]"
        } else {
            tools.joinToString(prefix = "[", postfix = "]", separator = ",") { toWireJson(it).toString() }
        }
}
