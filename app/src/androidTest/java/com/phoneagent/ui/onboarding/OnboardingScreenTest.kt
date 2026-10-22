package com.phoneagent.ui.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phoneagent.ui.MainActivity
import com.phoneagent.ui.task.TaskTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Onboarding is the only screen the user must complete, so it is verified on a device.
 * These assertions do not depend on permission state: they check the screen renders every
 * permission row and that the navigation gates behave.
 *
 * The list is taller than a phone screen, so rows are scrolled into view before being
 * asserted as displayed; `assertExists` alone would not prove the user can reach them.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun onboardingListsEveryRequiredPermission() {
        rule.onNodeWithTag("tab_setup").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(OnboardingTags.SCREEN).assertIsDisplayed()
        listOf(
            "ACCESSIBILITY",
            "OVERLAY",
            "NOTIFICATIONS",
            "NOTIFICATION_LISTENER",
            "BATTERY_OPTIMIZATION",
        ).forEach { id ->
            val row = rule.onNodeWithTag(OnboardingTags.ROW_PREFIX + id)
            row.performScrollTo()
            row.assertIsDisplayed()
        }
    }

    @Test
    fun recheckButtonIsPresent() {
        rule.onNodeWithTag("tab_setup").performClick()
        rule.waitForIdle()

        val refresh = rule.onNodeWithTag(OnboardingTags.REFRESH)
        refresh.performScrollTo()
        refresh.assertIsDisplayed()
        refresh.performClick()
        rule.waitForIdle()
    }

    @Test
    fun taskTabOffersStartAndStop() {
        rule.onNodeWithTag("tab_task").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(TaskTags.SCREEN).assertIsDisplayed()
        rule.onNodeWithTag(TaskTags.START).assertIsDisplayed()
        rule.onNodeWithTag(TaskTags.STOP).assertIsDisplayed()
        rule.onNodeWithText("STOP").assertIsDisplayed()
    }
}
