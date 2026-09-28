package com.aiturbo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Static proof that the stage feature can only read (FR-10, NFR-03, D-08).
 *
 * The scan is scoped to the stage sources on purpose: the weather repository
 * (`ExposedWeatherRecordRepository`, the `users` table) legitimately inserts
 * rows and must not be flagged by this guard.
 */
class StageReadOnlyGuardTest {

    /** The `fueling` package plus the four stage types in `db`. */
    private val stageSourceFiles: List<File> = buildList {
        addAll(
            File("src/main/kotlin/com/aiturbo/fueling")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList(),
        )
        addAll(
            listOf(
                "src/main/kotlin/com/aiturbo/db/StageDbConfig.kt",
                "src/main/kotlin/com/aiturbo/db/StageFuelingRepository.kt",
                "src/main/kotlin/com/aiturbo/db/StageTables.kt",
                "src/main/kotlin/com/aiturbo/db/ExposedStageFuelingRepository.kt",
            ).map(::File),
        )
    }

    private val repositoryFile = File("src/main/kotlin/com/aiturbo/db/ExposedStageFuelingRepository.kt")

    /** The Exposed write DSL, statement builders and DDL helpers. */
    private val writeOrDdlCall = Regex(
        """(?i)\b(insertReturning|batchInsert|upsert|replace|deleteWhere|deleteAll|SchemaUtils|""" +
            """createStatement|prepareStatement)\b|\.(insert|update|delete|replace)\s*\(|""" +
            """\.(execute|executeQuery|executeUpdate|exec)\s*\(""",
    )

    /** Raw SQL statement literals (the previous scan's DDL list plus plain DML). */
    private val writeOrDdlStatement = Regex(
        """(?i)\b(insert\s+into|update\s+\S+\s+set|delete\s+from|merge\s+into|select\s+\S+\s+from|""" +
            """drop\s+(table|schema|index)|alter\s+(table|schema|role)|create\s+(table|index|schema|role|database)|""" +
            """truncate\s+table|grant\s+\S|revoke\s+\S)""",
    )

    @Test
    fun `the stage sources exist and contain no write or DDL statement`() {
        assertTrue(stageSourceFiles.size >= 4, "the stage sources were not found: $stageSourceFiles")

        stageSourceFiles.forEach { file ->
            val source = file.readText()
            val call = writeOrDdlCall.find(source)
            assertEquals(null, call?.value, "${file.path} contains a write or DDL call")
            val statement = writeOrDdlStatement.find(source)
            assertEquals(null, statement?.value, "${file.path} contains a write/DDL statement")
        }
    }

    @Test
    fun `the stage repository only builds lookups that bind the GUID`() {
        val source = repositoryFile.readText()

        // The fueling lookup is shared by the three source tables and matches
        // case-insensitively (R-3 fallback); the three related lookups filter by
        // the same bound id and order by their epoch column.
        assertEquals(1, Regex("""lowerCase\(\) eq fuelingId""").findAll(source).count(), source)
        assertEquals(4, Regex("""\.where \{""").findAll(source).count(), source)
        assertEquals(3, Regex("""to SortOrder\.DESC_NULLS_LAST""").findAll(source).count(), source)
        // Exactly the three related lookups are ordered; the main lookups are not.
        assertEquals(3, Regex("""\.orderBy\(""").findAll(source).count(), source)
        assertEquals(0, Regex("""(?i)order by""").findAll(source).count(), source)
    }

    @Test
    fun `the GUID is never interpolated and the table names come from the enum`() {
        val source = repositoryFile.readText()

        // The one main lookup plus the three related lookups.
        val sqlLikeLines = source.lines().filter {
            it.contains("selectAll(") || it.contains(" eq fuelingId")
        }
        assertTrue(sqlLikeLines.size >= 4, source)
        sqlLikeLines.forEach { line ->
            assertFalse(
                line.contains("\$fuelingId") || line.contains("\${fuelingId}"),
                "the GUID must be bound, never interpolated: $line",
            )
        }
        assertTrue(source.contains("FuelingSource.entries"), "the stage tables come from the enum")
        assertTrue(source.contains("TABLES.getValue(source)"), source)
        assertTrue(source.contains("fuelingId.lowerCase() eq fuelingId"), source)
    }

    @Test
    fun `the weather repository is outside the scan scope`() {
        val weather = File("src/main/kotlin/com/aiturbo/db/ExposedWeatherRecordRepository.kt")

        assertTrue(weather.readText().contains("insertReturning"), "the weather repository writes by design")
        assertFalse(stageSourceFiles.any { it.path.endsWith("ExposedWeatherRecordRepository.kt") })
    }
}
