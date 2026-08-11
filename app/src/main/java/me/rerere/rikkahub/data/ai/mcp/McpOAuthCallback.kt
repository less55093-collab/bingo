package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/**
 * OAuth 授权回调的 redirect_uri。
 *
 * 目前没有任何界面可以发起 MCP 授权，接收该回调的 exported Activity 已随之移除，
 * 因此这条链路是不可达的。若日后重新开放 MCP 配置入口，需要同时恢复 Manifest 中的 intent-filter。
 */
const val MCP_OAUTH_REDIRECT_URI = "rikkahub://mcp-oauth-callback"

/** 使用 Chrome Custom Tabs 打开授权 URL。 */
fun launchOAuthAuthorization(context: Context, authorizationUrl: String) {
    val intent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
    intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.launchUrl(context, authorizationUrl.toUri())
}
