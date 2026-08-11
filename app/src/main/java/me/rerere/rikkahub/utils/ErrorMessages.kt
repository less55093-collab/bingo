package me.rerere.rikkahub.utils

import android.content.Context
import me.rerere.rikkahub.R
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Upstream providers report failures as raw English JSON blobs ("insufficient_quota",
 * "rate_limit_exceeded", ...) that end users cannot act on. This maps the handful of failures they
 * actually hit onto localized, actionable text, and falls back to the original message so an
 * unrecognized error is never swallowed.
 */
fun Throwable.localizedChatMessage(context: Context): String {
    val resId = chatErrorResId()
    if (resId != null) return context.getString(resId)
    return this.message?.trim().orEmpty().ifBlank { context.getString(R.string.chat_error_unknown) }
}

/**
 * Resolution is kept free of [Context] so the pattern table can be unit-tested directly; returns
 * null when nothing matches, which means the raw upstream text should be shown as-is.
 */
internal fun Throwable.chatErrorResId(): Int? {
    val raw = this.message?.trim().orEmpty()
    val hay = raw.lowercase()

    return when {
        this is UnknownHostException -> R.string.chat_error_no_network
        this is SocketTimeoutException -> R.string.chat_error_timeout
        this is SSLException -> R.string.chat_error_ssl

        hay.containsAny("insufficient_quota", "insufficient quota", "insufficient balance",
            "insufficient_user_quota", "quota exceeded", "exceeded your current quota",
            "余额不足", "额度不足") -> R.string.chat_error_insufficient_balance

        hay.containsAny("rate_limit", "rate limit", "too many requests", "429") ->
            R.string.chat_error_rate_limited

        hay.containsAny("invalid_api_key", "invalid api key", "incorrect api key",
            "unauthorized", "no auth credentials", "authentication_error", "401") ->
            R.string.chat_error_invalid_key

        hay.containsAny("permission_denied", "forbidden", "403") -> R.string.chat_error_forbidden

        hay.containsAny("model_not_found", "does not exist or you do not have access",
            "unknown model", "unsupported model", "404") -> R.string.chat_error_model_unavailable

        hay.containsAny("context_length_exceeded", "maximum context length",
            "context window", "too long", "reduce the length") ->
            R.string.chat_error_context_too_long

        hay.containsAny("content_filter", "content_policy", "safety", "blocked by",
            "prohibited_content", "recitation") -> R.string.chat_error_content_filtered

        hay.containsAny("overloaded", "server_error", "internal server error",
            "bad gateway", "service unavailable", "500", "502", "503") ->
            R.string.chat_error_upstream_busy

        this is IOException -> R.string.chat_error_network_interrupted

        else -> null
    }
}

private fun String.containsAny(vararg needles: String): Boolean =
    needles.any { this.contains(it) }
