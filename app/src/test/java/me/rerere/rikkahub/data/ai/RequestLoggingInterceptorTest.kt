package me.rerere.rikkahub.data.ai

import java.io.IOException
import me.rerere.common.android.Logging
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RequestLoggingInterceptorTest {
    @Before
    fun setUp() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(true)
    }

    @After
    fun tearDown() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(false)
    }

    @Test
    fun `request diagnostic excludes body headers and query values`() {
        val client = clientWithTerminalInterceptor { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("response-secret".toResponseBody())
                .build()
        }

        client.newCall(sensitiveRequest()).execute().close()

        val entry = Logging.getRequestLogs().single()
        val serialized = entry.toString()
        assertEquals("POST", entry.method)
        assertTrue(entry.url.startsWith("https://example.test/"))
        assertFalse(serialized.contains("query-secret"))
        assertFalse(serialized.contains("header-secret"))
        assertFalse(serialized.contains("prompt-secret"))
        assertFalse(serialized.contains("response-secret"))
        assertTrue(entry.requestHeaders.isEmpty())
        assertTrue(entry.responseHeaders.isEmpty())
        assertNull(entry.requestBody)
    }

    @Test
    fun `request diagnostic records error type without error message`() {
        val client = clientWithTerminalInterceptor {
            throw IOException("gateway response contains prompt-secret")
        }

        try {
            client.newCall(sensitiveRequest()).execute()
        } catch (_: IOException) {
            // Expected: this test only verifies what the diagnostic path retains.
        }

        val entry = Logging.getRequestLogs().single()
        assertEquals("IOException", entry.error)
        assertFalse(entry.toString().contains("prompt-secret"))
    }

    private fun clientWithTerminalInterceptor(
        terminal: (Request) -> Response,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(RequestLoggingInterceptor())
        .addInterceptor { chain -> terminal(chain.request()) }
        .build()

    private fun sensitiveRequest(): Request = Request.Builder()
        .url("https://example.test/v1/chat/completions?access_token=query-secret")
        .header("Authorization", "Bearer header-secret")
        .header("X-Prompt", "prompt-secret")
        .post(
            "{\"messages\":[{\"content\":\"prompt-secret\"}]}"
                .toRequestBody("application/json".toMediaType())
        )
        .build()
}
