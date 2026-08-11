package me.rerere.rikkahub.ui.pages.account

import me.rerere.rikkahub.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression cover for the redeem type spelling. A live `POST /redeem` and `GET /redeem/history`
 * return the bare `"balance"` / `"concurrency"`, while admin-issued rows have been seen as
 * `"admin_balance"`. Matching only the prefixed spelling silently mislabelled real rows and dropped
 * the ¥ from the amount, so both spellings are pinned here.
 */
class RedeemTypeLabelTest {
    @Test
    fun `bare wire spellings map to a label`() {
        assertEquals(R.string.redeem_type_balance, redeemTypeLabel("balance"))
        assertEquals(R.string.redeem_type_concurrency, redeemTypeLabel("concurrency"))
        assertEquals(R.string.redeem_type_subscription, redeemTypeLabel("subscription"))
    }

    @Test
    fun `admin prefixed spellings map to the same label`() {
        assertEquals(R.string.redeem_type_balance, redeemTypeLabel("admin_balance"))
        assertEquals(R.string.redeem_type_concurrency, redeemTypeLabel("admin_concurrency"))
    }

    @Test
    fun `casing and padding from the wire do not break the mapping`() {
        assertEquals(R.string.redeem_type_balance, redeemTypeLabel(" Balance "))
        assertEquals(R.string.redeem_type_balance, redeemTypeLabel("ADMIN_BALANCE"))
    }

    @Test
    fun `an unknown type falls back to showing the raw value`() {
        assertNull(redeemTypeLabel("something_new"))
        assertNull(redeemTypeLabel(""))
    }

    @Test
    fun `only balance codes are money`() {
        // A concurrency code's value is a seat count, so rendering it as ¥5 would be a lie.
        assertEquals("¥10", formatRedeemValue("balance", 10.0))
        assertEquals("¥10", formatRedeemValue("admin_balance", 10.0))
        assertEquals("5", formatRedeemValue("concurrency", 5.0))
        assertEquals("5", formatRedeemValue("admin_concurrency", 5.0))
    }
}
