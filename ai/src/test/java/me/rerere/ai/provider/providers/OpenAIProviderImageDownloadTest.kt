package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 覆盖 `data[].url` 分支: 网关回的是远端 URL, 出图后还要再下一次图, 这条路才是耗时大头.
 * 用 MockWebServer 顶掉真实图床, 断言的是「下载落到临时文件、不经过 base64、多张并发、失败不漏文件」。
 */
class OpenAIProviderImageDownloadTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAIProvider(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun pngBody(sizeBytes: Int): Buffer =
        Buffer().write(ByteArray(sizeBytes) { (it % 251).toByte() })

    private fun parse(bodyStr: String) = runBlocking { provider.parseImageResponse(bodyStr) }

    private fun urlPayload(vararg paths: String): String =
        paths.joinToString(prefix = """{"data":[""", postfix = "]}") {
            """{"url":"${server.url(it)}"}"""
        }

    @Test
    fun `url payload lands in a local file instead of base64`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/png")
                .body(pngBody(4096))
                .build()
        )

        val items = parse(urlPayload("/img0.png"))

        assertEquals(1, items.size)
        val item = items[0]
        // 关键回归点: 走 localPath, data 保持为空 —— 不再把整张图编成 base64 字符串来回倒一遍.
        assertEquals("", item.data)
        val path = assertNotNull("expected a downloaded file", item.localPath).let { item.localPath!! }
        val file = File(path)
        assertTrue("downloaded file should exist", file.exists())
        assertEquals(4096L, file.length())
        assertEquals("image/png", item.mimeType)
        file.delete()
    }

    @Test
    fun `multiple urls download concurrently and keep response order`() {
        // 按 path 派发, 不用 enqueue: 并发下 enqueue 是按连接到达顺序配对的,
        // 谁先连上就拿走队首的 body, 没法用来验证「结果顺序 == response 顺序」.
        val sizeByPath = mapOf("/a.png" to 1024, "/b.png" to 2048, "/c.png" to 3072)
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                val size = sizeByPath[request.url.encodedPath] ?: return MockResponse(code = 404)
                return MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "image/png")
                    .body(pngBody(size))
                    // 每张都压 300ms: 串行下载总耗时会 >=900ms, 并发则接近 300ms.
                    .bodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
            }
        }

        val startedAt = System.nanoTime()
        val items = parse(urlPayload("/a.png", "/b.png", "/c.png"))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(3, items.size)
        // 顺序必须跟 response 里的顺序一致, 不能被 async 的完成先后打乱.
        assertEquals(
            listOf(1024L, 2048L, 3072L),
            items.map { File(it.localPath!!).length() },
        )
        assertTrue(
            "3 downloads of 300ms each took ${elapsedMs}ms; expected concurrent (<750ms), not serial",
            elapsedMs < 750,
        )
        items.forEach { File(it.localPath!!).delete() }
    }

    @Test
    fun `content type from the cdn overrides the png default`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/webp")
                .body(pngBody(64))
                .build()
        )

        val items = parse(urlPayload("/img.webp"))

        assertEquals("image/webp", items[0].mimeType)
        File(items[0].localPath!!).delete()
    }

    @Test
    fun `retries a failing download and succeeds on a later attempt`() {
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/png")
                .body(pngBody(512))
                .build()
        )

        val items = parse(urlPayload("/flaky.png"))

        assertEquals(512L, File(items[0].localPath!!).length())
        assertEquals(2, server.requestCount)
        File(items[0].localPath!!).delete()
    }

    @Test
    fun `gives up after the attempt budget and leaves no partial files behind`() {
        repeat(3) { server.enqueue(MockResponse.Builder().code(500).build()) }
        val before = tempFileNames()

        try {
            parse(urlPayload("/broken.png"))
            fail("expected the download to fail after exhausting retries")
        } catch (e: Exception) {
            assertTrue(
                "unexpected message: ${e.message}",
                e.message?.contains("500") == true,
            )
        }

        assertEquals(3, server.requestCount)
        // 半截文件必须被清掉, 否则每次失败都漏一个临时文件在 cache 里.
        assertEquals(before, tempFileNames())
    }

    @Test
    fun `an empty body is treated as a failed download`() {
        repeat(3) {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "image/png")
                    .build()
            )
        }
        val before = tempFileNames()

        try {
            parse(urlPayload("/empty.png"))
            fail("expected an empty body to be rejected")
        } catch (e: Exception) {
            assertTrue("unexpected message: ${e.message}", e.message?.contains("empty") == true)
        }

        assertEquals(before, tempFileNames())
    }

    @Test
    fun `a payload with neither b64_json nor url fails loudly`() {
        try {
            parse("""{"data":[{"revised_prompt":"a cat"}]}""")
            fail("expected a payload with no image source to fail")
        } catch (e: Exception) {
            assertTrue("unexpected message: ${e.message}", e.message?.contains("url") == true)
        }
    }

    @Test
    fun `mixed b64 and url items are both materialized in order`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/png")
                .body(pngBody(256))
                .build()
        )

        val items = parse(
            """{"data":[{"b64_json":"QUJD"},{"url":"${server.url("/second.png")}"}]}"""
        )

        assertEquals(2, items.size)
        assertEquals("QUJD", items[0].data)
        assertNull(items[0].localPath)
        assertEquals("", items[1].data)
        assertEquals(256L, File(items[1].localPath!!).length())
        File(items[1].localPath!!).delete()
    }

    /** 下载临时目录在无 Context 时退回 JVM 临时目录, 用它来断言没有残留. */
    private fun tempFileNames(): Set<String> {
        val dir = File(System.getProperty("java.io.tmpdir") ?: ".")
        return dir.listFiles { f -> f.isFile && f.name.startsWith("imggen_dl_") }
            ?.map { it.name }?.toSet() ?: emptySet()
    }
}
