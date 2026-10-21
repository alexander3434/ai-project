package com.aiturbo.tools

import io.ktor.server.config.ApplicationConfig

/**
 * Web-search settings of the chat's `web_search` tool, read from application.conf.
 * Environment variables take precedence over the file values (the
 * `${?WEB_SEARCH_*}` substitutions in application.conf).
 *
 * [mojeekBaseUrl] is the primary source; [baseUrl] and [fallbackBaseUrl] are the
 * DuckDuckGo html and lite endpoints kept as fallbacks. One endpoint is not
 * enough: the public search pages serve anti-bot pages instead of results from
 * time to time (DuckDuckGo's "anomaly" page, Mojeek's captcha), and the tool
 * walks the whole chain before it gives up.
 */
data class WebSearchConfig(
    val mojeekBaseUrl: String = DEFAULT_MOJEEK_BASE_URL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val fallbackBaseUrl: String = DEFAULT_FALLBACK_BASE_URL,
    val maxResults: Int = DEFAULT_MAX_RESULTS,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
) {
    companion object {
        const val DEFAULT_MOJEEK_BASE_URL = "https://www.mojeek.com/search"
        const val DEFAULT_BASE_URL = "https://html.duckduckgo.com/html/"
        const val DEFAULT_FALLBACK_BASE_URL = "https://lite.duckduckgo.com/lite/"
        const val DEFAULT_MAX_RESULTS = 5
        const val DEFAULT_TIMEOUT_SECONDS = 10

        fun from(config: ApplicationConfig): WebSearchConfig = WebSearchConfig(
            mojeekBaseUrl = config.propertyOrNull("webSearch.mojeekBaseUrl")?.getString()
                ?: DEFAULT_MOJEEK_BASE_URL,
            baseUrl = config.propertyOrNull("webSearch.baseUrl")?.getString() ?: DEFAULT_BASE_URL,
            fallbackBaseUrl = config.propertyOrNull("webSearch.fallbackBaseUrl")?.getString()
                ?: DEFAULT_FALLBACK_BASE_URL,
            maxResults = config.propertyOrNull("webSearch.maxResults")?.getString()?.toIntOrNull()
                ?: DEFAULT_MAX_RESULTS,
            timeoutSeconds = config.propertyOrNull("webSearch.timeoutSeconds")?.getString()?.toIntOrNull()
                ?: DEFAULT_TIMEOUT_SECONDS,
        )
    }
}
