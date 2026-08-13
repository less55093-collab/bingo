package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.auth.ProviderInjector
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.search.SearchServiceOptions

/**
 * Returns the settings payload that may be stored in a user's backup bucket.
 *
 * Storage credentials and the local web-server password must not be copied into an archive that
 * can be downloaded from that same storage. Provider keys are re-injected from the account
 * gateway, so they are cleared here as well.
 */
internal fun Settings.forBackup(): Settings {
    return ProviderInjector.clear(this).copy(
        webDavConfig = webDavConfig.copy(password = ""),
        s3Config = s3Config.copy(accessKeyId = "", secretAccessKey = ""),
        webServerAccessPassword = "",
        searchServices = searchServices.map { it.withoutSecrets() },
        mcpServers = mcpServers.map { it.withoutSecrets() },
    )
}

/**
 * Restores settings from a backup without replacing sync configuration already configured on this
 * device.
 *
 * The backup payload intentionally strips storage secrets, while older payloads may contain stale
 * endpoints or paths. In both cases the local sync configuration is the only one that should be
 * retained.
 */
internal fun Settings.withLocalSyncConfiguration(current: Settings): Settings {
    return copy(
        webDavConfig = current.webDavConfig,
        s3Config = current.s3Config,
    )
}

private fun McpServerConfig.withoutSecrets(): McpServerConfig = clone(
    commonOptions = commonOptions.withoutSecrets(),
)

private fun McpCommonOptions.withoutSecrets(): McpCommonOptions = copy(
    headers = headers.map { (name, value) ->
        name to if (name.isSensitiveHeader()) "" else value
    },
    oauth = oauth?.withoutSecrets(),
)

private fun String.isSensitiveHeader(): Boolean {
    val normalized = trim().lowercase()
    return normalized == "authorization" ||
        normalized == "proxy-authorization" ||
        normalized == "x-api-key" ||
        normalized == "api-key" ||
        normalized == "apikey" ||
        normalized == "x-auth-token" ||
        normalized == "x-access-token" ||
        normalized == "access-token" ||
        normalized == "token"
}

private fun McpOAuthState.withoutSecrets(): McpOAuthState = copy(
    clientSecret = null,
    accessToken = null,
    refreshToken = null,
)

private fun SearchServiceOptions.withoutSecrets(): SearchServiceOptions = when (this) {
    is SearchServiceOptions.ZhipuOptions -> copy(apiKey = "")
    is SearchServiceOptions.TavilyOptions -> copy(apiKey = "")
    is SearchServiceOptions.ExaOptions -> copy(apiKey = "")
    is SearchServiceOptions.SearXNGOptions -> copy(username = "", password = "")
    is SearchServiceOptions.LinkUpOptions -> copy(apiKey = "")
    is SearchServiceOptions.BraveOptions -> copy(apiKey = "")
    is SearchServiceOptions.MetasoOptions -> copy(apiKey = "")
    is SearchServiceOptions.OllamaOptions -> copy(apiKey = "")
    is SearchServiceOptions.PerplexityOptions -> copy(apiKey = "")
    is SearchServiceOptions.FirecrawlOptions -> copy(apiKey = "")
    is SearchServiceOptions.JinaOptions -> copy(apiKey = "")
    is SearchServiceOptions.BochaOptions -> copy(apiKey = "")
    is SearchServiceOptions.RikkaHubOptions -> copy(apiKey = "")
    is SearchServiceOptions.GrokOptions -> copy(apiKey = "")
    is SearchServiceOptions.TinyfishOptions -> copy(apiKey = "")
    is SearchServiceOptions.SerperOptions -> copy(apiKey = "")
    is SearchServiceOptions.BingLocalOptions,
    is SearchServiceOptions.CustomJsOptions -> this
}
