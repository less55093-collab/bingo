package me.rerere.rikkahub.data.api.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every api.bingoapi.top response is wrapped in this envelope. `code == 0` means success;
 * any other value carries a machine-readable [reason] such as `EMAIL_VERIFY_REQUIRED`.
 */
@Serializable
data class GatewayEnvelope<T>(
    val code: Int = 0,
    val message: String = "",
    val reason: String = "",
    val data: T? = null,
) {
    val isSuccess: Boolean
        get() = code == 0
}

/**
 * Thrown when the gateway returns a non-zero [code]. [reason] is the stable identifier to
 * branch on; [message] is server-supplied prose and may be localized or change without notice.
 */
class GatewayException(
    val code: Int,
    val reason: String,
    message: String,
) : Exception(message.ifBlank { reason.ifBlank { "gateway error $code" } })

/**
 * Unwraps a successful envelope or throws [GatewayException]. Endpoints that return no body
 * still emit an envelope, so callers of those should use [GatewayEnvelope.requireSuccess].
 */
fun <T> GatewayEnvelope<T>.requireData(): T {
    requireSuccess()
    return data ?: throw GatewayException(code, reason, "missing data in successful response")
}

fun GatewayEnvelope<*>.requireSuccess() {
    if (!isSuccess) throw GatewayException(code, reason, message)
}

object GatewayReasons {
    const val EMAIL_VERIFY_REQUIRED = "EMAIL_VERIFY_REQUIRED"
    const val REFRESH_TOKEN_INVALID = "REFRESH_TOKEN_INVALID"
    const val REDEEM_CODE_NOT_FOUND = "REDEEM_CODE_NOT_FOUND"
    const val REDEEM_CODE_USED = "REDEEM_CODE_USED"
    const val USER_EXISTS = "USER_EXISTS"
    const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
    const val INVALID_VERIFY_CODE = "INVALID_VERIFY_CODE"
}

/** Legacy defaults, used only when first migrating an account without a saved selection. */
object GatewayGroups {
    /** deepseek platform, rate multiplier 1.2 — serves the deepseek-* models. */
    const val CHAT = 29
    const val GPT = 29

    /** openai platform, image generation — the app exposes only `gpt-image-2` from it. */
    const val IMAGE = 2
}

/** Reserved API-key names so provisioning is idempotent across reinstalls. */
object GatewayKeyNames {
    const val CHAT = "app-gpt"
    const val GPT = "app-gpt"
    const val IMAGE = "app-image"
}
