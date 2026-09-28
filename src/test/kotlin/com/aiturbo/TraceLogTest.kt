package com.aiturbo

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ch.qos.logback.classic.Level
import com.aiturbo.log.CallTrace
import com.aiturbo.log.TraceLog
import com.aiturbo.log.newTraceId
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceLogTest {

    @Test
    fun `newTraceId returns an 8 character id`() {
        val id = newTraceId()

        assertEquals(8, id.length)
    }

    @Test
    fun `currentId returns the id inside the trace context and null outside`() = runTest {
        assertNull(TraceLog.currentId())

        withContext(CallTrace("8f2c1ad4")) {
            assertEquals("8f2c1ad4", TraceLog.currentId())
        }

        assertNull(TraceLog.currentId())
    }

    @Test
    fun `truncate keeps texts up to the limit and marks longer ones`() {
        assertEquals("x".repeat(4095), TraceLog.truncate("x".repeat(4095)))
        assertEquals("x".repeat(4096), TraceLog.truncate("x".repeat(4096)))
        assertEquals(
            "x".repeat(4096) + "…[truncated, 4097 chars total]",
            TraceLog.truncate("x".repeat(4097)),
        )
    }

    @Test
    fun `describeError renders the cause chain`() {
        val error = RuntimeException("outer", IllegalStateException("root cause"))

        assertEquals(
            "RuntimeException: outer <- IllegalStateException: root cause",
            TraceLog.describeError(error),
        )
    }

    @Test
    fun `describeError appends status and body of a Koog HTTP exception`() {
        val error = KoogHttpClientException(
            clientName = "deepseek",
            statusCode = 401,
            errorBody = """{"error":"invalid api key"}""",
            message = "LLM request failed",
            cause = null,
        )

        val described = TraceLog.describeError(error)

        assertTrue(described.startsWith("KoogHttpClientException: "), described)
        assertTrue(described.contains("status=401"), described)
        assertTrue(described.contains("""body={"error":"invalid api key"}"""), described)
    }

    @Test
    fun `every stage method writes one line with the shared id`() = runTest {
        val assistant = Message.Assistant(
            parts = listOf(
                MessagePart.Text("готово"),
                MessagePart.Tool.Call(id = "call-1", tool = "get_weather", args = """{"location":"Москва"}"""),
            ),
            metaInfo = ResponseMetaInfo.Empty,
        )

        LogCapture().use { capture ->
            withContext(CallTrace("abcd1234")) {
                TraceLog.inbound("abcd1234", "POST", "/weather", null, "127.0.0.1:5432", """{"message":"привет"}""")
                TraceLog.deepseekRequest(
                    id = "abcd1234",
                    endpoint = "https://api.deepseek.com/chat/completions",
                    model = "deepseek-chat",
                    messages = listOf(Message.User("привет", RequestMetaInfo.Empty)),
                    toolsCount = 1,
                    tools = """[{"type":"function"}]""",
                    toolChoice = null,
                )
                TraceLog.deepseekResponse("abcd1234", "deepseek-chat", assistant)
                TraceLog.deepseekFailure("abcd1234", "deepseek-chat", RuntimeException("boom"))
                TraceLog.tool("abcd1234", "get_weather", """{"location":"Москва"}""", "Москва: +15.4°C", false)
                TraceLog.toolDb("abcd1234", "get_weather", saved = true, recordId = 7, reason = null)
                TraceLog.outbound("abcd1234", 200, """{"answer":"готово"}""")
            }

            val lines = capture.lines()
            assertEquals(7, lines.size)
            assertTrue(lines.all { it.startsWith("req=abcd1234 ") }, lines.toString())

            assertTrue(lines[0].contains("stage=inbound method=POST path=/weather query=- "), lines[0])
            assertTrue(lines[0].contains("client=127.0.0.1:5432"), lines[0])
            assertTrue(lines[0].contains("""body={"message":"привет"}"""), lines[0])
            assertTrue(lines[1].contains("stage=deepseek-request endpoint=https://api.deepseek.com/chat/completions"), lines[1])
            assertTrue(lines[1].contains("model=deepseek-chat tool_choice=- tools_count=1"), lines[1])
            assertTrue(lines[2].contains("stage=deepseek-response model=deepseek-chat"), lines[2])
            assertTrue(lines[3].contains("stage=deepseek-response") && lines[3].contains("error=RuntimeException: boom"), lines[3])
            assertTrue(lines[4].contains("""stage=tool tool=get_weather args={"location":"Москва"} is_error=false"""), lines[4])
            assertTrue(lines[5].contains("stage=db tool=get_weather saved=true id=7"), lines[5])
            assertTrue(lines[6].contains("stage=outbound status=200"), lines[6])
        }
    }

    @Test
    fun `a missing id renders as a dash and never as null`() = runTest {
        LogCapture().use { capture ->
            TraceLog.deepseekResponse(null, "deepseek-chat", Message.Assistant(parts = listOf(MessagePart.Text("ok")), metaInfo = ResponseMetaInfo.Empty))
            TraceLog.tool(null, "get_weather", "{}", "ok", false)
            TraceLog.toolDb(null, "get_weather", saved = false, recordId = null, reason = "db down")

            val lines = capture.lines()
            assertEquals(3, lines.size)
            assertTrue(lines.all { it.startsWith("req=- stage=") }, lines.toString())
            assertTrue(lines.none { it.contains("null") }, lines.toString())
        }
    }

    @Test
    fun `tool result and args are truncated`() = runTest {
        LogCapture().use { capture ->
            TraceLog.tool("id", "get_weather", "a".repeat(5000), "b".repeat(5000), false)

            val line = capture.lines().single()
            assertTrue(line.contains("…[truncated, 5000 chars total]"), line)
            assertTrue(line.length < 9000, "line should be truncated: ${line.length}")
        }
    }

    @Test
    fun `warn-level lines stay visible for the empty tools warning`() {
        LogCapture().use { capture ->
            TraceLog.logger.warn("empty tools")

            assertEquals(listOf("empty tools"), capture.linesAt(Level.WARN))
        }
    }

    @Test
    fun `the fueling lookup lines carry the outcome, tables and counts`() = runTest {
        LogCapture().use { capture ->
            withContext(CallTrace("abcd1234")) {
                TraceLog.fuelingLookupFound(
                    "abcd1234",
                    "find_fueling",
                    tables = listOf("fuelings", "fuelings_archive"),
                    events = 2,
                    tokens = 1,
                    feedback = 0,
                    capped = false,
                )
                TraceLog.fuelingLookupFound(
                    "abcd1234",
                    "find_fueling",
                    tables = listOf("fuelings_drop"),
                    events = 0,
                    tokens = 0,
                    feedback = 0,
                    capped = true,
                )
                TraceLog.fuelingLookupNotFound(
                    "abcd1234",
                    "find_fueling",
                    tables = listOf("fuelings", "fuelings_archive", "fuelings_drop"),
                )
                TraceLog.fuelingLookupUnavailable("abcd1234", "find_fueling", "connection refused")
            }

            val lines = capture.lines()
            assertEquals(4, lines.size, lines.toString())
            assertTrue(lines.all { it.startsWith("req=abcd1234 stage=db tool=find_fueling ") }, lines.toString())
            assertEquals(
                "req=abcd1234 stage=db tool=find_fueling lookup=found records=2 " +
                    "tables=fuelings,fuelings_archive events=2 tokens=1 feedback=0",
                lines[0],
            )
            assertEquals(
                "req=abcd1234 stage=db tool=find_fueling lookup=found records=1 " +
                    "tables=fuelings_drop events=0 tokens=0 feedback=0 capped=true",
                lines[1],
            )
            assertEquals(
                "req=abcd1234 stage=db tool=find_fueling lookup=not_found records=0 " +
                    "tables=fuelings,fuelings_archive,fuelings_drop",
                lines[2],
            )
            assertEquals(
                "req=abcd1234 stage=db tool=find_fueling lookup=unavailable reason=\"connection refused\"",
                lines[3],
            )
        }
    }

    @Test
    fun `a missing id or reason renders as a dash and never as null`() = runTest {
        LogCapture().use { capture ->
            TraceLog.fuelingLookupFound(null, "find_fueling", emptyList(), 0, 0, 0, capped = false)
            TraceLog.fuelingLookupNotFound(null, "find_fueling", emptyList())
            TraceLog.fuelingLookupUnavailable(null, "find_fueling", null)

            val lines = capture.lines()
            assertEquals(3, lines.size, lines.toString())
            assertTrue(lines.all { it.startsWith("req=- stage=") }, lines.toString())
            assertTrue(lines.none { it.contains("null") }, lines.toString())
            assertTrue(lines[0].contains("records=0 tables=- events=0 tokens=0 feedback=0"), lines[0])
            assertTrue(lines[1].contains("lookup=not_found records=0 tables=-"), lines[1])
            assertTrue(lines[2].contains("lookup=unavailable reason=\"-\""), lines[2])
        }
    }

    @Test
    fun `a long unavailable reason is truncated`() = runTest {
        LogCapture().use { capture ->
            TraceLog.fuelingLookupUnavailable("id", "find_fueling", "x".repeat(5000))

            val line = capture.lines().single()
            assertTrue(line.contains("…[truncated, 5000 chars total]"), line.takeLast(120))
            assertTrue(line.length < 2 * TraceLog.MAX_BODY_CHARS + 1024, "line length: ${line.length}")
        }
    }
}
