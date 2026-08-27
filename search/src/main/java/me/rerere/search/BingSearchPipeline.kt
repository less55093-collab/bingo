package me.rerere.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.rerere.search.SearchResult.SearchResultItem
import okio.ByteString.Companion.decodeBase64
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

private val SEARCH_ENTITY_PATTERN = Regex("[\\p{IsHan}]{2,}?(?:大学|学院|医院|公司|学校|研究院|研究所|银行|机场|车站|法院|检察院)")
private val SEARCH_YEAR_PATTERN = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")

internal const val MAX_BING_SUBQUERIES = 6
internal const val MAX_BING_CONCURRENT_REQUESTS = 3

internal data class BingSearchConcept(
    val name: String,
    val queryTerm: String,
    val resultTerms: List<String>,
)

private data class SearchQualifierRule(
    val name: String,
    val queryPattern: Regex,
    val resultTerms: List<String>,
)

private val SEARCH_QUALIFIER_RULES = listOf(
    SearchQualifierRule("复试线", Regex("复试(?:分数)?线"), listOf("复试线", "复试分数线", "分数线", "复试基本要求")),
    SearchQualifierRule("分数线", Regex("分数线"), listOf("分数线")),
    SearchQualifierRule("金融", Regex("金融"), listOf("金融")),
    SearchQualifierRule(
        "专硕",
        Regex("专硕|专业硕士|专业学位"),
        listOf("专硕", "专业硕士", "专业学位", "金融硕士"),
    ),
    SearchQualifierRule("录取", Regex("录取"), listOf("录取")),
    SearchQualifierRule("调剂", Regex("调剂"), listOf("调剂")),
    SearchQualifierRule("报录比", Regex("报录比"), listOf("报录比", "报考录取")),
    SearchQualifierRule("招生简章", Regex("招生简章"), listOf("招生简章", "招生")),
    SearchQualifierRule("招生", Regex("招生"), listOf("招生")),
    SearchQualifierRule("初试", Regex("初试"), listOf("初试")),
    SearchQualifierRule("考试科目", Regex("考试科目"), listOf("考试科目", "初试科目")),
    SearchQualifierRule("压分", Regex("压分"), listOf("压分")),
    SearchQualifierRule("地域", Regex("地域|地区|区位"), listOf("地域", "地区", "区位", "城市")),
    SearchQualifierRule("学费", Regex("学费"), listOf("学费", "费用")),
    SearchQualifierRule("就业", Regex("就业"), listOf("就业")),
    SearchQualifierRule("宿舍", Regex("宿舍"), listOf("宿舍")),
)

private val SEARCH_QUERY_PREFIXES = listOf(
    "请帮我查找", "请帮我搜索", "请查找", "请搜索", "请查询",
    "查找", "搜索", "查询", "了解", "关于", "比较", "对比", "请问",
    "请", "和", "与", "及",
)
private val SEARCH_QUERY_SUFFIXES = listOf(
    "怎么样", "如何", "有哪些", "情况", "信息", "资料", "最新", "呢",
)
private val SEARCH_STOP_TOKENS = setOf("年", "的", "和", "与", "对", "比", "一下", "请", "帮我")

internal data class BingPlannedQuery(
    val query: String,
    val discoveryForEntity: String? = null,
)

internal data class BingSearchOutcome(
    val items: List<SearchResultItem>,
    val missingCoverage: List<String>,
    val attemptedQueries: List<String>,
    val failedQueries: List<String>,
)

private data class BingCandidate(
    val item: SearchResultItem,
    val score: Int,
    val sourceQueries: Set<Int>,
)

internal fun extractSearchEntities(query: String): List<String> {
    val matches = SEARCH_ENTITY_PATTERN.findAll(query).toList()
    return matches.mapIndexedNotNull { index, match ->
        val previous = matches.getOrNull(index - 1)
        val nestedCollege = previous != null &&
            previous.value.endsWith("大学") &&
            match.value.endsWith("学院") &&
            previous.range.last + 1 == match.range.first
        if (nestedCollege) null else stripSearchEntityNoise(match.value)
    }.filter { it.length >= 3 }.distinct()
}

private fun stripSearchEntityNoise(raw: String): String {
    var value = raw.trim('"', '\'', '“', '”', '「', '」', '、', '，')
    var changed: Boolean
    do {
        changed = false
        SEARCH_QUERY_PREFIXES.firstOrNull { value.startsWith(it) }?.let {
            value = value.removePrefix(it)
            changed = true
        }
    } while (changed && value.isNotEmpty())
    return value
}

