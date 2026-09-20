package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class UIMessageAnnotation {
    /** Explicit user-selected image action; ordinary chat text never sets this marker. */
    @Serializable
    @SerialName("direct_image_request")
    data object DirectImageRequest : UIMessageAnnotation()

    /** Durable delivery receipt; a stale stream snapshot cannot overwrite downloaded results. */
    @Serializable
    @SerialName("image_delivery")
    data class ImageDelivery(
        val toolCallId: String,
        val requestId: String,
        val imageUrls: List<String> = emptyList(),
        val error: String? = null,
    ) : UIMessageAnnotation()

    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()

    /** The stream ended before the provider confirmed a complete response. */
    @Serializable
    @SerialName("generation_interrupted")
    data object GenerationInterrupted : UIMessageAnnotation()

    @Serializable
    @SerialName("generation_failure")
    data class GenerationFailure(val message: String) : UIMessageAnnotation()

    /** Saved with the user message before starting a reply; never automatically resubmitted. */
    @Serializable
    @SerialName("reply_pending")
    data object ReplyPending : UIMessageAnnotation()
}
