package me.rerere.rikkahub.data.api.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.data.model.gateway.GatewayModel
import okhttp3.OkHttpClient
import okhttp3.Request

/** Uses an inference key; the control-plane JWT interceptor must never be attached. */
class GatewayModelAPI(
    client: OkHttpClient,
    private val baseUrl: String = BingoGatewayAPI.INFERENCE_BASE_URL,
) {
    private val client = client.newBuilder()
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ModelList(val data: List<GatewayModel>)

    suspend fun listModels(key: String): List<GatewayModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/models")
            .header("Authorization", "Bearer $key")
            .get().build()
        client.newCall(request).await().use { response ->
            // Do not put response bodies, credentials or upstream diagnostics in user-facing errors.
            check(response.isSuccessful) { "模型同步失败（HTTP ${response.code}），请重试" }
            json.decodeFromString<ModelList>(response.body.string()).data
                .filter { it.id.isNotBlank() }.distinctBy { it.id }
        }
    }
}
