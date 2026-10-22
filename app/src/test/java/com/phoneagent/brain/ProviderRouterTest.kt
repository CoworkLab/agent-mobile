package com.phoneagent.brain

import com.phoneagent.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRouterTest {

    /** A provider that fails a set number of times and then succeeds. */
    private class ScriptedProvider(
        override val config: ProviderConfig,
        private val failures: List<LlmError> = emptyList(),
        private val answer: String = "ok",
    ) : LlmProvider {
        var calls = 0
            private set
        val received = mutableListOf<LlmRequest>()

        override suspend fun chat(request: LlmRequest): LlmResponse {
            received += request
            val failure = failures.getOrNull(calls)
            calls++
            if (failure != null) throw failure
            return LlmResponse(
                text = answer,
                providerName = config.name,
                usage = TokenUsage(10, 5, 15),
            )
        }
    }

    private fun config(id: String, priority: Int, vision: Boolean = true, enabled: Boolean = true) =
        ProviderConfig(
            id = id,
            name = id,
            baseUrl = "https://$id.example.com/v1",
            model = "m",
            supportsVision = vision,
            enabled = enabled,
            priority = priority,
        )

    private fun request() = LlmRequest(
        systemPrompt = "sys",
        messages = listOf(LlmMessage(LlmMessage.Role.USER, text = "hi")),
    )

    private fun router(
        providers: List<LlmProvider>,
        clock: () -> Long = { 0L },
    ) = ProviderRouter(providers, Logger(), clock = clock)

    @Test
    fun `the first provider is used when it works`() = runBlocking {
        val first = ScriptedProvider(config("a", 0))
        val second = ScriptedProvider(config("b", 1))

        val response = router(listOf(first, second)).chat(request())

        assertEquals("a", response.providerName)
        assertEquals(0, second.calls)
    }

    @Test
    fun `providers are tried in priority order, not list order`() = runBlocking {
        val low = ScriptedProvider(config("low", 5))
        val high = ScriptedProvider(config("high", 1))

        val response = router(listOf(low, high)).chat(request())

        assertEquals("high", response.providerName)
    }

    @Test
    fun `a rate limited provider falls through to the next one`() = runBlocking {
        val first = ScriptedProvider(
            config("a", 0),
            failures = listOf(LlmError.RateLimited("429")),
        )
        val second = ScriptedProvider(config("b", 1))

        val response = router(listOf(first, second)).chat(request())

        assertEquals("b", response.providerName)
        assertEquals(1, first.calls)
    }

    @Test
    fun `a server error falls through to the next provider`() = runBlocking {
        val first = ScriptedProvider(config("a", 0), failures = listOf(LlmError.Server("500", 500)))
        val second = ScriptedProvider(config("b", 1))

        assertEquals("b", router(listOf(first, second)).chat(request()).providerName)
    }

    @Test
    fun `a bad request stops immediately because the next provider would fail too`() = runBlocking {
        val first = ScriptedProvider(config("a", 0), failures = listOf(LlmError.BadRequest("bad")))
        val second = ScriptedProvider(config("b", 1))

        try {
            router(listOf(first, second)).chat(request())
            org.junit.Assert.fail("expected a bad request error")
        } catch (error: LlmError) {
            assertTrue(error is LlmError.BadRequest)
        }
        assertEquals("a malformed request must not be retried elsewhere", 0, second.calls)
    }

    @Test
    fun `a failed provider is skipped on the next step`() = runBlocking {
        var now = 0L
        val first = ScriptedProvider(config("a", 0), failures = listOf(LlmError.Server("500", 500)))
        val second = ScriptedProvider(config("b", 1))
        val router = router(listOf(first, second)) { now }

        router.chat(request())
        assertEquals(1, first.calls)

        // Still inside the cooldown: the dead provider must not be tried again.
        now = 1000L
        router.chat(request())
        assertEquals("a cooldown provider must not be hammered", 1, first.calls)
        assertEquals(2, second.calls)
    }

    @Test
    fun `a provider is tried again once the cooldown expires`() = runBlocking {
        var now = 0L
        val first = ScriptedProvider(config("a", 0), failures = listOf(LlmError.Server("500", 500)))
        val second = ScriptedProvider(config("b", 1))
        val router = router(listOf(first, second)) { now }

        router.chat(request())
        now = ProviderRouter.DEFAULT_COOLDOWN_MS + 1
        router.chat(request())

        assertEquals("the provider should get another chance", 2, first.calls)
    }

    @Test
    fun `a provider that recovers has its cooldown cleared`() = runBlocking {
        var now = 0L
        // Fails once, then succeeds on every later call.
        val flaky = ScriptedProvider(
            config("a", 0),
            failures = listOf(LlmError.Server("500", 500)),
        )
        val router = router(listOf(flaky)) { now }

        // First call fails and starts a cooldown.
        runCatching { router.chat(request()) }
        now = ProviderRouter.DEFAULT_COOLDOWN_MS + 1

        val response = router.chat(request())
        assertEquals("a", response.providerName)
        assertEquals(0, router.cooldownRemainingMs("a"))
    }

    @Test
    fun `an auth failure moves on and is cooled down longer`() = runBlocking {
        var now = 0L
        val first = ScriptedProvider(config("a", 0), failures = listOf(LlmError.Auth("bad key")))
        val second = ScriptedProvider(config("b", 1))
        val router = router(listOf(first, second)) { now }

        assertEquals("b", router.chat(request()).providerName)

        now = ProviderRouter.DEFAULT_COOLDOWN_MS + 1
        assertEquals(
            "a wrong key should not be retried as often as a rate limit",
            true,
            router.cooldownRemainingMs("a") > 0,
        )
    }

    @Test
    fun `a retry delay from the provider is respected as the cooldown`() = runBlocking {
        val first = ScriptedProvider(
            config("a", 0),
            failures = listOf(LlmError.RateLimited("429", retryAfterMs = 60_000)),
        )
        val second = ScriptedProvider(config("b", 1))
        val router = router(listOf(first, second)) { 0L }

        router.chat(request())

        assertTrue(
            "the provider asked for 60s and should get it",
            router.cooldownRemainingMs("a") >= 59_000,
        )
    }

    @Test
    fun `an image is dropped for a provider that cannot see it`() = runBlocking {
        val textOnly = ScriptedProvider(config("text", 0, vision = false))
        val request = request().copy(
            messages = listOf(
                LlmMessage(LlmMessage.Role.USER, text = "look", imageJpeg = byteArrayOf(1, 2, 3)),
            ),
        )

        router(listOf(textOnly)).chat(request)

        val sent = textOnly.received.single().messages.single()
        assertEquals("the image must not be uploaded", null, sent.imageJpeg)
        assertTrue(
            "the model should know an image existed: ${sent.text}",
            sent.text!!.contains("cannot see images"),
        )
    }

    @Test
    fun `an image is kept for a provider that can see it`() = runBlocking {
        val vision = ScriptedProvider(config("vision", 0, vision = true))
        val request = request().copy(
            messages = listOf(
                LlmMessage(LlmMessage.Role.USER, text = "look", imageJpeg = byteArrayOf(1, 2, 3)),
            ),
        )

        router(listOf(vision)).chat(request)

        assertEquals(3, vision.received.single().messages.single().imageJpeg!!.size)
    }

    @Test
    fun `a text-only message is left alone for a text-only provider`() = runBlocking {
        val textOnly = ScriptedProvider(config("text", 0, vision = false))
        router(listOf(textOnly)).chat(request())
        assertEquals("hi", textOnly.received.single().messages.single().text)
    }

    @Test
    fun `a disabled provider is never used`() = runBlocking {
        val disabled = ScriptedProvider(config("off", 0, enabled = false))
        val enabled = ScriptedProvider(config("on", 1))

        val response = router(listOf(disabled, enabled)).chat(request())

        assertEquals("on", response.providerName)
        assertEquals(0, disabled.calls)
    }

    @Test
    fun `a provider with no model is skipped`() = runBlocking {
        val incomplete = ScriptedProvider(config("a", 0).copy(model = ""))
        val working = ScriptedProvider(config("b", 1))

        assertEquals("b", router(listOf(incomplete, working)).chat(request()).providerName)
    }

    @Test
    fun `no configured providers gives an actionable error`() = runBlocking {
        try {
            router(listOf(ScriptedProvider(config("a", 0, enabled = false)))).chat(request())
            org.junit.Assert.fail("expected an error")
        } catch (error: LlmError) {
            assertTrue(error.message!!, error.message!!.contains("Settings"))
        }
    }

    @Test
    fun `when every provider is in cooldown one is still tried`() = runBlocking {
        val first = ScriptedProvider(config("a", 0), failures = listOf(LlmError.Server("500", 500)))
        val second = ScriptedProvider(config("b", 1), failures = listOf(LlmError.Server("500", 500)))
        val router = router(listOf(first, second)) { 0L }

        runCatching { router.chat(request()) }
        val before = first.calls + second.calls

        // Refusing to try at all would fail a run a short wait could have saved.
        runCatching { router.chat(request()) }
        assertTrue(
            "a provider should be tried even while cooling down",
            first.calls + second.calls > before,
        )
    }

    @Test
    fun `token usage is counted per provider and in total`() = runBlocking {
        val a = ScriptedProvider(config("a", 0))
        val router = router(listOf(a))

        router.chat(request())
        router.chat(request())

        assertEquals(30, router.usageFor("a").totalTokens)
        assertEquals(30, router.totalUsage().totalTokens)
    }

    @Test
    fun `usage from a fallback provider is attributed to that provider`() = runBlocking {
        val first = ScriptedProvider(config("a", 0), failures = listOf(LlmError.Server("500", 500)))
        val second = ScriptedProvider(config("b", 1))
        val router = router(listOf(first, second))

        router.chat(request())

        assertEquals(0, router.usageFor("a").totalTokens)
        assertEquals(15, router.usageFor("b").totalTokens)
    }

    @Test
    fun `the available list reports the usable providers in order`() {
        val router = router(
            listOf(
                ScriptedProvider(config("low", 5)),
                ScriptedProvider(config("off", 1, enabled = false)),
                ScriptedProvider(config("high", 0)),
            ),
        )

        assertEquals(listOf("high", "low"), router.available.map { it.id })
    }
}