private fun cleanResidualTerm(raw: String): String {
    var value = raw.trim()
    var changed: Boolean
    do {
        changed = false
        SEARCH_QUERY_PREFIXES.firstOrNull { value.startsWith(it) }?.let {
            value = value.removePrefix(it)
            changed = true
        }
    } while (changed && value.isNotEmpty())
    SEARCH_QUERY_SUFFIXES.forEach { suffix ->
        if (value.endsWith(suffix)) value = value.removeSuffix(suffix)
    }
    return value.trim('年', '的', '、', '，', ',', '.', '。')
}

internal fun extractSearchConcepts(query: String): List<BingSearchConcept> {
    var remaining = query
    extractSearchEntities(query).forEach { entity ->
        remaining = remaining.replace(entity, " ")
    }
    remaining = SEARCH_YEAR_PATTERN.replace(remaining, " ")

    val concepts = mutableListOf<BingSearchConcept>()
    SEARCH_QUALIFIER_RULES.forEach { rule ->
        if (rule.queryPattern.containsMatchIn(remaining)) {
            concepts += BingSearchConcept(rule.name, rule.name, rule.resultTerms)
            remaining = rule.queryPattern.replace(remaining, " ")
        }
    }

    val residualTerms = remaining
        .split(Regex("[\\s\\p{P}\\p{S}]+"))
        .map(::cleanResidualTerm)
        .filter { it.length >= 2 && it !in SEARCH_STOP_TOKENS }
        .distinct()
    concepts += residualTerms.map { term ->
        BingSearchConcept(name = term, queryTerm = term, resultTerms = listOf(term))
    }
    return concepts.distinctBy { it.name }
}

internal fun extractSearchYears(query: String): List<String> =
    SEARCH_YEAR_PATTERN.findAll(query).map { it.value }.distinct().toList()

internal fun planBingQueries(query: String, maxQueries: Int = MAX_BING_SUBQUERIES): List<BingPlannedQuery> {
    val normalized = query.replace(Regex("[\\s\\u201c\\u201d\\u300c\\u300d]+"), " ").trim()
    val entities = extractSearchEntities(normalized)
    val concepts = extractSearchConcepts(normalized)
    val years = extractSearchYears(normalized)
    if (entities.isEmpty()) return listOf(BingPlannedQuery(normalized))

    val simpleQuery = entities.size == 1 && concepts.size <= 1 && years.size <= 1
    if (simpleQuery) return listOf(BingPlannedQuery(normalized))

    val planned = mutableListOf<String>()
    fun add(value: String) {
        val candidate = value.trim().replace(Regex("\\s+"), " ")
        if (candidate.isNotEmpty() && candidate !in planned && planned.size < maxQueries) {
            planned += candidate
        }
    }

    val primaryConcept = concepts.firstOrNull()
    if (primaryConcept != null) {
        entities.forEach { entity -> add("$entity ${primaryConcept.queryTerm}") }
        years.forEach { year ->
            entities.forEach { entity ->
                add("$entity ${primaryConcept.queryTerm} $year")
            }
        }
        concepts.drop(1).forEach { concept ->
            entities.forEach { entity -> add("$entity ${concept.queryTerm}") }
        }
        years.forEach { year ->
            concepts.drop(1).forEach { concept ->
                entities.forEach { entity -> add("$entity ${concept.queryTerm} $year") }
            }
        }
    } else if (years.isNotEmpty()) {
        years.forEach { year -> entities.forEach { entity -> add("$entity $year") } }
    } else {
        entities.forEach(::add)
    }

    return planned.map(::BingPlannedQuery).ifEmpty { listOf(BingPlannedQuery(normalized)) }
}

internal fun missingSearchCoverage(
    query: String,
    items: List<SearchResultItem>,
): List<String> {
    val entities = extractSearchEntities(query)
    if (entities.isEmpty()) return emptyList()
    val content = items.joinToString(" ") { item -> "${item.title} ${item.text}" }.lowercase(Locale.ROOT)
    val missingEntities = entities
        .filterNot { content.contains(it.lowercase(Locale.ROOT)) }
        .map { "institution '$it'" }
    val missingConcepts = extractSearchConcepts(query)
        .filterNot { concept -> concept.resultTerms.any { content.contains(it.lowercase(Locale.ROOT)) } }
        .map { "qualifier '${it.name}'" }
    val missingYears = extractSearchYears(query)
        .filterNot { content.contains(it) }
        .map { "year '$it'" }
    return missingEntities + missingConcepts + missingYears
}

