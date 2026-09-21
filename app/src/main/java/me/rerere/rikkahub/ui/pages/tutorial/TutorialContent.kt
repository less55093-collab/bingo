package me.rerere.rikkahub.ui.pages.tutorial

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import me.rerere.rikkahub.R

data class TutorialImage(
    @StringRes val label: Int,
    @DrawableRes val preview: Int,
    @DrawableRes val detail: Int = preview,
)

data class TutorialStep(
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val instruction: Int,
    val images: List<TutorialImage>,
)

// First-run examples must be available before login and without a network connection.
val TutorialSteps = listOf(
    TutorialStep(
        R.string.tutorial_shop_copy_title,
        R.string.tutorial_shop_copy_body,
        R.string.tutorial_shop_copy_instruction,
        listOf(TutorialImage(R.string.tutorial_shop_copy_example, R.drawable.tutorial_shop_copy, R.drawable.tutorial_shop_chat)),
    ),
    TutorialStep(
        R.string.tutorial_shop_models_title,
        R.string.tutorial_shop_models_body,
        R.string.tutorial_shop_models_instruction,
        listOf(
            TutorialImage(R.string.tutorial_shop_models_example, R.drawable.tutorial_shop_models),
            TutorialImage(R.string.tutorial_shop_groups_example, R.drawable.tutorial_shop_groups),
        ),
    ),
    TutorialStep(
        R.string.tutorial_shop_image_title,
        R.string.tutorial_shop_image_body,
        R.string.tutorial_shop_image_instruction,
        listOf(
            TutorialImage(R.string.tutorial_shop_result_example, R.drawable.tutorial_shop_result),
            TutorialImage(R.string.tutorial_shop_product_example, R.drawable.tutorial_shop_product),
            TutorialImage(R.string.tutorial_shop_plan_example, R.drawable.tutorial_shop_plan, R.drawable.tutorial_shop_plan_full),
        ),
    ),
    TutorialStep(
        R.string.tutorial_shop_gallery_title,
        R.string.tutorial_shop_gallery_body,
        R.string.tutorial_shop_gallery_instruction,
        listOf(TutorialImage(R.string.tutorial_shop_gallery_example, R.drawable.tutorial_shop_gallery)),
    ),
)