class ProviderRepositoryTest {

    private val cipher = ReversingSecretCipher()
    private val logger = Logger()

    private fun config(
        id: String,
        priority: Int = 0,
        key: String = "secret-key",
        model: String = "m",
    ) = ProviderConfig(
        id = id,
        name = id,
        baseUrl = "https://$id.example.com/v1",
        apiKey = key,
        model = model,
        priority = priority,
    )

    private fun repo(store: InMemoryProviderStore = InMemoryProviderStore()) =
        ProviderRepository(store, cipher, logger)

    @Test
    fun `an empty store gives an empty list`() = runBlocking {
        assertEquals(emptyList<ProviderConfig>(), repo().current())
    }

    @Test
    fun `saving then reading returns the same provider`() = runBlocking {
        val repository = repo()
        repository.save(config("gemini", key = "abc123"))

        val stored = repository.current().single()
        assertEquals("gemini", stored.id)
        assertEquals("abc123", stored.apiKey)
    }

    @Test
    fun `the api key is not written in plain text`() = runBlocking {
        val store = InMemoryProviderStore()
        repo(store).save(config("gemini", key = "plaintext-key-here"))

        val raw = store.raw().orEmpty()
        assertFalse("the key must be encrypted at rest", raw.contains("plaintext-key-here"))
        assertTrue("the cipher output should be there", raw.contains("enc:"))
    }

