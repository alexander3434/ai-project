package com.aiturbo.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class WebSearchArgs(
    @property:LLMDescription("Поисковый запрос на языке пользователя, например 'когда матч Зенит Оренбург'")
    val query: String,
)

/** One organic result of a DuckDuckGo result page. */
internal data class WebSearchResult(val title: String, val url: String, val snippet: String)

/** The turn text of a search that reached DuckDuckGo but found nothing. */
internal const val WEB_SEARCH_NO_RESULTS = "Поиск не дал результатов."

/** The turn text of a search that could not reach any endpoint. */
internal const val WEB_SEARCH_UNAVAILABLE = "Веб-поиск временно недоступен."

/** The turn text of a search asked with a blank query. */
internal const val WEB_SEARCH_EMPTY_QUERY = "Поисковый запрос пуст."

/**
 * Koog tool `web_search`: fetches a public search result page for the query and
 * returns up to [WebSearchConfig.maxResults] compact lines
 * (`1. TITLE — URL — snippet`), capped at [MAX_OUTPUT_CHARS] characters.
 *
 * Sources are tried in order — Mojeek, then the DuckDuckGo html endpoint, then
 * its lite endpoint — and an endpoint that fails, answers an anti-bot page or
 * yields no parseable result is skipped, so one blocked source does not fail the
 * turn.
 *
 * The tool never throws: no results, an upstream error and a timeout all return
 * a short Russian message, so the model keeps the turn instead of failing it.
 *
 * The tool name and description come from the JSON resource ([ToolSpec]); the
 * parameter schema is generated from [WebSearchArgs] by Koog.
 */
