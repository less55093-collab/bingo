package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale

private const val BING_TIMEOUT_MILLIS = 8_000

object BingSearchService : SearchService<SearchServiceOptions.BingLocalOptions> {
    override val name: String = "Bing"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.bing_desc))
    }

    override fun parameters(options: SearchServiceOptions.BingLocalOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Short search query. For Chinese institutions, use one full institution " +
                            "name and one core qualifier per call, such as '东北财经大学 复试线'."
                    )
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.BingLocalOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content?.trim()
                ?: error("query is required")
            require(query.isNotEmpty()) { "query must not be blank" }
            val fetchCount = (commonOptions.resultSize.coerceIn(1, 20) * 2).coerceIn(10, 20)
            val outcome = executeBingSearch(query, commonOptions.resultSize) { plannedQuery ->
                fetchBingResults(plannedQuery, fetchCount)
            }
            val warning = outcome.missingCoverage.takeIf { it.isNotEmpty() }?.let { missing ->
                buildString {
                    append("Partial Bing results. Missing requested coverage: ${missing.joinToString()}. ")
                    append("Do not infer the missing facts; search each missing institution or qualifier separately.")
                }
            }
            SearchResult(answer = warning, items = outcome.items)
        }
    }

    private fun fetchBingResults(query: String, count: Int): List<SearchResultItem> {
        val isChinese = query.any { it in '\u4e00'..'\u9fff' }
        val locale = Locale.getDefault()
        val market = if (isChinese) "zh-CN" else {
            val language = locale.language.ifBlank { "en" }
            val country = locale.country.ifBlank { "US" }
            "$language-$country"
        }
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = buildString {
            append("https://www.bing.com/search?q=").append(encodedQuery)
            append("&count=").append(count)
            append("&mkt=").append(URLEncoder.encode(market, Charsets.UTF_8.name()))
            append("&setlang=").append(if (isChinese) "zh-Hans" else locale.language.ifBlank { "en" })
            if (isChinese) append("&cc=CN&ensearch=0")
        }
        val response = Jsoup.connect(url)
            .userAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", if (isChinese) "zh-CN,zh;q=0.9,en;q=0.7" else "$market,en;q=0.7")
            .header("Accept-Encoding", "gzip, deflate")
            .header("Connection", "keep-alive")
            .referrer("https://www.bing.com/")
            .cookie("SRCHHPGUSR", "ULSR=1")
            .timeout(BING_TIMEOUT_MILLIS)
            .ignoreHttpErrors(true)
            .execute()
        require(response.statusCode() in 200..299) {
            "Bing request failed with HTTP ${response.statusCode()}"
        }
        return parseBingSearchHtml(response.body())
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Bing"))
    }
}
