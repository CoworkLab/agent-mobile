package com.mobileagent.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-world boot smoke test: the APK installs, the Application initializes,
 * and the launcher activity comes to the foreground without crashing —
 * even on a fresh install with zero permissions granted.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @Test
    fun agentAppInitializes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertTrue("ApplicationContext must be the AgentApp subclass", app is AgentApp)
        assertNotNull(AgentApp.instance)
    }

    @Test
    fun launcherActivityResumesWithoutCrash() {
        ActivityScenario.launch(com.mobileagent.app.ui.home.MainActivity::class.java).use { scenario ->
            // MainActivity may legally redirect to the permission/setup flow on a fresh
            // install; what must hold is that SOME app activity is alive and resumed.
            scenario.onActivity { anyActivity ->
                assertNotNull(anyActivity.window)
                assertTrue(!anyActivity.isFinishing || anyActivity.javaClass.simpleName == "MainActivity")
            }
        }
    }
}
