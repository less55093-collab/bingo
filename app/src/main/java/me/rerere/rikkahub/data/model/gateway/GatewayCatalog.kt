package me.rerere.rikkahub.data.model.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GatewayGroup(
    val id: Int,
    val name: String,
    val platform: String = "openai",
    val description: String = "",
    @SerialName("rate_multiplier") val rateMultiplier: Double = 1.0,
    @SerialName("claude_code_only") val claudeCodeOnly: Boolean = false,
) {
    val displayName: String
        get() = when (id) {
            29 -> "DeepSeek"
            16 -> "gpt"
            23 -> "claude"
            else -> name
        }
}

@Serializable
data class GatewayModel(
    val id: String,
    @SerialName("display_name") val displayName: String = "",
)

/** Stored with authentication, never in transferable settings backups. */
@Serializable
data class GatewayBinding(
    val group: GatewayGroup,
    val key: String,
    val models: List<GatewayModel> = emptyList(),
    val syncedAt: Long = 0,
)

@Serializable
data class GatewayRouting(
    val chat: GatewayBinding? = null,
    val image: GatewayBinding? = null,
    // Preserve old identities for in-flight image tasks, including a process death during switching.
    val imageHistory: List<GatewayBinding> = emptyList(),
)

enum class GatewayPurpose(val allowedGroupIds: List<Int>, val defaultGroupId: Int) {
    CHAT(listOf(29), 29),
    IMAGE(listOf(13, 2), 2);

    fun allowsGroup(groupId: Int): Boolean = groupId in allowedGroupIds

    fun availableGroups(groups: List<GatewayGroup>): List<GatewayGroup> =
        allowedGroupIds.mapNotNull { id -> groups.firstOrNull { it.id == id } }
}

/**
 * Retain an unavailable selection's identity so an upgrade never silently changes its billing group.
 * Historical image credentials remain recovery-only and are never injected into the model picker.
 */
fun GatewayRouting.enforceGroupRestrictions(availableGroupIds: Set<Int>? = null): GatewayRouting {
    fun permitted(binding: GatewayBinding, purpose: GatewayPurpose): Boolean =
        purpose.allowsGroup(binding.group.id) &&
            (availableGroupIds == null || binding.group.id in availableGroupIds)

    fun restrict(binding: GatewayBinding?, purpose: GatewayPurpose): GatewayBinding? =
        binding?.let {
            if (permitted(it, purpose)) it else it.copy(key = "", models = emptyList(), syncedAt = 0)
        }

    val restrictedImage = restrict(image, GatewayPurpose.IMAGE)
    return copy(
        chat = restrict(chat, GatewayPurpose.CHAT),
        image = restrictedImage,
        imageHistory = if (image != restrictedImage && image?.key?.isNotBlank() == true) {
            (imageHistory + listOfNotNull(image)).distinctBy { it.key to it.models }
        } else imageHistory,
    )
}
