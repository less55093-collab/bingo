package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class OpenAIProviderAsyncImageTaskTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAIProvider(OkHttpClient(), imageTaskPollIntervalMillis = 1)
    }

    @After
    fun tearDown() = server.close()

    private fun setting() = ProviderSetting.OpenAI(
        id = Uuid.random(),
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "sk-image",
        useAsyncImageTasks = true,
    )

    private fun model() = Model(
        id = Uuid.random(),
        modelId = "gpt-image-2",
        displayName = "AI image",
        type = ModelType.IMAGE,
    )

    @Test
    fun `generation submits once then polls until completed`() = runBlocking {
        server.enqueue(jsonResponse(202, TASK_SUBMITTED))
        server.enqueue(jsonResponse(200, TASK_PROCESSING))
        server.enqueue(jsonResponse(200, TASK_COMPLETED))
        var submitted = ""

        val images = provider.generateImage(
            setting(),
            ImageGenerationParams(
                model = model(),
                prompt = "circle",
                onTaskSubmitted = { submitted = it },
            ),
        ).toList()

        assertEquals("imgtask_test", submitted)
        assertEquals("AAAA", images.single().data)
        assertEquals("/v1/images/generations/async", server.takeRequest().url.encodedPath)
        assertEquals("/v1/images/tasks/imgtask_test", server.takeRequest().url.encodedPath)
        assertEquals("/v1/images/tasks/imgtask_test", server.takeRequest().url.encodedPath)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `failed task exposes gateway message and invokes terminal callback`() {
        server.enqueue(jsonResponse(202, TASK_SUBMITTED))
        server.enqueue(jsonResponse(200, TASK_FAILED))
        var failedTask = ""

        val result = runCatching {
            runBlocking {
                provider.generateImage(
                    setting(),
                    ImageGenerationParams(
                        model = model(),
                        prompt = "circle",
                        onTaskFailed = { failedTask = it },
                    ),
                ).toList()
            }
        }

        assertTrue(result.exceptionOrNull()?.message?.contains("Upstream request failed") == true)
        assertEquals("imgtask_test", failedTask)
    }

    @Test
    fun `missing task invokes terminal callback`() {
        server.enqueue(jsonResponse(202, TASK_SUBMITTED))
        server.enqueue(
            jsonResponse(404, """{"error":{"message":"image task not found"}}""")
        )
        var failedTask = ""

        runCatching {
            runBlocking {
                provider.generateImage(
                    setting(),
                    ImageGenerationParams(
                        model = model(),
                        prompt = "circle",
                        onTaskFailed = { failedTask = it },
                    ),
                ).toList()
            }
        }

        assertEquals("imgtask_test", failedTask)
    }

    @Test
    fun `edit uses multipart async endpoint`() = runBlocking {
        val image = File.createTempFile("async-edit", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            server.enqueue(jsonResponse(202, TASK_SUBMITTED))
            server.enqueue(jsonResponse(200, TASK_COMPLETED))

            provider.editImage(
                setting(),
                ImageEditParams(model = model(), prompt = "edit", images = listOf(image.absolutePath)),
            ).toList()

            val request = server.takeRequest()
            assertEquals("/v1/images/edits/async", request.url.encodedPath)
            assertTrue(request.headers["Content-Type"]?.startsWith("multipart/form-data") == true)
        } finally {
            image.delete()
        }
    }

    @Test
    fun `resume polls existing task without submitting again`() = runBlocking {
        server.enqueue(jsonResponse(200, TASK_COMPLETED))

        val images = provider.resumeImageTask(setting(), "imgtask_test").toList()

        assertEquals("AAAA", images.single().data)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/images/tasks/imgtask_test", request.url.encodedPath)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `completed object storage url is downloaded`() = runBlocking {
        val imageUrl = server.url("/objects/result.png")
        server.enqueue(jsonResponse(202, TASK_SUBMITTED))
        server.enqueue(
            jsonResponse(
                200,
                """{"task_id":"imgtask_test","status":"completed","result":{"data":[{"url":"$imageUrl"}]}}""",
            )
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/png")
                .body("PNG")
                .build()
        )

        val item = provider.generateImage(
            setting(),
            ImageGenerationParams(model = model(), prompt = "circle"),
        ).toList().single()

        val localPath = item.localPath
        assertNotNull(localPath)
        assertEquals("PNG", File(localPath!!).readText())
        File(localPath).delete()
        server.takeRequest() // submit
        server.takeRequest() // poll
        assertEquals("/objects/result.png", server.takeRequest().url.encodedPath)
    }

    private fun jsonResponse(code: Int, body: String) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    companion object {
        private const val TASK_SUBMITTED =
            """{"task_id":"imgtask_test","status":"processing"}"""
        private const val TASK_PROCESSING =
            """{"task_id":"imgtask_test","status":"processing"}"""
        private const val TASK_COMPLETED =
            """{"task_id":"imgtask_test","status":"completed","result":{"data":[{"b64_json":"AAAA"}]}}"""
        private const val TASK_FAILED =
            """{"task_id":"imgtask_test","status":"failed","error":{"message":"Upstream request failed"}}"""
    }
}
