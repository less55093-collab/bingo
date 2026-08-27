package me.rerere.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.search.SearchResult.SearchResultItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BingSearchServiceTest {
    @Test
    fun `extracts every Chinese institution from a model query`() {
        assertEquals(
            listOf("东北财经大学", "山东财经大学"),
            extractSearchEntities("“东北财经大学” “山东财经大学” 金融专硕 压分 地域"),
        )
    }

    @Test
    fun `does not treat a university department as a second institution`() {
        assertEquals(
            listOf("东北财经大学"),
            extractSearchEntities("东北财经大学金融学院"),
        )
    }

    @Test
    fun `removes natural language noise around institution names`() {
        assertEquals(
            listOf("东北财经大学", "山东财经大学"),
            extractSearchEntities("请比较东北财经大学和山东财经大学"),
        )
    }

    @Test
    fun `school home page does not cover a qualified query`() {
        val results = listOf(
            SearchResultItem("山东财经大学", "https://www.sdufe.edu.cn", "学校首页"),
        )

        assertEquals(
            listOf("qualifier '复试线'"),
            missingSearchCoverage("山东财经大学 复试线", results),
        )
    }

    @Test
    fun `overlapping qualifier phrases are extracted once`() {
        assertEquals(
            listOf("招生简章"),
            extractSearchConcepts("山东大学 招生简章").map { it.name },
        )
        assertEquals(
            listOf("复试线"),
            extractSearchConcepts("山东大学 复试分数线").map { it.name },
        )
    }

    @Test
    fun `multi institution query rejects results covering only one institution`() {
        val results = listOf(
            SearchResultItem("东北财经大学金融学院", "https://sf.dufe.edu.cn", "金融专硕招生信息"),
        )

        assertEquals(
            listOf("institution '山东财经大学'"),
            missingSearchCoverage("东北财经大学 山东财经大学 金融专硕", results),
        )
    }

    @Test
    fun `institution and score line result covers a focused query`() {
        val results = listOf(
            SearchResultItem(
                "东北财经大学历年研究生分数线",
                "https://graduate.dufe.edu.cn",
                "东北财经大学研究生院公布复试基本要求",
            ),
        )

        assertTrue(missingSearchCoverage("东北财经大学 复试线", results).isEmpty())
    }

    @Test
    fun `planner splits multiple institutions and never falls back to entity only`() {
        val planned = planBingQueries(
            "东北财经大学 山东财经大学 金融专硕 压分 地域",
        ).map { it.query }

        assertTrue(planned.size <= MAX_BING_SUBQUERIES)
        assertTrue(planned.all { it.contains("东北财经大学") || it.contains("山东财经大学") })
        assertTrue(planned.all { it != "东北财经大学" && it != "山东财经大学" })
        assertTrue(planned.any { it == "东北财经大学 金融" })
        assertTrue(planned.any { it == "山东财经大学 金融" })
    }

    @Test
    fun `focused execution filters school home pages and honors result size`() = runBlocking {
        val outcome = executeBingSearch("山东财经大学 复试线", resultSize = 1) {
            listOf(
                SearchResultItem("山东财经大学", "https://www.sdufe.edu.cn", "学校首页"),
                SearchResultItem(
                    "山东财经大学历年研究生分数线",
                    "https://www.sdufe.edu.cn/score?utm_source=bing",
                    "复试分数线与历年分数线",
                ),
            )
        }

        assertEquals(1, outcome.items.size)
        assertTrue(outcome.items.single().title.contains("分数线"))
        assertTrue(outcome.missingCoverage.isEmpty())
    }

    @Test
    fun `adaptive discovery can recover qualified pages without returning the school home page`() = runBlocking {
        val requested = mutableListOf<String>()
        val outcome = executeBingSearch("山东财经大学 复试线", resultSize = 10) { query ->
            requested += query
            if (query == "山东财经大学 复试线") {
                listOf(SearchResultItem("山东省人民政府", "https://www.shandong.gov.cn", "山东旅游信息"))
            } else {
                listOf(
                    SearchResultItem("山东财经大学", "https://www.sdufe.edu.cn", "学校首页"),
                    SearchResultItem(
                        "山东财经大学2025年录取分数线",
                        "https://www.example.com/sdufe-score",
                        "历年复试基本要求",
                    ),
                )
            }
        }

        assertEquals(listOf("山东财经大学 复试线", "山东财经大学"), requested)
        assertEquals(1, outcome.items.size)
        assertTrue(outcome.items.single().title.contains("分数线"))
    }

    @Test
    fun `multi institution execution keeps relevant results from both institutions`() = runBlocking {
        val requested = mutableListOf<String>()
        val outcome = executeBingSearch("东北财经大学 山东财经大学 金融", resultSize = 10) { query ->
            requested += query
            when {
                query.contains("东北财经大学") -> listOf(
                    SearchResultItem("东北财经大学金融学院", "https://dufe.example/finance", "金融学科信息"),
                )
                else -> listOf(
                    SearchResultItem("山东财经大学金融学院", "https://sdufe.example/finance", "金融学科信息"),
                )
            }
        }

        assertEquals(2, requested.size)
        assertEquals(2, outcome.items.size)
        assertTrue(outcome.items.any { it.url.contains("dufe.example") })
        assertTrue(outcome.items.any { it.url.contains("sdufe.example") })
        assertTrue(outcome.missingCoverage.isEmpty())
    }

    @Test
    fun `one failed subquery preserves valid partial results and reports missing coverage`() = runBlocking {
        val outcome = executeBingSearch("东北财经大学 山东财经大学 金融", resultSize = 10) { query ->
            if (query.contains("山东财经大学")) error("temporary Bing failure")
            listOf(
                SearchResultItem("东北财经大学金融学院", "https://dufe.example/finance", "金融学科信息"),
            )
        }

        assertEquals(1, outcome.items.size)
        assertEquals(listOf("山东财经大学 金融", "山东财经大学"), outcome.failedQueries)
        assertEquals(listOf("institution '山东财经大学'"), outcome.missingCoverage)
    }

    @Test
    fun `cancellation is never downgraded to a failed subquery`() = runBlocking {
        try {
            executeBingSearch("山东财经大学 金融", resultSize = 10) {
                throw CancellationException("stopped by user")
            }
            fail("Expected cancellation to propagate")
        } catch (error: CancellationException) {
            assertEquals("stopped by user", error.message)
        }
    }

    @Test
    fun `parser extracts snippets and unwraps Bing redirect links`() {
        val html = """
            <ol id="b_results">
              <li class="b_algo">
                <h2><a href="https://www.bing.com/ck/a?u=https%3A%2F%2Fexample.com%2Fpage">Example</a></h2>
                <div class="b_caption"><p>Useful snippet</p></div>
              </li>
              <li class="b_algo"><h2>Missing link</h2></li>
            </ol>
        """.trimIndent()

        val result = parseBingSearchHtml(html).single()
        assertEquals("https://example.com/page", result.url)
        assertEquals("Useful snippet", result.text)
    }

    @Test
    fun `canonical URL removes tracking parameters`() {
        assertEquals(
            "https://example.com/path?a=1&b=2",
            canonicalBingResultUrl("https://EXAMPLE.com/path/?utm_source=bing&b=2&a=1#fragment"),
        )
    }

    @Test
    fun `ordinary query does not require institution coverage`() {
        assertTrue(missingSearchCoverage("今日天气", emptyList()).isEmpty())
        assertFalse(planBingQueries("今日天气").isEmpty())
    }
}
