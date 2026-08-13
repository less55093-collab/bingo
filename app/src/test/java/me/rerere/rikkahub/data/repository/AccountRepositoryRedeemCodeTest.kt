package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.gateway.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountRepositoryRedeemCodeTest {

    @Test
    fun `lowercase redeem code is preserved`() {
        val code = "03ae124d1c13dd9e07408f268c5077e5"

        assertEquals(code, AccountRepository.normalizeRedeemCode(code))
    }

    @Test
    fun `mixed case and separators are preserved`() {
        val code = "AbCd1234-EfGh5678-IjKl9012-MnOp3456"

        assertEquals(code, AccountRepository.normalizeRedeemCode(code))
    }

    @Test
    fun `surrounding whitespace and zero width characters are removed`() {
        val input = " \n\u200BAb-cD\uFEFF\t "

        assertEquals("Ab-cD", AccountRepository.normalizeRedeemCode(input))
    }

    @Test
    fun `only a positive profile id selects an S3 credential namespace`() {
        assertNull(null.s3CredentialAccountId())
        assertNull(UserProfile(id = 0).s3CredentialAccountId())
        assertNull(UserProfile(id = -1).s3CredentialAccountId())
        assertEquals(42L, UserProfile(id = 42).s3CredentialAccountId())
    }
}
