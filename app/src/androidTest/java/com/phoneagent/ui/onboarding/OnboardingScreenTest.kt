package com.phoneagent.ui.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        rule.onNodeWithTag(OnboardingTags.ROW_PREFIX + "ACCESSIBILITY").assertIsDisplayed()
        rule.onNodeWithTag(OnboardingTags.ROW_PREFIX + "OVERLAY").assertIsDisplayed()
        rule.onNodeWithTag(OnboardingTags.ROW_PREFIX + "NOTIFICATIONS").assertIsDisplayed()
        rule.onNodeWithTag(OnboardingTags.ROW_PREFIX + "NOTIFICATION_LISTENER").assertIsDisplayed()
        rule.onNodeWithTag(OnboardingTags.ROW_PREFIX + "BATTERY_OPTIMIZATION").assertIsDisplayed()
    }

    @Test
    fun recheckButtonIsPresent() {
        rule.onNodeWithTag("tab_setup").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(OnboardingTags.REFRESH).assertIsDisplayed()
        rule.onNodeWithTag(OnboardingTags.REFRESH).performClick()
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
