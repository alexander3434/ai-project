package com.aiturbo.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.aiturbo.db.DatabaseUnavailableException
import com.aiturbo.db.FuelingLookupResult
import com.aiturbo.db.FuelingSource
import com.aiturbo.db.StageFuelingRepository
import com.aiturbo.fueling.FuelingId
import com.aiturbo.fueling.FuelingReport
import com.aiturbo.fueling.InvalidFuelingIdException
import com.aiturbo.fueling.StageDatabaseUnavailableException
import com.aiturbo.log.TraceLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class FindFuelingArgs(
    @property:LLMDescription(
        "Идентификатор заказа (GUID в формате 8-4-4-4-12), например 5e12bef2-2f78-48f0-aab5-ccb6bfeb8469"
    )
    val orderId: String,
)

/**
 * Koog tool `find_fueling`: validates the order id, reads the fueling from the
 * stage database through [StageFuelingRepository] (read-only), logs the lookup
 * outcome as the `stage=db` chain entry and returns the rendered report.
 *
 * The tool name and description come from the JSON resource ([ToolSpec]), so
 * the file is what the model receives; the parameter schema is generated from
 * [FindFuelingArgs] by Koog.
 *
 * Failures are distinct and never reach the client as a 500: an invalid id
 * throws [InvalidFuelingIdException] **before** any database access and a stage
 * failure throws [StageDatabaseUnavailableException] (FR-04, FR-12).
 */
class FindFuelingTool(
    private val repository: StageFuelingRepository,
    spec: ToolSpec,
) : SimpleTool<FindFuelingArgs>(
    argsType = typeToken<FindFuelingArgs>(),
    name = spec.name,
    description = spec.description,
) {

    override suspend fun execute(args: FindFuelingArgs): String {
        val canonical = FuelingId.canonicalize(args.orderId) ?: throw InvalidFuelingIdException(
            "Идентификатор '${args.orderId}' не является корректным GUID (формат 8-4-4-4-12). " +
                "Запрос в базу не выполнялся."
        )

        val result = try {
            withContext(Dispatchers.IO) { repository.findByFuelingId(canonical) }
        } catch (e: DatabaseUnavailableException) {
            TraceLog.fuelingLookupUnavailable(TraceLog.currentId(), name, e.message)
            throw StageDatabaseUnavailableException(
                "Данные stage временно недоступны: ${e.message}. Повторите запрос позже.",
                e,
            )
        }

        if (result.matches.isEmpty()) {
            TraceLog.fuelingLookupNotFound(
                TraceLog.currentId(),
                name,
                FuelingSource.entries.map { it.tableName },
            )
        } else {
            TraceLog.fuelingLookupFound(
                id = TraceLog.currentId(),
                tool = name,
                tables = result.matches.map { it.source.tableName },
                events = result.related.events.size,
                tokens = result.related.tokens.size,
                feedback = result.related.feedback.size,
                capped = isCapped(result),
            )
        }

        return FuelingReport.render(result)
    }

    /** True when at least one related list was cut at the render cap (ASM-09). */
    private fun isCapped(result: FuelingLookupResult): Boolean =
        result.related.events.size > FuelingReport.MAX_RENDERED_RELATED_ROWS ||
            result.related.feedback.size > FuelingReport.MAX_RENDERED_RELATED_ROWS ||
            result.related.tokens.size > FuelingReport.MAX_RENDERED_RELATED_ROWS
}
