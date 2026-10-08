package me.rerere.rikkahub.data.api.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.data.model.gateway.GatewayModel
import okhttp3.OkHttpClient
import okhttp3.Request

class InsufficientBalanceException(message: String) : Exception(message)
class ApiKeyExpiredException(message: String) : Exception(message)

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
            if (response.code == 402 || response.code == 403) {
                val errorBody = response.body.string()
                if (errorBody.contains("INSUFFICIENT_BALANCE", ignoreCase = true) || errorBody.contains("balance", ignoreCase = true)) {
                    throw InsufficientBalanceException("账户余额不足，请充值后使用")
                }
                if (errorBody.contains("API_KEY_EXPIRED", ignoreCase = true) || errorBody.contains("expired", ignoreCase = true)) {
                    throw ApiKeyExpiredException("API Key 已过期")
                }
            }
            // Do not put response bodies, credentials or upstream diagnostics in user-facing errors.
            check(response.isSuccessful) { "模型同步失败（HTTP ${response.code}），请重试" }
            json.decodeFromString<ModelList>(response.body.string()).data
                .filter { it.id.isNotBlank() }.distinctBy { it.id }
        }
    }
}
