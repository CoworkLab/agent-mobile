package com.phoneagent.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phoneagent.ui.logs.LogViewerScreen
import com.phoneagent.ui.logs.LogViewerTags
import com.phoneagent.ui.logs.LogViewerViewModel
import com.phoneagent.ui.theme.PhoneAgentTheme
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogViewerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun viewModelWith(vararg messages: String): LogViewerViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val logger = Logger()
        messages.forEach { logger.i("Test", it) }
        return LogViewerViewModel(logger, context)
    }

    @Test
    fun screenShowsLogEntries() {
        val vm = viewModelWith("hello from the agent")
        composeRule.setContent { PhoneAgentTheme { LogViewerScreen(viewModel = vm) } }

        composeRule.onNodeWithText("hello from the agent").assertIsDisplayed()
    }

    @Test
    fun levelFilterHidesLowerLevels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val logger = Logger()
        logger.d("Test", "a debug line")
        logger.e("Test", "an error line")
        val vm = LogViewerViewModel(logger, context)

        composeRule.setContent { PhoneAgentTheme { LogViewerScreen(viewModel = vm) } }
        composeRule.onNodeWithText("a debug line").assertIsDisplayed()

        composeRule.onNodeWithTag(LogViewerTags.LEVEL_PREFIX + LogLevel.ERROR.name).performClick()
        composeRule.onNodeWithText("an error line").assertIsDisplayed()
        composeRule.onNodeWithText("a debug line").assertDoesNotExist()
    }

    @Test
    fun clearButtonEmptiesTheList() {
        val vm = viewModelWith("something")
        composeRule.setContent { PhoneAgentTheme { LogViewerScreen(viewModel = vm) } }

        composeRule.onNodeWithTag(LogViewerTags.CLEAR).performClick()
        composeRule.onNodeWithText("No log entries yet.").assertIsDisplayed()
    }
}
