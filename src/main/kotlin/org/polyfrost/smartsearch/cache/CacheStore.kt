package org.polyfrost.smartsearch.cache

import dev.langchain4j.data.embedding.Embedding
import org.polyfrost.smartsearch.SmartSearchClient
import java.lang.ref.SoftReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier

/**
 * Object responsible for storing all caches, and handling all cache providers
 */
object CacheStore {
    private val loadLock = Any()

    @Volatile
    private var caches: SoftReference<List<EmbeddingCache>> = SoftReference(null)
    private var cacheProviders: CopyOnWriteArrayList<Supplier<EmbeddingCache?>> = CopyOnWriteArrayList()

    fun get(md5: String): Embedding? {
        return getCaches().firstNotNullOfOrNull { it.cache[md5] }
    }

    fun has(md5: String): Boolean {
        return getCaches().any { it.cache.containsKey(md5) }
    }

    /**
     * Register a provider that creates a cache object
     */
    fun registerCacheProvider(provider: Supplier<EmbeddingCache?>) {
        cacheProviders += provider
    }

    private fun getCaches(): List<EmbeddingCache> {
        caches.get()?.let { return it }
        synchronized(loadLock) {
            caches.get()?.let { return it }
            val loaded = loadCaches()
            caches = SoftReference(loaded)
            return loaded
        }
    }

    private fun loadCaches(): List<EmbeddingCache> {
        val res = mutableListOf<EmbeddingCache>()
        cacheProviders.forEach {
            runCatching {
                it.get()?.let { cache -> res.add(cache) }
            }.onFailure { e ->
                SmartSearchClient.LOGGER.error("Error while loading cache", e)
            }
        }
        return res
    }
}