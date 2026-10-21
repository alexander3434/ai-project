package com.aiturbo

import com.aiturbo.tools.ToolSpec
import com.aiturbo.tools.ToolSpecLoader
import com.aiturbo.tools.WEB_SEARCH_EMPTY_QUERY
import com.aiturbo.tools.WEB_SEARCH_NO_RESULTS
import com.aiturbo.tools.WEB_SEARCH_UNAVAILABLE
import com.aiturbo.tools.WebSearchArgs
import com.aiturbo.tools.WebSearchConfig
import com.aiturbo.tools.WebSearchTool
import com.aiturbo.tools.decodeHtmlEntities
import com.aiturbo.tools.looksLikeAntiBotPage
import com.aiturbo.tools.parseResults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A trimmed copy of the `html` endpoint page: two results, the second snippet in a table. */
private val ddgHtmlPage = """
    <html><body>
    <div class="result results_links">
      <h2 class="result__title">
        <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.sports.ru%2Fzenit-orenburg%2F&amp;rut=aaa">Когда матч <b>Зенит</b> — Оренбург &amp; во сколько</a>
      </h2>
      <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.sports.ru%2Fzenit-orenburg%2F&amp;rut=aaa">Матч пройдёт 20 сентября 2026 года&nbsp;в 18:00 по Москве.</a>
    </div>
    <div class="result results_links">
      <h2 class="result__title">
        <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.championat.com%2Ffootball%2F%3Futm%3Drss&amp;rut=bbb">Календарь РПЛ</a>
      </h2>
      <table><tr><td class="result__snippet">Полный календарь матчей РПЛ сезона 2026/2027.</td></tr></table>
    </div>
    </body></html>
""".trimIndent()

/** A trimmed copy of the Mojeek result page: `a.ob` links and `p.s` snippets. */
private val mojeekPage = """
    <html><body><ul class="results-standard">
    <li><h2><a class="ob" href="https://www.sports.ru/zenit-orenburg/">Когда матч <b>Зенит</b> — Оренбург</a></h2>
      <p class="s">Матч пройдёт 20 сентября 2026 года в 18:00 по Москве.</p></li>
    <li><h2><a class="ob" href="https://www.championat.com/football/schedule/?utm=rss">Календарь РПЛ &amp; расписание</a></h2>
      <p class="s">Полное расписание матчей сезона 2026/2027.</p></li>
    </ul></body></html>
""".trimIndent()

/** The page Mojeek serves instead of results when it rate-limits the client (HTTP 200). */
private val mojeekCaptchaPage = """
    <html><head><title>Captcha</title></head><body><p>Please prove you are not a robot.</p></body></html>
""".trimIndent()

/** The lite endpoint shape: `result-link` anchors and `result-snippet` cells. */
private val ddgLitePage = """
    <html><body><table>
    <tr><td><a class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fnews%3Fid%3D7%26lang%3Dru&amp;rut=ccc">Новости дня</a></td></tr>
    <tr><td class="result-snippet">Главные события дня — коротко.</td></tr>
    </table></body></html>
""".trimIndent()

/** The page DuckDuckGo serves instead of results when it rate-limits the client. */
private val ddgAntiBotPage = """
    <html><body><div class="anomaly-modal__box"><h1 class="anomaly-modal__title">Unfortunately, bots use DuckDuckGo too.</h1></div></body></html>
""".trimIndent()

private val htmlHeaders = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")

class WebSearchToolTest {

    private val spec: ToolSpec = ToolSpecLoader.load(ToolSpecLoader.WEB_SEARCH_RESOURCE_PATH)

    private val config = WebSearchConfig(
        mojeekBaseUrl = "https://mojeek.test/search",
        baseUrl = "https://primary.test/html/",
        fallbackBaseUrl = "https://fallback.test/lite/",
        maxResults = 5,
        timeoutSeconds = 2,
    )

    private fun tool(client: HttpClient, config: WebSearchConfig = this.config) = WebSearchTool(client, config, spec)

