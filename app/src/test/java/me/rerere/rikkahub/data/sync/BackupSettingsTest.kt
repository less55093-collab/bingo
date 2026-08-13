package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSettingsTest {
    @Test
    fun `backup settings strip sync secrets`() {
        val settings = Settings(
            webDavConfig = WebDavConfig(
                username = "alice",
                password = "webdav-password",
            ),
            s3Config = S3Config(
                accessKeyId = "access-key",
                secretAccessKey = "secret-key",
            ),
        )

        val backup = settings.forBackup()

        assertEquals("", backup.webDavConfig.password)
        assertEquals("", backup.s3Config.accessKeyId)
        assertEquals("", backup.s3Config.secretAccessKey)
    }

    @Test
    fun `restoring settings keeps local sync configuration`() {
        val current = Settings(
            webDavConfig = WebDavConfig(
                url = "https://local.example/dav",
                username = "local-user",
                password = "local-password",
                path = "local-backups",
            ),
            s3Config = S3Config(
                endpoint = "https://local.example",
                accessKeyId = "local-access-key",
                secretAccessKey = "local-secret-key",
                bucket = "local-bucket",
                prefix = "local-prefix",
            ),
        )
        val backup = Settings(
            webDavConfig = WebDavConfig(
                url = "https://backup.example/dav",
                username = "stale-user",
                password = "stale-password",
                path = "backup-path",
            ),
            s3Config = S3Config(
                endpoint = "https://backup.example",
                accessKeyId = "stale-access-key",
                secretAccessKey = "stale-secret-key",
                bucket = "backup-bucket",
                prefix = "backup-prefix",
            ),
        )

        val restored = backup.withLocalSyncConfiguration(current)

        assertEquals(current.webDavConfig, restored.webDavConfig)
        assertEquals(current.s3Config, restored.s3Config)
    }

    @Test
    fun `backup settings strip search and MCP secrets`() {
        val settings = Settings(
            searchServices = listOf(
                SearchServiceOptions.TavilyOptions(apiKey = "search-key"),
                SearchServiceOptions.SearXNGOptions(username = "search-user", password = "search-password"),
            ),
            mcpServers = listOf(
                McpServerConfig.StreamableHTTPServer(
                    commonOptions = McpCommonOptions(
                        headers = listOf(
                            "Authorization" to "Bearer custom-token",
                            "X-API-Key" to "custom-api-key",
                            "X-Trace" to "keep",
                        ),
                        oauth = McpOAuthState(
                            clientSecret = "client-secret",
                            accessToken = "access-token",
                            refreshToken = "refresh-token",
                        ),
                    ),
                ),
            ),
        )

        val backup = settings.forBackup()

        assertEquals("", (backup.searchServices[0] as SearchServiceOptions.TavilyOptions).apiKey)
        val searx = backup.searchServices[1] as SearchServiceOptions.SearXNGOptions
        assertEquals("", searx.username)
        assertEquals("", searx.password)
        val options = backup.mcpServers.single().commonOptions
        assertEquals("", options.headers.first().second)
        assertEquals("", options.headers[1].second)
        assertEquals("keep", options.headers.last().second)
        assertEquals(null, options.oauth?.clientSecret)
        assertEquals(null, options.oauth?.accessToken)
        assertEquals(null, options.oauth?.refreshToken)
    }
}
