package com.mobileagent.app.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the tool schema the app sends to the Gemini Live API.
 *
 * These catch the exact class of bugs that make an agent unusable in the real world:
 * duplicate tool names, invalid schemas, required params missing from properties,
 * declared tools nothing can execute, and identity leakage in descriptions.
 */
class ToolRegistryTest {

    private val allowedTypes = setOf("STRING", "INTEGER", "BOOLEAN", "NUMBER", "OBJECT", "ARRAY")

    @Test
    fun toolCountMeetsAgentSpec() {
        val tools = ToolRegistry.allTools()
        assertTrue(
            "Agent must declare at least 90 tools, found ${tools.size}",
            tools.size >= 90
        )
    }

    @Test
    fun allToolNamesAreUniqueAndWellFormed() {
        val names = ToolRegistry.allTools().map { it.name }
        assertEquals(
            "Duplicate tool names: ${names.groupBy { it }.filterValues { it.size > 1 }.keys}",
            names.size,
            names.toSet().size
        )
        for (name in names) {
            assertTrue(
                "Tool name '$name' must match ^[a-z][a-z0-9_]{1,63}$",
                Regex("^[a-z][a-z0-9_]{1,63}$").matches(name)
            )
        }
    }

    @Test
    fun everyToolHasAMeaningfulDescription() {
        for (tool in ToolRegistry.allTools()) {
            assertTrue(
                "Tool '${tool.name}' needs a description (>= 20 chars)",
                tool.desc.length >= 20
            )
        }
    }

    @Test
    fun everyParamSchemaIsValid() {
        for (tool in ToolRegistry.allTools()) {
            val seen = HashSet<String>()
            for (p in tool.params) {
                assertTrue(
                    "'${tool.name}.${p.name}': param type '${p.type}' not in $allowedTypes",
                    p.type.uppercase() in allowedTypes
                )
                assertTrue(
                    "'${tool.name}.${p.name}': duplicate param name",
                    seen.add(p.name)
                )
                p.enum?.let { e ->
                    assertTrue("'${tool.name}.${p.name}': enum must be non-empty", e.isNotEmpty())
                    assertTrue("'${tool.name}.${p.name}': enum values must be unique", e.toSet().size == e.size)
                }
                if (p.items != null) {
                    assertEquals(
                        "'${tool.name}.${p.name}': items is only valid on ARRAY params",
                        "ARRAY", p.type.uppercase()
                    )
                }
            }
        }
    }

    @Test
    fun functionDeclarationsJsonIsCompleteAndValid() {
        val decls = ToolRegistry.functionDeclarationsJson()
        val tools = ToolRegistry.allTools()
        assertEquals(decls.length(), tools.size)

        for (i in 0 until decls.length()) {
            val d = decls.getJSONObject(i)
            assertTrue(d.optString("name").isNotEmpty())
            assertTrue(d.optString("description").isNotEmpty())
            val params = d.getJSONObject("parameters")
            assertEquals("OBJECT", params.getString("type"))
            assertTrue(params.has("properties"))
            val required = params.optJSONArray("required") ?: JSONArray()
            val props = params.getJSONObject("properties")
            for (r in 0 until required.length()) {
                assertTrue(
                    "Required param '${required.getString(r)}' missing in properties of '${d.optString("name")}'",
                    props.has(required.getString(r))
                )
            }
        }
    }

    @Test
    fun setupToolsJsonIsFunctionDeclarationsOnly() {
        // Verified live against the real Gemini Live API: google_search in the
        // setup tools makes the server close the WebSocket at setup time, which
        // used to leave the agent silently reconnecting forever ("no response").
        val setup = ToolRegistry.setupToolsJson()
        assertEquals("tools array must be [functionDeclarations] only", 1, setup.length())
        assertTrue(setup.getJSONObject(0).has("functionDeclarations"))
        assertFalse(
            "google_search must never be sent in the Live setup payload",
            setup.toString().contains("google_search")
        )
    }

    @Test
    fun routingIsComplete_noOrphanDeclarations() {
        // Every declared tool must be executable somewhere: either the registry
        // dispatches it (handles == true) or the service's legacy handler owns it.
        val orphans = ToolRegistry.allTools().map { it.name }.filter {
            !ToolRegistry.handles(it) && it !in ToolRegistry.legacyToolNames
        }
        assertTrue("Declared but unexecutable tools: $orphans", orphans.isEmpty())
    }

    @Test
    fun legacyToolsAreNeverHandledByRegistry() {
        for (name in ToolRegistry.legacyToolNames) {
            assertFalse("Legacy tool '$name' must not be routed to ToolRegistry.dispatch", ToolRegistry.handles(name))
        }
    }

    @Test
    fun noIdentityLeakageInToolSchemas() {
        val json = ToolRegistry.setupToolsJson().toString().lowercase()
        for (banned in listOf("jarvis", "techgptx", "rehaan", "tadipaar")) {
            assertFalse("Tool schema leaks '$banned'", json.contains(banned))
        }
    }

    /** Unknown tools must produce a structured error, never a crash. Null deps are never touched on this path. */
    @Test
    fun dispatchRejectsUnknownToolWithStructuredError() {
        val ctx = ToolRegistryTestCtx.buildContextForErrorPaths()
        val result = kotlinx.coroutines.runBlocking {
            ToolRegistry.dispatch("definitely_not_a_tool", JSONObject(), ctx)
        }
        assertEquals(false, result.optBoolean("success", true))
        val msg = result.optString("message")
        assertTrue("Unexpected message: $msg", msg.contains("not executable") || msg.contains("Unknown tool"))
    }
}

/** Helper kept outside the test class so the unsafe allocation stays contained. */
object ToolRegistryTestCtx {
    /**
     * Builds a ToolContext for error-path tests without Android dependencies.
     * Kotlin's constructor null-check intrinsics reject reflective nulls, so the
     * instance is allocated without running the constructor; ok()/fail() only
     * build JSON and never touch the app/service fields.
     */
    fun buildContextForErrorPaths(): ToolContext {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, ToolContext::class.java) as ToolContext
    }
}
