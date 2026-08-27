package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageGenerationTerminalException
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.keyFingerprint
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
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
                idempotencyKey = "request-generation-1",
                onTaskSubmitted = { submitted = it },
            ),
        ).toList()

        assertEquals("imgtask_test", submitted)
        assertEquals("AAAA", images.single().data)
        val submitRequest = server.takeRequest()
        assertEquals("/v1/images/generations/async", submitRequest.url.encodedPath)
        assertEquals("request-generation-1", submitRequest.headers["Idempotency-Key"])
        assertEquals("/v1/images/tasks/imgtask_test", server.takeRequest().url.encodedPath)
        assertEquals("/v1/images/tasks/imgtask_test", server.takeRequest().url.encodedPath)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `recovered generation submit uses persisted API key fingerprint`() = runBlocking {
        server.enqueue(jsonResponse(202, TASK_SUBMITTED))
        server.enqueue(jsonResponse(200, TASK_COMPLETED))

        val images = provider.generateImage(
            setting().copy(apiKey = "sk-first,sk-second"),
            ImageGenerationParams(
                model = model(),
                prompt = "circle",
                idempotencyKey = "request-recovered-generation",
                requiredApiKeyFingerprint = keyFingerprint("sk-second"),
            ),
        ).toList()

        assertEquals("AAAA", images.single().data)
        assertEquals("Bearer sk-second", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer sk-second", server.takeRequest().headers["Authorization"])
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `recovered generation without persisted API key never posts`() {
        val result = runCatching {
            runBlocking {
                provider.generateImage(
                    setting().copy(apiKey = "sk-first,sk-second"),
                    ImageGenerationParams(
                        model = model(),
                        prompt = "circle",
                        requiredApiKeyFingerprint = "",
                    ),
                ).toList()
            }
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("original API key") == true)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `recovered generation with removed API key never posts`() {
        val result = runCatching {
            runBlocking {
                provider.generateImage(
                    setting().copy(apiKey = "sk-current"),
                    ImageGenerationParams(
                        model = model(),
                        prompt = "circle",
                        requiredApiKeyFingerprint = keyFingerprint("sk-removed"),
                    ),
                ).toList()
            }
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("no longer configured") == true)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `bound retry after lost submit reuses original API key`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .onResponseStart(SocketEffect.CloseSocket())
                .build()
        )
        val setting = setting().copy(apiKey = "sk-first,sk-second")
        var selectedFingerprint: String? = null
        val firstAttempt = runCatching {
            provider.generateImage(
                setting,
                ImageGenerationParams(
                    model = model(),
                    prompt = "circle",
                    idempotencyKey = "request-lost-submit",
                    onImageKeySelected = { fingerprint ->
                        if (selectedFingerprint == null) selectedFingerprint = fingerprint
                    },
                ),
            ).toList()
        }

        assertTrue(firstAttempt.isFailure)
        val firstRequest = server.takeRequest()
        val firstAuthorization = firstRequest.headers["Authorization"]
        assertNotNull(selectedFingerprint)
        assertNotNull(firstAuthorization)
        assertEquals(1, server.requestCount)

        server.enqueue(jsonResponse(202, TASK_SUBMITTED))
        server.enqueue(jsonResponse(200, TASK_COMPLETED))
        val images = provider.generateImage(
            setting,
            ImageGenerationParams(
                model = model(),
                prompt = "circle",
                idempotencyKey = "request-lost-submit",
                requiredApiKeyFingerprint = selectedFingerprint,
            ),
        ).toList()

        assertEquals("AAAA", images.single().data)
        val retriedSubmit = server.takeRequest()
        assertEquals(firstAuthorization, retriedSubmit.headers["Authorization"])
        assertEquals("request-lost-submit", retriedSubmit.headers["Idempotency-Key"])
        assertEquals(firstAuthorization, server.takeRequest().headers["Authorization"])
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `bound retry after original API key removal never posts`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .onResponseStart(SocketEffect.CloseSocket())
                .build()
        )
        val setting = setting().copy(apiKey = "sk-first,sk-second")
        var selectedFingerprint: String? = null
        val firstAttempt = runCatching {
            provider.generateImage(
                setting,
                ImageGenerationParams(
                    model = model(),
                    prompt = "circle",
                    idempotencyKey = "request-key-removed-after-submit",
                    onImageKeySelected = { fingerprint -> selectedFingerprint = fingerprint },
                ),
            ).toList()
        }

        assertTrue(firstAttempt.isFailure)
        val firstAuthorization = server.takeRequest().headers["Authorization"]
        val remainingKey = if (firstAuthorization == "Bearer sk-first") "sk-second" else "sk-first"
        val retry = runCatching {
            provider.generateImage(
                setting.copy(apiKey = remainingKey),
                ImageGenerationParams(
                    model = model(),
                    prompt = "circle",
                    idempotencyKey = "request-key-removed-after-submit",
                    requiredApiKeyFingerprint = selectedFingerprint,
                ),
            ).toList()
        }

        assertTrue(retry.isFailure)
        assertTrue(retry.exceptionOrNull()?.message?.contains("no longer configured") == true)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `generation falls back once when async endpoint is explicitly unsupported`() = runBlocking {
        server.enqueue(
            jsonResponse(
                404,
                """{"error":{"message":"async image tasks are not enabled"}}""",
                mapOf("X-Sub2-Async-Image" to "unsupported"),
            )
        )
        server.enqueue(jsonResponse(200, """{"data":[{"b64_json":"SYNC"}]}"""))

        var fallbacks = 0
        val images = provider.generateImage(
            setting(),
            ImageGenerationParams(
                model = model(),
                prompt = "circle",
                idempotencyKey = "request-generation-fallback",
                onAsyncFallback = { fallbacks++ },
            ),
        ).toList()

        assertEquals("SYNC", images.single().data)
        assertEquals(1, fallbacks)
        val asyncRequest = server.takeRequest()
        val syncRequest = server.takeRequest()
        assertEquals("/v1/images/generations/async", asyncRequest.url.encodedPath)
        assertEquals("/v1/images/generations", syncRequest.url.encodedPath)
        assertEquals("request-generation-fallback", asyncRequest.headers["Idempotency-Key"])
        assertEquals("request-generation-fallback", syncRequest.headers["Idempotency-Key"])
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `recovery never falls back to an unsafe synchronous request`() {
        server.enqueue(
            jsonResponse(
                404,
                """{"error":{"message":"async image tasks are not enabled"}}""",
                mapOf("X-Sub2-Async-Image" to "unsupported"),
            )
        )

        val result = runCatching {
            runBlocking {
                provider.generateImage(
                    setting(),
                    ImageGenerationParams(
                        model = model(),
                        prompt = "circle",
                        idempotencyKey = "request-recovery-no-sync",
                        requiredApiKeyFingerprint = keyFingerprint("sk-image"),
                        allowSynchronousFallback = false,
                    ),
                ).toList()
            }
        }

        assertTrue(result.exceptionOrNull() is ImageGenerationTerminalException)
        assertEquals(1, server.requestCount)
        assertEquals("/v1/images/generations/async", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `generic async 404 is not retried through synchronous endpoint`() {
        server.enqueue(jsonResponse(404, """{"error":{"message":"model not found"}}"""))
        var fallbacks = 0

        val result = runCatching {
            runBlocking {
                provider.generateImage(
                    setting(),
                    ImageGenerationParams(
                        model = model(),
                        prompt = "circle",
                        onAsyncFallback = { fallbacks++ },
                    ),
                ).toList()
            }
        }

        assertTrue(result.isFailure)
        assertEquals(0, fallbacks)
        assertEquals(1, server.requestCount)
        assertEquals("/v1/images/generations/async", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `legacy disabled async 404 falls back without capability header`() = runBlocking {
        server.enqueue(jsonResponse(404, """{"error":{"message":"async image tasks are not enabled"}}"""))
        server.enqueue(jsonResponse(200, """{"data":[{"b64_json":"LEGACY_SYNC"}]}"""))

        val images = provider.generateImage(
            setting(),
            ImageGenerationParams(model = model(), prompt = "circle"),
        ).toList()

        assertEquals("LEGACY_SYNC", images.single().data)
        assertEquals("/v1/images/generations/async", server.takeRequest().url.encodedPath)
        assertEquals("/v1/images/generations", server.takeRequest().url.encodedPath)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `async 5xx is never retried through synchronous endpoint`() {
        server.enqueue(jsonResponse(503, """{"error":{"message":"gateway unavailable"}}"""))

        val result = runCatching {
            runBlocking {
                provider.generateImage(
                    setting(),
                    ImageGenerationParams(model = model(), prompt = "circle"),
                ).toList()
            }
        }

        assertTrue(result.isFailure)
        assertEquals(1, server.requestCount)
        assertEquals("/v1/images/generations/async", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `definitive async submission rejection clears the request correlation`() = runBlocking {
        server.enqueue(jsonResponse(400, """{"error":{"message":"invalid image size"}}"""))
        var failedCorrelation = ""

        val result = runCatching {
            provider.generateImage(
                setting(),
                ImageGenerationParams(
                    model = model(),
                    prompt = "circle",
                    idempotencyKey = "request-submit-rejected",
                    onTaskFailed = { failedCorrelation = it },
                ),
            ).toList()
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ImageGenerationTerminalException)
        assertEquals("request-submit-rejected", failedCorrelation)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `idempotency fingerprint conflict is a terminal recovery failure`() {
        server.enqueue(
            jsonResponse(
                409,
                """{"error":{"code":"IMAGE_TASK_IDEMPOTENCY_CONFLICT","message":"request changed"}}""",
            )
        )

        val result = runCatching {
            runBlocking {
                provider.generateImage(
                    setting(),
                    ImageGenerationParams(
                        model = model(),
                        prompt = "changed",
                        idempotencyKey = "request-conflict",
                        requiredApiKeyFingerprint = keyFingerprint("sk-image"),
                        allowSynchronousFallback = false,
                    ),
                ).toList()
            }
        }

        assertTrue(result.exceptionOrNull() is ImageGenerationTerminalException)
        assertEquals(1, server.requestCount)
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

        assertTrue(result.exceptionOrNull() is ImageGenerationTerminalException)
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
                ImageEditParams(
                    model = model(),
                    prompt = "edit",
                    images = listOf(image.absolutePath),
                    idempotencyKey = "request-edit-1",
                ),
            ).toList()

            val request = server.takeRequest()
            assertEquals("/v1/images/edits/async", request.url.encodedPath)
            assertEquals("request-edit-1", request.headers["Idempotency-Key"])
            assertTrue(request.headers["Content-Type"]?.startsWith("multipart/form-data") == true)
        } finally {
            image.delete()
        }
    }

    @Test
    fun `recovered edit submit uses persisted API key fingerprint`() = runBlocking {
        val image = File.createTempFile("async-edit-recovery", ".png").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        try {
            server.enqueue(jsonResponse(202, TASK_SUBMITTED))
            server.enqueue(jsonResponse(200, TASK_COMPLETED))

            val images = provider.editImage(
                setting().copy(apiKey = "sk-first,sk-second"),
                ImageEditParams(
                    model = model(),
                    prompt = "edit",
                    images = listOf(image.absolutePath),
                    idempotencyKey = "request-recovered-edit",
                    requiredApiKeyFingerprint = keyFingerprint("sk-second"),
                ),
            ).toList()

            assertEquals("AAAA", images.single().data)
            assertEquals("Bearer sk-second", server.takeRequest().headers["Authorization"])
            assertEquals("Bearer sk-second", server.takeRequest().headers["Authorization"])
            assertEquals(2, server.requestCount)
        } finally {
            image.delete()
        }
    }

    @Test
    fun `edit falls back once when async endpoint is explicitly unsupported`() = runBlocking {
        val image = File.createTempFile("async-edit-fallback", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            server.enqueue(
                jsonResponse(
                    404,
                    """{"error":{"message":"async image tasks are not enabled"}}""",
                    mapOf("X-Sub2-Async-Image" to "unsupported"),
                )
            )
            server.enqueue(jsonResponse(200, """{"data":[{"b64_json":"SYNC_EDIT"}]}"""))

            var fallbacks = 0
            val images = provider.editImage(
                setting(),
                ImageEditParams(
                    model = model(),
                    prompt = "edit",
                    images = listOf(image.absolutePath),
                    idempotencyKey = "request-edit-fallback",
                    onAsyncFallback = { fallbacks++ },
                ),
            ).toList()

            assertEquals("SYNC_EDIT", images.single().data)
            assertEquals(1, fallbacks)
            val asyncRequest = server.takeRequest()
            val syncRequest = server.takeRequest()
            assertEquals("/v1/images/edits/async", asyncRequest.url.encodedPath)
            assertEquals("/v1/images/edits", syncRequest.url.encodedPath)
            assertEquals("request-edit-fallback", asyncRequest.headers["Idempotency-Key"])
            assertEquals("request-edit-fallback", syncRequest.headers["Idempotency-Key"])
            assertEquals(2, server.requestCount)
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
    fun `resume polls existing task even when new async submissions are disabled`() = runBlocking {
        server.enqueue(jsonResponse(200, TASK_COMPLETED))

        val images = provider.resumeImageTask(
            setting().copy(useAsyncImageTasks = false),
            "imgtask_test",
        ).toList()

        assertEquals("AAAA", images.single().data)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/images/tasks/imgtask_test", request.url.encodedPath)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `resume prefers the persisted API key fingerprint`() = runBlocking {
        server.enqueue(jsonResponse(200, TASK_COMPLETED))

        val images = provider.resumeImageTask(
            setting().copy(apiKey = "sk-first,sk-second"),
            "imgtask_test",
            apiKeyFingerprint = keyFingerprint("sk-second"),
        ).toList()

        assertEquals("AAAA", images.single().data)
        assertEquals("Bearer sk-second", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `resume probes another key after persisted owner key returns not found`() = runBlocking {
        server.enqueue(jsonResponse(404, """{"error":{"message":"image task not found"}}"""))
        server.enqueue(jsonResponse(200, TASK_COMPLETED))

        val images = provider.resumeImageTask(
            setting().copy(apiKey = "sk-first,sk-second"),
            "imgtask_test",
            apiKeyFingerprint = keyFingerprint("sk-second"),
        ).toList()

        assertEquals("AAAA", images.single().data)
        assertEquals("Bearer sk-second", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer sk-first", server.takeRequest().headers["Authorization"])
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `resume invokes terminal callback once only after every key returns not found`() {
        server.enqueue(jsonResponse(404, """{"error":{"message":"image task not found"}}"""))
        server.enqueue(jsonResponse(404, """{"error":{"message":"image task not found"}}"""))
        var failures = 0

        val result = runCatching {
            runBlocking {
                provider.resumeImageTask(
                    setting().copy(apiKey = "sk-first,sk-second"),
                    "imgtask_test",
                    apiKeyFingerprint = keyFingerprint("sk-second"),
                    onTaskFailed = { failures++ },
                ).toList()
            }
        }

        assertTrue(result.isFailure)
        assertEquals(1, failures)
        assertEquals("Bearer sk-second", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer sk-first", server.takeRequest().headers["Authorization"])
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `resume treats expired result as terminal without trying another owner key`() {
        server.enqueue(
            jsonResponse(
                410,
                """{"error":{"code":"IMAGE_TASK_RESULT_EXPIRED","message":"result is no longer available"}}""",
            ),
        )
        var failures = 0

        val result = runCatching {
            runBlocking {
                provider.resumeImageTask(
                    setting().copy(apiKey = "sk-first,sk-second"),
                    "imgtask_test",
                    apiKeyFingerprint = keyFingerprint("sk-second"),
                    onTaskFailed = { failures++ },
                ).toList()
            }
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("result expired", ignoreCase = true) == true)
        assertEquals(1, failures)
        assertEquals(1, server.requestCount)
        assertEquals("Bearer sk-second", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `completed task without image data is terminal and clears the task`() = runBlocking {
        server.enqueue(jsonResponse(202, TASK_SUBMITTED))
        server.enqueue(
            jsonResponse(
                200,
                """{"task_id":"imgtask_test","status":"completed","result":{"data":[]}}""",
            ),
        )
        var failures = 0

        val result = runCatching {
            provider.generateImage(
                setting(),
                ImageGenerationParams(
                    model = model(),
                    prompt = "circle",
                    onTaskFailed = { failures++ },
                ),
            ).toList()
        }

        assertTrue(result.exceptionOrNull() is ImageGenerationTerminalException)
        assertEquals(1, failures)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `non transient poll failure is terminal instead of retrying forever`() = runBlocking {
        server.enqueue(jsonResponse(202, TASK_SUBMITTED))
        server.enqueue(jsonResponse(401, """{"error":{"message":"invalid key"}}"""))
        var failures = 0

        val result = runCatching {
            provider.generateImage(
                setting(),
                ImageGenerationParams(
                    model = model(),
                    prompt = "circle",
                    onTaskFailed = { failures++ },
                ),
            ).toList()
        }

        assertTrue(result.exceptionOrNull() is ImageGenerationTerminalException)
        assertEquals(1, failures)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `accepted response without task id is terminal and clears correlation`() = runBlocking {
        server.enqueue(jsonResponse(202, """{"status":"processing"}"""))
        var failedCorrelation = ""

        val result = runCatching {
            provider.generateImage(
                setting(),
                ImageGenerationParams(
                    model = model(),
                    prompt = "circle",
                    idempotencyKey = "missing-task-id",
                    onTaskFailed = { failedCorrelation = it },
                ),
            ).toList()
        }

        assertTrue(result.exceptionOrNull() is ImageGenerationTerminalException)
        assertEquals("missing-task-id", failedCorrelation)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `resume probes all configured keys for legacy pending tasks`() = runBlocking {
        server.enqueue(jsonResponse(404, """{"error":{"message":"image task not found"}}"""))
        server.enqueue(jsonResponse(200, TASK_COMPLETED))

        val images = provider.resumeImageTask(
            setting().copy(apiKey = "sk-first,sk-second"),
            "imgtask_test",
        ).toList()

        assertEquals("AAAA", images.single().data)
        assertEquals("Bearer sk-first", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer sk-second", server.takeRequest().headers["Authorization"])
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

    @Test
    fun `completed task is polled again after object download retries are exhausted`() = runBlocking {
        val imageUrl = server.url("/objects/retry-result.png")
        val completed =
            """{"task_id":"imgtask_test","status":"completed","result":{"data":[{"url":"$imageUrl"}]}}"""
        server.enqueue(jsonResponse(202, TASK_SUBMITTED))
        server.enqueue(jsonResponse(200, completed))
        repeat(3) { server.enqueue(MockResponse.Builder().code(503).build()) }
        server.enqueue(jsonResponse(200, completed))
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/png")
                .body("RECOVERED")
                .build()
        )

        val item = provider.generateImage(
            setting(),
            ImageGenerationParams(model = model(), prompt = "circle"),
        ).toList().single()

        val localPath = requireNotNull(item.localPath)
        assertEquals("RECOVERED", File(localPath).readText())
        File(localPath).delete()
        assertEquals("/v1/images/generations/async", server.takeRequest().url.encodedPath)
        assertEquals("/v1/images/tasks/imgtask_test", server.takeRequest().url.encodedPath)
        repeat(3) { assertEquals("/objects/retry-result.png", server.takeRequest().url.encodedPath) }
        assertEquals("/v1/images/tasks/imgtask_test", server.takeRequest().url.encodedPath)
        assertEquals("/objects/retry-result.png", server.takeRequest().url.encodedPath)
        assertEquals(7, server.requestCount)
    }

    private fun jsonResponse(
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
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