class WebSearchTool(
    private val client: HttpClient,
    private val config: WebSearchConfig,
    spec: ToolSpec,
) : SimpleTool<WebSearchArgs>(
    argsType = typeToken<WebSearchArgs>(),
    name = spec.name,
    description = spec.description,
) {

    private val logger = LoggerFactory.getLogger(WebSearchTool::class.java)

    override suspend fun execute(args: WebSearchArgs): String {
        val query = args.query.trim()
        if (query.isEmpty()) return WEB_SEARCH_EMPTY_QUERY

        val results = search(query) ?: return WEB_SEARCH_UNAVAILABLE
        return if (results.isEmpty()) WEB_SEARCH_NO_RESULTS else renderResults(results)
    }

    /**
     * Mojeek first, then the DuckDuckGo html and lite fallbacks: `null` when no
     * endpoint answered (network error, HTTP error, anti-bot page or timeout),
     * an empty list when an endpoint answered but had no results to parse.
     */
    private suspend fun search(query: String): List<WebSearchResult>? {
        var answered = false
        val endpoints = listOf(config.mojeekBaseUrl, config.baseUrl, config.fallbackBaseUrl).distinct()
        for (endpoint in endpoints) {
            val html = fetch(query, endpoint) ?: continue
            if (looksLikeAntiBotPage(html)) {
                logger.warn("Web search endpoint {} answered with an anti-bot page", endpoint)
                continue
            }
            answered = true
            val results = parseResults(html, config.maxResults)
            if (results.isNotEmpty()) return results
        }
        return if (answered) emptyList() else null
    }

    /**
     * One GET with the browser headers DuckDuckGo expects, bounded by
     * [WebSearchConfig.timeoutSeconds] through Ktor's `HttpTimeout` capability;
     * `null` on any failure (network, HTTP status, timeout).
     */
    private suspend fun fetch(query: String, endpoint: String): String? = try {
        val response = client.get(endpoint) {
            timeout { requestTimeoutMillis = config.timeoutSeconds * 1_000L }
            header(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
            header(HttpHeaders.AcceptLanguage, "ru,en;q=0.9")
            parameter("q", query)
        }
        if (response.status.isSuccess()) {
            response.bodyAsText()
        } else {
            logger.warn("Web search request to {} failed with HTTP {}", endpoint, response.status.value)
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Web search request to {} failed: {}", endpoint, e.message)
        null
    }

    private fun renderResults(results: List<WebSearchResult>): String {
        val text = results.mapIndexed { index, result ->
            val snippet = truncate(result.snippet, MAX_SNIPPET_CHARS)
            val parts = mutableListOf("${index + 1}.", result.title, "—", result.url)
            if (snippet.isNotEmpty()) parts += listOf("—", snippet)
            parts.joinToString(" ")
        }.joinToString("\n")
        return truncate(text, MAX_OUTPUT_CHARS)
    }

    /** Cuts [text] to [limit] characters, marking the cut with an ellipsis. */
    private fun truncate(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit - 1).trimEnd() + "…"

    private companion object {
        /** A desktop browser is required: the bare client is served the anti-bot page. */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"

        /** The whole tool answer stays compact enough for one model turn. */
        const val MAX_OUTPUT_CHARS = 2_000
        const val MAX_SNIPPET_CHARS = 300
    }
}

/**
 * Parses the organic results out of a search result page. The first shape with
 * result links wins: Mojeek (`ob` / `s`), the DuckDuckGo html endpoint
 * (`result__a` / `result__snippet`) or its lite endpoint (`result-link` /
 * `result-snippet`). DuckDuckGo result links are redirects whose real target is
 * the URL-encoded `uddg` parameter (Mojeek links are the real URLs); titles and
 * snippets are stripped of tags and HTML entities.
 *
 * Pure and side-effect free, so the sample pages of the tests drive it directly.
 */
internal fun parseResults(html: String, max: Int): List<WebSearchResult> {
    if (max <= 0) return emptyList()
    for ((linkClass, snippetClass) in RESULT_SHAPES) {
        val links = htmlElements(html, linkClass)
        if (links.isEmpty()) continue
        val snippets = htmlElements(html, snippetClass)
        return links.mapIndexed { index, link ->
            WebSearchResult(
                title = cleanText(link.text),
                url = decodeResultUrl(link.href),
                snippet = snippets.getOrNull(index)?.let { cleanText(it.text) }.orEmpty(),
            )
        }.filter { it.title.isNotEmpty() && it.url.isNotEmpty() }.take(max)
    }
    return emptyList()
}

/**
 * True when the page is a rate-limit / anti-bot page instead of results:
 * DuckDuckGo's "anomaly" page or Mojeek's captcha page (HTTP 200 with the bare
 * `<title>Captcha`).
 */
internal fun looksLikeAntiBotPage(html: String): Boolean =
    html.contains("anomaly-modal") || html.contains("<title>Captcha", ignoreCase = true)

/** Decodes HTML entities (`&amp;`, `&quot;`, `&#39;`, `&#x2F;`, …) of a text fragment. */
internal fun decodeHtmlEntities(text: String): String =
    ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        val codePoint = when {
            body.startsWith("#x", ignoreCase = true) -> body.drop(2).toIntOrNull(16)
            body.startsWith("#") -> body.drop(1).toIntOrNull()
            else -> null
        }
        when {
            body.startsWith("#") -> codePoint?.let(::codePointToString) ?: match.value
            else -> NAMED_ENTITIES[body.lowercase()] ?: match.value
        }
    }

private fun codePointToString(codePoint: Int): String? =
    runCatching { String(Character.toChars(codePoint)) }.getOrNull()

/** Strips tags, decodes entities and collapses whitespace of one result fragment. */
private fun cleanText(raw: String): String =
    decodeHtmlEntities(raw.replace(TAG, " ")).replace(WHITESPACE, " ").trim()

/** The real target of a DuckDuckGo redirect link: `uddg` decoded, or the plain href. */
private fun decodeResultUrl(rawHref: String): String {
    val href = decodeHtmlEntities(rawHref.trim())
    val encoded = UDDG_PARAMETER.find(href)?.groupValues?.get(1)
    if (!encoded.isNullOrEmpty()) {
        return runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8) }.getOrDefault(encoded)
    }
    return if (href.startsWith("//")) "https:$href" else href
}

/** Elements carrying [className]; [HtmlElement.attributes] keeps the raw tag body. */
private fun htmlElements(html: String, className: String): List<HtmlElement> =
    Regex(
        """<(\w+)\b([^>]*class=["'][^"']*(?<![\w-])${Regex.escape(className)}(?![\w-])[^"']*["'][^>]*)>(.*?)</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    ).findAll(html).map { HtmlElement(it.groupValues[2], it.groupValues[3]) }.toList()

private class HtmlElement(private val attributes: String, val text: String) {
    val href: String get() = HREF_ATTRIBUTE.find(attributes)?.groupValues?.get(1).orEmpty()
}

/** The result-page markups the parser knows, as `link class` to `snippet class`. */
private val RESULT_SHAPES = listOf(
    "ob" to "s", // Mojeek — the primary source
    "result__a" to "result__snippet", // DuckDuckGo html endpoint
    "result-link" to "result-snippet", // DuckDuckGo lite endpoint
)
private val HREF_ATTRIBUTE = Regex("""href=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
private val UDDG_PARAMETER = Regex("""[?&]uddg=([^&"']+)""")
private val TAG = Regex("<[^>]*>")
private val WHITESPACE = Regex("\\s+")
private val ENTITY = Regex("""&(#\d+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);""")

private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "hellip" to "…",
    "mdash" to "—",
    "ndash" to "–",
    "laquo" to "«",
    "raquo" to "»",
    "ldquo" to "“",
    "rdquo" to "”",
    "lsquo" to "‘",
    "rsquo" to "’",
    "deg" to "°",
    "middot" to "·",
)
