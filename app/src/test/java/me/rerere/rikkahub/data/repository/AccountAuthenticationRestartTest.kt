package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountAuthenticationRestartTest {
    @Test
    fun `switching account namespace requires a process restart`() {
        assertTrue(
            shouldRestartAfterAuthentication(
                previousDatabase = "rikka_hub",
                targetDatabase = "rikka_hub_user_42",
                pendingRestore = false,
            )
        )
    }

    @Test
    fun `pending restore restarts even when the namespace is unchanged`() {
        assertTrue(
            shouldRestartAfterAuthentication(
                previousDatabase = "rikka_hub_user_42",
                targetDatabase = "rikka_hub_user_42",
                pendingRestore = true,
            )
        )
    }

    @Test
    fun `same namespace without restore stays in process`() {
        assertFalse(
            shouldRestartAfterAuthentication(
                previousDatabase = "rikka_hub_user_42",
                targetDatabase = "rikka_hub_user_42",
                pendingRestore = false,
            )
        )
    }
}