    @Test
    fun `the api key is not written even inside the json structure`() = runBlocking {
        val store = InMemoryProviderStore()
        repo(store).save(config("a", key = "topsecret"))

        assertFalse(store.raw().orEmpty().contains("topsecret"))
    }

    @Test
    fun `a key that cannot be decrypted reads back blank rather than wrong`() = runBlocking {
        val store = InMemoryProviderStore(
            """[{"id":"a","name":"A","baseUrl":"https://a/v1","apiKey":"garbage","model":"m"}]""",
        )

        assertEquals("", repo(store).current().single().apiKey)
    }

    @Test
    fun `corrupt json yields an empty list instead of crashing`() = runBlocking {
        assertEquals(
            emptyList<ProviderConfig>(),
            repo(InMemoryProviderStore("{not json at all")).current(),
        )
    }

    @Test
    fun `deleting a provider removes it`() = runBlocking {
        val repository = repo()
        repository.save(config("a"))
        repository.save(config("b", priority = 1))

        repository.delete("a")

        assertEquals(listOf("b"), repository.current().map { it.id })
    }

    @Test
    fun `saving the same id updates in place instead of duplicating`() = runBlocking {
        val repository = repo()
        repository.save(config("a"))
        repository.save(config("a", model = "changed"))

        val all = repository.current()
        assertEquals(1, all.size)
        assertEquals("changed", all.single().model)
    }

