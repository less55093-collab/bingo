package me.rerere.rikkahub.ui.pages.tutorial

import androidx.annotation.StringRes
import me.rerere.rikkahub.R

/**
 * What the step's primary button does. Kept as a sealed type rather than a lambda so the content
 * list stays a plain value that can be declared at the top level.
 */
enum class TutorialAction { None }

enum class TutorialPreview { Idea, Plan, Result }

data class TutorialStep(
    @StringRes val title: Int,
    @StringRes val body: Int,
    val preview: TutorialPreview,
    val action: TutorialAction = TutorialAction.None,
    @StringRes val actionLabel: Int? = null,
)

/**
 * Copy and illustrations ship in-app rather than being fetched: the tutorial has to work before the
 * user has an account, a balance, or any reason to trust the network.
 */
val TutorialSteps: List<TutorialStep> = listOf(
    TutorialStep(
        title = R.string.tutorial_step_idea_title,
        body = R.string.tutorial_step_idea_body,
        preview = TutorialPreview.Idea,
    ),
    TutorialStep(
        title = R.string.tutorial_step_plan_title,
        body = R.string.tutorial_step_plan_body,
        preview = TutorialPreview.Plan,
    ),
    TutorialStep(
        title = R.string.tutorial_step_approve_title,
        body = R.string.tutorial_step_approve_body,
        preview = TutorialPreview.Result,
    ),
)
