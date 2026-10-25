package com.mobileagent.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobileagent.app.tools.ToolRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real Android system (emulator) where the runtime org.json and
 * the app's actual classpath are in play — catches JVM-vs-device drift.
 */
@RunWith(AndroidJUnit4::class)
class ToolRegistryDeviceTest {

    @Test
    fun registryDeclaresFullToolsetOnDevice() {
        val tools = ToolRegistry.allTools()
        assertTrue("Expected >= 90 tools on device, got ${tools.size}", tools.size >= 90)
        assertEquals(tools.size, tools.map { it.name }.toSet().size)
    }

    @Test
    fun functionDeclarationsJsonSerializesOnDevice() {
        val decls: JSONArray = ToolRegistry.functionDeclarationsJson()
        assertEquals(ToolRegistry.allTools().size, decls.length())
        // Spot-check one declaration round-trips through the device JSON parser.
        val first = decls.getJSONObject(0)
        assertTrue(first.has("name") && first.has("description") && first.has("parameters"))
    }

    @Test
    fun setupToolsJsonIsFunctionDeclarationsOnlyOnDevice() {
        // google_search must NOT be sent in the Live setup payload — the real API
        // closes the WebSocket when it is present (verified live; see live-test CI).
        val setup = ToolRegistry.setupToolsJson()
        assertEquals(1, setup.length())
        assertTrue(setup.getJSONObject(0).has("functionDeclarations"))
        assertFalse(setup.toString().contains("google_search"))
    }
}
