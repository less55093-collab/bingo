package me.rerere.rikkahub.data.model.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class GatewayImageTask(
    val id: String,
    val status: String,
    @SerialName("request_id") val requestId: String = "",
    val result: JsonElement? = null,
    val error: JsonElement? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class GatewayImageTaskPage(
    val data: List<GatewayImageTask> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String = "",
)
