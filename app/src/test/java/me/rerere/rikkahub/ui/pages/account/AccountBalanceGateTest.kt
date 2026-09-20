package me.rerere.rikkahub.ui.pages.account

import me.rerere.rikkahub.data.model.gateway.UserProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBalanceGateTest {
    @Test
    fun `unknown profile does not block chat`() {
        assertFalse(isBalanceDepleted(null))
    }

    @Test
    fun `zero balance blocks chat`() {
        assertTrue(isBalanceDepleted(UserProfile(balance = 0.0)))
    }

    @Test
    fun `negative balance blocks chat`() {
        assertTrue(isBalanceDepleted(UserProfile(balance = -0.01)))
    }

    @Test
    fun `gifted starting balance does not block chat`() {
        assertFalse(isBalanceDepleted(UserProfile(balance = 0.5)))
    }

    @Test
    fun `positive balance does not block chat`() {
        assertFalse(isBalanceDepleted(UserProfile(balance = 1.2)))
    }
}
