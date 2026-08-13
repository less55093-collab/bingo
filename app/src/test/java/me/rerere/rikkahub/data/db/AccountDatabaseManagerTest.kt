package me.rerere.rikkahub.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDatabaseManagerTest {
    @Test
    fun `database namespace is deterministic and account isolated`() {
        assertEquals("rikka_hub", AccountDatabaseManager.databaseName(null))
        assertEquals("rikka_hub_user_42", AccountDatabaseManager.databaseName(42))
        assertTrue(AccountDatabaseManager.belongsToAccount("rikka_hub_user_42", 42))
        assertFalse(AccountDatabaseManager.belongsToAccount("rikka_hub_user_7", 42))
        assertFalse(AccountDatabaseManager.belongsToAccount("rikka_hub", 42))
    }
}
