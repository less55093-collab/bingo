package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class UIMessageAnnotation {
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
}
