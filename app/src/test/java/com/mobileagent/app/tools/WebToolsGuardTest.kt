package com.mobileagent.app.tools

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SSRF guard is a security boundary: web tools must never touch private,
 * loopback or link-local targets (cloud metadata endpoints included).
 */
class WebToolsGuardTest {

    private fun blocked(url: String) {
        val e = assertThrows("Expected '$url' to be blocked", ToolException::class.java) {
            WebTools.requirePublicHost(url)
        }
        assertTrue("Unhelpful error for '$url': ${e.message}", e.message!!.contains("Blocked") || e.message!!.contains("not a public"))
    }

    private fun allowed(url: String) {
        WebTools.requirePublicHost(url) // must not throw
    }

    @Test
    fun loopbackAndLocalnamesAreBlocked() {
        blocked("http://localhost:8080/admin")
        blocked("http://app.localhost/")
        blocked("http://printer.local/")
        blocked("http://0.0.0.0/")
        blocked("http://[::1]/")
    }

    @Test
    fun literalPrivateAddressesAreBlocked() {
        blocked("http://127.0.0.1:9090/")
        blocked("http://10.1.2.3/internal")
        blocked("http://192.168.0.10/router")
        blocked("http://172.16.5.4/")
    }

    @Test
    fun cloudMetadataEndpointsAreBlocked() {
        // GCP metadata endpoint - the highest value SSRF target on a phone/VM.
        blocked("http://169.254.169.254/computeMetadata/v1/")
        blocked("http://metadata.google.internal/computeMetadata/v1/")
    }

    @Test
    fun publicLiteralIpIsAllowedWithoutDns() {
        allowed("http://8.8.8.8/dns-query")
        allowed("https://1.1.1.1/")
    }

    @Test
    fun garbageUrlsFailCleanly() {
        assertThrows(ToolException::class.java) { WebTools.requirePublicHost("not a url at all") }
    }
}