    @Test
    fun `parses titles, urls and snippets from the mojeek page`() {
        val results = parseResults(mojeekPage, max = 5)

        assertEquals(2, results.size, results.toString())
        assertEquals("Когда матч Зенит — Оренбург", results[0].title)
        assertEquals("https://www.sports.ru/zenit-orenburg/", results[0].url)
        assertEquals("Матч пройдёт 20 сентября 2026 года в 18:00 по Москве.", results[0].snippet)
        assertEquals("Календарь РПЛ & расписание", results[1].title)
        assertEquals("https://www.championat.com/football/schedule/?utm=rss", results[1].url)
        assertEquals("Полное расписание матчей сезона 2026/2027.", results[1].snippet)
    }

    @Test
    fun `parses titles, decoded result urls and snippets from the html page`() {
        val results = parseResults(ddgHtmlPage, max = 5)

        assertEquals(2, results.size, results.toString())
        assertEquals("Когда матч Зенит — Оренбург & во сколько", results[0].title)
        assertEquals("https://www.sports.ru/zenit-orenburg/", results[0].url)
        assertEquals("Матч пройдёт 20 сентября 2026 года в 18:00 по Москве.", results[0].snippet)
        assertEquals("Календарь РПЛ", results[1].title)
        assertEquals("https://www.championat.com/football/?utm=rss", results[1].url)
        assertEquals("Полный календарь матчей РПЛ сезона 2026/2027.", results[1].snippet)
    }

    @Test
    fun `parses the lite fallback page shape`() {
        val results = parseResults(ddgLitePage, max = 5)

        assertEquals(1, results.size, results.toString())
        assertEquals("Новости дня", results.single().title)
        assertEquals("https://example.org/news?id=7&lang=ru", results.single().url)
        assertEquals("Главные события дня — коротко.", results.single().snippet)
    }

    @Test
    fun `the result limit is honoured and a non-positive limit yields nothing`() {
        assertEquals(1, parseResults(ddgHtmlPage, max = 1).size)
        assertEquals(emptyList(), parseResults(ddgHtmlPage, max = 0))
    }

    @Test
    fun `entities and anti-bot pages are recognised`() {
        assertEquals("a & b «q» 'x' — 5°", decodeHtmlEntities("a &amp; b &laquo;q&raquo; &#39;x&#39; &mdash; 5&deg;"))
        assertTrue(looksLikeAntiBotPage(ddgAntiBotPage))
        assertTrue(looksLikeAntiBotPage(mojeekCaptchaPage))
        assertFalse(looksLikeAntiBotPage(ddgHtmlPage))
        assertFalse(looksLikeAntiBotPage(mojeekPage))
    }

    @Test
    fun `mojeek is the primary source and its result is rendered`() = runTest {
        val urls = mutableListOf<String>()
        var userAgent: String? = null
        var query: String? = null
        val client = HttpClient(
            MockEngine { request ->
                urls += request.url.toString()
                userAgent = request.headers[HttpHeaders.UserAgent]
                query = request.url.parameters["q"]
                respond(mojeekPage, HttpStatusCode.OK, htmlHeaders)
            },
        )

        val answer = tool(client).execute(WebSearchArgs("когда матч Зенит Оренбург"))

        assertEquals(
            "1. Когда матч Зенит — Оренбург — https://www.sports.ru/zenit-orenburg/ — " +
                "Матч пройдёт 20 сентября 2026 года в 18:00 по Москве.\n" +
                "2. Календарь РПЛ & расписание — https://www.championat.com/football/schedule/?utm=rss — " +
                "Полное расписание матчей сезона 2026/2027.",
            answer,
        )
        assertEquals(1, urls.size, urls.toString())
        assertTrue(urls.single().startsWith("https://mojeek.test/search"), urls.single())
        assertEquals("когда матч Зенит Оренбург", query)
        assertTrue(userAgent!!.startsWith("Mozilla/5.0"), userAgent)
    }

