package me.rerere.rikkahub.utils

import me.rerere.rikkahub.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The pattern table is the only thing standing between a raw upstream JSON blob and text a user can
 * act on, so each branch is pinned here: a silent regression surfaces as English gibberish in chat.
 * A null result means "show the upstream text verbatim", which must stay reachable so an
 * unrecognized failure is never swallowed.
 */
class ErrorMessagesTest {
    @Test
    fun `余额不足的多种上游写法都被识别`() {
        listOf(
            "insufficient_quota",
            "Insufficient Balance",
            "You exceeded your current quota, please check your plan",
            "当前分组上游负载已饱和，余额不足",
            "额度不足",
        ).forEach { raw ->
            assertEquals(
                "未能识别余额不足: $raw",
                R.string.chat_error_insufficient_balance,
                RuntimeException(raw).chatErrorResId(),
            )
        }
    }

    @Test
    fun `限流与鉴权错误分别映射`() {
        assertEquals(
            R.string.chat_error_rate_limited,
            RuntimeException("429 Too Many Requests").chatErrorResId(),
        )
        assertEquals(
            R.string.chat_error_invalid_key,
            RuntimeException("invalid_api_key").chatErrorResId(),
        )
        assertEquals(
            R.string.chat_error_forbidden,
            RuntimeException("permission_denied").chatErrorResId(),
        )
    }

    @Test
    fun `模型与上下文错误映射`() {
        assertEquals(
            R.string.chat_error_model_unavailable,
            RuntimeException("The model does not exist or you do not have access to it").chatErrorResId(),
        )
        assertEquals(
            R.string.chat_error_context_too_long,
            RuntimeException("context_length_exceeded").chatErrorResId(),
        )
        assertEquals(
            R.string.chat_error_content_filtered,
            RuntimeException("blocked by content_policy").chatErrorResId(),
        )
        assertEquals(
            R.string.chat_error_upstream_busy,
            RuntimeException("Overloaded").chatErrorResId(),
        )
    }

    @Test
    fun `网络异常按异常类型映射而不依赖文案`() {
        assertEquals(R.string.chat_error_no_network, UnknownHostException("api.x").chatErrorResId())
        assertEquals(R.string.chat_error_timeout, SocketTimeoutException("timeout").chatErrorResId())
        assertEquals(R.string.chat_error_ssl, SSLException("handshake failed").chatErrorResId())
        assertEquals(
            R.string.chat_error_network_interrupted,
            IOException("unexpected end of stream").chatErrorResId(),
        )
    }

    @Test
    fun `无法识别的错误不映射以便保留原文`() {
        assertNull(RuntimeException("some brand new upstream failure").chatErrorResId())
    }

    @Test
    fun `空白错误信息不映射并交由调用方回退`() {
        assertNull(RuntimeException("   ").chatErrorResId())
        assertNull(RuntimeException().chatErrorResId())
    }

    @Test
    fun `只有已知余额错误触发充值分类`() {
        assertTrue("INSUFFICIENT_BALANCE: Insufficient account balance".isInsufficientBalanceError())
        assertTrue("余额不足".isInsufficientBalanceError())
        assertFalse("insufficient image detail".isInsufficientBalanceError())
    }
}
