package com.phoneagent.ui.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneagent.ui.MainActivity
import com.phoneagent.ui.task.TaskTags
import com.phoneagent.ui.theme.PhoneAgentTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Onboarding is the only screen the user must complete, so it is verified on a device.
 *
 * The screen is rendered directly rather than reached by tapping a tab. Setup is an overlay
 * that MainActivity shows only while the core permissions are missing, and there is no tab
 * that opens it — so on an emulator where those permissions are already granted, navigating
 * to it was impossible and the test failed on a screen that was working correctly.
 *
 * Rendering it directly also makes the permission state irrelevant, which is what these
 * assertions want: they are about the screen, not about what the device has granted.
 *
 * The list is taller than a phone screen, so rows are scrolled into view before being
 * asserted as displayed; `assertExists` alone would not prove the user can reach them.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun render() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Built here, not inside the composable: lint flags a view model constructed during
        // composition, and the state has to survive recomposition anyway.
        val viewModel = OnboardingViewModel(context)
        rule.setContent {
            PhoneAgentTheme {
                OnboardingScreen(viewModel = viewModel, onDone = {})
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun onboardingListsEveryRequiredPermission() {
        render()

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
        render()

        val refresh = rule.onNodeWithTag(OnboardingTags.REFRESH)
        refresh.performScrollTo()
        refresh.assertIsDisplayed()
        refresh.performClick()
        rule.waitForIdle()
    }

    /**
     * The way past setup must always exist.
     *
     * Setup covers the whole app, so a screen with no exit is a dead end for anyone whose
     * phone will not grant one of these permissions.
     */
    @Test
    fun thereIsAlwaysAWayPastSetup() {
        render()

        val skip = rule.onNodeWithText("Not now")
        skip.performScrollTo()
        skip.assertIsDisplayed()
        skip.performClick()
    }
}

/**
 * The bottom tabs, driven through the real activity.
 *
 * Kept separate because it needs a launched MainActivity, while the onboarding assertions
 * above want a screen they can render in isolation.
 */
@RunWith(AndroidJUnit4::class)
class MainTabsTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun taskTabOffersStartAndStop() {
        // Setup is shown instead of the tabs until the core permissions are granted. There is
        // no tab to reach it with, so the test takes the way out that the screen offers —
        // but only if setup is actually up, which depends on what the device has granted.
        if (rule.onAllNodesWithText("Not now").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Not now").performClick()
            rule.waitForIdle()
        }

        rule.onNodeWithTag("tab_task").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(TaskTags.SCREEN).assertIsDisplayed()
        rule.onNodeWithTag(TaskTags.START).assertIsDisplayed()
        rule.onNodeWithTag(TaskTags.STOP).assertIsDisplayed()
        rule.onNodeWithText("STOP").assertIsDisplayed()
    }
}