internal fun parseBingSearchHtml(html: String): List<SearchResultItem> {
    return Jsoup.parse(html)
        .select("li.b_algo")
        .mapNotNull { element ->
            val titleLink = element.selectFirst("h2 a[href]") ?: return@mapNotNull null
            val title = titleLink.text().trim()
            val url = unwrapBingRedirectUrl(titleLink.attr("href").trim())
            val snippet = element.selectFirst(".b_caption p, .b_snippet, p")?.text()?.trim().orEmpty()
            if (title.isBlank() || !url.startsWith("http://") && !url.startsWith("https://")) {
                null
            } else {
                SearchResultItem(title = title, url = url, text = snippet)
            }
        }
}

internal fun unwrapBingRedirectUrl(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    if (!uri.host.orEmpty().endsWith("bing.com", ignoreCase = true)) return url
    val encoded = uri.rawQuery.orEmpty().split('&')
        .firstOrNull { it.substringBefore('=').equals("u", ignoreCase = true) }
        ?.substringAfter('=', "")
        ?: return url
    val decoded = runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()) }.getOrDefault(encoded)
    val candidates = buildList {
        add(decoded)
        if (decoded.startsWith("a1")) add(decoded.removePrefix("a1"))
    }
    candidates.forEach { candidate ->
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) return candidate
        val base64 = runCatching {
            val padded = candidate.padEnd((candidate.length + 3) / 4 * 4, '=')
            padded.decodeBase64()?.utf8()
        }.getOrNull()
        if (base64?.startsWith("http://") == true || base64?.startsWith("https://") == true) return base64
    }
    return url
}

internal fun canonicalBingResultUrl(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url.substringBefore('#').lowercase(Locale.ROOT)
    val authority = uri.rawAuthority.orEmpty().lowercase(Locale.ROOT)
    val path = uri.rawPath.orEmpty().ifBlank { "/" }.trimEnd('/').ifBlank { "/" }
    val query = uri.rawQuery.orEmpty().split('&')
        .filter { part ->
            val name = part.substringBefore('=').lowercase(Locale.ROOT)
            name.isNotBlank() && !name.startsWith("utm_") && name !in setOf("gclid", "fbclid", "msclkid")
        }
        .sorted()
        .joinToString("&")
    return buildString {
        append(uri.scheme.orEmpty().lowercase(Locale.ROOT))
        append("://")
        append(authority)
        append(path)
        if (query.isNotBlank()) append('?').append(query)
    }
}

private fun textForScoring(item: SearchResultItem): Pair<String, String> =
    item.title.lowercase(Locale.ROOT) to item.text.lowercase(Locale.ROOT)

private fun scoreBingResult(originalQuery: String, plannedQuery: String, item: SearchResultItem, rank: Int): Int {
    val (title, text) = textForScoring(item)
    fun scoreTerm(term: String, titlePoints: Int, textPoints: Int): Int = when {
        title.contains(term.lowercase(Locale.ROOT)) -> titlePoints
        text.contains(term.lowercase(Locale.ROOT)) -> textPoints
        else -> 0
    }

    var score = (20 - rank.coerceAtMost(20)).coerceAtLeast(0)
    extractSearchEntities(originalQuery).forEach { entity -> score += scoreTerm(entity, 18, 9) }
    extractSearchConcepts(originalQuery).forEach { concept ->
        score += concept.resultTerms.maxOf { scoreTerm(it, 10, 5) }
    }
    extractSearchYears(originalQuery).forEach { year -> score += scoreTerm(year, 8, 4) }
    if (plannedQuery != originalQuery) score += 3
    return score
}

private fun isRelevantToPlannedQuery(query: String, item: SearchResultItem): Boolean {
    val entities = extractSearchEntities(query)
    return entities.isEmpty() || missingSearchCoverage(query, listOf(item)).isEmpty()
}

