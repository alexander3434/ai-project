package com.aiturbo.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Description of a Koog tool (name, description, parameter documentation)
 * loaded from a JSON resource under `src/main/resources/tools/`.
 *
 * The file is the runtime source of truth for the tool's name and description,
 * so editing it (and restarting) changes what DeepSeek receives (FR-08, FR-09).
 */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** Reads the tool description from the classpath, failing fast on a bad resource. */
object ToolSpecLoader {

    const val RESOURCE_PATH = "tools/get-weather-tool.json"

    /** The fueling order lookup tool (the `find_fueling` tool of the /fueling route). */
    const val FUELING_RESOURCE_PATH = "tools/find-fueling-tool.json"

    private val logger = LoggerFactory.getLogger(ToolSpecLoader::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(
        resourcePath: String = RESOURCE_PATH,
        classLoader: ClassLoader = ToolSpec::class.java.classLoader,
    ): ToolSpec {
        val text = classLoader.getResourceAsStream(resourcePath)
            ?.use { it.readBytes().decodeToString() }
            ?: throw IllegalStateException("Tool spec resource '$resourcePath' was not found on the classpath")

        val spec = runCatching { json.decodeFromString<ToolSpec>(text) }.getOrElse { cause ->
            throw IllegalStateException("Tool spec resource '$resourcePath' is not a valid tool description", cause)
        }
        check(spec.name.isNotBlank()) {
            "Tool spec resource '$resourcePath' has a blank tool name"
        }
        check(spec.description.isNotBlank()) {
            "Tool spec resource '$resourcePath' has a blank tool description"
        }

        logger.info("Tool spec loaded: name={} description=\"{}\"", spec.name, spec.description)
        return spec
    }
}