    @Test
    fun `a mojeek captcha page falls back to the duckduckgo html endpoint`() = runTest {
        val urls = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                urls += request.url.toString()
                val page = if (request.url.host == "mojeek.test") mojeekCaptchaPage else ddgHtmlPage
                respond(page, HttpStatusCode.OK, htmlHeaders)
            },
        )

        val answer = tool(client).execute(WebSearchArgs("новости"))

        assertTrue(answer.startsWith("1. Когда матч Зенит — Оренбург & во сколько — "), answer)
        assertEquals(2, urls.size, urls.toString())
        assertTrue(urls.first().startsWith("https://mojeek.test/search"), urls.toString())
        assertTrue(urls.last().startsWith("https://primary.test/html/"), urls.toString())
    }

    @Test
    fun `a page without results on every source answers with the no-results message`() = runTest {
        val urls = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                urls += request.url.toString()
                respond("<html><body>Ничего не найдено</body></html>", HttpStatusCode.OK, htmlHeaders)
            },
        )

        assertEquals(WEB_SEARCH_NO_RESULTS, tool(client).execute(WebSearchArgs("q")))
        assertEquals(3, urls.size, "all sources are tried before giving up: $urls")
    }

    @Test
    fun `an http failure on every source answers with the unavailable message`() = runTest {
        val client = HttpClient(
            MockEngine { respondError(HttpStatusCode.ServiceUnavailable) },
        )

        assertEquals(WEB_SEARCH_UNAVAILABLE, tool(client).execute(WebSearchArgs("q")))
    }

    @Test
    fun `an anti-bot page on one source switches to the next one`() = runTest {
        val urls = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                urls += request.url.toString()
                val page = when (request.url.host) {
                    "primary.test" -> ddgAntiBotPage
                    "fallback.test" -> ddgLitePage
                    else -> mojeekCaptchaPage
                }
                respond(page, HttpStatusCode.OK, htmlHeaders)
            },
        )

        val answer = tool(client).execute(WebSearchArgs("новости"))

        assertEquals("1. Новости дня — https://example.org/news?id=7&lang=ru — Главные события дня — коротко.", answer)
        assertEquals(3, urls.size, urls.toString())
        assertTrue(urls.first().startsWith("https://mojeek.test/search"), urls.toString())
        assertTrue(urls.last().startsWith("https://fallback.test/lite/"), urls.toString())
    }

    @Test
    fun `a blank query is answered without any request`() = runTest {
        val urls = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                urls += request.url.toString()
                respond(ddgHtmlPage, HttpStatusCode.OK, htmlHeaders)
            },
        )

        assertEquals(WEB_SEARCH_EMPTY_QUERY, tool(client).execute(WebSearchArgs("   ")))
        assertEquals(emptyList(), urls)
    }

    @Test
    fun `the answer is capped and long snippets are cut`() = runTest {
        val longText = "очень длинный текст результата ".repeat(40)
        val page = (1..20).joinToString("") { index ->
            """<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2F$index&amp;rut=x">Заголовок $index</a>""" +
                """<a class="result__snippet">$longText</a>"""
        }
        val client = HttpClient(MockEngine { respond(page, HttpStatusCode.OK, htmlHeaders) })

        val answer = tool(client, config.copy(maxResults = 20)).execute(WebSearchArgs("q"))

        assertTrue(answer.length <= 2_000, "the answer must stay compact, was ${answer.length}")
        assertTrue(answer.length > 1_900, "the cap must be used, was ${answer.length}")
        assertTrue(answer.endsWith("…"), answer.takeLast(50))
        assertTrue(answer.startsWith("1. Заголовок 1 — https://example.org/1 — "), answer.take(80))
        // The first snippet alone is cut to the snippet cap, not to the whole answer cap.
        val firstLine = answer.lineSequence().first()
        assertTrue(firstLine.length < 350, "the snippet must be cut, was ${firstLine.length}")
        assertTrue(firstLine.endsWith("…"), firstLine.takeLast(50))
    }
}
