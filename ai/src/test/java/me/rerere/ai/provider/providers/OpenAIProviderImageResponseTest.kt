package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.ImageGenerationItem
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 覆盖 parseImageResponse 的 base64 分支。URL 分支要真去下载, 留给手动验证;
 * 这里锁住的是「并发化改造之后, 内联的 b64 结果仍然按原顺序原样返回」。
 */
class OpenAIProviderImageResponseTest {

    private lateinit var provider: OpenAIProvider

    @Before
    fun setUp() {
        provider = OpenAIProvider(OkHttpClient())
    }

    private fun parse(bodyStr: String): List<ImageGenerationItem> = runBlocking {
        provider.parseImageResponse(bodyStr)
    }

    @Test
    fun `base64 payload is returned inline without a local file`() {
        val items = parse("""{"data":[{"b64_json":"QUJD"}]}""")

        assertEquals(1, items.size)
        assertEquals("QUJD", items[0].data)
        assertEquals("image/png", items[0].mimeType)
        // localPath 为空表示不需要搬运临时文件, 消费方应该走 base64 解码那条路.
        assertNull(items[0].localPath)
    }

    @Test
    fun `per-item output_format wins over the top level default`() {
        val items = parse(
            """{"output_format":"png","data":[{"b64_json":"QQ==","output_format":"webp"}]}"""
        )

        assertEquals("image/webp", items[0].mimeType)
    }

    @Test
    fun `top level output_format applies when the item omits it`() {
        val items = parse("""{"output_format":"jpeg","data":[{"b64_json":"QQ=="}]}""")

        assertEquals("image/jpeg", items[0].mimeType)
    }

    @Test
    fun `data uri in the url field is treated as inline data`() {
        // 网关实测会把整张图塞进 data URI 而不是给可下载地址. OkHttp 不认 data: scheme,
        // 走下载分支会直接抛 IllegalArgumentException, 所以必须当内联数据解析.
        val items = parse("""{"data":[{"url":"data:image/png;base64,QUJD"}]}""")

        assertEquals(1, items.size)
        assertEquals("QUJD", items[0].data)
        assertEquals("image/png", items[0].mimeType)
        assertNull(items[0].localPath)
    }

    @Test
    fun `data uri keeps its own mime type`() {
        val items = parse("""{"data":[{"url":"data:image/webp;base64,QQ=="}]}""")

        assertEquals("image/webp", items[0].mimeType)
        assertEquals("QQ==", items[0].data)
    }

    @Test
    fun `multiple base64 items keep their response order`() {
        val items = parse(
            """{"data":[{"b64_json":"QQ=="},{"b64_json":"Qg=="},{"b64_json":"Qw=="}]}"""
        )

        assertEquals(listOf("QQ==", "Qg==", "Qw=="), items.map { it.data })
    }
}
