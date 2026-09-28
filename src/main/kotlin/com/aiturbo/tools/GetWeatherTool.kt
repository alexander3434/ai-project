package com.aiturbo.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.aiturbo.db.WeatherRecordRepository
import com.aiturbo.log.TraceLog
import com.aiturbo.time.TimeService
import com.aiturbo.time.TimeZoneResolver
import com.aiturbo.weather.WeatherClient
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class GetWeatherArgs(
    @property:LLMDescription("Город или регион, например 'Москва' или 'Berlin'")
    val location: String,
)

/**
 * Koog tool "get_weather": resolves the location into a time zone (reusing the
 * existing [TimeZoneResolver]), fetches the current weather from Open-Meteo and
 * records the request in Postgres — the local date and time in the region goes
 * into `users.data`, the local time into `users.time`, the id increments on
 * every insert.
 *
 * A database failure is logged but does not break the weather answer.
 *
 * The tool name and description come from the JSON resource ([ToolSpec]), so the
 * file is what the model receives; the parameter schema is still generated from
 * [GetWeatherArgs] by Koog.
 */
class GetWeatherTool(
    private val resolver: TimeZoneResolver,
    private val weatherClient: WeatherClient,
    private val timeService: TimeService,
    private val repository: WeatherRecordRepository,
    spec: ToolSpec,
) : SimpleTool<GetWeatherArgs>(
    argsType = typeToken<GetWeatherArgs>(),
    name = spec.name,
    description = spec.description,
) {

    private val logger = LoggerFactory.getLogger(GetWeatherTool::class.java)

    override suspend fun execute(args: GetWeatherArgs): String {
        val location = args.location.trim()

        val zone = resolver.resolve(location)
            ?: return "Не удалось определить часовой пояс для '$location'."

        val snapshot = weatherClient.currentWeather(location)
            ?: return "Не удалось получить погоду для '$location'."

        val time = timeService.timeFor(snapshot.location, zone)

        runCatching {
            withContext(Dispatchers.IO) { repository.save(timeService.nowIn(zone).toLocalDateTime()) }
        }.onSuccess { id ->
            if (id >= 0) {
                TraceLog.toolDb(TraceLog.currentId(), name, saved = true, recordId = id, reason = null)
            } else {
                TraceLog.toolDb(
                    TraceLog.currentId(),
                    name,
                    saved = false,
                    recordId = null,
                    reason = "unique conflict on 'data'",
                )
            }
        }.onFailure {
            logger.warn("Weather record was not saved: {}", it.message)
            TraceLog.toolDb(TraceLog.currentId(), name, saved = false, recordId = null, reason = it.message)
        }

        return buildString {
            append(snapshot.location)
            if (!snapshot.country.isNullOrBlank()) append(", ").append(snapshot.country)
            append(": ")
            append(formatTemperature(snapshot.temperatureC)).append(", ").append(snapshot.description)
            snapshot.humidityPercent?.let { append(", влажность ").append(it).append('%') }
            append(", часовой пояс ").append(time.timezone)
            append(", местное время ").append(time.date).append(' ').append(time.time)
            append(" (").append(time.dayOfWeek).append(')')
        }
    }

    private fun formatTemperature(celsius: Double): String =
        String.format(Locale.US, "%.1f", celsius) + "°C"
}

/**
 * The descriptor of [GetWeatherTool] without constructing the tool. Koog derives
 * the parameter schema from the args type token at construction, so this is the
 * same [ToolDescriptor] the live tool publishes.
 *
 * The LLM time-zone resolver needs the descriptor, but it cannot resolve the
 * tool registry from its binding: the registry needs the tool, which needs the
 * resolver, and Kodein reports a `DependencyLoopException` for that cycle even
 * for a deferred lookup (the resolution context captured by the binding keeps
 * `bind<TimeZoneResolver>` in the dependency chain).
 */
fun weatherToolDescriptor(spec: ToolSpec): ToolDescriptor = DescriptorOnlyWeatherTool(spec).descriptor

private class DescriptorOnlyWeatherTool(spec: ToolSpec) : SimpleTool<GetWeatherArgs>(
    argsType = typeToken<GetWeatherArgs>(),
    name = spec.name,
    description = spec.description,
) {
    override suspend fun execute(args: GetWeatherArgs): String =
        error("descriptor-only tool must never be executed")
}
