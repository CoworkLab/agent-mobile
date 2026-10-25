package com.mobileagent.app.tools

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Web tools: raw HTTP requests, a key-less web search (DuckDuckGo Lite) and clean-text page fetch.
 * The Gemini Live session also carries native google_search grounding, so the model can combine both.
 */
object WebTools {

    val NAMES: Set<String> = setOf("http_request", "web_search", "fetch_page")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(45))
            .followRedirects(true)
            .build()
    }

    fun tools(): List<ToolRegistry.T> = listOf(
        ToolRegistry.t(
            "http_request", "Make an HTTP request (public internet only; internal/private addresses are blocked).",
            ToolRegistry.p("url", "STRING", req = true),
            ToolRegistry.p("method", "STRING", "GET | POST | PUT | PATCH | DELETE, default GET",
                enum = listOf("GET", "POST", "PUT", "PATCH", "DELETE")),
            ToolRegistry.p("headers", "OBJECT", "string key/value headers"),
            ToolRegistry.p("body", "STRING", "request body (sent as text)")
        ),
        ToolRegistry.t("web_search", "Search the web, returns titles, snippets and URLs.",
            ToolRegistry.p("query", "STRING", req = true),
            ToolRegistry.p("limit", "INTEGER", "default 5")),
        ToolRegistry.t("fetch_page", "Fetch a web page as clean readable text.",
            ToolRegistry.p("url", "STRING", req = true),
            ToolRegistry.p("max_chars", "INTEGER", "default 8000"))
    )

    suspend fun run(name: String, args: JSONObject, ctx: ToolContext): JSONObject {
        return when (name) {
            "http_request" -> {
                val url = args.getString("url").trim()
                requirePublicHost(url)
                val method = args.optString("method", "GET").uppercase()
                val body = args.optString("body", "")
                val builder = Request.Builder().url(url)
                args.optJSONObject("headers")?.let { h ->
                    h.keys().forEach { key -> builder.header(key, h.optString(key)) }
                }
                when (method) {
                    "POST", "PUT", "PATCH" -> {
                        val mime = (args.optJSONObject("headers")?.optString("Content-Type", "")
                            ?: "").ifBlank { "application/json" }
                        builder.method(method, body.toRequestBody(mime.toMediaTypeOrNull()))
                    }
                    "DELETE" -> builder.delete()
                    else -> builder.get()
                }
                client.newCall(builder.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty().take(20_000)
                    ctx.ok(
                        "status" to resp.code,
                        "ok" to resp.isSuccessful,
                        "content_type" to (resp.header("Content-Type") ?: ""),
                        "body" to text
                    )
                }
            }
            "web_search" -> {
                val query = args.getString("query")
                val limit = args.optInt("limit", 5).coerceIn(1, 10)
                val results = searchDuckDuckGo(query, limit)
                if (results.isEmpty()) {
                    ctx.fail(
                        "Web search returned no results (the endpoint may be rate-limited).",
                        JSONObject().put("fix", "Retry once, or use fetch_page on a known URL, or rely on native google_search grounding.")
                    )
                } else {
                    val arr = org.json.JSONArray()
                    results.forEach { r ->
                        arr.put(JSONObject().put("title", r.first).put("url", r.second).put("snippet", r.third))
                    }
                    ctx.ok("count" to arr.length(), "results" to arr)
                }
            }
            "fetch_page" -> {
                val url = args.getString("url").trim()
                requirePublicHost(url)
                val max = args.optInt("max_chars", 8000).coerceIn(500, 50_000)
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) MobileAgent/2.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw ToolException("Fetch failed: HTTP ${resp.code}")
                    val raw = resp.body?.string().orEmpty()
                    val clean = htmlToText(raw).take(max)
                    ctx.ok("url" to url, "title" to extractTitle(raw), "text" to clean)
                }
            }
            else -> ctx.fail("Unknown web tool '$name'.")
        }
    }

    private fun requirePublicHost(url: String) {
        val host = runCatching { java.net.URI(url).host }.getOrNull()
            ?: throw ToolException("Invalid URL '$url'.")
        val normalized = host.removeSurrounding("[", "]").lowercase()
        if (normalized == "localhost" || normalized.endsWith(".localhost") || normalized.endsWith(".local") ||
            normalized == "0.0.0.0" || normalized == "::1"
        ) throw ToolException("Blocked: '$host' is not a public host.")
        runCatching {
            val addresses = InetAddress.getAllByName(normalized)
            addresses.forEach { addr ->
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
                    addr.isAnyLocalAddress || addr.isMulticastAddress
                ) {
                    throw ToolException("Blocked: '$host' resolves to a private address (${addr.hostAddress}).")
                }
            }
        }
    }

    private fun searchDuckDuckGo(query: String, limit: Int): List<Triple<String, String, String>> {
        val url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) MobileAgent/2.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val html = resp.body?.string().orEmpty()
            val out = ArrayList<Triple<String, String, String>>()
            val linkRegex = Regex(
                """<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""",
                RegexOption.DOT_MATCHES_ALL
            )
            val snippetRegex = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            val snippets = snippetRegex.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
            var i = 0
            for (m in linkRegex.findAll(html)) {
                if (out.size >= limit) break
                var href = m.groupValues[1]
                if (href.startsWith("//")) href = "https:$href"
                val uddg = Regex("""uddg=([^&]+)""").find(href)?.groupValues?.get(1)
                if (uddg != null) href = java.net.URLDecoder.decode(uddg, "UTF-8")
                if (href.startsWith("https://duckduckgo.com/l/")) continue
                val title = stripTags(m.groupValues[2])
                if (title.isBlank() || href.isBlank()) continue
                out.add(Triple(title, href, snippets.getOrNull(i) ?: ""))
                i++
            }
            return out
        }
    }

    private fun extractTitle(html: String): String =
        Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { stripTags(it) }.orEmpty().take(200)

    private fun stripTags(html: String): String =
        html.replace(Regex("""<[^>]+>"""), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("""\s+"""), " ").trim()

    fun htmlToText(html: String): String {
        val withoutScripts = html
            .replace(Regex("""<script.*?</script>""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""<style.*?</style>""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""<noscript.*?</noscript>""", RegexOption.DOT_MATCHES_ALL), " ")
        val withBreaks = withoutScripts
            .replace(Regex("""</(p|div|li|h[1-6]|tr|br)>"""), "\n")
            .replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
        return stripTags(withBreaks)
    }
}