    @Test
    fun `moving a provider up rewrites the order`() = runBlocking {
        val repository = repo()
        repository.save(config("a", priority = 0))
        repository.save(config("b", priority = 1))
        repository.save(config("c", priority = 2))

        repository.move("c", up = true)

        assertEquals(listOf("a", "c", "b"), repository.current().map { it.id })
        assertEquals(
            "priorities must stay unambiguous",
            listOf(0, 1, 2),
            repository.current().map { it.priority },
        )
    }

    @Test
    fun `moving a provider down rewrites the order`() = runBlocking {
        val repository = repo()
        repository.save(config("a", priority = 0))
        repository.save(config("b", priority = 1))

        repository.move("a", up = false)

        assertEquals(listOf("b", "a"), repository.current().map { it.id })
    }

    @Test
    fun `moving the first provider up does nothing`() = runBlocking {
        val repository = repo()
        repository.save(config("a", priority = 0))
        repository.save(config("b", priority = 1))

        repository.move("a", up = true)

        assertEquals(listOf("a", "b"), repository.current().map { it.id })
    }

    @Test
    fun `moving the last provider down does nothing`() = runBlocking {
        val repository = repo()
        repository.save(config("a", priority = 0))
        repository.save(config("b", priority = 1))

        repository.move("b", up = false)

        assertEquals(listOf("a", "b"), repository.current().map { it.id })
    }

    @Test
    fun `moving an unknown provider does nothing`() = runBlocking {
        val repository = repo()
        repository.save(config("a"))

        repository.move("zzz", up = true)

        assertEquals(listOf("a"), repository.current().map { it.id })
    }

    @Test
    fun `disabling a provider persists`() = runBlocking {
        val repository = repo()
        repository.save(config("a"))

        repository.setEnabled("a", false)

        assertFalse(repository.current().single().enabled)
    }

    @Test
    fun `providers are returned in priority order`() = runBlocking {
        val repository = repo()
        repository.save(config("late", priority = 9))
        repository.save(config("early", priority = 1))

        assertEquals(listOf("early", "late"), repository.current().map { it.id })
    }

    @Test
    fun `an empty key stays empty and is not encrypted`() = runBlocking {
        val store = InMemoryProviderStore()
        repo(store).save(config("a", key = ""))

        assertFalse(store.raw().orEmpty().contains("enc:"))
    }

    @Test
    fun `a change is visible through the flow`() = runBlocking {
        val repository = repo()
        repository.save(config("a"))

        assertEquals(1, repository.providers.first().size)
    }

    @Test
    fun `a saved key survives a round trip through the cipher`() = runBlocking {
        val store = InMemoryProviderStore()
        repo(store).save(config("a", key = "AQ.Ab8-some-gemini-key"))

        // A fresh repository reading the same store must recover the key exactly.
        assertEquals(
            "AQ.Ab8-some-gemini-key",
            repo(store).current().single().apiKey,
        )
    }
}
