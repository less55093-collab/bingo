package me.rerere.rikkahub.data.ai.mcp

sealed class McpStatus {
    data object Idle : McpStatus()
    data object Connecting : McpStatus()
    data object Connected : McpStatus()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : McpStatus()

    /**
     * 连接/同步出错。
     *
     * @param message 简短摘要，用于列表内联展示
     * @param detail 安全的诊断类别。异常消息和堆栈可能包含服务器返回的请求/响应内容，
     * 因此不应保存或显示它们。
     */
    data class Error(val message: String, val detail: String? = null) : McpStatus() {
        companion object {
            fun from(throwable: Throwable, fallbackMessage: String? = null): Error {
                val summary = fallbackMessage ?: throwable.javaClass.simpleName
                return Error(message = summary, detail = throwable.javaClass.name)
            }
        }
    }

    /** 服务器返回 401，需要用户完成 OAuth 授权。 */
    data object NeedsAuthorization : McpStatus()

    /** 正在进行 OAuth 授权流程（等待浏览器回调 / 交换令牌）。 */
    data object Authorizing : McpStatus()
}
