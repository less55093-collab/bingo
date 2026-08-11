package me.rerere.rikkahub.ui.pages.tutorial

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bulb
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.MoneyBag02
import me.rerere.hugeicons.stroke.ShoppingBag02
import me.rerere.hugeicons.stroke.Message01
import me.rerere.rikkahub.R

/**
 * What the step's primary button does. Kept as a sealed type rather than a lambda so the content
 * list stays a plain value that can be declared at the top level.
 */
enum class TutorialAction { None, OpenShop, GoRedeem }

data class TutorialStep(
    @StringRes val title: Int,
    @StringRes val body: Int,
    val icon: ImageVector,
    val action: TutorialAction = TutorialAction.None,
    @StringRes val actionLabel: Int? = null,
)

/**
 * Copy and illustrations ship in-app rather than being fetched: the tutorial has to work before the
 * user has an account, a balance, or any reason to trust the network.
 */
val TutorialSteps: List<TutorialStep> = listOf(
    TutorialStep(
        title = R.string.tutorial_step_credit_title,
        body = R.string.tutorial_step_credit_body,
        icon = HugeIcons.MoneyBag02,
    ),
    TutorialStep(
        title = R.string.tutorial_step_buy_title,
        body = R.string.tutorial_step_buy_body,
        icon = HugeIcons.ShoppingBag02,
        action = TutorialAction.OpenShop,
        actionLabel = R.string.tutorial_step_buy_action,
    ),
    TutorialStep(
        title = R.string.tutorial_step_copy_title,
        body = R.string.tutorial_step_copy_body,
        icon = HugeIcons.Copy01,
    ),
    TutorialStep(
        title = R.string.tutorial_step_redeem_title,
        body = R.string.tutorial_step_redeem_body,
        icon = HugeIcons.Bulb,
        action = TutorialAction.GoRedeem,
        actionLabel = R.string.account_topup,
    ),
    TutorialStep(
        title = R.string.tutorial_step_chat_title,
        body = R.string.tutorial_step_chat_body,
        icon = HugeIcons.Message01,
    ),
)
