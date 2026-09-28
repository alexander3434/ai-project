package com.aiturbo

import com.aiturbo.db.DatabaseUnavailableException
import com.aiturbo.db.WeatherRecord
import com.aiturbo.db.WeatherRecordRepository
import com.aiturbo.time.TimeService
import com.aiturbo.time.TimeZoneResolver
import com.aiturbo.tools.GetWeatherTool
import com.aiturbo.tools.ToolSpec
import com.aiturbo.tools.ToolSpecLoader
import com.aiturbo.tools.GetWeatherArgs
import com.aiturbo.weather.WeatherClient
import com.aiturbo.weather.WeatherSnapshot
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

private class FakeZoneResolver : TimeZoneResolver {
    override suspend fun resolve(location: String): ZoneId? =
        if (location.equals("Moscow", ignoreCase = true)) ZoneId.of("Europe/Moscow") else null
}

private class FakeWeatherClient(private val snapshot: WeatherSnapshot?) : WeatherClient {
    override suspend fun currentWeather(location: String): WeatherSnapshot? = snapshot
}

private class FakeWeatherRecordRepository : WeatherRecordRepository {
    val saved = mutableListOf<LocalDateTime>()
    var throwOnSave = false
    var returnId: Int? = null

    override fun save(at: LocalDateTime): Int {
        if (throwOnSave) throw DatabaseUnavailableException("db down")
        saved += at
        return returnId ?: saved.size
    }

    override fun recent(limit: Int): List<WeatherRecord> = emptyList()
}

class GetWeatherToolTest {

    // 2026-09-09T12:00:00Z — Moscow is UTC+3, so the local time is 15:00:00
    private val clock = Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC)
    private val timeService = TimeService(clock)
    private val snapshot = WeatherSnapshot(
        location = "Москва",
        country = "Россия",
        latitude = 55.75204,
        longitude = 37.61781,
        temperatureC = 15.4,
        humidityPercent = 66,
        weatherCode = 3,
        description = "облачно",
    )

    private val spec: ToolSpec = ToolSpecLoader.load()

    private fun tool(resolver: TimeZoneResolver, client: WeatherClient, repository: WeatherRecordRepository) =
        GetWeatherTool(resolver, client, timeService, repository, spec)

    @Test
    fun `returns the weather summary and records the local date and time`() = runTest {
        val repository = FakeWeatherRecordRepository()
        val tool = tool(FakeZoneResolver(), FakeWeatherClient(snapshot), repository)

        val result = tool.execute(GetWeatherArgs("Moscow"))

        assertTrue(result.contains("Москва"), result)
        assertTrue(result.contains("15.4°C"), result)
        assertTrue(result.contains("облачно"), result)
        assertTrue(result.contains("местное время 2026-09-09 15:00:00"), result)
        assertEquals(listOf(LocalDateTime.of(2026, 9, 9, 15, 0, 0)), repository.saved)
    }

    @Test
    fun `does not record anything when the time zone is unknown`() = runTest {
        val repository = FakeWeatherRecordRepository()
        val tool = tool(FakeZoneResolver(), FakeWeatherClient(snapshot), repository)

        val result = tool.execute(GetWeatherArgs("Atlantis"))

        assertTrue(result.contains("Не удалось определить часовой пояс"), result)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `does not record anything when the weather lookup fails`() = runTest {
        val repository = FakeWeatherRecordRepository()
        val tool = tool(FakeZoneResolver(), FakeWeatherClient(null), repository)

        val result = tool.execute(GetWeatherArgs("Moscow"))

        assertTrue(result.contains("Не удалось получить погоду"), result)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `still answers with the weather when the database write fails`() = runTest {
        val repository = FakeWeatherRecordRepository().apply { throwOnSave = true }
        val tool = tool(FakeZoneResolver(), FakeWeatherClient(snapshot), repository)

        val result = tool.execute(GetWeatherArgs("Moscow"))

        assertTrue(result.contains("15.4°C"), result)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `logs the saved record id when the insert succeeds`() = runTest {
        val repository = FakeWeatherRecordRepository()
        val tool = tool(FakeZoneResolver(), FakeWeatherClient(snapshot), repository)

        LogCapture().use { capture ->
            tool.execute(GetWeatherArgs("Moscow"))

            val dbLines = capture.lines().filter { it.contains("stage=db") }
            assertEquals(1, dbLines.size, dbLines.toString())
            assertTrue(dbLines.single().contains("tool=get_weather saved=true id=1"), dbLines.single())
        }
    }

    @Test
    fun `logs a unique conflict when the repository returns a negative id`() = runTest {
        val repository = FakeWeatherRecordRepository().apply { returnId = -1 }
        val tool = tool(FakeZoneResolver(), FakeWeatherClient(snapshot), repository)

        LogCapture().use { capture ->
            val result = tool.execute(GetWeatherArgs("Moscow"))

            assertTrue(result.contains("15.4°C"), result)
            val dbLines = capture.lines().filter { it.contains("stage=db") }
            assertEquals(1, dbLines.size, dbLines.toString())
            assertTrue(dbLines.single().contains("saved=false"), dbLines.single())
            assertTrue(dbLines.single().contains("reason=unique conflict on 'data'"), dbLines.single())
        }
    }

    @Test
    fun `logs the failure reason when the database is down and still answers`() = runTest {
        val repository = FakeWeatherRecordRepository().apply { throwOnSave = true }
        val tool = tool(FakeZoneResolver(), FakeWeatherClient(snapshot), repository)

        LogCapture().use { capture ->
            val result = tool.execute(GetWeatherArgs("Moscow"))

            assertTrue(result.contains("15.4°C"), result)
            val dbLines = capture.lines().filter { it.contains("stage=db") }
            assertEquals(1, dbLines.size, dbLines.toString())
            assertTrue(dbLines.single().contains("saved=false"), dbLines.single())
            assertTrue(dbLines.single().contains("reason=db down"), dbLines.single())
        }
    }

    @Test
    fun `the descriptor comes from the shipped tool spec`() {
        assertEquals(spec.name, tool(FakeZoneResolver(), FakeWeatherClient(snapshot), FakeWeatherRecordRepository()).descriptor.name)
        assertEquals(spec.description, tool(FakeZoneResolver(), FakeWeatherClient(snapshot), FakeWeatherRecordRepository()).descriptor.description)
        assertTrue(
            tool(FakeZoneResolver(), FakeWeatherClient(snapshot), FakeWeatherRecordRepository())
                .descriptor.requiredParameters.map { it.name }
                .contains("location")
        )
    }
}