private fun isRelevantDiscoveryResult(originalQuery: String, entity: String, item: SearchResultItem): Boolean {
    val content = "${item.title} ${item.text}".lowercase(Locale.ROOT)
    if (!content.contains(entity.lowercase(Locale.ROOT))) return false
    val concepts = extractSearchConcepts(originalQuery)
    if (concepts.isNotEmpty() && concepts.none { concept ->
            concept.resultTerms.any { content.contains(it.lowercase(Locale.ROOT)) }
        }
    ) return false
    val years = extractSearchYears(originalQuery)
    return years.isEmpty() || years.any(content::contains)
}

internal suspend fun executeBingSearch(
    query: String,
    resultSize: Int,
    fetchResults: suspend (String) -> List<SearchResultItem>,
): BingSearchOutcome {
    val plannedQueries = planBingQueries(query)
    suspend fun fetch(plans: List<BingPlannedQuery>) = plans
        .chunked(MAX_BING_CONCURRENT_REQUESTS)
        .flatMap { batch ->
            coroutineScope {
                batch.map { planned ->
                    async {
                        val result = try {
                            Result.success(fetchResults(planned.query))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                        planned to result
                    }
                }.awaitAll()
            }
        }
    val initialFetched = fetch(plannedQueries)
    val initialRelevantItems = initialFetched.flatMap { (planned, response) ->
        response.getOrNull().orEmpty().filter { item -> isRelevantToPlannedQuery(planned.query, item) }
    }
    val initialContent = initialRelevantItems.joinToString(" ") { "${it.title} ${it.text}" }.lowercase(Locale.ROOT)
    val discoveryQueries = extractSearchEntities(query)
        .filterNot { initialContent.contains(it.lowercase(Locale.ROOT)) }
        .take(MAX_BING_CONCURRENT_REQUESTS)
        .map { entity -> BingPlannedQuery(query = entity, discoveryForEntity = entity) }
    val allPlannedQueries = plannedQueries + discoveryQueries
    val fetched = initialFetched + fetch(discoveryQueries)
    val failedQueries = fetched.filter { it.second.isFailure }.map { it.first.query }
    val candidates = linkedMapOf<String, BingCandidate>()

    fetched.forEachIndexed { queryIndex, (planned, response) ->
        response.getOrNull().orEmpty().forEachIndexed { rank, item ->
            val directUrl = unwrapBingRedirectUrl(item.url)
            val normalized = item.copy(url = directUrl)
            val relevant = planned.discoveryForEntity?.let { entity ->
                isRelevantDiscoveryResult(query, entity, normalized)
            } ?: isRelevantToPlannedQuery(planned.query, normalized)
            if (directUrl.isBlank() || !relevant) return@forEachIndexed
            val key = canonicalBingResultUrl(directUrl)
            val score = scoreBingResult(query, planned.query, normalized, rank)
            val existing = candidates[key]
            candidates[key] = if (existing == null) {
                BingCandidate(normalized, score, setOf(queryIndex))
            } else {
                val betterItem = if (score > existing.score || normalized.text.length > existing.item.text.length) normalized else existing.item
                BingCandidate(
                    item = betterItem,
                    score = maxOf(score, existing.score),
                    sourceQueries = existing.sourceQueries + queryIndex,
                )
            }
        }
    }

    require(candidates.isNotEmpty()) {
        buildString {
            append("Bing returned no relevant results after focused queries: ")
            append(allPlannedQueries.joinToString { "'${it.query}'" })
            if (failedQueries.isNotEmpty()) append(". Failed queries: ${failedQueries.joinToString()}")
            append(". Retry with one institution and one core qualifier per search.")
        }
    }

    val limit = resultSize.coerceIn(1, 20)
    val ranked = candidates.values.sortedWith(compareByDescending<BingCandidate> { it.score }.thenBy { it.item.url })
    val selected = linkedMapOf<String, BingCandidate>()
    allPlannedQueries.indices.forEach { queryIndex ->
        ranked.firstOrNull { queryIndex in it.sourceQueries }?.let { selected[canonicalBingResultUrl(it.item.url)] = it }
    }
    ranked.forEach { candidate ->
        val key = canonicalBingResultUrl(candidate.item.url)
        if (selected.size < limit && key !in selected) selected[key] = candidate
    }
    val items = selected.values.take(limit).map { it.item }
    val missingCoverage = missingSearchCoverage(query, items)
    return BingSearchOutcome(
        items = items,
        missingCoverage = missingCoverage,
        attemptedQueries = allPlannedQueries.map { it.query },
        failedQueries = failedQueries,
    )
}
