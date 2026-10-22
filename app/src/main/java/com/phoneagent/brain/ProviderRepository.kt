package com.phoneagent.brain

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * The one thing the repository needs from storage.
 *
 * Narrowing it to "read a string, write a string" keeps the encryption, ordering and merging
 * rules testable on the JVM, where the Android DataStore is not available. It also means a
 * future move to a different store touches one small class.
 */
interface ProviderStore {
    val value: Flow<String?>
    suspend fun write(json: String)
}

/** The DataStore-backed store used on the device. */
class DataStoreProviderStore(
    private val store: DataStore<Preferences>,
) : ProviderStore {

    override val value: Flow<String?> = store.data.map { it[KEY] }

    override suspend fun write(json: String) {
        store.edit { it[KEY] = json }
    }

    companion object {
        val KEY = stringPreferencesKey("providers_json")
    }
}

/** An in-memory store, for tests. */
class InMemoryProviderStore(initial: String? = null) : ProviderStore {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
    override val value: Flow<String?> = state
    override suspend fun write(json: String) {
        state.value = json
    }

    /** The raw stored text, so a test can prove the key is not in it. */
    fun raw(): String? = state.value
}

/**
 * Stores provider settings, with API keys encrypted before they are written.
 *
 * Providers are kept as one JSON array rather than as a preference per field: the list is
 * reordered and re-saved as a whole, and a single value means a reorder cannot half-apply.
 *
 * The in-memory cache exists because the router asks for the provider list on every step, and
 * reading DataStore on each step would add a disk read to the loop's critical path.
 */
class ProviderRepository(
    private val store: ProviderStore,
    private val cipher: SecretCipher,
    private val logger: Logger,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var cache: List<ProviderConfig>? = null

    val providers: Flow<List<ProviderConfig>> = store.value.map { raw -> decode(raw) }

    /** The cached list, reading from disk only on the first call or after a change. */
    suspend fun current(): List<ProviderConfig> {
        cache?.let { return it }
        val loaded = providers.first()
        cache = loaded
        return loaded
    }

    suspend fun save(config: ProviderConfig) {
        update { list ->
            val index = list.indexOfFirst { it.id == config.id }
            if (index >= 0) list.toMutableList().also { it[index] = config } else list + config
        }
    }

    suspend fun delete(id: String) {
        update { list -> list.filterNot { it.id == id } }
    }

    /**
     * Moves a provider up or down and rewrites every priority so the order is unambiguous.
     * Without the rewrite, two providers could share a priority and the order would depend on
     * map iteration.
     */
    suspend fun move(id: String, up: Boolean) {
        update { list ->
            val sorted = list.sortedBy { it.priority }.toMutableList()
            val index = sorted.indexOfFirst { it.id == id }
            if (index < 0) return@update list

            val target = if (up) index - 1 else index + 1
            if (target !in sorted.indices) return@update list

            val moved = sorted.removeAt(index)
            sorted.add(target, moved)
            sorted.mapIndexed { position, config -> config.copy(priority = position) }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        update { list ->
            list.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }
    }

    private suspend fun update(transform: (List<ProviderConfig>) -> List<ProviderConfig>) {
        val currentList = decode(store.value.first())
        store.write(encode(transform(currentList)))
        cache = null
    }

    private fun decode(raw: String?): List<ProviderConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = runCatching {
            json.decodeFromString<List<ProviderConfig>>(raw)
        }.getOrElse { error ->
            logger.log(LogLevel.ERROR, TAG, "Provider settings could not be read: ${error.message}")
            return emptyList()
        }

        return parsed.map { stored ->
            // A key that will not decrypt becomes blank rather than wrong, so the settings
            // screen shows "add a key" instead of a broken value.
            val key = if (stored.apiKey.isEmpty()) "" else cipher.decrypt(stored.apiKey) ?: ""
            stored.copy(apiKey = key)
        }.sortedBy { it.priority }
    }

    private fun encode(list: List<ProviderConfig>): String {
        val stored = list.map { config ->
            config.copy(
                apiKey = if (config.apiKey.isEmpty()) "" else cipher.encrypt(config.apiKey),
            )
        }
        return json.encodeToString(stored)
    }

    companion object {
        const val TAG = "ProviderRepo"
    }
}

private val Context.providerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "phoneagent_providers",
)

fun createProviderRepository(
    context: Context,
    cipher: SecretCipher,
    logger: Logger,
): ProviderRepository = ProviderRepository(
    DataStoreProviderStore(context.providerDataStore),
    cipher,
    logger,
)
