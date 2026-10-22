package com.phoneagent.perception

/**
 * Where perception comes from. The real implementation wraps an `AccessibilityService`;
 * tests use a fake, so the whole loop runs on the JVM.
 */
interface PerceptionSource {
    /**
     * Reads the current screen. Never throws: failures come back as a [ScreenSnapshot] with
     * [ScreenSnapshot.failure] set, because "cannot see" is a normal state the agent must
     * handle (a secure banking screen, a transition, a rate limit).
     */
    suspend fun capture(mode: PerceptionMode): ScreenSnapshot

    /** Whether the accessibility service is connected and able to read the screen. */
    fun isAvailable(): Boolean

    /** Milliseconds to wait before the next screenshot is allowed. Zero when ready. */
    fun screenshotCooldownMs(): Long
}
